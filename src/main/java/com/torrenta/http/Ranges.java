package com.torrenta.http;

import java.util.Locale;

/**
 * A {@code Range} header against a file of {@code length} bytes (RFC 9110 §14), for 1.4's
 * streaming and for any file the server hands out: one range is served as 206, a range past the
 * end is 416, and anything else (no header, another unit, several ranges, a header that does not
 * read) is the whole file, which the RFC allows a server to send instead. Pure.
 */
public final class Ranges {

    private Ranges() {}

    /** What to send. */
    public sealed interface Plan permits Whole, Part, Unsatisfiable {}

    /** All of it, 200. */
    public record Whole() implements Plan {}

    /** Bytes {@code first} to {@code last}, both included, 206 with {@code Content-Range}. */
    public record Part(long first, long last) implements Plan {
        public long length() {
            return last - first + 1;
        }

        public String contentRange(long total) {
            return "bytes " + first + "-" + last + "/" + total;
        }
    }

    /** Nothing of it can be sent, 416 with {@code Content-Range: bytes *}{@code /length}. */
    public record Unsatisfiable() implements Plan {
        public String contentRange(long total) {
            return "bytes */" + total;
        }
    }

    public static Plan plan(String header, long length) {
        if (header == null) return new Whole();
        String h = header.trim().toLowerCase(Locale.ROOT);
        if (!h.startsWith("bytes=")) return new Whole();
        String spec = h.substring("bytes=".length()).trim();
        if (spec.contains(",")) return new Whole();
        int dash = spec.indexOf('-');
        if (dash < 0) return new Whole();
        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        try {
            if (from.isEmpty()) {
                // The last N bytes.
                long n = Long.parseLong(to);
                if (n <= 0 || length == 0) return new Unsatisfiable();
                return new Part(Math.max(0, length - n), length - 1);
            }
            long first = Long.parseLong(from);
            if (first < 0) return new Whole();
            if (first >= length) return new Unsatisfiable();
            long last = to.isEmpty() ? length - 1 : Long.parseLong(to);
            if (last < first) return new Whole();
            return new Part(first, Math.min(last, length - 1));
        } catch (NumberFormatException e) {
            return new Whole();
        }
    }
}
