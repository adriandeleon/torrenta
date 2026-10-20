package com.torrenta.engine;

import java.util.Locale;

/** What a string handed to "add" is. Pure, so the three cases are tested. */
public final class Inputs {

    public enum Kind {
        MAGNET,
        URL,
        FILE
    }

    private Inputs() {}

    public static Kind classify(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("magnet:")) return Kind.MAGNET;
        if (s.startsWith("http://") || s.startsWith("https://")) return Kind.URL;
        return Kind.FILE;
    }

    /** A line that looks like something to add: a magnet or a link to a {@code .torrent}. */
    public static boolean looksAddable(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || s.contains("\n")) return false;
        return switch (classify(s)) {
            case MAGNET -> s.length() > "magnet:?".length();
            case URL -> s.toLowerCase(Locale.ROOT).contains(".torrent");
            case FILE -> false;
        };
    }
}
