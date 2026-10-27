package com.torrenta.engine.transmission;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine against a real {@code transmission-daemon}, started here on free ports with a
 * throw-away config folder and a user name and password, and destroyed afterwards. Runs only where
 * the daemon is on the PATH (a developer's machine); a CI runner without it skips the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("daemonOnPath")
class TransmissionDaemonTest {

    static final String USER = "torrenta";
    static final String PASSWORD = "test-" + System.nanoTime();

    @TempDir
    static Path dir;

    private Process daemon;
    private int rpcPort;
    private EngineProfile profile;

    static boolean daemonOnPath() {
        return onPath("transmission-daemon") != null && onPath("transmission-create") != null;
    }

    static String onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String d : path.split(File.pathSeparator)) {
            for (String name : List.of(program, program + ".exe")) {
                Path candidate = Path.of(d, name);
                if (Files.isExecutable(candidate)) return candidate.toString();
            }
        }
        return null;
    }

    @BeforeAll
    void startDaemon() throws Exception {
        rpcPort = freePort();
        int peerPort = freePort();
        Path config = dir.resolve("daemon-config");
        Path downloads = dir.resolve("daemon-downloads");
        Files.createDirectories(config);
        Files.createDirectories(downloads);
        daemon = new ProcessBuilder(
                        onPath("transmission-daemon"),
                        "-f",
                        "--config-dir",
                        config.toString(),
                        "--port",
                        Integer.toString(rpcPort),
                        "--auth",
                        "--username",
                        USER,
                        "--password",
                        PASSWORD,
                        "--download-dir",
                        downloads.toString(),
                        "--peerport",
                        Integer.toString(peerPort),
                        "--no-dht",
                        "--no-lpd",
                        "--no-portmap",
                        "--no-utp",
                        "--log-level=error")
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("daemon.log").toFile())
                .start();
        profile = new EngineProfile(
                "t", "Test daemon", EngineProfile.Kind.REMOTE, "http://127.0.0.1:" + rpcPort, USER, PASSWORD);
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder(URI.create(profile.url() + EngineProfile.RPC_PATH))
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() > 0) break;
            } catch (IOException e) {
                if (!daemon.isAlive())
                    throw new AssertionError("the daemon died: " + Files.readString(dir.resolve("daemon.log")));
                if (System.currentTimeMillis() > deadline)
                    throw new AssertionError("the daemon never listened on " + rpcPort);
                Thread.sleep(100);
            }
        }
    }

    @AfterAll
    void stopDaemon() throws Exception {
        if (daemon == null) return;
        daemon.destroy();
        if (!daemon.waitFor(5, TimeUnit.SECONDS)) daemon.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        assertFalse(daemon.isAlive(), "no daemon left behind");
    }

    private TransmissionEngine engine() {
        return new TransmissionEngine(profile, () -> dir.resolve("local-downloads"), EngineSettings.defaults());
    }

    /** A two-file torrent made by the daemon's own tool, with the files where a seed finds them. */
    private byte[] twoFileTorrent(Path root, String name) throws Exception {
        Path folder = root.resolve(name);
        Files.createDirectories(folder.resolve("sub"));
        byte[] a = new byte[300 * 1024];
        byte[] b = new byte[70 * 1024];
        new Random(7).nextBytes(a);
        new Random(8).nextBytes(b);
        Files.write(folder.resolve("a.bin"), a);
        Files.write(folder.resolve("sub").resolve("b.bin"), b);
        Path out = root.resolve(name + ".torrent");
        Process create = new ProcessBuilder(
                        onPath("transmission-create"),
                        "-o",
                        out.toString(),
                        "-t",
                        "udp://tracker.example.org:6969/announce",
                        folder.toString())
                .redirectErrorStream(true)
                .redirectOutput(root.resolve("create.log").toFile())
                .start();
        assertEquals(0, create.waitFor(), Files.readString(root.resolve("create.log")));
        return Files.readAllBytes(out);
    }

    @Test
    void theProbeAndTheWrongPasswordBothAnswer() throws Exception {
        TransmissionEngine.Daemon d = TransmissionEngine.probe(profile);
        assertTrue(d.version().startsWith("4."), d.version());
        assertTrue(d.rpcVersion() >= 17, "rpc " + d.rpcVersion());
        assertTrue(d.downloadDir().endsWith("daemon-downloads"));
        EngineException wrong =
                assertThrows(EngineException.class, () -> TransmissionEngine.probe(profile.withPassword("nope")));
        assertTrue(wrong.getMessage().contains("refused the username or password"), wrong.getMessage());
    }

    @Test
    void addsSeedsLabelsLimitsAndRemovesAgainstTheRealDaemon(@TempDir Path work) throws Exception {
        Path seeds = work.resolve("seeds");
        byte[] torrent = twoFileTorrent(seeds, "pair");
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (TransmissionEngine e = engine()) {
            e.addListener(events::add);
            e.start();
            assertTrue(e.description().startsWith("Transmission 4."), e.description());
            Engine.Capabilities caps = e.capabilities();
            assertTrue(caps.lacks(Engine.Capability.CREATE_TORRENT));
            assertTrue(caps.has(Engine.Capability.LOCAL_FILES), "the daemon is on this machine");
            assertTrue(e.torrents().isEmpty());

            TorrentPreview preview = e.inspect(torrent);
            assertEquals("pair", preview.name());
            assertEquals(2, preview.files().size());

            // The .torrent, seeded from where the files are, with one file skipped, filed and tagged.
            AddRequest request = new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    seeds,
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(1, Priority.IGNORE),
                    new TorrentLabels("Movies", List.of("tv")));
            Torrent added = e.add(request);
            assertEquals(preview.id(), added.id());
            assertEquals("pair", added.name());
            await("the seed to be checked", () -> {
                Torrent t = find(e, preview.id());
                return t != null && (t.state() == TorrentState.SEEDING || t.state() == TorrentState.FINISHED);
            });
            Torrent seed = find(e, preview.id());
            // The daemon counts pieces it already has towards sizeWhenDone, wanted or not, so a
            // complete torrent with a file left out is a seed to it; a download with one skipped
            // would be finished short of the total.
            assertTrue(
                    seed.state() == TorrentState.SEEDING || seed.state() == TorrentState.FINISHED,
                    seed.state().name());
            assertTrue(seed.size() >= 300 * 1024, "at least the wanted bytes: " + seed.size());
            assertEquals(seed.size(), seed.done());
            assertEquals("Movies", seed.category());
            assertEquals(List.of("tv"), seed.tags());
            assertEquals(
                    seeds.toRealPath().toString(),
                    Path.of(seed.savePath()).toRealPath().toString());
            assertEquals(List.of("tracker.example.org"), seed.trackers());

            TorrentDetail detail = e.detail(seed.id());
            assertEquals(2, detail.files().size());
            assertEquals(Priority.IGNORE, detail.files().get(1).priority());
            assertEquals(Priority.NORMAL, detail.files().get(0).priority());
            assertTrue(detail.general().pieceCount() > 0);
            assertEquals(detail.general().pieceCount(), detail.pieces().length);
            assertTrue(detail.general().piecesHave() > 0, "the checked pieces are in the bitfield");
            assertTrue(
                    List.of("not contacted", "updating", "error", "working")
                            .contains(detail.trackers().get(0).status()),
                    detail.trackers().get(0).status());
            assertTrue(e.magnetLink(seed.id()).startsWith("magnet:?xt=urn:btih:" + seed.id()));

            // Labels and limits round-trip through the daemon.
            e.setTags(seed.id(), List.of("x", "tv"));
            assertEquals(List.of("tv", "x"), e.tags(seed.id()));
            e.setCategory(seed.id(), "");
            assertEquals("", e.category(seed.id()));
            assertEquals(List.of("tv", "x"), e.tags(seed.id()), "the tags stay when the category goes");
            e.setLimits(seed.id(), new TorrentLimits(200_000, 50_000, 12, 0, false));
            TorrentLimits limits = e.limits(seed.id());
            assertEquals(200_000, limits.downloadLimit());
            assertEquals(50_000, limits.uploadLimit());
            assertEquals(12, limits.maxConnections());
            e.setFilePriority(seed.id(), 1, Priority.HIGH);
            assertEquals(Priority.HIGH, e.detail(seed.id()).files().get(1).priority());
            e.addTrackers(seed.id(), List.of("udp://b.example.org:1/announce"));
            assertEquals(2, e.detail(seed.id()).trackers().size());
            e.removeTracker(seed.id(), "udp://b.example.org:1/announce");
            assertEquals(1, e.detail(seed.id()).trackers().size());

            // A folder renamed on the daemon's disk, a clash refused before the daemon is asked.
            assertThrows(EngineException.class, () -> e.renamePath(seed.id(), "pair/sub", "a.bin"));
            e.renamePath(seed.id(), "pair/sub", "extras");
            await(
                    "the folder renamed",
                    () -> e.detail(seed.id()).files().stream()
                            .anyMatch(f -> f.path().equals("pair/extras/b.bin")));
            assertTrue(Files.exists(seeds.resolve("pair").resolve("extras").resolve("b.bin")));

            e.pause(seed.id());
            await("paused", () -> find(e, preview.id()).paused());
            e.resume(seed.id());
            await("resumed", () -> !find(e, preview.id()).paused());
            e.recheck(seed.id());
            e.reannounce(seed.id());

            // A magnet, paused, with sequential download where the daemon knows it.
            String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Torrenta%20test";
            Torrent m = e.add(new AddRequest(
                    new AddRequest.Magnet(magnet),
                    work.resolve("local-downloads"),
                    true,
                    true,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    TorrentLabels.NONE));
            assertEquals("0123456789abcdef0123456789abcdef01234567", m.id());
            await("the magnet listed", () -> find(e, m.id()) != null);
            Torrent listed = find(e, m.id());
            assertEquals(TorrentState.FETCHING_METADATA, listed.state());
            assertTrue(listed.paused());
            if (caps.has(Engine.Capability.SEQUENTIAL_DOWNLOAD)) {
                assertTrue(listed.sequential(), "sequential_download went with the add");
                e.setSequential(m.id(), false);
                await("sequential off", () -> !find(e, m.id()).sequential());
            } else {
                assertThrows(EngineException.class, () -> e.setSequential(m.id(), true));
            }
            assertThrows(EngineException.class, () -> e.add(magnet), "a duplicate is refused");

            // Settings: one changed key reaches the daemon, nothing else of the defaults.
            EngineSettings s = EngineSettings.defaults();
            EngineSettings.Speed sp = s.speed();
            e.applySettings(s.withSpeed(new EngineSettings.Speed(
                    0,
                    0,
                    sp.alternativeDownload(),
                    sp.alternativeUpload(),
                    true,
                    sp.scheduleOn(),
                    sp.scheduleFrom(),
                    sp.scheduleTo(),
                    sp.scheduleDays(),
                    sp.countOverhead())));
            assertTrue(e.refreshLimits(false), "the alternative pair is on in the daemon, whatever is asked");
            assertEquals(2, e.torrents().size());
            assertTrue(e.stats().sessionSeconds() >= 0);

            e.remove(seed.id(), false);
            e.remove(m.id(), false);
            await("both gone", () -> e.torrents().isEmpty());
            assertTrue(
                    Files.exists(seeds.resolve("pair").resolve("a.bin")), "the files stay without delete-local-data");
        }
        List<String> lines = events.stream().map(EngineEvent::message).toList();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Engine started: Transmission 4.")), lines.toString());
        assertTrue(lines.contains("Added pair"), lines.toString());
        assertTrue(lines.contains("Added Torrenta test"), lines.toString());
        assertTrue(lines.contains("Removed pair"), lines.toString());
        assertTrue(lines.contains("Engine stopped"), lines.toString());
        assertEquals(
                2,
                events.stream()
                        .filter(ev -> ev.kind() == EngineEvent.Kind.ADDED)
                        .count(),
                "each add announced once");
    }

    @Test
    void anotherClientsAddAndRemoveReachTheIncrementalPoll(@TempDir Path work) throws Exception {
        Path seeds = work.resolve("seeds");
        byte[] torrent = twoFileTorrent(seeds, "other");
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        RpcClient other = new RpcClient(profile);
        try (TransmissionEngine e = engine()) {
            e.addListener(events::add);
            e.start();
            e.torrents();
            long fullBefore = System.nanoTime();
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("metainfo", java.util.Base64.getEncoder().encodeToString(torrent));
            args.put("download-dir", seeds.toString());
            String id = Json.str(Json.obj(other.call("torrent-add", args), "torrent-added"), "hashString", "");
            await("the other client's add listed", () -> find(e, id) != null);
            assertEquals(List.of("tracker.example.org"), find(e, id).trackers(), "read whole when new");
            for (int i = 0; i < 3; i++) {
                assertEquals(List.of("tracker.example.org"), find(e, id).trackers(), "and kept after");
            }
            other.call("torrent-remove", Map.of("ids", List.of(id)));
            await("the other client's remove seen", () -> find(e, id) == null);
            assertTrue(
                    System.nanoTime() - fullBefore < TransmissionEngine.FULL_POLL_EVERY_MILLIS * 1_000_000,
                    "seen before a full poll was due, so by the incremental ones");
        }
        List<String> lines = events.stream().map(EngineEvent::message).toList();
        assertTrue(lines.contains("Added other"), lines.toString());
        assertTrue(lines.contains("Removed other"), lines.toString());
    }

    private static Torrent find(Engine e, String id) {
        for (Torrent t : e.torrents()) if (t.id().equals(id)) return t;
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private interface Condition {
        boolean test() throws Exception;
    }

    private static void await(String what, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.test()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(100);
        }
    }
}
