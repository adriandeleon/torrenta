package com.torrenta.stream;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.torrenta.engine.Engine;
import com.torrenta.engine.PieceMath;
import com.torrenta.engine.StreamFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server over real loopback HTTP, on a fake engine whose file waits on a latch. */
class StreamServerTest {

    static final String ID = "a".repeat(40);
    static final int PIECE = 1000;

    private final HttpClient client = HttpClient.newHttpClient();

    /** A file of {@code bytes} in pieces of {@link #PIECE}; its pieces are there once {@code ready} opens. */
    private static StreamFile file(Path path, byte[] bytes, CountDownLatch ready) {
        return new StreamFile() {
            @Override
            public String name() {
                return "Film.mkv";
            }

            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public long chunkEnd(long from) {
                return PieceMath.lastByteOf(PieceMath.span(0, PIECE, from, from).first(), 0, bytes.length, PIECE);
            }

            @Override
            public boolean await(long from, long to, Duration timeout) throws InterruptedException {
                return ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            @Override
            public Path path() {
                return path;
            }
        };
    }

    private static Engine engine(StreamFile file) {
        return (Engine) Proxy.newProxyInstance(
                Engine.class.getClassLoader(),
                new Class<?>[] {Engine.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stream" -> file;
                    case "toString" -> "stream engine";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private HttpResponse<byte[]> get(String url, String range) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if (range != null) b.header("Range", range);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7 + 3);
        return b;
    }

    @Test
    void aTokensFileWholeOrByRangeOnLoopbackAndNothingElse(@TempDir Path dir) throws Exception {
        byte[] content = bytes(5_500);
        Path path = Files.write(dir.resolve("Film.mkv"), content);
        try (StreamServer server = new StreamServer(() -> engine(file(path, content, new CountDownLatch(0))))) {
            String url = server.url(ID, 3, "Film (2026).mkv");
            assertTrue(url.startsWith("http://127.0.0.1:" + server.port() + "/s/"), url);
            assertTrue(url.endsWith("/" + ID + "/3/Film%20%282026%29.mkv"), url);

            HttpResponse<byte[]> whole = get(url, null);
            assertEquals(200, whole.statusCode());
            assertArrayEquals(content, whole.body());
            assertEquals(
                    "video/x-matroska",
                    whole.headers().firstValue("Content-Type").orElse(""));
            assertEquals("bytes", whole.headers().firstValue("Accept-Ranges").orElse(""));

            // Across a piece's end: two chunks, one answer.
            HttpResponse<byte[]> part = get(url, "bytes=990-2009");
            assertEquals(206, part.statusCode());
            assertEquals(
                    "bytes 990-2009/5500",
                    part.headers().firstValue("Content-Range").orElse(""));
            assertArrayEquals(Arrays.copyOfRange(content, 990, 2010), part.body());
            assertArrayEquals(
                    Arrays.copyOfRange(content, 5000, 5500),
                    get(url, "bytes=-500").body());
            assertEquals(416, get(url, "bytes=6000-").statusCode());

            HttpResponse<Void> head = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(200, head.statusCode());
            assertEquals("5500", head.headers().firstValue("Content-Length").orElse(""));

            String token = url.split("/")[4];
            String base = "http://127.0.0.1:" + server.port() + "/s/";
            assertEquals(404, get(base + "nope/" + ID + "/3/x", null).statusCode(), "a token never handed out");
            assertEquals(404, get(base + token + "/" + ID + "/4/x", null).statusCode(), "another file of the torrent");
            assertEquals(
                    404, get(base + token + "/" + "b".repeat(40) + "/3/x", null).statusCode(), "another torrent");
            assertEquals(404, get(base, null).statusCode(), "no listing");
            assertEquals(
                    404, get("http://127.0.0.1:" + server.port() + "/", null).statusCode());
            assertFalse(server.url(ID, 3, "x").contains(token), "a new token for each URL handed out");
        }
    }

    @Test
    void aRangeWaitsForItsPiecesAndACloseEndsTheWait(@TempDir Path dir) throws Exception {
        byte[] content = bytes(3_000);
        Path path = Files.write(dir.resolve("Film.mkv"), content);
        CountDownLatch ready = new CountDownLatch(1);
        try (StreamServer server = new StreamServer(() -> engine(file(path, content, ready)))) {
            String url = server.url(ID, 0, "Film.mkv");
            CompletableFuture<HttpResponse<byte[]>> asked = CompletableFuture.supplyAsync(() -> {
                try {
                    return get(url, "bytes=1500-1599");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(500);
            assertFalse(asked.isDone(), "waiting for its piece");
            ready.countDown();
            HttpResponse<byte[]> answer = asked.get(10, TimeUnit.SECONDS);
            assertEquals(206, answer.statusCode());
            assertArrayEquals(Arrays.copyOfRange(content, 1500, 1600), answer.body());
        }

        // A piece that never comes: the answer is cut short at the timeout, and a close ends it sooner.
        CountDownLatch never = new CountDownLatch(1);
        StreamServer server = new StreamServer(() -> engine(file(path, content, never)), Duration.ofSeconds(30));
        String url = server.url(ID, 0, "Film.mkv");
        CompletableFuture<HttpResponse<byte[]>> stuck = CompletableFuture.supplyAsync(() -> {
            try {
                return get(url, "bytes=0-99");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500);
        long closing = System.nanoTime();
        server.close();
        assertTrue(
                Duration.ofNanos(System.nanoTime() - closing).toSeconds() < 5, "the close does not wait out the piece");
        assertThrows(Exception.class, () -> stuck.get(10, TimeUnit.SECONDS).body(), "cut short");
    }

    @Test
    void theContentTypeFollowsTheExtension() {
        assertEquals("video/mp4", StreamServer.contentType("a.MP4"));
        assertEquals("audio/flac", StreamServer.contentType("track.flac"));
        assertEquals("application/octet-stream", StreamServer.contentType("notes"));
        assertEquals(new PieceMath.Span(1, 2), PieceMath.span(500, 1000, 600, 2000));
        assertEquals(1499, PieceMath.lastByteOf(1, 500, 10_000, 1000), "piece 1 ends 1999 into the torrent");
        assertEquals(99, PieceMath.lastByteOf(9, 0, 100, 1000), "never past the file's end");
    }
}
