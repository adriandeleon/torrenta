package com.torrenta.rss;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fetcher against a server of ours: a feed, a 304 for the right validators, the failures. */
class FeedFetcherTest {

    private HttpServer server;
    private URI base;
    private final AtomicReference<String> lastAgent = new AtomicReference<>("");
    private final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>("");
    private final AtomicReference<String> lastIfModifiedSince = new AtomicReference<>("");
    private final CountDownLatch hanging = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.xml", exchange -> {
            lastAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            lastIfNoneMatch.set(String.valueOf(exchange.getRequestHeaders().getFirst("If-None-Match")));
            lastIfModifiedSince.set(String.valueOf(exchange.getRequestHeaders().getFirst("If-Modified-Since")));
            if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.getResponseHeaders().add("Last-Modified", "Sun, 18 Oct 2026 09:12:00 GMT");
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            respond(exchange, 200, fixture("rss2.xml"));
        });
        server.createContext("/missing", exchange -> respond(exchange, 404, "no".getBytes(StandardCharsets.UTF_8)));
        server.createContext(
                "/html", exchange -> respond(exchange, 200, "<html><body>hi".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/slow", exchange -> {
            try {
                hanging.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, new byte[0]);
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        hanging.countDown();
        server.stop(0);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = FeedFetcherTest.class.getResourceAsStream("/com/torrenta/rss/" + name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return in.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private Feed feed(String path) {
        return new Feed("f1", base.resolve(path).toString(), "", 0);
    }

    @Test
    void aFeedComesBackWithItsValidatorsAndAnUnchangedOneIsA304() throws FeedException {
        FeedFetcher fetcher = new FeedFetcher();
        FeedSource.Result first = fetcher.fetch(feed("/feed.xml"), "", "");
        assertTrue(first.changed());
        assertEquals("Example Tracker & Friends", first.title());
        assertEquals(7, first.articles().size());
        assertEquals("f1", first.articles().get(0).feedId());
        assertEquals("\"v1\"", first.etag());
        assertEquals("Sun, 18 Oct 2026 09:12:00 GMT", first.lastModified());
        assertTrue(lastAgent.get().startsWith("Torrenta/"), "the app's User-Agent: " + lastAgent.get());
        assertEquals("null", lastIfNoneMatch.get(), "no validators the first time");

        FeedSource.Result again = fetcher.fetch(feed("/feed.xml"), first.etag(), first.lastModified());
        assertFalse(again.changed());
        assertEquals("\"v1\"", lastIfNoneMatch.get());
        assertEquals("Sun, 18 Oct 2026 09:12:00 GMT", lastIfModifiedSince.get());
        assertEquals("\"v1\"", again.etag(), "the validators sent are the ones to keep");
        assertEquals(0, again.articles().size());
    }

    @Test
    void failuresAreSaidInWords() {
        FeedFetcher fetcher = new FeedFetcher();
        assertEquals(
                "the server answered 404",
                assertThrows(FeedException.class, () -> fetcher.fetch(feed("/missing"), "", ""))
                        .getMessage());
        assertEquals(
                "not an RSS or Atom feed",
                assertThrows(FeedException.class, () -> fetcher.fetch(feed("/html"), "", ""))
                        .getMessage());
        assertEquals(
                "the URL must start with http:// or https://",
                assertThrows(FeedException.class, () -> fetcher.fetch(new Feed("x", "ftp://a/b", "", 0), "", ""))
                        .getMessage());
        assertEquals(
                "could not connect",
                assertThrows(
                                FeedException.class,
                                () -> fetcher.fetch(new Feed("x", "http://127.0.0.1:1/feed", "", 0), "", ""))
                        .getMessage());
    }

    @Test
    void aServerThatNeverAnswersIsATimeoutNotAHang() {
        assertEquals(Duration.ofSeconds(20), FeedFetcher.TIMEOUT, "the real limit; the test uses a short one");
        FeedFetcher quick = new FeedFetcher(HttpClient.newHttpClient(), Duration.ofMillis(300));
        FeedException e = assertThrows(FeedException.class, () -> quick.fetch(feed("/slow"), "", ""));
        assertEquals("timed out after 0 s", e.getMessage());
    }
}
