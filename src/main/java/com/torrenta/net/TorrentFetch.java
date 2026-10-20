package com.torrenta.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Downloads a {@code .torrent} from a URL: bounded in time and size, and nothing else. */
public final class TorrentFetch {

    /** A .torrent is kilobytes; anything bigger is not one. */
    static final long MAX_BYTES = 10L << 20;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    public TorrentFetch() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build());
    }

    TorrentFetch(HttpClient client) {
        this.client = client;
    }

    public byte[] fetch(URI url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("Accept", "application/x-bittorrent, */*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("The server answered " + response.statusCode() + " for " + url);
        }
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("That is not a .torrent: more than 10 MiB");
            return bytes;
        }
    }
}
