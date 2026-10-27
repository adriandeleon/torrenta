package com.torrenta.ui;

import java.util.List;
import java.util.function.Predicate;

import com.torrenta.engine.Torrent;

import static com.torrenta.i18n.Messages.tr;

/** Rules behind the torrent actions. Pure, so they are tested rather than assumed. */
public final class Actions {

    private Actions() {}

    /**
     * Space on a selection: resume if every selected torrent is paused, otherwise pause. Mixed
     * selections pause, because a person who presses Space on a running torrent expects it to
     * stop, and a paused one in the same selection staying paused costs nothing.
     */
    /**
     * The torrents of a selection, its downloads from links left out: what a command only a torrent
     * understands (recheck, reannounce, the queue, magnets, export…) acts on.
     */
    public static List<Torrent> torrentsOnly(List<Torrent> selected) {
        return selected.stream().filter(t -> !t.direct()).toList();
    }

    /**
     * What a command typed in the omnibox acts on: every match while the box holds a filter (so
     * {@code state:paused >resume} resumes what the list shows), the selection otherwise.
     *
     * @param filtering the box holds chips: a filter is in force
     */
    public static List<Torrent> targets(List<Torrent> selection, List<Torrent> matches, boolean filtering) {
        return filtering ? List.copyOf(matches) : List.copyOf(selection);
    }

    /** Whether such a command has anything to act on: off for a selection of downloads alone. */
    public static boolean anyTorrent(List<Torrent> selected) {
        return selected.stream().anyMatch(t -> !t.direct());
    }

    public static boolean shouldResume(List<Torrent> selected) {
        return !selected.isEmpty() && selected.stream().allMatch(Torrent::paused);
    }

    /** Sequential on a selection: turn it on unless every selected torrent already has it. */
    public static boolean shouldEnableSequential(List<Torrent> selected) {
        return !selected.stream().allMatch(Torrent::sequential);
    }

    /**
     * The file a torrent exports to: its name with the characters no file system takes (and
     * control characters) as {@code _}, leading dots and trailing dots and spaces dropped (a
     * hidden file, Windows' rule), never empty, with {@code .torrent} after it.
     */
    public static String exportFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        safe = safe.replaceAll("^\\.+", "").replaceAll("[. ]+$", "").strip();
        if (safe.isEmpty()) safe = "torrent";
        return safe + ".torrent";
    }

    /**
     * A name among {@code taken} that is not one of them: {@code a.torrent}, then {@code a
     * (2).torrent}, {@code a (3).torrent}…, so exporting a selection never writes over a file.
     */
    public static String unusedName(String file, java.util.Set<String> taken) {
        if (!taken.contains(file)) return file;
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        String ext = dot > 0 ? file.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + " (" + n + ")" + ext;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /** The torrents super seeding can apply to: the complete ones. */
    public static List<Torrent> superSeedTargets(List<Torrent> selected) {
        return selected.stream()
                .filter(t -> t.size() > 0 && t.done() >= t.size())
                .toList();
    }

    /** Super seeding on a selection: on unless every one already has it. */
    public static boolean shouldEnableSuperSeeding(List<Torrent> seeds) {
        return !seeds.stream().allMatch(Torrent::superSeeding);
    }

    /**
     * {@code Paused 1 torrent}, {@code Paused 3 torrents}, {@code Paused 2 torrents, 1 failed: …}.
     * The verb arrives translated ({@code actions.verb.*}); the count and the failures are the
     * catalogue's own sentence.
     */
    public static String outcome(String verb, int done, List<String> failures) {
        if (failures.isEmpty()) return tr("actions.outcome", verb, done);
        return tr("actions.outcomeFailed", verb, done, failures.size(), failures.get(0));
    }

    /** What Escape on the list does, one layer at a time. */
    public enum Escape {
        CLEAR_FILTER,
        CLEAR_SELECTION,
        NOTHING
    }

    /**
     * Escape peels one layer: a filter in force goes first, then the selection, then nothing
     * (the dialog and the omnibox handle their own Escape before the list sees one).
     */
    public static Escape escape(boolean filterActive, boolean hasSelection) {
        if (filterActive) return Escape.CLEAR_FILTER;
        if (hasSelection) return Escape.CLEAR_SELECTION;
        return Escape.NOTHING;
    }

    /**
     * Choose Files… is for one torrent at a time, and only one added with "Add now, pick files
     * when ready" whose files have not been chosen yet ({@code marked}).
     */
    public static boolean canChooseFiles(List<String> selectedIds, Predicate<String> marked) {
        return selectedIds.size() == 1 && marked.test(selectedIds.get(0));
    }

    /**
     * The Add Trackers prompt's text as announce URLs: one per whitespace-separated word, in the
     * order typed, each once. Lines, spaces and tabs all separate; nothing left is no URLs.
     */
    public static List<String> trackerUrls(String text) {
        if (text == null) return List.of();
        return java.util.Arrays.stream(text.strip().split("\\s+"))
                .filter(u -> !u.isEmpty())
                .distinct()
                .toList();
    }

    /** The confirmation for removal, worded by what it will do. */
    public static String removeQuestion(int count, boolean deleteFiles) {
        return removeQuestion(count, deleteFiles, false);
    }

    /** The same, for files that go to the trash rather than away for good. */
    public static String removeQuestion(int count, boolean deleteFiles, boolean toTrash) {
        if (!deleteFiles) return tr("actions.removeQuestion.keep", count);
        return toTrash ? tr("actions.removeQuestion.trash", count) : tr("actions.removeQuestion.delete", count);
    }

    /**
     * A peer's address as the Peers tab shows it, without its scheme or port: libtorrent writes
     * {@code tcp://10.0.0.2:6881} (or {@code utp://[::1]:6881}), Transmission {@code 10.0.0.2:6881};
     * either is {@code 10.0.0.2}. A bare IPv6 literal (more than one colon, no brackets) and an
     * address with no port are kept whole.
     */
    public static String peerHost(String address) {
        String ip = address.strip();
        int scheme = ip.indexOf("://");
        if (scheme >= 0) ip = ip.substring(scheme + 3);
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            return close < 0 ? ip.substring(1) : ip.substring(1, close);
        }
        if (ip.chars().filter(c -> c == ':').count() == 1) return ip.substring(0, ip.indexOf(':'));
        return ip;
    }
}
