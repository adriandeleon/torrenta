package com.torrenta.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static com.torrenta.i18n.Messages.tr;

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

    /** Why a request failed, as the status bar says it: the JDK's exceptions often carry no message. */
    static String reason(IOException e, Duration timeout) {
        if (e instanceof ConnectException) return tr("net.couldNotConnect");
        if (e instanceof UnknownHostException) return tr("net.unknownHost", e.getMessage());
        if (e instanceof HttpTimeoutException) return tr("net.timedOut", timeout.toSeconds());
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
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
            throw new IOException(tr("net.fetchInterrupted", url), e);
        } catch (IOException e) {
            throw new IOException(tr("net.fetchFailed", url, TorrentFetch.reason(e, TIMEOUT)), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException(tr("net.fetchStatus", String.valueOf(response.statusCode()), url));
        }
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException(tr("net.torrentTooLarge"));
            return bytes;
        }
    }
}
