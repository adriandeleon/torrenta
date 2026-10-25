package com.torrenta.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;

import static com.torrenta.i18n.Messages.tr;

/**
 * What an add without a dialog does with a torrent the {@link ContentCheck} flags: watch folders,
 * RSS rules, an article's Download, Add without asking. {@code add.unsafeContent}, Skip by default.
 *
 * <p>A {@code .torrent} is checked before it is added. A magnet has no file list yet, so it is
 * added to stop once its metadata is in, marked ({@link Pending}), and checked by the window's
 * poll when the files are known: resumed as it is, resumed without the flagged files, or removed
 * before anything was downloaded.
 *
 * <p>The rule is pure and tested; the window applies it.
 */
public enum UnsafeContent {
    /** Added as it is; the log names what the check found. */
    ADD("settings.adding.unsafe.add"),
    /** Added without the flagged files; refused when nothing would be left. */
    SKIP("settings.adding.unsafe.skip"),
    /** Not added at all. */
    REFUSE("settings.adding.unsafe.refuse");

    private final String key;

    UnsafeContent(String key) {
        this.key = key;
    }

    public String label() {
        return tr(key);
    }

    /** The label, so a ChoiceBox shows it; read at call time, never cached. */
    @Override
    public String toString() {
        return label();
    }

    /** The setting's value back; Skip for anything unknown, the default. */
    public static UnsafeContent byName(String name) {
        if (name != null) {
            for (UnsafeContent c : values()) if (c.name().equalsIgnoreCase(name.trim())) return c;
        }
        return SKIP;
    }

    /** What happens to one torrent, from the setting and what the check found. */
    public enum Decision {
        /** Nothing found: added as it is, nothing said. */
        CLEAN,
        /** Found, and the setting says add anyway: added as it is, with a warning in the log. */
        WARN,
        /** Found and skipped: added without those files, with a line in the log. */
        SKIP,
        /** Found and refused by the setting. */
        REFUSE,
        /** Skipping what was found would leave nothing wanted: refused rather than added empty. */
        NOTHING_LEFT;

        /** Not added, or removed. */
        public boolean refused() {
            return this == REFUSE || this == NOTHING_LEFT;
        }
    }

    /**
     * @param wantedFiles how many files the add wants before the check
     */
    public static Decision decide(UnsafeContent mode, ContentCheck check, int wantedFiles) {
        if (check.isEmpty()) return Decision.CLEAN;
        return switch (mode) {
            case ADD -> Decision.WARN;
            case REFUSE -> Decision.REFUSE;
            case SKIP -> check.indices().size() >= wantedFiles ? Decision.NOTHING_LEFT : Decision.SKIP;
        };
    }

    /** The request with the flagged files set to Skip, the others' priorities as they were. */
    public static AddRequest withSkipped(AddRequest request, Set<Integer> flagged) {
        Map<Integer, Priority> priorities = new HashMap<>(request.filePriorities());
        for (int index : flagged) priorities.put(index, Priority.IGNORE);
        return request.withFilePriorities(priorities);
    }

    /** How many files of the preview the request wants. */
    public static int wanted(TorrentPreview preview, AddRequest request) {
        return (int) preview.files().stream()
                .filter(f -> request.filePriorities().getOrDefault(f.index(), Priority.NORMAL) != Priority.IGNORE)
                .count();
    }

    /** The check of a request's own wanted files. */
    public static ContentCheck check(TorrentPreview preview, AddRequest request) {
        return ContentCheck.of(
                preview, i -> request.filePriorities().getOrDefault(i, Priority.NORMAL) != Priority.IGNORE);
    }

    /**
     * An added torrent's files as a preview, for a magnet checked once its metadata is in. Only
     * the name and the files matter to the check.
     */
    public static TorrentPreview previewOf(Torrent torrent, List<TorrentDetail.FileStatus> files) {
        List<TorrentPreview.FileEntry> entries = files.stream()
                .map(f -> new TorrentPreview.FileEntry(f.index(), f.path(), f.size()))
                .toList();
        long size = entries.stream().mapToLong(TorrentPreview.FileEntry::size).sum();
        return new TorrentPreview(
                torrent.id(), torrent.name(), size, entries, false, List.of(), "", "", 0, true, false);
    }

    /** What the check found, for a log line: each line of the card, joined. */
    public static String findings(ContentCheck check) {
        return check.lines().stream().map(ContentCheck.Line::text).collect(Collectors.joining("; "));
    }

    /** The log line for a decision about a torrent of that name; empty for a clean one. */
    public static String logLine(Decision decision, String name, ContentCheck check, boolean afterAdd) {
        String found = findings(check);
        return switch (decision) {
            case CLEAN -> "";
            case WARN -> tr("log.unsafe.added", name, found);
            case SKIP -> tr("log.unsafe.skipped", name, check.indices().size(), found);
            case REFUSE, NOTHING_LEFT -> {
                String reason = reason(decision, check);
                yield afterAdd ? tr("log.unsafe.removed", name, reason) : tr("log.unsafe.refused", name, reason);
            }
        };
    }

    /** Why a torrent was refused, without its name: what a path that reports failures appends. */
    public static String reason(Decision decision, ContentCheck check) {
        return decision == Decision.NOTHING_LEFT
                ? tr("log.unsafe.reasonAll", findings(check))
                : tr("log.unsafe.reason", findings(check));
    }

    /**
     * A magnet added without a dialog, waiting for its file list: the setting it was added under,
     * and whether it is to be started once checked (it was stopped for the check, not by a person).
     * Kept as {@code torrent.<hash>.unsafeContent} so a restart before the metadata keeps it.
     */
    public record Pending(UnsafeContent mode, boolean resume) {

        public String format() {
            return mode.name() + (resume ? " resume" : "");
        }

        public static Pending parse(String value) {
            String v = value == null ? "" : value.trim();
            boolean resume = v.endsWith(" resume");
            return new Pending(byName(resume ? v.substring(0, v.length() - " resume".length()) : v), resume);
        }
    }

    /**
     * A refusal, thrown by the add so each path reports it as it reports a failure: the message
     * is the reason alone (a watch folder's or a rule's line already names the torrent), and
     * {@link #line()} the whole sentence for a path that reports nothing of its own.
     */
    public static final class Refused extends EngineException {
        private final String line;

        public Refused(String name, Decision decision, ContentCheck check) {
            super(reason(decision, check));
            this.line = logLine(decision, name, check, false);
        }

        public String line() {
            return line;
        }
    }
}
