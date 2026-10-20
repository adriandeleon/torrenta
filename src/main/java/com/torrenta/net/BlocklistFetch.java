package com.torrenta.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/** Downloads a blocklist, plain or gzip-compressed, bounded in time and size. */
public final class BlocklistFetch {

    /** The largest lists in the wild are tens of megabytes gzipped; this is well above them. */
    static final long MAX_BYTES = 200L << 20;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client;

    public BlocklistFetch() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build());
    }

    BlocklistFetch(HttpClient client) {
        this.client = client;
    }

    /** The list's text, decompressed if it came gzipped (by magic number, not by extension). */
    public byte[] fetch(URI url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
        if (response.statusCode() / 100 != 2)
            throw new IOException("The server answered " + response.statusCode() + " for " + url);
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("The blocklist is larger than 200 MiB; refusing it");
            return gunzipIfNeeded(bytes);
        }
    }

    /** Gzip's magic number is {@code 1f 8b}; a list served without a {@code .gz} name still has it. */
    public static byte[] gunzipIfNeeded(byte[] bytes) throws IOException {
        if (bytes.length < 2 || (bytes[0] & 0xff) != 0x1f || (bytes[1] & 0xff) != 0x8b) return bytes;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] out = in.readNBytes((int) MAX_BYTES + 1);
            if (out.length > MAX_BYTES) throw new IOException("The blocklist is larger than 200 MiB; refusing it");
            return out;
        }
    }
}
