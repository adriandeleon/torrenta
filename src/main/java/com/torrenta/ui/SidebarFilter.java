package com.torrenta.ui;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.torrenta.engine.Torrent;

/**
 * What the sidebar narrows the list to: everything, one category, no category, one tag, no tag.
 *
 * <p>Pure, so the rule is tested: {@link #matches} decides, {@link #format} and {@link #parse} are
 * how the choice survives a restart ({@code ui.sidebar.filter}), and {@link #names} is which
 * entries the sidebar lists — the ones that exist plus the ones in use, so a count never points at
 * an entry that is not there. Status and tracker kinds join in a later milestone.
 *
 * @param kind which rule
 * @param value the category or tag for {@link Kind#CATEGORY} and {@link Kind#TAG}; empty otherwise
 */
public record SidebarFilter(Kind kind, String value) {

    public enum Kind {
        ALL,
        CATEGORY,
        UNCATEGORISED,
        TAG,
        UNTAGGED
    }

    public static final SidebarFilter ALL = new SidebarFilter(Kind.ALL, "");
    public static final SidebarFilter UNCATEGORISED = new SidebarFilter(Kind.UNCATEGORISED, "");
    public static final SidebarFilter UNTAGGED = new SidebarFilter(Kind.UNTAGGED, "");

    public SidebarFilter {
        value = kind == Kind.CATEGORY || kind == Kind.TAG ? (value == null ? "" : value.trim()) : "";
    }

    public static SidebarFilter category(String name) {
        return new SidebarFilter(Kind.CATEGORY, name);
    }

    public static SidebarFilter tag(String name) {
        return new SidebarFilter(Kind.TAG, name);
    }

    public boolean matches(Torrent t) {
        return switch (kind) {
            case ALL -> true;
            case CATEGORY -> t.category().equals(value);
            case UNCATEGORISED -> t.category().isEmpty();
            case TAG -> t.tags().contains(value);
            case UNTAGGED -> t.tags().isEmpty();
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
            case CATEGORY, TAG -> value;
            case UNCATEGORISED -> "Uncategorised";
            case UNTAGGED -> "Untagged";
        };
    }

    /** {@code all}, {@code category:<name>}, {@code uncategorised}, {@code tag:<name>}, {@code untagged}. */
    public String format() {
        return switch (kind) {
            case ALL -> "all";
            case CATEGORY -> "category:" + value;
            case UNCATEGORISED -> "uncategorised";
            case TAG -> "tag:" + value;
            case UNTAGGED -> "untagged";
        };
    }

    /** The inverse of {@link #format}; anything unreadable is {@link #ALL}. */
    public static SidebarFilter parse(String text) {
        if (text == null) return ALL;
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("category:")) {
            String name = t.substring("category:".length()).trim();
            return name.isEmpty() ? UNCATEGORISED : category(name);
        }
        if (lower.startsWith("tag:")) {
            String name = t.substring("tag:".length()).trim();
            return name.isEmpty() ? UNTAGGED : tag(name);
        }
        return switch (lower) {
            case "uncategorised", "uncategorized" -> UNCATEGORISED;
            case "untagged" -> UNTAGGED;
            default -> ALL;
        };
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
}
