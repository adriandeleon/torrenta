package com.torrenta.ui;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.torrenta.engine.Torrent;

/**
 * One entry of the sidebar: everything, a status, one category, no category, one tag, no tag, one
 * tracker host, no tracker, a tracker in error.
 *
 * <p>Pure, so the rule is tested: {@link #matches} decides, {@link #format} and {@link #parse} are
 * how a choice survives a restart (one {@code ui.sidebar.<axis>} key per section), and
 * {@link #names} and {@link #trackerHosts} are which entries the sidebar lists — the ones that
 * exist plus the ones in use, so a count never points at an entry that is not there. Each entry
 * belongs to one {@link Axis}; the sidebar keeps one choice per axis and {@link SidebarSelection}
 * composes them.
 *
 * @param kind which rule
 * @param value the status key, category, tag or host for the kinds that take one; empty otherwise
 */
public record SidebarFilter(Kind kind, String value) {

    public enum Kind {
        ALL,
        STATUS,
        CATEGORY,
        UNCATEGORISED,
        TAG,
        UNTAGGED,
        TRACKER,
        TRACKERLESS,
        TRACKER_ERROR
    }

    /** The section an entry belongs to; {@link #ALL} is the one entry above the sections. */
    public enum Axis {
        ALL,
        STATUS,
        CATEGORY,
        TAG,
        TRACKER;

        /** The settings-file suffix: {@code ui.sidebar.<key>}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final SidebarFilter ALL = new SidebarFilter(Kind.ALL, "");
    public static final SidebarFilter UNCATEGORISED = new SidebarFilter(Kind.UNCATEGORISED, "");
    public static final SidebarFilter UNTAGGED = new SidebarFilter(Kind.UNTAGGED, "");
    public static final SidebarFilter TRACKERLESS = new SidebarFilter(Kind.TRACKERLESS, "");
    public static final SidebarFilter TRACKER_ERROR = new SidebarFilter(Kind.TRACKER_ERROR, "");

    public SidebarFilter {
        value = switch (kind) {
            case CATEGORY, TAG -> value == null ? "" : value.trim();
            case TRACKER -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            case STATUS -> SidebarStatus.parse(value).map(SidebarStatus::key).orElse("");
            default -> "";
        };
        // A status that is not one is no filter at all; the record stays total.
        if (kind == Kind.STATUS && value.isEmpty()) kind = Kind.ALL;
    }

    public static SidebarFilter status(SidebarStatus status) {
        return new SidebarFilter(Kind.STATUS, status.key());
    }

    public static SidebarFilter category(String name) {
        return new SidebarFilter(Kind.CATEGORY, name);
    }

    public static SidebarFilter tag(String name) {
        return new SidebarFilter(Kind.TAG, name);
    }

    public static SidebarFilter tracker(String host) {
        return new SidebarFilter(Kind.TRACKER, host);
    }

    public Axis axis() {
        return switch (kind) {
            case ALL -> Axis.ALL;
            case STATUS -> Axis.STATUS;
            case CATEGORY, UNCATEGORISED -> Axis.CATEGORY;
            case TAG, UNTAGGED -> Axis.TAG;
            case TRACKER, TRACKERLESS, TRACKER_ERROR -> Axis.TRACKER;
        };
    }

    /** The status this names; only for {@link Kind#STATUS}. */
    public SidebarStatus status() {
        return SidebarStatus.parse(value).orElseThrow();
    }

    public boolean matches(Torrent t) {
        return switch (kind) {
            case ALL -> true;
            case STATUS -> status().matches(t);
            case CATEGORY -> t.category().equals(value);
            case UNCATEGORISED -> t.category().isEmpty();
            case TAG -> t.tags().contains(value);
            case UNTAGGED -> t.tags().isEmpty();
            case TRACKER -> t.trackers().contains(value);
            case TRACKERLESS -> t.trackers().isEmpty();
            case TRACKER_ERROR -> t.trackerError();
        };
    }

    public int count(List<Torrent> torrents) {
        int n = 0;
        for (Torrent t : torrents) if (matches(t)) n++;
        return n;
    }

    /** The entry's text in the sidebar. */
    public String title() {
        return switch (kind) {
            case ALL -> "All";
            case STATUS -> status().title();
            case CATEGORY, TAG, TRACKER -> value;
            case UNCATEGORISED -> "Uncategorised";
            case UNTAGGED -> "Untagged";
            case TRACKERLESS -> "Trackerless";
            case TRACKER_ERROR -> "Tracker error";
        };
    }

    /**
     * {@code all}, {@code status:<key>}, {@code category:<name>}, {@code uncategorised},
     * {@code tag:<name>}, {@code untagged}, {@code tracker:<host>}, {@code trackerless},
     * {@code tracker-error}.
     */
    public String format() {
        return switch (kind) {
            case ALL -> "all";
            case STATUS -> "status:" + value;
            case CATEGORY -> "category:" + value;
            case UNCATEGORISED -> "uncategorised";
            case TAG -> "tag:" + value;
            case UNTAGGED -> "untagged";
            case TRACKER -> "tracker:" + value;
            case TRACKERLESS -> "trackerless";
            case TRACKER_ERROR -> "tracker-error";
        };
    }

    /** The inverse of {@link #format}; anything unreadable is {@link #ALL}. */
    public static SidebarFilter parse(String text) {
        if (text == null) return ALL;
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("status:")) {
            return SidebarStatus.parse(t.substring("status:".length()))
                    .map(SidebarFilter::status)
                    .orElse(ALL);
        }
        if (lower.startsWith("category:")) {
            String name = t.substring("category:".length()).trim();
            return name.isEmpty() ? UNCATEGORISED : category(name);
        }
        if (lower.startsWith("tag:")) {
            String name = t.substring("tag:".length()).trim();
            return name.isEmpty() ? UNTAGGED : tag(name);
        }
        if (lower.startsWith("tracker:")) {
            String host = t.substring("tracker:".length()).trim();
            return host.isEmpty() ? TRACKERLESS : tracker(host);
        }
        return switch (lower) {
            case "uncategorised", "uncategorized" -> UNCATEGORISED;
            case "untagged" -> UNTAGGED;
            case "trackerless" -> TRACKERLESS;
            case "tracker-error" -> TRACKER_ERROR;
            default -> ALL;
        };
    }

    /**
     * {@link #parse}, kept only if it is on the axis asked for: a remembered {@code ui.sidebar.tag}
     * that reads {@code category:x} is nothing. {@link Axis#ALL} takes anything.
     */
    public static SidebarFilter parse(String text, Axis axis) {
        SidebarFilter f = parse(text);
        return axis == Axis.ALL || f.axis() == axis ? f : ALL;
    }

    /**
     * The names to list: the ones that exist and the ones some torrent carries, sorted ignoring
     * case, each once. For categories pass each torrent's category; for tags, its tags.
     */
    public static List<String> names(List<String> existing, List<Torrent> torrents, boolean tags) {
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : existing) if (name != null && !name.isBlank()) out.add(name.trim());
        for (Torrent t : torrents) {
            if (tags) out.addAll(t.tags());
            else if (!t.category().isEmpty()) out.add(t.category());
        }
        return List.copyOf(out);
    }

    /** Every tracker host some torrent has, sorted, each once. */
    public static List<String> trackerHosts(List<Torrent> torrents) {
        TreeSet<String> out = new TreeSet<>();
        for (Torrent t : torrents) out.addAll(t.trackers());
        return List.copyOf(out);
    }
}
