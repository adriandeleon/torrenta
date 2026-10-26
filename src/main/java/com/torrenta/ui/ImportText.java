package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.torrenta.migrate.ImportCandidate;
import com.torrenta.migrate.ImportPlan;
import com.torrenta.migrate.QbittorrentReader;
import com.torrenta.migrate.TransmissionReader;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Import dialog's words and rules, apart from the dialog: where the sources are, what a row
 * says, what is ticked, the notes under the table, the button's count. Pure but for {@link
 * #found}, which looks at the disk.
 */
final class ImportText {

    private ImportText() {}

    /**
     * What a folder held, for the dialog.
     *
     * @param backup the folder read: qBittorrent's {@code BT_backup}, Transmission's config folder
     * @param running the other client is running
     * @param client whose folder it is, for the notes
     */
    record Loaded(
            Path backup,
            List<ImportPlan.Item> items,
            boolean sqlite,
            boolean running,
            List<String> unreadable,
            String client) {

        /** qBittorrent's, the first client the dialog knew. */
        Loaded(Path backup, List<ImportPlan.Item> items, boolean sqlite, boolean running, List<String> unreadable) {
            this(backup, items, sqlite, running, unreadable, QbittorrentReader.CLIENT);
        }
    }

    /**
     * The other clients' folders that exist on this machine, Flatpak's included: qBittorrent's,
     * then Transmission's (the daemon's, the app's); none when there are none.
     */
    static List<Path> found(String osName, Path home, Map<String, String> env) {
        List<Path> out = new ArrayList<>();
        for (Path p : QbittorrentReader.knownLocations(osName, home, env)) if (Files.isDirectory(p)) out.add(p);
        for (Path p : TransmissionReader.knownLocations(osName, home, env))
            if (TransmissionReader.isConfigFolder(p)) out.add(p);
        return out;
    }

    /** Whose folder this is: Transmission's has {@code resume} beside {@code torrents}; else qBittorrent's. */
    static String clientOf(Path folder) {
        return TransmissionReader.isConfigFolder(folder) ? TransmissionReader.CLIENT : QbittorrentReader.CLIENT;
    }

    /** A source in the picker: the client and where. */
    static String source(Path folder) {
        return tr("dialog.import.sourceLine", clientOf(folder), folder.toString());
    }

    /** The row's progress: a share, stopped or not; a magnet has none. */
    static String progress(ImportCandidate c) {
        if (c.metainfo() == null) return tr("dialog.import.progress.magnet");
        long percent = c.pieces() == 0 ? 0 : Math.round(100.0 * c.piecesHave() / c.pieces());
        String share = percent + " %";
        return c.stopped() ? tr("dialog.import.progress.stopped", share) : share;
    }

    /** Ticked to begin with: what the plan imports by default. */
    static boolean tickedAtFirst(ImportPlan.Item item) {
        return item.verdict().importedByDefault();
    }

    /** The lines under the table: why some are missing or held back, and what to do about it. */
    static List<String> notes(Loaded l) {
        List<String> out = new ArrayList<>();
        if (l.sqlite()) out.add(tr("dialog.import.sqlite"));
        else if (l.items().isEmpty() && l.unreadable().isEmpty())
            out.add(tr("dialog.import.empty", l.backup().toString()));
        if (l.running() && l.items().stream().anyMatch(i -> i.verdict() == ImportPlan.Verdict.CLIENT_RUNNING))
            out.add(tr("dialog.import.running", l.client()));
        if (!l.unreadable().isEmpty())
            out.add(tr("dialog.import.unreadable", l.unreadable().size()));
        return out;
    }

    /** The Import button: how many will come over. */
    static String importButton(int chosen) {
        return tr("dialog.import.import", chosen);
    }
}
