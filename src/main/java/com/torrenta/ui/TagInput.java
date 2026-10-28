package com.torrenta.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The rules of a comma-separated tags field that knows the tags that exist: which tag the caret is
 * in, which existing tags to suggest for it, what picking one writes, and what a tag's chip does.
 *
 * <p>Pure, so {@link TagField} only draws it. A suggestion is a tag not already in the field:
 * those that start with what is typed first, then those that contain it, each alphabetical; the
 * tag typed out in full is not suggested, so Enter on a finished tag adds rather than completes.
 */
final class TagInput {

    /** As many suggestions as the popup shows. */
    static final int LIMIT = 8;

    private TagInput() {}

    /** The part of the field between the commas around the caret: {@code [start, end)}, trimmed as {@code text}. */
    record Token(int start, int end, String text) {}

    /** What picking a suggestion leaves in the field, and where the caret goes. */
    record Edit(String text, int caret) {}

    static Token at(String text, int caret) {
        int at = Math.clamp(caret, 0, text.length());
        int start = text.lastIndexOf(',', at - 1) + 1;
        int comma = text.indexOf(',', at);
        int end = comma < 0 ? text.length() : comma;
        return new Token(start, end, text.substring(start, end).trim());
    }

    /** The tags typed, in the field's order, empty parts dropped. */
    static List<String> entered(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String tag = part.trim();
            if (!tag.isEmpty()) out.add(tag);
        }
        return out;
    }

    static boolean has(String text, String tag) {
        return entered(text).stream().anyMatch(tag::equalsIgnoreCase);
    }

    /**
     * The existing tags to offer for the token at the caret, at most {@link #LIMIT}. An empty
     * token offers every tag not yet in the field.
     */
    static List<String> suggest(String text, int caret, Collection<String> existing) {
        Token token = at(text, caret);
        TreeSet<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        taken.addAll(entered(text.substring(0, token.start()) + "," + text.substring(token.end())));
        TreeSet<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String tag : existing) if (!taken.contains(tag)) candidates.add(tag);

        String typed = token.text().toLowerCase(Locale.ROOT);
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String tag : candidates) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.equals(typed)) continue;
            if (lower.startsWith(typed)) starts.add(tag);
            else if (lower.contains(typed)) contains.add(tag);
        }
        starts.addAll(contains);
        return List.copyOf(starts.subList(0, Math.min(LIMIT, starts.size())));
    }

    /**
     * The token at the caret replaced by {@code tag}. At the end of the field a separator follows,
     * so the next tag can be typed at once; elsewhere the caret goes to the tag's end.
     */
    static Edit complete(String text, int caret, String tag) {
        Token token = at(text, caret);
        String before = text.substring(0, token.start());
        String after = text.substring(token.end());
        String lead = before.isEmpty() ? "" : " ";
        String done = before + lead + tag;
        if (after.isBlank()) return new Edit(done + ", ", done.length() + 2);
        return new Edit(done + after, done.length());
    }

    /** A chip pressed: the tag comes out of the field if it is there, else goes on the end. */
    static String toggle(String text, String tag) {
        List<String> tags = new ArrayList<>(entered(text));
        if (!tags.removeIf(tag::equalsIgnoreCase)) tags.add(tag);
        return String.join(", ", tags);
    }
}
