package com.torrenta.engine.direct;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a downloaded file is called on disk. Pure: the name a link suggests, made safe for every
 * file system the app runs on, and kept clear of the files already there.
 */
public final class DirectNames {

    /** When neither the link nor the server says anything usable. */
    static final String FALLBACK = "download";

    /** Long enough for any real name; well under the 255 bytes most file systems allow. */
    static final int MAX_LENGTH = 200;

    /** Windows refuses these as a file's base name, whatever the extension. */
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1",
            "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private DirectNames() {}

    /**
     * The link's last path segment, percent-decoded: {@code https://x.org/iso/a%20b.iso?t=1} is
     * {@code a b.iso}. A link that ends in a folder, or has no path, is named after its host.
     */
    public static String fromUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return FALLBACK;
        }
        String path = uri.getRawPath();
        if (path != null) {
            int end = path.length();
            while (end > 0 && path.charAt(end - 1) == '/') end--;
            String segment = path.substring(path.lastIndexOf('/', end - 1) + 1, end);
            if (!segment.isEmpty()) {
                String name = safe(decode(segment));
                if (!name.equals(FALLBACK)) return name;
            }
        }
        return uri.getHost() == null ? FALLBACK : safe(uri.getHost());
    }

    /** The host a link points at, for the list and the sidebar; empty when it has none. */
    public static String host(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * A name any of Linux, macOS and Windows accepts: no folder separators or characters Windows
     * forbids, no control characters, no leading dots (a hidden file nobody asked for), no
     * trailing dots or spaces, not a device name, not too long. What is left empty is {@link
     * #FALLBACK}.
     */
    public static String safe(String name) {
        if (name == null) return FALLBACK;
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(c -> {
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) sb.append('_');
            else sb.appendCodePoint(c);
        });
        String s = sb.toString().strip();
        int start = 0;
        while (start < s.length() && s.charAt(start) == '.') start++;
        s = s.substring(start);
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        s = s.substring(0, end);
        if (s.length() > MAX_LENGTH) s = shorten(s);
        if (s.isEmpty()) return FALLBACK;
        int dot = s.indexOf('.');
        String base = (dot < 0 ? s : s.substring(0, dot)).toLowerCase(Locale.ROOT);
        return RESERVED.contains(base) ? "_" + s : s;
    }

    /**
     * {@code name}, or {@code name (2).ext}, {@code name (3).ext}… the first one {@code taken}
     * refuses. The number goes before the last extension, and before {@code .tar} of a {@code
     * .tar.gz}, as a browser does.
     */
    public static String unused(String name, Predicate<String> taken) {
        if (!taken.test(name)) return name;
        int dot = extensionStart(name);
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        for (int n = 2; ; n++) {
            String candidate = base + " (" + n + ")" + ext;
            if (!taken.test(candidate)) return candidate;
        }
    }

    private static int extensionStart(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return -1;
        int before = name.lastIndexOf('.', dot - 1);
        if (before > 0 && name.substring(before, dot).equalsIgnoreCase(".tar")) return before;
        return dot;
    }

    /** Keeps the extension, cuts the base. */
    private static String shorten(String s) {
        int dot = s.lastIndexOf('.');
        String ext = dot > 0 && s.length() - dot <= 16 ? s.substring(dot) : "";
        return s.substring(0, MAX_LENGTH - ext.length()).strip() + ext;
    }

    private static String decode(String segment) {
        try {
            // URLDecoder is for forms, where '+' is a space; in a path it is a plus.
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
