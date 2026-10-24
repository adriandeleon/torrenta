package com.torrenta.engine.direct;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * The final HTTP answer, read from what curl's {@code --dump-header} wrote: one block per answer,
 * a redirect's and the {@code 100 Continue}'s before the real one, so only the last block counts.
 * Pure. Empty fields for what the server did not say, and for an FTP transfer, whose dump is the
 * server's dialogue rather than headers.
 *
 * @param status the last answer's code, or 0 when there was none (yet)
 * @param total the whole file's size in bytes, or -1 when unknown: a {@code 206}'s {@code
 *     Content-Range} total, else a {@code 200}'s {@code Content-Length}
 * @param fileName the name a {@code Content-Disposition} gives, not yet made safe; empty for none
 * @param contentType the media type without its parameters, lower case; empty for none
 */
public record CurlHeaders(int status, long total, String fileName, String contentType) {

    public static final CurlHeaders NONE = new CurlHeaders(0, -1, "", "");

    public static CurlHeaders parse(String dump) {
        if (dump == null || dump.isEmpty()) return NONE;
        String[] lines = dump.split("\r?\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("HTTP/")) start = i;
        }
        if (start < 0) return NONE;
        int status = status(lines[start]);
        long length = -1;
        long rangeTotal = -1;
        String name = "";
        String type = "";
        for (int i = start + 1; i < lines.length && !lines[i].isEmpty(); i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            switch (key) {
                case "content-length" -> length = number(value);
                case "content-range" -> rangeTotal = rangeTotal(value);
                case "content-disposition" -> name = dispositionName(value);
                case "content-type" -> {
                    int semi = value.indexOf(';');
                    type = (semi < 0 ? value : value.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
                }
                default -> {}
            }
        }
        long total = status == 206 || status == 416 ? rangeTotal : status / 100 == 2 ? length : -1;
        return new CurlHeaders(status, total, name, type);
    }

    /** What the server says the file is called; empty when it says nothing. */
    public Optional<String> name() {
        return fileName.isBlank() ? Optional.empty() : Optional.of(fileName);
    }

    /** The answer is a {@code .torrent}, by its type or by the name the server gives it. */
    public boolean isTorrent() {
        return contentType.equals("application/x-bittorrent")
                || fileName.toLowerCase(Locale.ROOT).endsWith(".torrent");
    }

    private static int status(String line) {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long number(String value) {
        try {
            long n = Long.parseLong(value.trim());
            return n < 0 ? -1 : n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code bytes 100-199/1000} and {@code bytes *}{@code /1000} are 1000; {@code /*} is unknown. */
    static long rangeTotal(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? -1 : number(value.substring(slash + 1));
    }

    /**
     * RFC 6266: {@code filename*=UTF-8''a%20b.zip} wins over {@code filename="a b.zip"}, which
     * wins over a bare {@code filename=a.zip}.
     */
    static String dispositionName(String value) {
        String plain = "";
        String extended = "";
        for (String part : splitParams(value)) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = part.substring(eq + 1).trim();
            if (key.equals("filename*")) extended = extended(v);
            else if (key.equals("filename")) plain = unquote(v);
        }
        String name = extended.isEmpty() ? plain : extended;
        // A server may send a path; only its last segment is a name.
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return sep < 0 ? name : name.substring(sep + 1);
    }

    /** Splits on {@code ;} outside quotes. */
    private static java.util.List<String> splitParams(String value) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ';' && !quoted) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    /** {@code charset'language'percent-encoded}; an unknown charset is read as UTF-8. */
    private static String extended(String v) {
        int first = v.indexOf('\'');
        int second = first < 0 ? -1 : v.indexOf('\'', first + 1);
        if (second < 0) return "";
        Charset charset;
        try {
            charset = Charset.forName(v.substring(0, first).trim());
        } catch (IllegalArgumentException e) {
            charset = StandardCharsets.UTF_8;
        }
        try {
            return URLDecoder.decode(v.substring(second + 1).replace("+", "%2B"), charset);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
