package com.torrenta.engine;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** What a string handed to "add" is. Pure, so the cases are tested. */
public final class Inputs {

    public enum Kind {
        MAGNET,
        URL,
        FILE
    }

    /** The schemes a link may have: what curl is allowed to fetch, plus the web's two. */
    private static final Set<String> SCHEMES = Set.of("http", "https", "ftp", "ftps", "sftp");

    /** The last segment of a page rather than a file: not offered from the clipboard. */
    private static final Set<String> PAGES =
            Set.of("htm", "html", "xhtml", "php", "asp", "aspx", "jsp", "cgi", "shtml", "cfm");

    private Inputs() {}

    public static Kind classify(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("magnet:")) return Kind.MAGNET;
        int colon = s.indexOf("://");
        if (colon > 0 && SCHEMES.contains(s.substring(0, colon))) return Kind.URL;
        return Kind.FILE;
    }

    /** A web link to a {@code .torrent}: fetched and added as a torrent, not downloaded as a file. */
    public static boolean isTorrentLink(String input) {
        if (input == null || classify(input) != Kind.URL) return false;
        String s = input.trim().toLowerCase(Locale.ROOT);
        return (s.startsWith("http://") || s.startsWith("https://")) && s.contains(".torrent");
    }

    /** A link to download as it is, by curl: any link with a host that is not a {@code .torrent}'s. */
    public static boolean isDirectLink(String input) {
        if (input == null || classify(input) != Kind.URL || isTorrentLink(input)) return false;
        try {
            return URI.create(input.trim()).getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * A line that looks like something to add: a magnet, a link to a {@code .torrent}, or a link
     * to a file (its last segment has an extension, and not a web page's). A link to a page is
     * not offered, since links to pages are most of what gets copied.
     */
    public static boolean looksAddable(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || s.contains("\n")) return false;
        return switch (classify(s)) {
            case MAGNET -> s.length() > "magnet:?".length();
            case URL -> isTorrentLink(s) || (isDirectLink(s) && namesAFile(s));
            case FILE -> false;
        };
    }

    private static boolean namesAFile(String link) {
        String path;
        try {
            path = URI.create(link.trim()).getRawPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (path == null || path.endsWith("/")) return false;
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || dot == last.length() - 1) return false;
        String ext = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.chars().allMatch(Character::isLetterOrDigit) && !PAGES.contains(ext);
    }
}
