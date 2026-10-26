package com.torrenta.http;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a handler answers: a status, headers, and a body that is bytes in memory, a file on disk,
 * or bytes a {@link Ranged} writes as they become ready (the last two sent in part for a {@code
 * Range} request, {@link Ranges}). Immutable; the {@code with…} methods return a copy.
 *
 * @param headers in order, a name possibly more than once ({@code Set-Cookie})
 * @param file a file to send instead of {@code body}; null for none
 * @param ranged a body written range by range instead of {@code body}; null for none
 */
public record Response(int status, List<Map.Entry<String, String>> headers, byte[] body, Path file, Ranged ranged) {

    /**
     * A body of a known size whose bytes are written on demand, range by range: a torrent's file
     * while it downloads, whose pieces may have to be waited for. A write that cannot finish
     * throws, and the connection is closed short, which a player takes as a cue to ask again.
     */
    public interface Ranged {
        long size();

        /** Writes bytes {@code first} to {@code first + length - 1}. */
        void write(long first, long length, java.io.OutputStream out) throws java.io.IOException;
    }

    public Response {
        headers = List.copyOf(headers);
    }

    public Response(int status, List<Map.Entry<String, String>> headers, byte[] body, Path file) {
        this(status, headers, body, file, null);
    }

    /** All of a {@link Ranged} body, or the part a {@code Range} header asks for. */
    public static Response ranged(String contentType, Ranged body) {
        return new Response(200, List.of(Map.entry("Content-Type", contentType)), new byte[0], null, body);
    }

    public static Response bytes(int status, String contentType, byte[] body) {
        return new Response(status, List.of(Map.entry("Content-Type", contentType)), body, null);
    }

    public static Response text(int status, String text) {
        return bytes(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    public static Response json(String json) {
        return bytes(200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** No body. */
    public static Response empty(int status) {
        return new Response(status, List.of(), new byte[0], null);
    }

    /** A file, all of it or the part a {@code Range} header asks for. */
    public static Response file(Path file, String contentType) {
        return new Response(200, List.of(Map.entry("Content-Type", contentType)), new byte[0], file);
    }

    public Response withHeader(String name, String value) {
        List<Map.Entry<String, String>> more = new ArrayList<>(headers);
        more.add(Map.entry(name, value));
        return new Response(status, more, body, file, ranged);
    }

    public Response withCookie(String setCookie) {
        return withHeader("Set-Cookie", setCookie);
    }
}
