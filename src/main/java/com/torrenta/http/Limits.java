package com.torrenta.http;

/**
 * What one request may bring: the body in bytes, and the parts of a multipart body. Past either,
 * the request is refused with 413 before a handler sees it. The API's uploads are {@code
 * .torrent} files, which are small; the defaults leave room for a batch of them and nothing more.
 *
 * @param maxBody the most a body may hold, in bytes
 * @param maxParts the most parts a multipart body may have
 */
public record Limits(long maxBody, int maxParts) {

    /** 32 MiB and 64 parts: a few dozen {@code .torrent} files in one add. */
    public static final Limits DEFAULT = new Limits(32L * 1024 * 1024, 64);

    public Limits {
        if (maxBody < 0 || maxParts < 0) throw new IllegalArgumentException("limits are not negative");
    }
}
