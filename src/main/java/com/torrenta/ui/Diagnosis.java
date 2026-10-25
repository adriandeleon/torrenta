package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;

import static com.torrenta.i18n.Messages.tr;

/**
 * Why a torrent is not moving, in a sentence, with at most two things to do about it: the amber
 * card at the top of the Overview tab. Only a torrent that needs a person gets one (stalled, in
 * error, waiting in the queue); the first rule that fits wins.
 *
 * <p>Pure, and tested: the pane only lays the card out.
 *
 * @param title what the card is answering
 * @param body the sentence; a file name, when the sentence names one, stands at {@link #MARK} so
 *     the pane can set it in the working face
 * @param file the file the sentence names, or empty
 * @param fileIndex that file's index in the torrent, or -1
 * @param remedies what the card offers, the first one emphasised; never more than two
 */
public record Diagnosis(Title title, String body, String file, int fileIndex, List<Remedy> remedies) {

    /** Stands in for the file name inside a translated sentence; never typed by anyone. */
    public static final String MARK = "\u0000";

    public Diagnosis {
        remedies = List.copyOf(remedies);
    }

    /** The card's heading, by what kind of stop it is explaining. */
    public enum Title {
        STALLED("diagnosis.title.stalled"),
        ERROR("diagnosis.title.error"),
        QUEUED("diagnosis.title.queued"),
        /** An archive it wants asks for a password (engine.ArchiveProbe): red, like an error. */
        ENCRYPTED("diagnosis.title.encrypted");

        private final String key;

        Title(String key) {
            this.key = key;
        }

        public String label() {
            return tr(key);
        }
    }

    /** One thing to do. {@link #command()} names the window's command it runs, when it is one. */
    public enum Remedy {
        SKIP_FILE("diagnosis.remedy.skipFile", null),
        ADD_TRACKERS("diagnosis.remedy.addTrackers", null),
        REANNOUNCE("diagnosis.remedy.reannounce", "reannounce"),
        FORCE_RESUME("diagnosis.remedy.forceResume", "force-resume"),
        RETRY("diagnosis.remedy.retry", "force-resume"),
        OPEN_FOLDER("diagnosis.remedy.openFolder", "open-folder"),
        REMOVE("diagnosis.remedy.remove", "remove");

        private final String key;
        private final String command;

        Remedy(String key, String command) {
            this.key = key;
            this.command = command;
        }

        public String label() {
            return tr(key);
        }

        /** The command id it runs, whose chord the card shows; empty for the pane's own actions. */
        public Optional<String> command() {
            return Optional.ofNullable(command);
        }
    }

    /** The body with the file put back in plainly, for the capture hook and the tests. */
    public String text() {
        return body.replace(MARK, file);
    }

    /**
     * @param state the row's state, from the same snapshot and stall clock the list uses
     * @param quietSeconds how long it has received nothing; what the last-resort sentence says
     */
    public static Optional<Diagnosis> of(Torrent t, RowState state, TorrentDetail d, long quietSeconds) {
        // A download from a link has no swarm to reason about: its error is the whole story.
        if (t.direct()) return state == RowState.ERROR ? Optional.of(error(t)) : Optional.empty();
        // A locked archive matters whether or not the torrent moves: before the stall rules, after
        // an error (which stops everything). Skipping the archive is what makes it go away.
        if (state != RowState.ERROR && d != null) {
            Optional<Diagnosis> locked = encrypted(d);
            if (locked.isPresent()) return locked;
        }
        return switch (state) {
            case ERROR -> Optional.of(error(t));
            case QUEUED -> RowState.complete(t) ? Optional.empty() : Optional.of(queued(t));
            case STALLED -> Optional.of(stalled(d, quietSeconds));
            default -> Optional.empty();
        };
    }

    /** The first wanted archive the engine found asking for a password, as a red card. */
    private static Optional<Diagnosis> encrypted(TorrentDetail d) {
        if (d.encryptedArchives().isEmpty()) return Optional.empty();
        return d.files().stream()
                .filter(f -> d.encryptedArchives().contains(f.index()) && f.priority() != Priority.IGNORE)
                .findFirst()
                .map(f -> new Diagnosis(
                        Title.ENCRYPTED,
                        tr("diagnosis.encrypted", MARK),
                        f.path().substring(f.path().lastIndexOf('/') + 1),
                        f.index(),
                        List.of(Remedy.SKIP_FILE, Remedy.REMOVE)));
    }

    private static Diagnosis error(Torrent t) {
        return new Diagnosis(
                Title.ERROR, tr("diagnosis.error", t.error()), "", -1, List.of(Remedy.RETRY, Remedy.OPEN_FOLDER));
    }

