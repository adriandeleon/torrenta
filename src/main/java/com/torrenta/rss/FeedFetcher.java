package com.torrenta.rss;

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
import java.util.Optional;
import javax.xml.stream.XMLStreamException;

import com.torrenta.AppInfo;

/**
 * Fetches a feed over HTTP: the app's User-Agent, redirects followed, 20 s for the whole request,
 * the body bounded, and a conditional request when the last answer carried an {@code ETag} or a
 * {@code Last-Modified}, so an unchanged feed costs one 304. The shape of the search client's GET.
 */
public final class FeedFetcher implements FeedSource {

    /** How long one request may take, connect included. */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** A feed is kilobytes; anything past this is not one. */
    static final long MAX_BYTES = 20L << 20;

    static final String USER_AGENT = AppInfo.NAME + "/" + AppInfo.VERSION;

    private final HttpClient client;
    private final Duration timeout;

    public FeedFetcher() {
        this(defaultClient(), TIMEOUT);
    }

    /** @param timeout the request's limit; a test gives a short one against a server that never answers */
    public FeedFetcher(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    /** One connection pool for every feed, redirects followed, the connect bounded. */
    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public Result fetch(Feed feed, String etag, String lastModified) throws FeedException {
        Optional<String> problem = Feed.problem(feed.url());
        if (problem.isPresent()) throw new FeedException(problem.get());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(feed.url()))
                .timeout(timeout)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .header("User-Agent", USER_AGENT)
                .GET();
        if (etag != null && !etag.isBlank()) builder.header("If-None-Match", etag.trim());
        if (lastModified != null && !lastModified.isBlank()) builder.header("If-Modified-Since", lastModified.trim());
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new FeedException("timed out after " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new FeedException(reason(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedException("interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new FeedException("the URL is not usable: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status == 304) {
            close(response);
            return Result.unchanged(etag, lastModified);
        }
        if (status / 100 != 2) {
            close(response);
            throw new FeedException("the server answered " + status);
        }
        String newEtag = response.headers().firstValue("ETag").orElse("");
        String newModified = response.headers().firstValue("Last-Modified").orElse("");
        try (InputStream in = new BoundedInput(response.body(), MAX_BYTES)) {
            FeedParser.Parsed parsed = FeedParser.parse(in, feed.id());
            return new Result(true, parsed.title(), parsed.articles(), newEtag, newModified);
        } catch (XMLStreamException e) {
            throw new FeedException("not an RSS or Atom feed", e);
        } catch (IOException e) {
            throw new FeedException(reason(e), e);
        }
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
            if (left <= 0) throw new IOException("the feed is larger than 20 MiB");
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("the feed is larger than 20 MiB");
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
