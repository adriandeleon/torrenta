package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.torrenta.engine.Inputs;

/**
 * The one box above the list, as a value: the terms already committed as chips and the text still
 * being typed. The window keeps one of these, hands every keystroke to it and draws the result;
 * the rules live here so they are tested rather than eyeballed.
 *
 * <p>Plain words filter the name as they are typed and stay text. A {@code key:value} term
 * becomes a chip the moment a space follows it, valid or not: an unreadable one is a red chip
 * saying what is wrong, so the box never goes blank mid-typing. Backspace on an empty box hands
 * the last chip back as text, to be edited. A leading {@code >} (or {@code ›}) means a command,
 * and a magnet, a link to a {@code .torrent} or a path to one means an add.
 *
 * @param chips the committed terms, as typed
 * @param text what is in the field
 */
public record Omnibox(List<String> chips, String text) {

    public enum Mode {
        FILTER,
        COMMAND,
        ADD
    }

    public static final Omnibox EMPTY = new Omnibox(List.of(), "");

    public Omnibox {
        chips = chips == null ? List.of() : List.copyOf(chips);
        text = text == null ? "" : text;
    }

    /** The text as it is after a keystroke: terms ended by a space move into chips. */
    public Omnibox typed(String text, long now) {
        if (text == null) text = "";
        if (mode(text) != Mode.FILTER) return new Omnibox(chips, text);
        int lastSpace = lastWhitespace(text);
        if (lastSpace < 0) return new Omnibox(chips, text);
        List<String> next = new ArrayList<>(chips);
        StringBuilder kept = new StringBuilder();
        for (String token : tokens(text.substring(0, lastSpace + 1))) {
            if (isTerm(token, now)) next.add(token);
            else kept.append(token).append(' ');
        }
        return new Omnibox(next, kept + text.substring(lastSpace + 1));
    }

    /** Backspace with nothing in the field: the last chip comes back as text, to be edited. */
    public Omnibox backspaceOnEmpty() {
        if (!text.isEmpty() || chips.isEmpty()) return this;
        return new Omnibox(chips.subList(0, chips.size() - 1), chips.getLast());
    }

    /** A click on a chip removes it; an index out of range changes nothing. */
    public Omnibox removeChip(int index) {
        if (index < 0 || index >= chips.size()) return this;
        List<String> next = new ArrayList<>(chips);
        next.remove(index);
        return new Omnibox(next, text);
    }

    public Omnibox withText(String text) {
        return new Omnibox(chips, text);
    }

    public boolean isEmpty() {
        return chips.isEmpty() && text.isBlank();
    }

    public Mode mode() {
        return mode(text);
    }

    static Mode mode(String text) {
        String t = text == null ? "" : text.stripLeading();
        if (t.startsWith(">") || t.startsWith("›")) return Mode.COMMAND;
        if (Inputs.looksAddable(t)) return Mode.ADD;
        if (t.toLowerCase(Locale.ROOT).endsWith(".torrent") && !t.contains("\n")) return Mode.ADD;
        return Mode.FILTER;
    }

    /** What the list is filtered by: the chips, and the text while it is filter text. */
    public String query() {
        String committed = String.join(" ", chips);
        if (mode() != Mode.FILTER || text.isBlank()) return committed;
        return committed.isEmpty() ? text.trim() : committed + " " + text.trim();
    }

    /** In command mode: what follows the prefix. */
    public String commandQuery() {
        String t = text.stripLeading();
        return t.isEmpty() ? "" : t.substring(1).trim();
    }

    /** Each chip's meaning, for drawing; an unreadable term's says what is wrong and is invalid. */
    public List<FilterQuery.Chip> chipViews(long now) {
        List<FilterQuery.Chip> out = new ArrayList<>();
        for (String term : chips) {
            List<FilterQuery.Chip> read = FilterQuery.parse(term, now).chips();
            out.add(read.isEmpty() ? new FilterQuery.Chip(term, term, false) : read.getFirst());
        }
        return out;
    }

    /** More than one character arrived at once: a paste, not typing. */
    public static boolean pasted(String was, String now) {
        int before = was == null ? 0 : was.length();
        int after = now == null ? 0 : now.length();
        return after - before > 1;
    }

    /** A token with a key in it, as {@link FilterQuery} reads one; a plain word is not. */
    private static boolean isTerm(String token, long now) {
        return !FilterQuery.parse(token, now).chips().isEmpty();
    }

    private static int lastWhitespace(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    /** Space-separated, quotes keeping their spaces, as the filter's own tokenizer does it. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            int start = i;
            boolean inQuotes = false;
            while (i < n && (inQuotes || !Character.isWhitespace(text.charAt(i)))) {
                if (text.charAt(i) == '"') inQuotes = !inQuotes;
                i++;
            }
            out.add(text.substring(start, i));
        }
        return out;
    }
}
