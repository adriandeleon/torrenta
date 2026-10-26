package com.torrenta.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A {@code multipart/form-data} body (RFC 7578), as the *arr apps post a {@code .torrent} file:
 * parts split on the boundary, each with its name, its file name if it has one, its type, and its
 * bytes. No more than {@link Limits#maxParts} parts; anything that does not read as the format is
 * a 400. Pure.
 */
public final class Multipart {

    private Multipart() {}

    /**
     * One part.
     *
     * @param name the form field's name
     * @param fileName the uploaded file's name; empty for a plain field
     * @param contentType the part's type; {@code text/plain} when it says none
     * @param bytes the part's content
     */
    public record Part(String name, String fileName, String contentType, byte[] bytes) {
        public boolean isFile() {
            return !fileName.isEmpty();
        }

        /** A plain field's value, as UTF-8. */
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** The boundary of a {@code multipart/form-data} content type; empty for any other type. */
    public static Optional<String> boundary(String contentType) {
        if (contentType == null) return Optional.empty();
        String[] pieces = contentType.split(";");
        if (!pieces[0].trim().equalsIgnoreCase("multipart/form-data")) return Optional.empty();
        for (int i = 1; i < pieces.length; i++) {
            String p = pieces[i].trim();
            if (!p.toLowerCase(Locale.ROOT).startsWith("boundary=")) continue;
            String b = p.substring("boundary=".length());
            if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) b = b.substring(1, b.length() - 1);
            if (!b.isEmpty() && b.length() <= 70) return Optional.of(b);
        }
        return Optional.empty();
    }

    public static List<Part> parse(byte[] body, String boundary, Limits limits) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] between = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(body, delimiter, 0);
        if (at < 0) throw HttpProblem.badRequest("no multipart boundary in the body");
        List<Part> parts = new ArrayList<>();
        at += delimiter.length;
        while (true) {
            // After a delimiter: "--" ends the body, CRLF starts a part.
            if (startsWith(body, at, "--")) return List.copyOf(parts);
            if (!startsWith(body, at, "\r\n")) throw HttpProblem.badRequest("a multipart delimiter without a line end");
            at += 2;
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), at);
            if (headersEnd < 0) throw HttpProblem.badRequest("a multipart part without its headers' end");
            String headers = new String(body, at, headersEnd - at, StandardCharsets.UTF_8);
            int contentStart = headersEnd + 4;
            int contentEnd = indexOf(body, between, contentStart);
            if (contentEnd < 0) throw HttpProblem.badRequest("a multipart part that does not end");
            if (parts.size() >= limits.maxParts())
                throw HttpProblem.tooLarge("more than " + limits.maxParts() + " multipart parts");
            parts.add(part(headers, java.util.Arrays.copyOfRange(body, contentStart, contentEnd)));
            at = contentEnd + between.length;
        }
    }

    private static Part part(String headers, byte[] bytes) {
        String name = null;
        String fileName = "";
        String type = "text/plain";
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String header = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (header.equals("content-type")) type = value;
            if (!header.equals("content-disposition")) continue;
            for (String piece : value.split(";")) {
                String p = piece.trim();
                if (p.startsWith("name=")) name = unquote(p.substring("name=".length()));
                if (p.startsWith("filename=")) fileName = unquote(p.substring("filename=".length()));
            }
        }
        if (name == null) throw HttpProblem.badRequest("a multipart part without a name");
        return new Part(name, fileName, type, bytes);
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
            return text.substring(1, text.length() - 1).replace("\\\"", "\"");
        return text;
    }

    private static boolean startsWith(byte[] body, int at, String text) {
        if (at + text.length() > body.length) return false;
        for (int i = 0; i < text.length(); i++) if (body[at + i] != text.charAt(i)) return false;
        return true;
    }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
