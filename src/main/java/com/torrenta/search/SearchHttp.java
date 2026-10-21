package com.torrenta.search;

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
import java.util.Map;
import javax.xml.stream.XMLStreamException;

import com.torrenta.AppInfo;

/**
 * The one HTTP GET every search provider makes: the app's User-Agent, a 20 s limit, redirects
 * followed by the client, the body bounded, and a status or a failure said in the words a status
 * line shows. Package-private; the providers wrap it.
 */
final class SearchHttp {

    /** How long one request may take, connect included. */
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** A result page is kilobytes; anything past this is not one. */
    static final long MAX_BYTES = 20L << 20;

    static final String USER_AGENT = AppInfo.NAME + "/" + AppInfo.VERSION;

    private SearchHttp() {}

    /** The client every provider shares: one connection pool, redirects followed, bounded. */
    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * GETs {@code uri} with {@code headers} over the defaults (a header given here wins), and
     * returns the body, bounded, for a 2xx; a 429 says so with the Retry-After, a 401 or 403
     * says {@code refused}, anything else its number.
     */
    static InputStream get(HttpClient client, URI uri, Map<String, String> headers, String refused)
            throws SearchException {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        boolean agent = false;
        boolean accept = false;
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("User-Agent")) agent = true;
            if (h.getKey().equalsIgnoreCase("Accept")) accept = true;
            try {
                builder.header(h.getKey(), h.getValue());
            } catch (IllegalArgumentException e) {
                throw new SearchException("the header " + h.getKey() + " is not allowed: " + e.getMessage(), e);
            }
        }
        if (!agent) builder.header("User-Agent", USER_AGENT);
        if (!accept) builder.header("Accept", "*/*");
        HttpRequest request = builder.build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new SearchException("timed out after " + TIMEOUT.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new SearchException(reason(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException("interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new SearchException("the URL is not usable: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status == 429) {
            String retry = response.headers().firstValue("Retry-After").orElse("");
            close(response);
            throw new SearchException(
                    "rate limited by the server" + (retry.isEmpty() ? "" : "; retry after " + retry + " s"));
        }
        if (status == 401 || status == 403) {
            close(response);
            throw new SearchException(refused + " (" + status + ")");
        }
        if (status / 100 != 2) {
            close(response);
            throw new SearchException("the server answered " + status);
        }
        return new BoundedInput(response.body(), MAX_BYTES);
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // The body is not wanted; its state is of no interest.
        }
    }

    /** An exception's message, as a status line should say it. */
    static String reason(Exception e) {
        if (e instanceof ConnectException) return "could not connect";
        if (e instanceof UnknownHostException) return "unknown host " + e.getMessage();
        if (e instanceof HttpTimeoutException) return "timed out after " + TIMEOUT.toSeconds() + " s";
        if (e instanceof XMLStreamException) return "not Torznab XML";
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Reads at most {@code limit} bytes, and says so rather than going on. */
    private static final class BoundedInput extends InputStream {
        private final InputStream in;
        private long left;

        BoundedInput(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("the answer is larger than 20 MiB");
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("the answer is larger than 20 MiB");
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