    private static Diagnosis queued(Torrent t) {
        String body = t.queuePosition() >= 0 ? tr("diagnosis.queuedAt", t.queuePosition() + 1) : tr("diagnosis.queued");
        return new Diagnosis(Title.QUEUED, body, "", -1, List.of(Remedy.FORCE_RESUME));
    }

    private static Diagnosis stalled(TorrentDetail d, long quietSeconds) {
        // Nobody at all comes first: every missing piece is "on no peer" then, which is true and
        // useless; finding peers is the thing to do.
        if (d.peers().isEmpty()) return noPeers(d);
        Unavailable missing = unavailable(d);
        if (missing.pieces() > 0) return unavailableCard(d, missing);
        boolean anySending = d.peers().stream().anyMatch(p -> p.flags().indexOf('D') >= 0 || p.downRate() > 0);
        if (!anySending) {
            return new Diagnosis(Title.STALLED, tr("diagnosis.choked", d.peers().size()), "", -1, List.of());
        }
        return new Diagnosis(
                Title.STALLED, tr("diagnosis.quiet", Format.coarse(quietSeconds)), "", -1, List.of(Remedy.REANNOUNCE));
    }

    private static Diagnosis unavailableCard(TorrentDetail d, Unavailable missing) {
        String availability =
                String.format(Locale.ROOT, "%.2f", Math.max(0, d.general().availability()));
        String lead = tr("diagnosis.unavailable", missing.pieces(), Bytes.human(missing.bytes()), availability);
        long wantedFiles =
                d.files().stream().filter(f -> f.priority() != Priority.IGNORE).count();
        // Skipping the one file is a remedy only when something else would be left to finish.
        if (missing.files().size() == 1 && wantedFiles > 1) {
            TorrentDetail.FileStatus file = d.files().stream()
                    .filter(f -> f.index() == missing.files().first())
                    .findFirst()
                    .orElseThrow();
            return new Diagnosis(
                    Title.STALLED,
                    lead + " " + tr("diagnosis.oneFile", missing.pieces(), MARK),
                    file.path(),
                    file.index(),
                    List.of(Remedy.SKIP_FILE, Remedy.ADD_TRACKERS));
        }
        return new Diagnosis(Title.STALLED, lead, "", -1, List.of(Remedy.ADD_TRACKERS));
    }

    private static Diagnosis noPeers(TorrentDetail d) {
        List<TorrentDetail.Tracker> trackers = d.trackers();
        if (trackers.isEmpty())
            return new Diagnosis(Title.STALLED, tr("diagnosis.noTrackers"), "", -1, List.of(Remedy.ADD_TRACKERS));
        // Not one answering: erroring, or never reached (libtorrent skips an unreachable one and
        // leaves it "not contacted"). Only a working tracker means the swarm is simply empty.
        boolean anyWorking = trackers.stream().anyMatch(x -> "working".equals(x.status()));
        String body = anyWorking ? tr("diagnosis.nobodyListed") : tr("diagnosis.trackersFailing", trackers.size());
        return new Diagnosis(Title.STALLED, body, "", -1, List.of(Remedy.ADD_TRACKERS, Remedy.REANNOUNCE));
    }

    /**
     * The wanted pieces no connected peer has: how many, about how many bytes, and which wanted
     * files they fall in (by index).
     */
    record Unavailable(int pieces, long bytes, TreeSet<Integer> files) {}

    static Unavailable unavailable(TorrentDetail d) {
        boolean[] have = d.pieces();
        int[] availability = d.availability();
        TreeSet<Integer> files = new TreeSet<>();
        // No count (a seed, a paused torrent) or a count from another moment: nothing to say.
        if (availability.length == 0 || availability.length != have.length) return new Unavailable(0, 0, files);
        List<TorrentDetail.FileStatus> wanted = new ArrayList<>();
        for (TorrentDetail.FileStatus f : d.files()) if (f.priority() != Priority.IGNORE) wanted.add(f);
        boolean ranges = !wanted.isEmpty() && wanted.stream().allMatch(f -> f.firstPiece() >= 0 || f.size() == 0);
        int count = 0;
        for (int i = 0; i < have.length; i++) {
            if (have[i] || availability[i] > 0) continue;
            if (!ranges) {
                count++;
                continue;
            }
            Set<Integer> in = new TreeSet<>();
            for (TorrentDetail.FileStatus f : wanted) if (f.touches(i)) in.add(f.index());
            if (in.isEmpty()) continue; // a piece of skipped files only: never going to be fetched
            count++;
            files.addAll(in);
        }
        return new Unavailable(count, (long) count * Math.max(0, d.general().pieceLength()), files);
    }
}
