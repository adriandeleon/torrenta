package com.torrenta.engine.direct;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Real curl against a server on loopback: the downloads as a person would see them. */
@EnabledIf("curlOnPath")
class DirectDownloadsTest {

    private static final byte[] SMALL = bytes(200_000);
    private static final byte[] SLOW = bytes(1 << 20);

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private final AtomicInteger ranges = new AtomicInteger();
    private final List<EngineEvent> events = new CopyOnWriteArrayList<>();
    private DirectDownloads downloads;

    static boolean curlOnPath() {
        try {
            Process p = new ProcessBuilder("curl", "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + (i >> 8));
        return b;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/files/small.bin", x -> serve(x, SMALL, 0));
        server.createContext("/files/slow.bin", x -> serve(x, SLOW, 30));
        server.createContext("/named", x -> {
            x.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"report 2026.pdf\"");
            serve(x, SMALL, 0);
        });
        server.createContext("/missing", x -> {
            x.sendResponseHeaders(404, -1);
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        downloads = open();
    }

    private DirectDownloads open() {
        DirectDownloads d = new DirectDownloads(
                dir.resolve("config"),
                () -> dir.resolve("dl").toString(),
                () -> DirectDownloads.Options.DEFAULTS,
                "Torrenta/test");
        d.addListener(events::add);
        d.start();
        return d;
    }

    /** The body, from a {@code Range: bytes=N-} if there is one, {@code pause} ms per 16 KiB. */
    private void serve(HttpExchange x, byte[] body, long pause) throws IOException {
        String range = x.getRequestHeaders().getFirst("Range");
        int from = 0;
        if (range != null && range.startsWith("bytes=")) {
            ranges.incrementAndGet();
            from = Integer.parseInt(range.substring(6, range.indexOf('-')));
            if (from >= body.length) {
                x.getResponseHeaders().add("Content-Range", "bytes */" + body.length);
                x.sendResponseHeaders(416, -1);
                x.close();
                return;
            }
            x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            x.sendResponseHeaders(206, body.length - from);
        } else {
            x.sendResponseHeaders(200, body.length);
        }
        try (OutputStream out = x.getResponseBody()) {
            for (int at = from; at < body.length; at += 16384) {
                out.write(body, at, Math.min(16384, body.length - at));
                out.flush();
                if (pause > 0) Thread.sleep(pause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client went away: a pause.
        }
    }

    @AfterEach
    void stop() {
        downloads.stop();
        server.stop(0);
    }

    private static void await(String what, BooleanSupplier condition) {
        long until = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
        fail("timed out waiting for " + what);
    }

    private Optional<Torrent> row(String id) {
        return downloads.torrents().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private boolean saw(EngineEvent.Kind kind, String id) {
        return events.stream().anyMatch(e -> e.kind() == kind && id.equals(e.torrentId()));
    }

    @Test
    void aLinkIsDownloadedIntoTheFolderUnderItsName() throws Exception {
        Torrent added = downloads.add(base + "/files/small.bin", null, false);
        assertTrue(added.direct());
        assertEquals("small.bin", added.name());
        assertTrue(saw(EngineEvent.Kind.ADDED, added.id()));
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));

        Path file = dir.resolve("dl/small.bin");
        assertArrayEquals(SMALL, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve("dl/small.bin.part")));
        Torrent done = row(added.id()).orElseThrow();
        assertEquals(TorrentState.FINISHED, done.state());
        assertEquals(SMALL.length, done.size());
        assertEquals(1.0, done.progress());
        assertTrue(done.completed() > 0);
        assertFalse(Files.exists(dir.resolve("config/downloads/" + added.id() + ".headers")), "run files tidied");
    }

    @Test
    void theSameLinkTwiceIsOneDownload() throws Exception {
        Torrent a = downloads.add(base + "/files/small.bin", null, true);
        Torrent b = downloads.add(base + "/files/small.bin", null, true);
        assertEquals(a.id(), b.id());
        assertEquals(1, downloads.torrents().size());
    }

    @Test
    void theServersNameWinsAndATakenNameIsNotOverwritten() throws Exception {
        Files.createDirectories(dir.resolve("dl"));
        Files.writeString(dir.resolve("dl/report 2026.pdf"), "mine");
        Torrent added = downloads.add(base + "/named", null, false);
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));
        assertEquals("mine", Files.readString(dir.resolve("dl/report 2026.pdf")));
        assertArrayEquals(SMALL, Files.readAllBytes(dir.resolve("dl/report 2026 (2).pdf")));
        assertEquals("report 2026 (2).pdf", row(added.id()).orElseThrow().name());
    }

    @Test
    void aNameChosenWhenAddingIsKeptOverTheServers() throws Exception {
        Torrent added = downloads.add(base + "/named", dir.resolve("mine").toString(), "my report.pdf", false);
        assertEquals("my report.pdf", added.name());
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));
        assertArrayEquals(SMALL, Files.readAllBytes(dir.resolve("mine/my report.pdf")));
        assertFalse(Files.exists(dir.resolve("mine/report 2026.pdf")));
    }

    @Test
    void aMissingFileIsAnErrorThatSaysSo() throws Exception {
        Torrent added = downloads.add(base + "/missing", null, false);
        await("the error", () -> saw(EngineEvent.Kind.ERROR, added.id()));
        Torrent row = row(added.id()).orElseThrow();
        assertEquals("The server answered HTTP 404", row.error());
        assertFalse(row.queued(), "an error is not waiting for a slot");
    }

    @Test
    void aPausedDownloadCarriesOnFromWhereItWas() throws Exception {
        Torrent added = downloads.add(base + "/files/slow.bin", null, false);
        await("some of it", () -> row(added.id()).orElseThrow().done() > 100_000);
        downloads.pause(added.id());
        Torrent paused = row(added.id()).orElseThrow();
        assertTrue(paused.paused());
        assertEquals(0, paused.downloadRate());
        long had = Files.size(dir.resolve("dl/slow.bin.part"));
        assertTrue(had > 0 && had < SLOW.length, "a part is kept: " + had);

        downloads.resume(added.id());
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));
        assertArrayEquals(SLOW, Files.readAllBytes(dir.resolve("dl/slow.bin")));
        assertTrue(ranges.get() >= 1, "the second run asked for the rest");
    }

    @Test
    void theGlobalLimitHoldsTheLinksBackAndLiftingItTakesAtOnce() throws Exception {
        downloads.setGlobalLimit(16 * 1024);
        Torrent added = downloads.add(base + "/files/small.bin", null, false);
        await("a start", () -> row(added.id()).orElseThrow().done() > 0);
        Thread.sleep(1500);
        long held = row(added.id()).orElseThrow().done();
        assertTrue(held < SMALL.length / 2, "16 KiB/s for a couple of seconds, not 200 kB: " + held);

        downloads.setGlobalLimit(0);
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));
        assertArrayEquals(SMALL, Files.readAllBytes(dir.resolve("dl/small.bin")));
        assertTrue(ranges.get() >= 1, "restarted for the new limit, carrying on where it was");
    }

    @Test
    void theListComesBackAfterARestart() throws Exception {
        Torrent added = downloads.add(base + "/files/small.bin", null, true);
        downloads.setCategory(added.id(), "ISOs");
        downloads.setTags(added.id(), List.of("b", "a"));
        downloads.stop();
        downloads = open();
        Torrent back = row(added.id()).orElseThrow();
        assertTrue(back.paused());
        assertEquals("ISOs", back.category());
        assertEquals(List.of("a", "b"), back.tags());

        downloads.resume(added.id());
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));
    }

    @Test
    void removingWithFilesDeletesThemAndForgetsTheDownload() throws Exception {
        Torrent added = downloads.add(base + "/files/small.bin", null, false);
        await("the download to finish", () -> saw(EngineEvent.Kind.FINISHED, added.id()));
        downloads.remove(added.id(), true);
        assertFalse(Files.exists(dir.resolve("dl/small.bin")));
        assertTrue(downloads.torrents().isEmpty());
        assertEquals("[]\n", Files.readString(dir.resolve("config/" + DirectDownloads.FILE)));
    }

    @Test
    void aCurlThatIsNotThereIsAnErrorThatSaysWhatToDo() throws Exception {
        downloads.stop();
        downloads = new DirectDownloads(
                dir.resolve("config"),
                () -> dir.resolve("dl").toString(),
                () -> new DirectDownloads.Options(dir.resolve("no-such-curl").toString(), 3),
                "Torrenta/test");
        downloads.addListener(events::add);
        downloads.start();
        Torrent added = downloads.add(base + "/files/small.bin", null, false);
        await("the error", () -> saw(EngineEvent.Kind.ERROR, added.id()));
        assertTrue(
                row(added.id()).orElseThrow().error().startsWith("Could not run "),
                row(added.id()).toString());
    }
}
