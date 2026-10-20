package com.torrenta.engine.libtorrent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libtorrent4j.TorrentBuilder;
import org.libtorrent4j.TorrentStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the real engine. This is the test that proves the native library unpacks and loads on the
 * platform the build runs on, which is the whole risk of a JNI dependency — so it runs on all three
 * CI platforms, and it is not mocked.
 */
class LibtorrentEngineTest {

    private static final String MAGNET =
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Torrenta%20test";
    private static final String MAGNET_ID = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void startsAddsAMagnetAndStops(@TempDir Path dir) throws EngineException {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            assertTrue(engine.description().startsWith("libtorrent "), engine.description());

            Torrent added = engine.add(MAGNET);
            assertEquals(MAGNET_ID, added.id());
            assertEquals("Torrenta test", added.name());
            assertNotEquals(TorrentState.UNKNOWN, added.state());

            List<Torrent> all = engine.torrents();
            assertEquals(1, all.size());
            assertEquals(added.id(), all.get(0).id());
        }
    }

    @Test
    void aMagnetIsStoredOnAddAndComesBackAfterARestart(@TempDir Path dir) throws Exception {
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            engine.add(MAGNET);
            // Written from the add alert, on the alert thread: shortly after, not synchronously.
            await("resume data written on add", () -> store.contains(MAGNET_ID));
        }
        assertEquals(1, store.loadAll().size(), "stop() keeps it");

        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine again = engine(dir, store)) {
            again.addListener(events::add);
            again.start();
            List<Torrent> back = again.torrents();
            assertEquals(1, back.size());
            assertEquals(MAGNET_ID, back.get(0).id());
            assertEquals("Torrenta test", back.get(0).name(), "the magnet's name survives too");
            await(
                    "restored event, not an added one",
                    () -> events.stream().anyMatch(e -> e.message().equals("Restored Torrenta test")));
        }
    }

    @Test
    void aRealTorrentSeedsItsOwnFileAndComesBackCompleteAfterARestart(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Files.createDirectories(downloads);
        Path payload = downloads.resolve("payload.bin");
        byte[] bytes = new byte[256 * 1024];
        new Random(42).nextBytes(bytes);
        Files.write(payload, bytes);

        NativeLibrary.ensureAvailable(); // TorrentBuilder is native too
        Path torrentFile = dir.resolve("payload.torrent");
        Files.write(
                torrentFile,
                new TorrentBuilder()
                        .path(payload.toFile())
                        .addTracker("udp://tracker.example.org:6969/announce")
                        .generate()
                        .entry()
                        .bencode());

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String id;
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, store)) {
            engine.start();
            Torrent added = engine.add(torrentFile.toString());
            id = added.id();
            assertEquals("payload.bin", added.name());
            assertEquals(bytes.length, added.size());
            // The data is already there, so checking ends in seeding.
            await("checked and seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
        }
        assertTrue(store.contains(id), "resume data written on stop");

        try (LibtorrentEngine again = new LibtorrentEngine(downloads, store)) {
            again.start();
            Torrent back = again.torrents().get(0);
            assertEquals(id, back.id());
            assertEquals("payload.bin", back.name());
            // Linux and macOS report 100 % straight from the resume data. Windows was seen at 0 %
            // for a moment after start (libtorrent re-checks when the recorded file times do not
            // match, and NTFS keeps them at a different precision), so: wait for complete, bounded.
            await("complete and seeding after the restart", () -> {
                Torrent t = again.torrents().get(0);
                return t.progress() >= 1.0 - 1e-6 && t.state() == TorrentState.SEEDING;
            });
        }
        try (LibtorrentEngine third = new LibtorrentEngine(downloads, store)) {
            third.start();
            assertEquals(1, third.detail(id).trackers().size(), "the tracker survives the resume data");
        }
    }

    @Test
    void removeForgetsTheTorrent(@TempDir Path dir) throws Exception {
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            engine.add(MAGNET);
            await("stored", () -> store.contains(MAGNET_ID));
            engine.remove(MAGNET_ID, false);
            assertFalse(store.contains(MAGNET_ID));
            await("gone from the session", () -> engine.torrents().isEmpty());
            assertThrows(EngineException.class, () -> engine.remove(MAGNET_ID, false), "twice is an error");
        }
        assertTrue(store.loadAll().isEmpty(), "stop() does not resurrect it");
    }

    @Test
    void actionsOnAMagnet(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent added = engine.add(MAGNET);
            assertFalse(added.paused());
            assertFalse(added.sequential());

            engine.pause(MAGNET_ID);
            await("paused", () -> engine.torrents().get(0).paused());
            engine.resume(MAGNET_ID);
            await("resumed", () -> !engine.torrents().get(0).paused());
            engine.forceResume(MAGNET_ID);
            await("still running", () -> !engine.torrents().get(0).paused());

            engine.setSequential(MAGNET_ID, true);
            await("sequential on", () -> engine.torrents().get(0).sequential());
            engine.setSequential(MAGNET_ID, false);
            await("sequential off", () -> !engine.torrents().get(0).sequential());

            engine.recheck(MAGNET_ID); // no metadata: harmless
            engine.reannounce(MAGNET_ID);
            String magnet = engine.magnetLink(MAGNET_ID);
            assertTrue(magnet.startsWith("magnet:?"), magnet);
            assertTrue(magnet.contains(MAGNET_ID), magnet);

            assertThrows(EngineException.class, () -> engine.pause("0000000000000000000000000000000000000000"));
        }
    }

    @Test
    void eventsForTheLifeOfATorrent(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Files.createDirectories(downloads);
        Path payload = downloads.resolve("payload.bin");
        Files.write(payload, new byte[64 * 1024]);
        NativeLibrary.ensureAvailable();
        Path torrentFile = dir.resolve("payload.torrent");
        Files.write(
                torrentFile,
                new TorrentBuilder()
                        .path(payload.toFile())
                        .addTracker("udp://tracker.example.org:6969/announce")
                        .generate()
                        .entry()
                        .bencode());

        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.addListener(events::add);
            engine.start();
            await(
                    "engine started event",
                    () -> events.stream().anyMatch(e -> e.message().startsWith("Engine started")));

            engine.add(MAGNET);
            await(
                    "added event names the magnet",
                    () -> events.stream()
                            .anyMatch(e -> e.level() == EngineEvent.Level.INFO
                                    && e.message().equals("Added Torrenta test")));

            Torrent real = engine.add(torrentFile.toString());
            await(
                    "checked event for the real torrent",
                    () -> events.stream()
                            .anyMatch(e -> real.id().equals(e.torrentId())
                                    && e.message().equals("payload.bin checked")));

            engine.remove(MAGNET_ID, false);
            assertTrue(events.stream().anyMatch(e -> e.message().equals("Removed Torrenta test")));
            assertTrue(events.stream().allMatch(e -> e.at() != null && e.torrent() != null));
        }
        assertTrue(events.stream().anyMatch(e -> e.message().equals("Engine stopped")));
    }

    /** A two-file torrent whose data is already in {@code downloads/<name>/}. */
    private static byte[] twoFileTorrent(Path downloads, String name) throws Exception {
        Path root = downloads.resolve(name);
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("a.bin"), new byte[100 * 1024]);
        Files.write(root.resolve("sub").resolve("b.bin"), new byte[50 * 1024]);
        NativeLibrary.ensureAvailable();
        return new TorrentBuilder()
                .path(root.toFile())
                .addTracker("udp://tracker.example.org:6969/announce")
                .generate()
                .entry()
                .bencode();
    }

    @Test
    void inspectShowsTheFilesWithoutAdding(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            TorrentPreview preview = engine.inspect(torrent);
            assertEquals("pair", preview.name());
            assertEquals(150 * 1024, preview.size());
            assertEquals(2, preview.files().size());
            assertEquals("pair/a.bin", preview.files().get(0).path());
            assertEquals(100 * 1024, preview.files().get(0).size());
            assertEquals("pair/sub/b.bin", preview.files().get(1).path());
            assertTrue(preview.hasRootFolder());
            assertFalse(preview.isPrivate());
            assertEquals(40, preview.id().length());
            assertEquals(
                    List.of("udp://tracker.example.org:6969/announce"),
                    preview.trackers(),
                    "trackers come with the file");
            assertThrows(EngineException.class, () -> engine.inspect("not bencode".getBytes()));
        }
    }

    @Test
    void addWithOptions(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            // Indices come from the preview: a hybrid torrent has padding files between the real ones.
            TorrentPreview preview = engine.inspect(torrent);
            int b = preview.files().stream()
                    .filter(f -> f.path().endsWith("b.bin"))
                    .findFirst()
                    .orElseThrow()
                    .index();
            AddRequest paused = new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    true,
                    true,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(b, Priority.IGNORE));
            Torrent t = engine.add(paused);
            assertTrue(t.paused(), "start paused");
            assertTrue(t.sequential());
            assertTrue(t.size() >= 100 * 1024 && t.size() < 150 * 1024, "b.bin is not wanted: " + t.size());
            engine.remove(t.id(), false);
            await("gone", () -> engine.torrents().isEmpty());

            // Skip check: seed mode trusts the files, so it is seeding without a pass over the disk.
            AddRequest trusted = new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    false,
                    false,
                    true,
                    AddRequest.StopCondition.NONE,
                    Map.of());
            Torrent seeded = engine.add(trusted);
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            assertEquals(1.0, engine.torrents().get(0).progress(), 1e-6);
            engine.remove(seeded.id(), false);
            await("gone again", () -> engine.torrents().isEmpty());
        }
    }

    @Test
    void detailOfASeededTorrent(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);

            TorrentDetail detail = engine.detail(t.id());
            TorrentDetail.General g = detail.general();
            assertEquals(t.id(), g.id());
            assertEquals(downloads.toString(), g.savePath());
            assertTrue(g.pieceCount() > 0);
            assertEquals(g.pieceCount(), g.piecesHave(), "complete");
            assertEquals(g.pieceCount(), detail.pieces().length);
            for (boolean have : detail.pieces()) assertTrue(have);
            assertTrue(g.pieceLength() > 0);
            assertFalse(g.isPrivate());
            assertTrue(g.activeSeconds() < 600, "seconds, not milliseconds: " + g.activeSeconds());
            assertTrue(g.seedingSeconds() <= g.activeSeconds());

            assertEquals(2, detail.files().size(), "padding files hidden");
            TorrentDetail.FileStatus a = detail.files().get(0);
            assertEquals("pair/a.bin", a.path());
            assertEquals(a.size(), a.done());
            assertEquals(1.0, a.progress(), 1e-9);
            assertEquals(Priority.NORMAL, a.priority());

            int bIndex = detail.files().get(1).index();
            engine.setFilePriority(t.id(), bIndex, Priority.IGNORE);
            await("priority applied", () -> engine.detail(t.id()).files().get(1).priority() == Priority.IGNORE);
            engine.setFilePriority(t.id(), bIndex, Priority.HIGH);
            await(
                    "priority applied again",
                    () -> engine.detail(t.id()).files().get(1).priority() == Priority.HIGH);

            assertTrue(detail.peers().isEmpty(), "nobody is connected in a test");

            assertEquals(1, detail.trackers().size(), "the one the torrent was built with");
            assertEquals(
                    "udp://tracker.example.org:6969/announce",
                    detail.trackers().get(0).url());
            engine.addTrackers(
                    t.id(),
                    List.of(
                            "udp://tracker.example.org:6969/announce",
                            " ",
                            "http://t.example.com/announce",
                            "http://u.example.com/announce"));
            await(
                    "three trackers, no duplicate",
                    () -> engine.detail(t.id()).trackers().size() == 3);
            List<TorrentDetail.Tracker> trackers = engine.detail(t.id()).trackers();
            assertTrue(trackers.stream()
                    .map(TorrentDetail.Tracker::url)
                    .toList()
                    .containsAll(List.of("http://t.example.com/announce", "http://u.example.com/announce")));
            assertTrue(
                    trackers.stream().filter(x -> x.url().startsWith("http://")).allMatch(x -> x.tier() == 1),
                    "added after the existing tier");
            engine.removeTracker(t.id(), "http://t.example.com/announce");
            await("two trackers", () -> engine.detail(t.id()).trackers().size() == 2);
        }
    }

    @Test
    void peerFlagsReadAsLetters() throws Exception {
        NativeLibrary.ensureAvailable();
        int interesting = org.libtorrent4j.swig.peer_info.interesting.to_int();
        int choked = org.libtorrent4j.swig.peer_info.choked.to_int();
        int remoteInterested = org.libtorrent4j.swig.peer_info.remote_interested.to_int();
        int local = org.libtorrent4j.swig.peer_info.local_connection.to_int();
        int seed = org.libtorrent4j.swig.peer_info.seed.to_int();
        int utp = org.libtorrent4j.swig.peer_info.utp_socket.to_int();
        int rc4 = org.libtorrent4j.swig.peer_info.rc4_encrypted.to_int();
        int dht = org.libtorrent4j.swig.peer_info.dht.to_int();
        var standard = org.libtorrent4j.PeerInfo.ConnectionType.STANDARD_BITTORRENT;
        assertEquals("DUI", PeerFlags.text(interesting | remoteInterested, 0, standard));
        assertEquals("d", PeerFlags.text(interesting | choked | local, 0, standard));
        assertEquals("SIEP", PeerFlags.text(seed | utp | rc4, 0, standard));
        assertEquals("H", PeerFlags.text(local, dht, standard));
        assertEquals("IW", PeerFlags.text(0, 0, org.libtorrent4j.PeerInfo.ConnectionType.WEB_SEED));
    }

    private static AddRequest downloadsTorrent(Path dir, byte[] torrent) {
        return new AddRequest(
                new AddRequest.TorrentFile(torrent, "pair.torrent"),
                dir.resolve("downloads"),
                false,
                false,
                false,
                AddRequest.StopCondition.NONE,
                Map.of());
    }

    @Test
    void connectionSettingsReachTheSessionAtStartAndLive(@TempDir Path dir) throws Exception {
        com.torrenta.engine.EngineSettings d = com.torrenta.engine.EngineSettings.defaults();
        com.torrenta.engine.EngineSettings quiet = new com.torrenta.engine.EngineSettings(
                d.listenPort(),
                true,
                "",
                d.ipv6(),
                false,
                123,
                7,
                3,
                d.proxy(),
                "",
                1080,
                "",
                "",
                true,
                com.torrenta.engine.EngineSettings.Protocol.defaults(),
                com.torrenta.engine.EngineSettings.Speed.defaults(),
                com.torrenta.engine.EngineSettings.Queue.defaults());
        try (LibtorrentEngine engine =
                new LibtorrentEngine(() -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), quiet)) {
            engine.start();
            assertEquals(123, engine.connectionsLimitForTest(), "applied at start");
            List<EngineEvent> events = new CopyOnWriteArrayList<>();
            engine.addListener(events::add);
            engine.applySettings(new com.torrenta.engine.EngineSettings(
                    d.listenPort(),
                    true,
                    "",
                    d.ipv6(),
                    false,
                    321,
                    7,
                    3,
                    d.proxy(),
                    "",
                    1080,
                    "",
                    "",
                    true,
                    com.torrenta.engine.EngineSettings.Protocol.defaults(),
                    com.torrenta.engine.EngineSettings.Speed.defaults(),
                    com.torrenta.engine.EngineSettings.Queue.defaults()));
            assertEquals(321, engine.connectionsLimitForTest(), "applied live");
            assertTrue(events.stream().anyMatch(ev -> ev.message().startsWith("Engine settings applied")));
            Torrent t = engine.add(MAGNET);
            assertFalse(t.id().isEmpty(), "adding still works with a per-torrent limit set");
        }
    }

    @Test
    void protocolSettingsReachTheSessionAndNewTorrents(@TempDir Path dir) throws Exception {
        com.torrenta.engine.EngineSettings.Protocol quiet = new com.torrenta.engine.EngineSettings.Protocol(
                false,
                false,
                false,
                false,
                com.torrenta.engine.EngineSettings.Protocol.Encryption.REQUIRE,
                true,
                List.of("udp://default.example.org:6969/announce"));
        com.torrenta.engine.EngineSettings settings =
                com.torrenta.engine.EngineSettings.defaults().withProtocol(quiet);
        try (LibtorrentEngine engine = new LibtorrentEngine(
                () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), settings)) {
            engine.start();
            assertFalse(engine.dhtEnabledForTest(), "DHT off at start");
            Torrent t = engine.add(MAGNET);
            await(
                    "the default tracker is on the new torrent",
                    () -> engine.detail(t.id()).trackers().stream()
                            .anyMatch(tr -> tr.url().equals("udp://default.example.org:6969/announce")));

            // Turned back on live, with another default tracker that reaches the existing torrent.
            engine.applySettings(settings.withProtocol(new com.torrenta.engine.EngineSettings.Protocol(
                    true,
                    true,
                    true,
                    true,
                    com.torrenta.engine.EngineSettings.Protocol.Encryption.PREFER,
                    false,
                    List.of("udp://second.example.org:6969/announce"))));
            assertTrue(engine.dhtEnabledForTest(), "DHT on after apply");
            await(
                    "the second default tracker reached the existing torrent",
                    () -> engine.detail(t.id()).trackers().stream()
                            .anyMatch(tr -> tr.url().equals("udp://second.example.org:6969/announce")));
        }
    }

    @Test
    void handlesOutliveTheSwigVectorsTheyCameFrom(@TempDir Path dir) throws Exception {
        // libtorrent4j's vector proxies hand out pointers into the vector and free the vector from a
        // finalizer: a handle, tracker or peer kept past that is a use-after-free that crashed CI.
        // A thread drives the collector and the finalizer the whole time the calls that used to
        // keep such pointers run; with freed memory poisoned (MALLOC_PERTURB_) the old code dies.
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "gc");
        java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread collector = new Thread(() -> {
            while (on.get()) collect();
        });
        collector.setDaemon(true);
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            engine.add(MAGNET);
            await("seeding", () -> engine.torrents().stream().anyMatch(x -> x.state() == TorrentState.SEEDING));
            collector.start();
            for (int i = 0; i < 40; i++) {
                assertEquals(2, engine.torrents().size());
                TorrentDetail detail = engine.detail(t.id());
                assertEquals(2, detail.files().size());
                assertEquals(1 + i, detail.trackers().size());
                engine.addTrackers(t.id(), List.of("udp://extra" + i + ".example.org:1/announce"));
                engine.reannounce(t.id());
                engine.setSequential(t.id(), i % 2 == 0);
            }
            engine.removeTracker(t.id(), "udp://extra0.example.org:1/announce");
            assertEquals(40, engine.detail(t.id()).trackers().size());
            engine.flush();
        } finally {
            on.set(false);
            collector.join(5_000);
        }
    }

    /** Runs the collector and the finalizer, then churns the native heap so freed memory is reused. */
    @SuppressWarnings("removal")
    private static void collect() {
        System.gc();
        System.runFinalization();
        for (int i = 0; i < 64; i++) new org.libtorrent4j.swig.torrent_handle_vector().reserve(1024);
    }

    @Test
    void ipFilterRefusesTheRangesItIsGiven(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.setIpFilter(List.of(new com.torrenta.engine.IpRange("10.0.0.0", "10.255.255.255")));
            engine.start();
            assertTrue(engine.isBlockedForTest("10.1.2.3"), "set before start, applied at start");
            assertFalse(engine.isBlockedForTest("192.168.1.1"));

            List<EngineEvent> events = new CopyOnWriteArrayList<>();
            engine.addListener(events::add);
            engine.setIpFilter(List.of(
                    com.torrenta.engine.IpRange.cidr("192.168.0.0/16"), com.torrenta.engine.IpRange.single("1.2.3.4")));
            assertFalse(engine.isBlockedForTest("10.1.2.3"), "replaced, not merged");
            assertTrue(engine.isBlockedForTest("192.168.1.1"));
            assertTrue(engine.isBlockedForTest("1.2.3.4"));
            assertTrue(events.stream().anyMatch(ev -> ev.message().equals("IP filter: 2 ranges")));
            engine.setIpFilter(List.of());
            assertFalse(engine.isBlockedForTest("1.2.3.4"), "cleared");
            assertEquals(0, engine.blockedPeers());
        }
    }

    @Test
    void speedLimitsFollowTheHandSwitch(@TempDir Path dir) throws Exception {
        com.torrenta.engine.EngineSettings.Speed speed = new com.torrenta.engine.EngineSettings.Speed(
                1000, 100, 200, 20, false, false, 0, 0, java.util.EnumSet.noneOf(java.time.DayOfWeek.class), false);
        com.torrenta.engine.EngineSettings settings =
                com.torrenta.engine.EngineSettings.defaults().withSpeed(speed);
        try (LibtorrentEngine engine = new LibtorrentEngine(
                () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), settings)) {
            engine.start();
            assertEquals(1000 * 1024, engine.rateLimitsForTest()[0], "normal pair at start");
            assertEquals(100 * 1024, engine.rateLimitsForTest()[1]);
            engine.applySettings(settings.withSpeed(speed.withAlternativeOn(true)));
            assertTrue(engine.refreshLimits(java.time.LocalDateTime.now()), "alternative in force");
            assertEquals(200 * 1024, engine.rateLimitsForTest()[0]);
            assertEquals(20 * 1024, engine.rateLimitsForTest()[1]);
            engine.applySettings(settings.withSpeed(speed.withAlternativeOn(false)));
            assertFalse(engine.refreshLimits(java.time.LocalDateTime.now()));
            assertEquals(1000 * 1024, engine.rateLimitsForTest()[0], "back to normal");
        }
    }

    @Test
    void queueSettingsReachTheSessionAtStartAndLive(@TempDir Path dir) throws Exception {
        com.torrenta.engine.EngineSettings.Queue queue = new com.torrenta.engine.EngineSettings.Queue(
                2,
                0,
                7,
                false,
                new com.torrenta.engine.ShareLimit(1.5, 30, com.torrenta.engine.ShareLimit.Action.PAUSE));
        com.torrenta.engine.EngineSettings settings =
                com.torrenta.engine.EngineSettings.defaults().withQueue(queue);
        try (LibtorrentEngine engine = new LibtorrentEngine(
                () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), settings)) {
            engine.start();
            assertEquals(2, engine.activeLimitsForTest()[0], "applied at start");
            assertEquals(-1, engine.activeLimitsForTest()[1], "0 to a person is -1 to libtorrent");
            assertEquals(7, engine.activeLimitsForTest()[2]);
            engine.applySettings(settings.withQueue(
                    new com.torrenta.engine.EngineSettings.Queue(4, 5, 6, true, com.torrenta.engine.ShareLimit.NONE)));
            assertEquals(4, engine.activeLimitsForTest()[0], "applied live");
            assertEquals(5, engine.activeLimitsForTest()[1]);
            assertEquals(6, engine.activeLimitsForTest()[2]);
        }
    }

    @Test
    void perTorrentLimitsAreKeptByTheHandleAndReadBack(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            com.torrenta.engine.TorrentLimits before = engine.limits(t.id());
            assertEquals(0, before.downloadLimit(), "none to begin with");
            assertEquals(0, before.uploadLimit());
            assertEquals(100, before.maxConnections(), "the per-torrent connection setting, set on add");
            assertFalse(before.firstAndLastFirst());

            com.torrenta.engine.TorrentLimits wanted =
                    new com.torrenta.engine.TorrentLimits(100 * 1024, 50 * 1024, 7, 3, true);
            engine.setLimits(t.id(), wanted);
            assertEquals(wanted, engine.limits(t.id()), "read back as set");

            engine.setLimits(t.id(), new com.torrenta.engine.TorrentLimits(0, 0, 0, 0, false));
            assertEquals(com.torrenta.engine.TorrentLimits.NONE, engine.limits(t.id()), "0 is none on both sides");

            // A magnet has no pieces to order yet: the flag is false and setting it is not an error.
            Torrent magnet = engine.add(MAGNET);
            engine.setLimits(magnet.id(), wanted);
            assertFalse(engine.limits(magnet.id()).firstAndLastFirst());
            assertEquals(7, engine.limits(magnet.id()).maxConnections());
        }
    }

    @Test
    void seedingTimeIsSecondsFinished(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            assertEquals(0, t.seedingTime(), "not yet checked");
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            await("the clock runs, in seconds not milliseconds", () -> {
                long seconds = engine.torrents().get(0).seedingTime();
                return seconds >= 1 && seconds < 100;
            });
        }
    }

    @Test
    void stopAfterMetadataIsAcceptedForAMagnet(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t = engine.add(new AddRequest(
                    new AddRequest.Magnet(MAGNET),
                    dir.resolve("downloads"),
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.METADATA_RECEIVED,
                    Map.of()));
            assertFalse(t.paused(), "runs until the metadata arrives");
            assertEquals(TorrentState.FETCHING_METADATA, t.state());
        }
    }

    @Test
    void badInputsAreErrorsNotCrashes(@TempDir Path dir) throws EngineException {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            assertThrows(EngineException.class, () -> engine.add("magnet:?xt=urn:btih:nope"));
            assertThrows(
                    EngineException.class,
                    () -> engine.add(dir.resolve("missing.torrent").toString()));
        }
    }

    @Test
    void notStartedIsAnErrorNotANullPointer(@TempDir Path dir) {
        LibtorrentEngine engine = engine(dir);
        assertThrows(EngineException.class, () -> engine.add(MAGNET));
        assertTrue(engine.torrents().isEmpty());
        engine.flush(); // harmless
    }

    @Test
    void everyLibtorrentStateMapsSomewhere() {
        for (TorrentStatus.State state : TorrentStatus.State.values()) {
            assertTrue(LibtorrentEngine.state(state) != null, state.name());
        }
        assertEquals(TorrentState.FETCHING_METADATA, LibtorrentEngine.state(TorrentStatus.State.DOWNLOADING_METADATA));
        assertEquals(TorrentState.CHECKING, LibtorrentEngine.state(TorrentStatus.State.CHECKING_RESUME_DATA));
    }

    private static LibtorrentEngine engine(Path dir) {
        return engine(dir, new TorrentStore(dir.resolve("store")));
    }

    private static LibtorrentEngine engine(Path dir, TorrentStore store) {
        return new LibtorrentEngine(dir.resolve("downloads"), store);
    }

    /** A condition that may ask the engine, which throws. */
    private interface Condition {
        boolean test() throws Exception;
    }

    /** Alerts arrive on their own thread; wait, bounded, rather than sleep and hope. */
    private static void await(String what, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.test()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what);
            }
        }
    }
}
