package com.torrenta.engine.libtorrent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.CreateRequest;
import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Priority;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.TorrentStorage;
import com.torrenta.store.StatsStore;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libtorrent4j.TorrentBuilder;
import org.libtorrent4j.TorrentStatus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /**
     * How long a loopback transfer or metadata fetch may take before the test gives up. It takes
     * a fraction of a second; the margin is for CI runners that get little CPU.
     */
    private static final int LOOPBACK_SECONDS = 120;

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
            assertEquals(List.of("tracker.example.org"), added.trackers(), "the host, from the add parameters");
            assertEquals("", added.error());
            // The data is already there, so checking ends in seeding.
            await("checked and seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
        }
        assertTrue(store.contains(id), "resume data written on stop");

        try (LibtorrentEngine again = new LibtorrentEngine(downloads, store)) {
            again.start();
            Torrent back = again.torrents().get(0);
            assertEquals(id, back.id());
            assertEquals("payload.bin", back.name());
            assertEquals(List.of("tracker.example.org"), back.trackers(), "the host survives the resume data");
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
    void superSeedingIsAFlagOfTheSeedAndSurvivesARestart(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Files.createDirectories(downloads);
        Path payload = downloads.resolve("seed.bin");
        byte[] bytes = new byte[64 * 1024];
        new Random(7).nextBytes(bytes);
        Files.write(payload, bytes);
        NativeLibrary.ensureAvailable();
        Path torrentFile = dir.resolve("seed.torrent");
        Files.write(
                torrentFile,
                new TorrentBuilder().path(payload.toFile()).generate().entry().bencode());

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String id;
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, store)) {
            engine.start();
            id = engine.add(torrentFile.toString()).id();
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            assertFalse(engine.torrents().get(0).superSeeding());
            engine.setSuperSeeding(id, true);
            await("super seeding", () -> engine.torrents().get(0).superSeeding());
        }
        try (LibtorrentEngine again = new LibtorrentEngine(downloads, store)) {
            again.start();
            assertTrue(again.torrents().get(0).superSeeding(), "kept in the resume data");
            again.setSuperSeeding(id, false);
            await("off again", () -> !again.torrents().get(0).superSeeding());
        }
    }

    /** An exported torrent is the same torrent: same hash, its trackers in their tiers, its web seed. */
    @Test
    void anExportedTorrentReadsBackAsTheSameTorrent(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Files.createDirectories(downloads);
        Path payload = downloads.resolve("export.bin");
        byte[] bytes = new byte[48 * 1024];
        new Random(11).nextBytes(bytes);
        Files.write(payload, bytes);
        NativeLibrary.ensureAvailable();
        Path torrentFile = dir.resolve("export.torrent");
        Files.write(
                torrentFile,
                new TorrentBuilder()
                        .path(payload.toFile())
                        .addTracker("udp://one.example.org:6969/announce", 0)
                        .addUrlSeed("http://seed.example.org/files/")
                        .generate()
                        .entry()
                        .bencode());

        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            String id = engine.add(torrentFile.toString()).id();
            engine.addTrackers(id, List.of("udp://two.example.org:6969/announce"));
            byte[] exported = engine.exportTorrent(id);

            TorrentPreview back = engine.inspect(exported);
            assertEquals(id, back.id(), "the info dictionary is the one that was added");
            assertEquals(
                    List.of("udp://one.example.org:6969/announce", "udp://two.example.org:6969/announce"),
                    back.trackers());
            String text = new String(exported, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("url-list"), "the web seed goes along");
            assertTrue(text.contains("http://seed.example.org/files/"));
            assertTrue(
                    text.contains("ll35:udp://one.example.org:6969/announceel35:udp://two"),
                    "two tiers, one tracker each: " + text.substring(0, Math.min(200, text.length())));

            engine.add(MAGNET);
            assertThrows(EngineException.class, () -> engine.exportTorrent(MAGNET_ID), "no file list yet");
        }
    }

    /** A torrent's given name is the engine's everywhere, and survives a restart; empty undoes it. */
    @Test
    void aRenamedTorrentKeepsItsNameAcrossARestart(@TempDir Path dir) throws Exception {
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            engine.add(MAGNET);
            engine.rename(MAGNET_ID, "My test");
            assertEquals("My test", engine.torrents().get(0).name());
        }
        try (LibtorrentEngine again = engine(dir, store)) {
            again.addListener(events::add);
            again.start();
            assertEquals("My test", again.torrents().get(0).name());
            await(
                    "the log uses it",
                    () -> events.stream().anyMatch(e -> e.message().equals("Restored My test")));
            again.rename(MAGNET_ID, "");
            assertEquals("Torrenta test", again.torrents().get(0).name(), "its own name back");
            assertTrue(store.loadLabels(MAGNET_ID).isEmpty(), "nothing left to keep");
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
        return twoFileTorrent(downloads, name, false);
    }

    private static byte[] twoFileTorrent(Path downloads, String name, boolean isPrivate) throws Exception {
        Path root = downloads.resolve(name);
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("a.bin"), new byte[100 * 1024]);
        Files.write(root.resolve("sub").resolve("b.bin"), new byte[50 * 1024]);
        NativeLibrary.ensureAvailable();
        return new TorrentBuilder()
                .path(root.toFile())
                .addTracker("udp://tracker.example.org:6969/announce")
                .setPrivate(isPrivate)
                .generate()
                .entry()
                .bencode();
    }

    /**
     * A wanted archive's first piece is asked for with a deadline and read back from libtorrent
     * (a {@code read_piece_alert}, copied out of native memory): the one zip of two that asks
     * for a password is found, said once, and kept for the detail; the plain one never is.
     */
    @Test
    void anEncryptedArchiveIsFoundFromItsFirstPiece(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("seed").resolve("pack");
        Files.createDirectories(root);
        // The film first and large, so the archives sit pieces in, not at the torrent's start.
        Files.write(root.resolve("film.mkv"), new byte[200 * 1024]);
        Files.write(root.resolve("locked.zip"), archiveFixture("zip-encrypted.zip"));
        Files.write(root.resolve("open.zip"), archiveFixture("zip-plain.zip"));
        NativeLibrary.ensureAvailable();
        byte[] torrent =
                new TorrentBuilder().path(root.toFile()).generate().entry().bencode();
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine engine = downloader(dir)) {
            engine.addListener(events::add);
            engine.start();
            int locked = engine.inspect(torrent).files().stream()
                    .filter(f -> f.path().endsWith("locked.zip"))
                    .findFirst()
                    .orElseThrow()
                    .index();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            awaitDownload(
                    "the locked archive found",
                    engine,
                    t.id(),
                    seed,
                    () -> !engine.detail(t.id()).encryptedArchives().isEmpty());
            awaitDownload(
                    "the rest",
                    engine,
                    t.id(),
                    seed,
                    () -> engine.torrents().get(0).progress() >= 1.0);
            assertEquals(Set.of(locked), engine.detail(t.id()).encryptedArchives(), "the plain zip is not");
            // The seeder added it complete, its files already there: new to it, so read as well.
            await(
                    "the seeder found it too",
                    () -> seed.detail(t.id()).encryptedArchives().equals(Set.of(locked)));
            assertEquals(
                    1,
                    events.stream()
                            .filter(e -> e.message().contains("locked.zip")
                                    && e.message().contains("password"))
                            .count(),
                    events.toString());
            engine.remove(t.id(), false);
            await("gone", () -> engine.torrents().isEmpty());
        }
    }

    private static byte[] archiveFixture(String name) throws java.io.IOException {
        try (java.io.InputStream in =
                LibtorrentEngineTest.class.getResourceAsStream("/com/torrenta/engine/archives/" + name)) {
            if (in == null) throw new java.io.IOException("no fixture " + name);
            return in.readAllBytes();
        }
    }

    /**
     * Undo's engine half: a detached torrent comes back as it was, labels and queue place and
     * all, as a restored one (nothing that runs on an add runs), and without a recheck. The proof
     * of the last is a byte changed in a downloaded file while it was out, its size kept: from its
     * resume data the torrent stays complete, where a recheck would fail that piece.
     */
    @Test
    void aDetachedTorrentComesBackAsItWasWithoutARecheck(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.addListener(events::add);
            engine.start();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            await(
                    "seeding from the files already there",
                    () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            engine.setCategory(t.id(), "Linux");
            engine.setTags(t.id(), List.of("keep"));

            com.torrenta.engine.Engine.Detached detached = engine.detach(t.id());
            assertTrue(engine.torrents().isEmpty(), "out of the list");
            assertFalse(store.contains(t.id()), "and out of the store, as a removal is");
            assertEquals("pair", detached.name());
            assertTrue(Files.isRegularFile(downloads.resolve("pair").resolve("a.bin")), "the files stay");

            Path a = downloads.resolve("pair").resolve("a.bin");
            byte[] bytes = Files.readAllBytes(a);
            bytes[0] ^= 0x5a;
            Files.write(a, bytes);

            events.clear();
            engine.reattach(detached);
            await("back in the list", () -> engine.torrents().size() == 1);
            Torrent back = engine.torrents().get(0);
            assertEquals(t.id(), back.id());
            assertEquals("Linux", back.category());
            assertEquals(List.of("keep"), back.tags());
            // Past any checking: a recheck would have ended in DOWNLOADING, short the changed piece.
            await("seeding again", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            assertTrue(engine.torrents().get(0).progress() >= 1.0, "no recheck found the changed byte");
            await(
                    "said as restored",
                    () -> events.stream().anyMatch(e -> e.message().equals("Restored pair")));
            assertTrue(
                    events.stream().noneMatch(e -> e.kind() == EngineEvent.Kind.ADDED),
                    "run-on-added does not run for a torrent put back: " + events);
            await("in the store again", () -> store.contains(t.id()));
            assertThrows(EngineException.class, () -> engine.reattach(detached), "it is back already");
        }
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

    /**
     * A second fetch of a magnet while its first is still running is refused. No peer can answer
     * here (an isolated session, a hash nobody has), so the first is still running when asked:
     * the check cannot race a fetch that finishes in milliseconds, as one from loopback can.
     */
    @Test
    void aSecondFetchOfAMagnetStillBeingFetchedIsRefused(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine engine = new LibtorrentEngine(
                () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), isolated(freePort()))) {
            engine.start();
            com.torrenta.engine.Engine.SwarmProbe fetch = engine.probe(new AddRequest.Magnet(MAGNET), false);
            assertThrows(
                    EngineException.class,
                    () -> engine.probe(new AddRequest.Magnet(MAGNET), false),
                    "one fetch per magnet");
            fetch.cancel();
            assertTrue(engine.torrents().isEmpty());
        }
    }

    @Test
    void aMagnetsFileListIsFetchedFromAPeerWithoutAddingIt(@TempDir Path dir) throws Exception {
        Path seeds = dir.resolve("seeds");
        byte[] torrent = twoFileTorrent(seeds, "pair");
        try (LibtorrentEngine seeder = new LibtorrentEngine(
                        () -> seeds, new TorrentStore(dir.resolve("seed-store")), isolated(ANY_PORT));
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), isolated(ANY_PORT))) {
            seeder.start();
            Torrent seeded = seeder.add(AddRequest.defaults(new AddRequest.TorrentFile(torrent, "pair"), seeds));
            await("seeding", () -> seeder.torrents().get(0).state() == TorrentState.SEEDING);
            // The port asked for is not always the one bound (libtorrent moves on when it is taken).
            String magnet = seeder.magnetLink(seeded.id()) + "&x.pe=127.0.0.1:" + awaitListening("the seeder", seeder);

            engine.start();
            engine.recordPeersForTest();
            engine.reconnectQuicklyForTest();
            List<EngineEvent> events = new CopyOnWriteArrayList<>();
            engine.addListener(events::add);
            com.torrenta.engine.Engine.SwarmProbe fetch = engine.probe(new AddRequest.Magnet(magnet), false);
            assertTrue(engine.torrents().isEmpty(), "a fetch is not a torrent in the list");
            // "One fetch per magnet" is aSecondFetchOfAMagnetStillBeingFetchedIsRefused's: from a
            // seeder on loopback this fetch can be over before a second call (CI saw it).

            byte[] fetched = awaitFetch(engine, fetch, seeder.listenPortForTest());
            TorrentPreview preview = engine.inspect(fetched);
            assertEquals("pair", preview.name());
            assertEquals(
                    List.of("pair/a.bin", "pair/sub/b.bin"),
                    preview.files().stream().map(TorrentPreview.FileEntry::path).toList());
            assertTrue(preview.v1() && preview.v2(), "TorrentBuilder makes hybrids");
            assertTrue(
                    preview.trackers().contains("udp://tracker.example.org:6969/announce"),
                    "the link's trackers are in the .torrent: " + preview.trackers());

            // It stays to look at the swarm, hidden, downloading nothing: the seeder that handed
            // over the metadata is a seed to it, so every piece of the ticked file is held.
            int first = preview.files().get(0).index();
            com.torrenta.engine.SwarmPreview look = awaitSwarm(fetch, Set.of(first), p -> p.seeds() >= 1);
            assertTrue(look.hasPieces(), "the file list is in: " + look);
            assertTrue(look.metadataAt() >= look.started(), look.toString());
            assertTrue(look.wanted() > 0 && look.wanted() < look.pieces(), "one file of two: " + look);
            assertEquals(look.wanted(), look.wantedSeen(), "a seed holds them all: " + look);
            assertTrue(engine.torrents().isEmpty(), "still not a torrent in the list");
            assertFalse(
                    Files.exists(dir.resolve("downloads").resolve("pair")),
                    "nothing in the download folder while it looks: " + onDisk(dir.resolve("downloads")));
            assertTrue(events.stream().noneMatch(e -> e.torrentId() != null), "a fetch raises no events: " + events);
            assertTrue(new TorrentStore(dir.resolve("store")).loadAll().isEmpty(), "and writes nothing");
            fetch.cancel();
            assertEquals(0, fetch.peers(), "over once cancelled");
            assertTrue(fetch.swarm(Set.of(first)).isEmpty(), "and looks no more");

            Torrent added = engine.add(
                    AddRequest.defaults(new AddRequest.TorrentFile(fetched, magnet), dir.resolve("downloads")));
            assertEquals(seeded.id(), added.id());
            assertEquals(2, engine.detail(added.id()).files().size(), "added with its files");
        }
    }

    /**
     * A {@code .torrent} has its file list, so its probe looks at the swarm from the start, hidden
     * and writing nothing, and the add takes over the peers it met: the torrent downloads from the
     * seeder it was never told about.
     */
    @Test
    void aTorrentFilesProbeLooksAtOnceAndHandsItsPeersToTheAdd(@TempDir Path dir) throws Exception {
        Path seeds = dir.resolve("seeds");
        byte[] torrent = twoFileTorrent(seeds, "pair");
        try (LibtorrentEngine seeder = new LibtorrentEngine(
                        () -> seeds, new TorrentStore(dir.resolve("seed-store")), isolated(ANY_PORT));
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), isolated(ANY_PORT))) {
            seeder.start();
            seeder.add(AddRequest.defaults(new AddRequest.TorrentFile(torrent, "pair"), seeds));
            await("seeding", () -> seeder.torrents().get(0).state() == TorrentState.SEEDING);
            engine.start();
            engine.recordPeersForTest();
            engine.reconnectQuicklyForTest();
            AddRequest.TorrentFile file = new AddRequest.TorrentFile(torrent, "pair.torrent");
            Engine.SwarmProbe probe = engine.probe(file, false);
            assertArrayEquals(torrent, probe.torrent().getNow(null), "the file list is the .torrent's own, at once");

            int first = engine.inspect(torrent).files().get(0).index();
            com.torrenta.engine.SwarmPreview look = awaitSwarmDialling(
                    engine, probe, awaitListening("the seeder", seeder), Set.of(first), p -> p.seeds() >= 1);
            assertTrue(look.hasPieces() && look.wanted() > 0, look.toString());
            assertEquals(look.wanted(), look.wantedSeen(), "a seed holds them all: " + look);
            assertTrue(engine.torrents().isEmpty(), "a probe is not a torrent in the list");
            assertTrue(new TorrentStore(dir.resolve("store")).loadAll().isEmpty(), "and writes nothing");

            engine.add(AddRequest.defaults(file, dir.resolve("downloads")), probe);
            await(
                    "downloaded from the peer the probe met",
                    () -> engine.torrents().get(0).progress() >= 1.0);
            assertTrue(probe.swarm(Set.of(first)).isEmpty(), "the add ended the probe");
        }
    }

    /**
     * A private tracker counts every announce: a private {@code .torrent}'s probe never joins the
     * swarm unless the person let it, and then looks as any other does. (A magnet cannot show
     * this: libtorrent never hands a private torrent's metadata to one.)
     */
    @Test
    void aPrivateTorrentIsLookedAtOnlyWhenLetIn(@TempDir Path dir) throws Exception {
        Path seeds = dir.resolve("seeds");
        byte[] torrent = twoFileTorrent(seeds, "pair", true);
        try (LibtorrentEngine seeder = new LibtorrentEngine(
                        () -> seeds, new TorrentStore(dir.resolve("seed-store")), isolated(ANY_PORT));
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), isolated(ANY_PORT))) {
            seeder.start();
            seeder.add(AddRequest.defaults(new AddRequest.TorrentFile(torrent, "pair"), seeds));
            await("seeding", () -> seeder.torrents().get(0).state() == TorrentState.SEEDING);
            engine.start();
            engine.recordPeersForTest();
            engine.reconnectQuicklyForTest();
            AddRequest.TorrentFile file = new AddRequest.TorrentFile(torrent, "pair.torrent");

            Engine.SwarmProbe kept = engine.probe(file, false);
            assertArrayEquals(torrent, kept.torrent().getNow(null), "the file list is still there");
            assertTrue(kept.swarm(Set.of()).isEmpty(), "but the swarm is not looked at");
            assertEquals(0, kept.peers());

            Engine.SwarmProbe let = engine.probe(file, true);
            com.torrenta.engine.SwarmPreview look = awaitSwarmDialling(
                    engine, let, awaitListening("the seeder", seeder), Set.of(), p -> p.seeds() >= 1);
            assertTrue(look.hasPieces(), look.toString());
            let.cancel();
        }
    }

    @Test
    void addingAfterTheFileListArrivedStartsWithThePeersTheFetchFound(@TempDir Path dir) throws Exception {
        Path seeds = dir.resolve("seeds");
        byte[] torrent = twoFileTorrent(seeds, "pair");
        try (LibtorrentEngine seeder = new LibtorrentEngine(
                        () -> seeds, new TorrentStore(dir.resolve("seed-store")), isolated(ANY_PORT));
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), isolated(ANY_PORT));
                LibtorrentEngine control = new LibtorrentEngine(
                        () -> dir.resolve("control"),
                        new TorrentStore(dir.resolve("control-store")),
                        isolated(ANY_PORT))) {
            seeder.start();
            Torrent seeded = seeder.add(AddRequest.defaults(new AddRequest.TorrentFile(torrent, "pair"), seeds));
            await("seeding", () -> seeder.torrents().get(0).state() == TorrentState.SEEDING);
            // No DHT, no local discovery, an unreachable tracker: the fetch meets the seeder only
            // because it is told to, and nothing else can tell the torrent where the seeder is.
            String magnet = seeder.magnetLink(seeded.id());

            engine.start();
            engine.recordPeersForTest();
            engine.reconnectQuicklyForTest();
            Engine.SwarmProbe fetch = engine.probe(new AddRequest.Magnet(magnet), false);
            engine.connectFetchForTest(fetch, awaitListening("the seeder", seeder));
            byte[] fetched = awaitFetch(engine, fetch, seeder.listenPortForTest());

            // Once the metadata is in, the fetch is upload-only and the seed drops it at once; the
            // torrent still knows where the seed is, and downloads all of it from there.
            Torrent added = engine.add(
                    AddRequest.defaults(new AddRequest.TorrentFile(fetched, magnet), dir.resolve("downloads")), fetch);
            await(
                    "downloaded from the peer the fetch had",
                    () -> engine.torrents().get(0).progress() >= 1.0);
            assertEquals(added.id(), engine.torrents().get(0).id());

            control.start();
            control.add(AddRequest.defaults(new AddRequest.TorrentFile(fetched, magnet), dir.resolve("control")));
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until) {
                assertEquals(0.0, control.torrents().get(0).progress(), "without the fetch it finds no one");
                Thread.sleep(100);
            }
        }
    }

    @Test
    void addingNowStartsWithThePeersTheFetchFound(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine other = new LibtorrentEngine(
                        () -> dir.resolve("other"), new TorrentStore(dir.resolve("other-store")), isolated(ANY_PORT));
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), isolated(ANY_PORT))) {
            // Another peer after the same magnet, without its metadata either: the fetch stays a
            // fetch, as it is when the person presses Add now.
            other.start();
            other.add(MAGNET);
            engine.start();
            engine.recordPeersForTest();
            engine.reconnectQuicklyForTest();
            Engine.SwarmProbe fetch = engine.probe(new AddRequest.Magnet(MAGNET), false);
            awaitFetchPeer(engine, fetch, awaitListening("the other peer", other));

            AddRequest now = new AddRequest(
                    new AddRequest.Magnet(MAGNET),
                    dir.resolve("downloads"),
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.METADATA_RECEIVED,
                    Map.of(),
                    TorrentLabels.NONE);
            Torrent added = engine.add(now, fetch);
            assertEquals(MAGNET_ID, added.id());
            assertTrue(fetch.torrent().isCompletedExceptionally(), "the fetch is over");
            await("the torrent dials the peer the fetch had", () -> engine.dialledPeersForTest(MAGNET_ID) > 0);
            assertEquals(1, engine.torrents().size());
        }
    }

    @Test
    void aFetchOfAnotherTorrentIsEndedAndLendsNothing(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Engine.SwarmProbe fetch = engine.probe(
                    new AddRequest.Magnet("magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef"), false);
            Torrent added =
                    engine.add(AddRequest.defaults(new AddRequest.Magnet(MAGNET), dir.resolve("downloads")), fetch);
            assertEquals(MAGNET_ID, added.id());
            assertTrue(fetch.torrent().isCompletedExceptionally());
            assertEquals(
                    List.of(MAGNET_ID),
                    engine.torrents().stream().map(Torrent::id).toList());
        }
    }

    @Test
    void aCancelledFetchLeavesRoomForTheRealAdd(@TempDir Path dir) throws Exception {
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            com.torrenta.engine.Engine.SwarmProbe fetch = engine.probe(new AddRequest.Magnet(MAGNET), false);
            assertTrue(engine.torrents().isEmpty());
            fetch.cancel();
            fetch.cancel();
            assertTrue(fetch.torrent().isCompletedExceptionally());
            assertEquals(0, fetch.peers());

            Torrent added = engine.add(MAGNET);
            assertEquals(MAGNET_ID, added.id());
            assertEquals(1, engine.torrents().size());
            await("the real add writes its blob", () -> store.contains(MAGNET_ID));
            assertThrows(
                    EngineException.class,
                    () -> engine.probe(new AddRequest.Magnet(MAGNET), false),
                    "already in the list");
        }
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            com.torrenta.engine.Engine.SwarmProbe fetch = engine.probe(
                    new AddRequest.Magnet("magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef"), false);
            engine.stop();
            assertTrue(fetch.torrent().isCompletedExceptionally(), "stopping ends a fetch");
        }
        assertEquals(java.util.Set.of(MAGNET_ID), store.loadAll().keySet(), "a fetch never reaches the store");
    }

    /**
     * Binding is meant for a VPN: no peer connection may leave by another interface. The session
     * is bound to loopback and dials this machine's other address, which the system would answer
     * from that other address if left to choose. Linux and macOS route a loopback-bound socket to
     * a local address, so the connection has to arrive from loopback; Windows refuses that
     * connection outright, so there it must not arrive at all. Either way never from the other
     * address, which is where it came from before {@code outgoing_interfaces} was set, on all
     * three. A dial to loopback first proves the bound session dials at all, so a refusal is the
     * binding's and not a test that dials nobody. Each dial has its own listener: libtorrent
     * redials a peer that hung up, and those must not be taken for the second dial.
     */
    @Test
    void aPeerIsDialledFromTheBoundAddressNotTheRoutesChoice(@TempDir Path dir) throws Exception {
        String other = otherAddress();
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "needs an IPv4 address besides loopback");
        java.net.InetAddress loopback = java.net.InetAddress.getLoopbackAddress();
        try (java.net.ServerSocket control = new java.net.ServerSocket(0);
                java.net.ServerSocket peer = new java.net.ServerSocket(0);
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), boundTo("127.0.0.1"))) {
            engine.start();
            String id = engine.add(MAGNET).id();
            assertEquals(loopback, dialledFrom(engine, id, control, "127.0.0.1", LOOPBACK_SECONDS), "the bound dial");
            java.net.InetAddress from = dialledFrom(engine, id, peer, other, 10);
            assertNotEquals(
                    java.net.InetAddress.getByName(other), from, "dialled " + other + " from the route's choice");
            if (!System.getProperty("os.name").startsWith("Windows")) {
                assertEquals(loopback, from, "dialled " + other);
            }
        }
    }

    /** An interface that is not there (the VPN went down) dials nobody, rather than the default route. */
    @Test
    void aBindingToAMissingInterfaceDialsNobody(@TempDir Path dir) throws Exception {
        String other = otherAddress();
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "needs an IPv4 address besides loopback");
        try (java.net.ServerSocket peer = new java.net.ServerSocket(0);
                LibtorrentEngine engine = new LibtorrentEngine(
                        () -> dir.resolve("downloads"), new TorrentStore(dir.resolve("store")), boundTo("torrenta0"))) {
            engine.start();
            String id = engine.add(MAGNET).id();
            // A negative cannot be awaited; ten dials, each refused at bind, is the bound.
            assertEquals(null, dialledFrom(engine, id, peer, other, 10), "dialled " + other);
        }
    }

    /**
     * Dials the listener from the torrent once a second until it accepts, and returns the address
     * the connection came from, or null if none came within {@code seconds}.
     */
    private static java.net.InetAddress dialledFrom(
            LibtorrentEngine engine, String id, java.net.ServerSocket listener, String address, int seconds)
            throws Exception {
        engine.reconnectQuicklyForTest();
        listener.setSoTimeout(1000);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            engine.connectPeerForTest(id, address, listener.getLocalPort());
            try (java.net.Socket s = listener.accept()) {
                return s.getInetAddress();
            } catch (java.net.SocketTimeoutException e) {
                // Not yet: dial again.
            }
        }
        return null;
    }

    /** An IPv4 address of this machine that is not loopback, or null on a machine with none. */
    private static String otherAddress() throws java.io.IOException {
        for (java.net.NetworkInterface i :
                java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!i.isUp() || i.isLoopback()) continue;
            for (java.net.InetAddress a : java.util.Collections.list(i.getInetAddresses())) {
                if (a instanceof java.net.Inet4Address && !a.isLinkLocalAddress()) return a.getHostAddress();
            }
        }
        return null;
    }

    /** {@link #isolated} bound to an address or interface name. */
    private static com.torrenta.engine.EngineSettings boundTo(String bound) throws java.io.IOException {
        com.torrenta.engine.EngineSettings d = isolated(ANY_PORT);
        return new com.torrenta.engine.EngineSettings(
                d.listenPort(),
                d.randomPort(),
                bound,
                d.ipv6(),
                d.upnp(),
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                d.proxyHost(),
                d.proxyPort(),
                d.proxyUsername(),
                d.proxyPassword(),
                d.proxyPeers(),
                d.protocol(),
                d.speed(),
                d.queue());
    }

    /**
     * A port picked here and bound later, for the few tests that need to know it before the
     * session exists. Everything else binds {@link #ANY_PORT}: between this socket closing and
     * libtorrent binding, Windows can refuse the number (a previous test's connections on it,
     * in TIME_WAIT), and a session refused its only port listens on nothing at all.
     */
    private static int freePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Port 0: the system picks a free one as the session binds; {@code listenPortForTest} says which. */
    private static final int ANY_PORT = 0;

    /** Where every loopback session listens; see {@link #isolated}. */
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Waits until the session listens, and says why not if it never does: a peer told to dial
     * port 0 is dropped without an attempt, and a loopback test then waits out its deadline with
     * no peer and no sign of why (CI's flakes: a seeder whose port was 0 for two minutes).
     */
    private static int awaitListening(String who, LibtorrentEngine engine) throws Exception {
        long start = System.currentTimeMillis();
        long deadline = start + LOOPBACK_SECONDS * 1000;
        boolean reopened = false;
        while (engine.listenPortForTest() == 0) {
            long now = System.currentTimeMillis();
            // A session can come up with no listen socket and say nothing about it (Windows CI):
            // asked to reopen its sockets once, and said, so the soak shows whether that cures it.
            if (!reopened && now - start > REOPEN_AFTER_MS) {
                System.err.println("awaitListening: " + who + " was not listening after " + REOPEN_AFTER_MS + " ms ("
                        + engine.connectivity() + "); reopening its sockets");
                engine.reopenSocketsForTest();
                reopened = true;
            }
            if (now > deadline)
                throw new AssertionError(who + " never listened" + (reopened ? ", its sockets reopened at 10 s" : "")
                        + ": " + engine.connectivity());
            Thread.sleep(25);
        }
        if (reopened)
            System.err.println("awaitListening: " + who + " listened on " + engine.listenPortForTest()
                    + " after its sockets were reopened, " + (System.currentTimeMillis() - start) + " ms in");
        return engine.listenPortForTest();
    }

    /** How long a session may take to listen before its sockets are reopened; it listens in milliseconds. */
    private static final long REOPEN_AFTER_MS = 10_000;

    /**
     * {@link #onPort}, with no way to find a peer but being told (no DHT, no local discovery) and
     * TCP only: this is both ends of every loopback transfer in these tests. Left to its defaults,
     * libtorrent dials a new peer over uTP first, a UDP protocol whose handshake and congestion
     * control run on timers a starved CI runner misses, and an attempt that fails is not retried
     * for a minute ({@code min_reconnect_time}); a TCP connect to a listening loopback socket
     * cannot fail. Discovery off also keeps the test's fixed info hash out of the public DHT,
     * where every CI run of this suite would otherwise find the others.
     */
    private static com.torrenta.engine.EngineSettings isolated(int port) {
        com.torrenta.engine.EngineSettings d = onPort(port);
        com.torrenta.engine.EngineSettings.Protocol p = d.protocol();
        return new com.torrenta.engine.EngineSettings(
                d.listenPort(),
                d.randomPort(),
                // Loopback, and only loopback: every peer these tests dial is 127.0.0.1. Bound to
                // 0.0.0.0 with port 0, libtorrent on Windows gives each local address a socket of
                // its own, each its own port, and listen_port() names one that is not loopback's
                // (measured: every dial of 127.0.0.1 at that port refused, in every run).
                LOOPBACK,
                d.ipv6(),
                d.upnp(),
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                d.proxyHost(),
                d.proxyPort(),
                d.proxyUsername(),
                d.proxyPassword(),
                d.proxyPeers(),
                new com.torrenta.engine.EngineSettings.Protocol(
                        false, p.pex(), false, false, p.encryption(), false, List.of()),
                d.speed(),
                d.queue());
    }

    /** The defaults on a fixed port, without UPnP: two sessions in one test must not collide. */
    private static com.torrenta.engine.EngineSettings onPort(int port) {
        com.torrenta.engine.EngineSettings d = com.torrenta.engine.EngineSettings.defaults();
        return new com.torrenta.engine.EngineSettings(
                port,
                false,
                "",
                d.ipv6(),
                false,
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                "",
                1080,
                "",
                "",
                true,
                d.protocol(),
                d.speed(),
                d.queue());
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
                    Map.of(b, Priority.IGNORE),
                    TorrentLabels.NONE);
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
                    Map.of(),
                    TorrentLabels.NONE);
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
            assertEquals(40, g.infoHashV1().length(), "the v1 hash, hex: " + g.infoHashV1());
            assertTrue(
                    g.infoHashV2().isEmpty() || g.infoHashV2().length() == 64, "the full v2 hash: " + g.infoHashV2());

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
    void webSeedsAreAddedAndRemovedAndTheChangeSurvivesARestart(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Path root = downloads.resolve("mirrored");
        Files.createDirectories(root);
        Files.write(root.resolve("a.bin"), new byte[64 * 1024]);
        NativeLibrary.ensureAvailable();
        // .invalid hosts: a seed never asks them, and nothing leaves the machine if it did.
        String fromFile = "http://seed-a.invalid/pub/";
        byte[] torrent = new TorrentBuilder()
                .path(root.toFile())
                .addUrlSeed(fromFile)
                .generate()
                .entry()
                .bencode();
        String added = "https://seed-b.invalid/pub/";
        String second = "http://seed-c.invalid/pub/";
        String id;
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            id = t.id();
            assertEquals(List.of(fromFile), engine.webSeeds(id), "the .torrent's url-list reaches the session");
            assertEquals(List.of(fromFile), engine.detail(id).webSeeds());

            EngineException refused = assertThrows(
                    EngineException.class, () -> engine.addWebSeeds(t.id(), List.of(added, "ftp://x.invalid/")));
            assertTrue(refused.getMessage().contains("ftp://x.invalid/"), refused.getMessage());
            assertEquals(List.of(fromFile), engine.webSeeds(id), "a refused batch adds none of it");

            engine.addWebSeeds(id, List.of(fromFile, " " + added + " ", "", second, added));
            await("two seeds added, no duplicate", () -> engine.webSeeds(t.id()).size() == 3);
            assertTrue(engine.webSeeds(id).containsAll(List.of(fromFile, added, second)));

            engine.removeWebSeed(id, fromFile);
            engine.removeWebSeed(id, "http://never.invalid/");
            await("the file's seed removed", () -> !engine.webSeeds(t.id()).contains(fromFile));
            assertEquals(Set.of(added, second), Set.copyOf(engine.webSeeds(id)));

            // Export writes the list as it is now, not the .torrent's.
            String exported = new String(engine.exportTorrent(id), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(exported.contains(added) && exported.contains(second), exported);
            assertFalse(exported.contains(fromFile), "the removed seed does not come back with the export");
        }
        try (LibtorrentEngine again = engine(dir)) {
            again.start();
            assertEquals(
                    Set.of(added, second),
                    Set.copyOf(again.webSeeds(id)),
                    "the resume data holds the list as changed, not the .torrent's");
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
                Map.of(),
                TorrentLabels.NONE);
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

    /** Every regular file under the folder with its size, for a failure message. */
    private static String onDisk(Path root) {
        if (root == null || !Files.isDirectory(root)) return "(nothing)";
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(f -> {
                        try {
                            return root.relativize(f) + "=" + Files.size(f);
                        } catch (java.io.IOException e) {
                            return root.relativize(f) + "=?";
                        }
                    })
                    .sorted()
                    .toList()
                    .toString();
        } catch (java.io.IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
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
    void stopWaitsForACallInFlightAndRefusesTheNext(@TempDir Path dir) throws Exception {
        // The window polls the engine from its own thread every second, actions run on virtual
        // threads, the flusher on another. A quit that deleted the native session under a call in
        // flight read freed memory. stop() now waits for the call in flight and later calls find
        // no session. Four threads hammer the engine through three start/stop rounds: no call may
        // throw anything but EngineException, every thread must end, and the stopped engine must
        // answer empty. (The window of the old race was too narrow for this to crash the old code
        // on a quiet machine; the test guards the contract, not the crash.)
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "race");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        java.util.concurrent.atomic.AtomicReference<Throwable> unexpected =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (int round = 0; round < 3; round++) {
            LibtorrentEngine engine = engine(dir, store);
            engine.start();
            String id = round == 0 ? engine.add(downloadsTorrent(dir, torrent)).id() : null;
            await("the torrent is back", () -> !engine.torrents().isEmpty());
            String tid = id != null ? id : engine.torrents().get(0).id();
            java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            List<Thread> hammers = new java.util.ArrayList<>();
            for (int n = 0; n < 4; n++) {
                Thread h = new Thread(
                        () -> {
                            while (on.get()) {
                                try {
                                    engine.torrents();
                                    engine.rates();
                                    engine.detail(tid);
                                    engine.flush();
                                    calls.incrementAndGet();
                                } catch (EngineException e) {
                                    // the engine stopped under the call: the right answer
                                } catch (Throwable e) {
                                    unexpected.set(e);
                                    return;
                                }
                            }
                        },
                        "hammer-" + n);
                h.start();
                hammers.add(h);
            }
            await("hammering", () -> calls.get() > 20);
            engine.stop();
            Thread.sleep(200);
            on.set(false);
            for (Thread h : hammers) h.join(5_000);
            for (Thread h : hammers) assertFalse(h.isAlive(), h.getName() + " ended");
            assertEquals(null, unexpected.get(), String.valueOf(unexpected.get()));
            assertEquals(List.of(), engine.torrents(), "a stopped engine has nothing");
            assertThrows(EngineException.class, () -> engine.detail(tid));
        }
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
            // The session decides the pair; the engine puts it in force and keeps it through a settings change.
            assertTrue(engine.refreshLimits(true), "alternative in force");
            assertEquals(200 * 1024, engine.rateLimitsForTest()[0]);
            assertEquals(20 * 1024, engine.rateLimitsForTest()[1]);
            engine.applySettings(settings);
            assertEquals(200 * 1024, engine.rateLimitsForTest()[0], "a settings change keeps the pair in force");
            assertFalse(engine.refreshLimits(false));
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
    void labelsAreKeptBesideTheResumeDataAndComeBackAfterARestart(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String id;
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            Torrent t = engine.add(new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    new TorrentLabels("Linux", List.of("iso"))));
            id = t.id();
            assertEquals("Linux", t.category(), "filed on add");
            assertEquals(List.of("iso"), t.tags());
            assertEquals("Linux", engine.category(id));
            assertEquals(List.of("iso"), engine.tags(id));
            assertTrue(store.loadLabels(id).isPresent(), "the sidecar is written at once, on the caller's thread");

            engine.setTags(id, List.of("iso", "hd"));
            engine.setCategory(id, "Movies");
            Torrent now = engine.torrents().get(0);
            assertEquals("Movies", now.category());
            assertEquals(List.of("hd", "iso"), now.tags(), "normalised as TorrentLabels does");

            Torrent magnet = engine.add(MAGNET);
            assertEquals("", magnet.category());
            assertTrue(magnet.tags().isEmpty());
            assertTrue(store.loadLabels(MAGNET_ID).isEmpty(), "no labels, no sidecar");
            assertThrows(
                    EngineException.class,
                    () -> engine.setCategory("0000000000000000000000000000000000000000", "x"),
                    "an id the session lacks");
            await("resume data written", () -> store.contains(id));
        }
        try (LibtorrentEngine again = engine(dir, store)) {
            again.start();
            Torrent back = again.torrents().stream()
                    .filter(t -> t.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Movies", back.category(), "restored with the resume data");
            assertEquals(List.of("hd", "iso"), back.tags());

            again.setCategory(id, "");
            again.setTags(id, List.of());
            assertTrue(store.loadLabels(id).isEmpty(), "nothing left to file under takes the sidecar away");
            again.setCategory(id, "Again");
            assertTrue(store.loadLabels(id).isPresent());
            again.remove(id, false);
            assertTrue(store.loadLabels(id).isEmpty(), "removed with the torrent");
            await("gone", () -> again.torrents().size() == 1);
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
                    Map.of(),
                    TorrentLabels.NONE));
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

    /**
     * Streaming's engine half: a range of a file is waited on until its piece is there, asked for
     * with a deadline; the wait ends on the piece's finished alert, and the bytes on disk are the
     * seeder's. The file was skipped: streaming it makes it wanted, since a skipped file's pieces
     * go into the part file and not the file a player is served from (measured: the wait ended and
     * the file was not there).
     */
    @Test
    void aStreamedRangeWaitsForItsPieceWhichTheDeadlineFetchesEvenFromASkippedFile(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("seed").resolve("pair"));
        byte[] content = new byte[300 * 1024];
        new Random(5).nextBytes(content);
        Files.write(root.resolve("film.bin"), content);
        NativeLibrary.ensureAvailable();
        byte[] torrent =
                new TorrentBuilder().path(root.toFile()).generate().entry().bencode();
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine engine = downloader(dir)) {
            engine.start();
            int film = engine.inspect(torrent).files().stream()
                    .filter(f -> f.path().endsWith("film.bin"))
                    .findFirst()
                    .orElseThrow()
                    .index();
            Torrent t = engine.add(new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    dir.resolve("downloads"),
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(film, Priority.IGNORE),
                    TorrentLabels.NONE));
            com.torrenta.engine.StreamFile file = engine.stream(t.id(), film);
            assertEquals(content.length, file.size());
            assertEquals("film.bin", file.name());
            await(
                    "the played file wanted",
                    () -> engine.detail(t.id()).files().stream()
                                    .filter(f -> f.index() == film)
                                    .findFirst()
                                    .orElseThrow()
                                    .priority()
                            == Priority.LOW);
            long from = 150 * 1024 + 7;
            long to = from + 999;
            assertFalse(file.await(from, to, java.time.Duration.ofMillis(300)), "no peer yet: the range waits");
            java.util.concurrent.CompletableFuture<Boolean> got =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return file.await(from, to, java.time.Duration.ofSeconds(LOOPBACK_SECONDS));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            awaitDownload("the range's piece, asked with a deadline", engine, t.id(), seed, got::isDone);
            assertTrue(got.get(), "there before the wait ran out");
            // Read through libtorrent, the moment its alert says the piece is there: on Windows the
            // file still had zeros there (CI, the first run of this test), the bytes in its buffer.
            assertArrayEquals(java.util.Arrays.copyOfRange(content, (int) from, (int) to + 1), file.read(from, to));
            assertTrue(file.await(from, to, java.time.Duration.ofMillis(10)), "there now: no wait");

            // And as a player has it: a range over loopback HTTP from the stream server.
            try (com.torrenta.stream.StreamServer server = new com.torrenta.stream.StreamServer(() -> engine)) {
                String url = server.url(t.id(), film, "film.bin");
                java.net.http.HttpResponse<byte[]> answer = java.net.http.HttpClient.newHttpClient()
                        .send(
                                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                                        .header("Range", "bytes=" + from + "-" + to)
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(206, answer.statusCode());
                assertArrayEquals(java.util.Arrays.copyOfRange(content, (int) from, (int) to + 1), answer.body());
            }
        }
    }

    /**
     * A torrent downloaded over loopback from a second engine: the one way to see the engine
     * write files, rather than find them. The seeder has the data under {@code dir/seed/pair}.
     */
    private static LibtorrentEngine seeder(Path dir, byte[] torrent) throws Exception {
        return seeder(dir, torrent, e -> {});
    }

    /**
     * The same, heard from before the add: SEEDING is read off the status, and the add's own
     * check alerts can still be on their way when it shows, so a listener added afterwards may
     * hear them later.
     */
    private static LibtorrentEngine seeder(Path dir, byte[] torrent, Consumer<EngineEvent> listener) throws Exception {
        LibtorrentEngine seed = new LibtorrentEngine(
                () -> dir.resolve("seed"), new TorrentStore(dir.resolve("seedstore")), isolated(ANY_PORT));
        seed.addListener(listener);
        seed.recordPeersForTest();
        seed.start();
        seed.add(new AddRequest(
                new AddRequest.TorrentFile(torrent, "pair.torrent"),
                dir.resolve("seed"),
                false,
                false,
                false,
                AddRequest.StopCondition.NONE,
                Map.of(),
                TorrentLabels.NONE));
        await("seeder seeding", () -> seed.torrents().get(0).state() == TorrentState.SEEDING);
        awaitListening("the seeder", seed);
        return seed;
    }

    /** The other end of a loopback transfer from {@link #seeder}: {@link #isolated} too. */
    private static LibtorrentEngine downloader(Path dir) throws Exception {
        return downloader(dir, new TorrentStore(dir.resolve("store")));
    }

    private static LibtorrentEngine downloader(Path dir, TorrentStore store) throws Exception {
        LibtorrentEngine engine = new LibtorrentEngine(() -> dir.resolve("downloads"), store, isolated(ANY_PORT));
        engine.recordPeersForTest();
        return engine;
    }

    /**
     * Waits for the condition while telling the torrent about the seeder whenever it has no peer,
     * at most once a second: a second connection from the same address makes the seeder drop
     * one as a duplicate, which stalls the transfer. A failed attempt is dialled again a second
     * later rather than a minute ({@link LibtorrentEngine#reconnectQuicklyForTest}); without that
     * the repeated {@code connect_peer} does nothing. After three failed attempts libtorrent stops
     * dialling the address altogether, and no {@code connect_peer} brings it back (measured: three
     * refusals, then a seeder on that very port, then twenty seconds of silence); so a torrent
     * that has gone {@link #FORGET_AFTER_MS} without a peer forgets its list before the next dial,
     * and the seeder is a new peer to it: a few early failures (refused, dropped, timed out at the
     * three seconds the test allows) no longer cost the whole deadline. CI's own flakes were the
     * seeder, not this: it listened on nothing ({@link #awaitListening}). The deadline is long
     * because CI runners are slow, not because the transfer is: it takes well under a second.
     */
    private static void awaitDownload(
            String what, LibtorrentEngine engine, String id, LibtorrentEngine seed, Condition condition)
            throws Exception {
        engine.reconnectQuicklyForTest();
        int port = awaitListening("the seeder", seed);
        long deadline = System.currentTimeMillis() + LOOPBACK_SECONDS * 1000;
        long nextConnect = 0;
        long lonelySince = System.currentTimeMillis();
        while (!condition.test()) {
            if (System.currentTimeMillis() > deadline) {
                // Which end failed, and how: the seeder's port then and now, whether it listened,
                // and each side's peer connections as they started and ended.
                throw new AssertionError("timed out waiting for: " + what + "; torrent " + engine.torrents()
                        + "; files "
                        + engine.detail(id).files() + "; on disk "
                        + onDisk(Path.of(engine.torrents().get(0).savePath()).getParent())
                        + "; seeder port dialled " + port + ", now " + seed.listenPortForTest()
                        + ", " + seed.connectivity() + ", torrents " + seed.torrents()
                        + "\n  downloader's peers:\n    " + String.join("\n    ", engine.peerLogForTest())
                        + "\n  seeder's peers:\n    " + String.join("\n    ", seed.peerLogForTest()));
            }
            long now = System.currentTimeMillis();
            if (engine.torrents().get(0).peers() > 0) {
                lonelySince = now;
            } else if (now >= nextConnect) {
                if (now - lonelySince >= FORGET_AFTER_MS) {
                    engine.forgetPeersForTest(id);
                    lonelySince = now;
                }
                engine.connectPeerForTest(id, port);
                nextConnect = now + 1000;
            }
            Thread.sleep(25);
        }
    }

    /**
     * How long a loopback torrent may go without a peer before its peer list is forgotten: long
     * enough for a dial and a handshake on a slow runner, short of libtorrent's third failure.
     */
    private static final long FORGET_AFTER_MS = 5000;

    /** A fetch connected to the peer on {@code port}: dialled once a second, forgetting as {@link #awaitFetch} does. */
    private static void awaitFetchPeer(LibtorrentEngine engine, Engine.SwarmProbe fetch, int port) throws Exception {
        long deadline = System.currentTimeMillis() + LOOPBACK_SECONDS * 1000;
        long lonelySince = System.currentTimeMillis();
        while (fetch.peers() == 0) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                throw new AssertionError("timed out connecting the fetch to port " + port + "\n  peers:\n    "
                        + String.join("\n    ", engine.peerLogForTest()));
            }
            if (now - lonelySince >= FORGET_AFTER_MS) {
                engine.forgetFetchPeersForTest(fetch);
                lonelySince = now;
            }
            engine.connectFetchForTest(fetch, port);
            Thread.sleep(1000);
        }
    }

    /**
     * A magnet fetch's metadata, from the seeder on {@code port}, with {@link #awaitDownload}'s
     * redial: the fetch is a hidden torrent with the same peer list, and gives up on the seeder the
     * same way after three failures.
     */
    private static byte[] awaitFetch(LibtorrentEngine engine, Engine.SwarmProbe fetch, int port) throws Exception {
        long deadline = System.currentTimeMillis() + LOOPBACK_SECONDS * 1000;
        long lonelySince = System.currentTimeMillis();
        while (true) {
            try {
                return fetch.torrent().get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException notYet) {
                long now = System.currentTimeMillis();
                if (now > deadline) {
                    throw new AssertionError("timed out fetching the metadata from port " + port + "\n  peers:\n    "
                            + String.join("\n    ", engine.peerLogForTest()));
                }
                if (fetch.peers() > 0) {
                    lonelySince = now;
                } else if (now - lonelySince >= FORGET_AFTER_MS) {
                    engine.forgetFetchPeersForTest(fetch);
                    engine.connectFetchForTest(fetch, port);
                    lonelySince = now;
                }
            }
        }
    }

    /**
     * {@link #awaitSwarm} for a probe that knows of no peer: the seeder on {@code port} is dialled
     * once a second while none is connected, forgetting as {@link #awaitFetch} does.
     */
    private static com.torrenta.engine.SwarmPreview awaitSwarmDialling(
            LibtorrentEngine engine,
            Engine.SwarmProbe probe,
            int port,
            Set<Integer> wanted,
            java.util.function.Predicate<com.torrenta.engine.SwarmPreview> until)
            throws Exception {
        long deadline = System.currentTimeMillis() + LOOPBACK_SECONDS * 1000;
        long lonelySince = System.currentTimeMillis();
        com.torrenta.engine.SwarmPreview last = null;
        while (System.currentTimeMillis() < deadline) {
            last = probe.swarm(wanted).orElse(null);
            if (last != null && until.test(last)) return last;
            long now = System.currentTimeMillis();
            if (probe.peers() > 0) {
                lonelySince = now;
            } else {
                if (now - lonelySince >= FORGET_AFTER_MS) {
                    engine.forgetFetchPeersForTest(probe);
                    lonelySince = now;
                }
                engine.connectFetchForTest(probe, port);
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("timed out looking at the swarm; last seen " + last + "\n  peers:\n    "
                + String.join("\n    ", engine.peerLogForTest()));
    }

    /** What a fetch sees of the swarm, once it shows what {@code until} asks: polled as the dialog polls it. */
    private static com.torrenta.engine.SwarmPreview awaitSwarm(
            Engine.SwarmProbe fetch,
            Set<Integer> wanted,
            java.util.function.Predicate<com.torrenta.engine.SwarmPreview> until)
            throws Exception {
        long deadline = System.currentTimeMillis() + LOOPBACK_SECONDS * 1000;
        com.torrenta.engine.SwarmPreview last = null;
        while (System.currentTimeMillis() < deadline) {
            last = fetch.swarm(wanted).orElse(null);
            if (last != null && until.test(last)) return last;
            Thread.sleep(250);
        }
        throw new AssertionError("timed out looking at the swarm; last seen " + last);
    }

    /**
     * The harness's own guarantee: a downloader whose first three
     * dials of an address failed still reaches the seeder that comes up there, through {@link
     * #awaitDownload}. Without the forgetting, libtorrent never dials that address again.
     */
    @Test
    void aSeederRefusedThreeTimesIsStillReachedByTheHarness(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        int port = freePort();
        try (LibtorrentEngine engine = downloader(dir)) {
            engine.start();
            engine.reconnectQuicklyForTest();
            String id = engine.add(downloadsTorrent(dir, torrent)).id();
            // Nothing listens on the port yet: every dial fails until libtorrent gives up on it.
            long deadline = System.currentTimeMillis() + LOOPBACK_SECONDS * 1000;
            while (engine.peerLogForTest().stream()
                            .filter(line -> line.contains(" CONNECT: "))
                            .count()
                    < 3) {
                if (System.currentTimeMillis() > deadline)
                    throw new AssertionError("three failed dials: " + engine.peerLogForTest());
                engine.connectPeerForTest(id, port);
                Thread.sleep(1000);
            }
            try (LibtorrentEngine seed = new LibtorrentEngine(
                    () -> dir.resolve("seed"), new TorrentStore(dir.resolve("seedstore")), isolated(port))) {
                seed.recordPeersForTest();
                seed.start();
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        awaitListening("the seeder", seed) == port, "the port was taken meanwhile: nothing to prove");
                seed.add(new AddRequest(
                        new AddRequest.TorrentFile(torrent, "pair.torrent"),
                        dir.resolve("seed"),
                        false,
                        false,
                        false,
                        AddRequest.StopCondition.NONE,
                        Map.of(),
                        TorrentLabels.NONE));
                await("seeder seeding", () -> seed.torrents().get(0).state() == TorrentState.SEEDING);
                awaitDownload(
                        "downloaded after three refusals",
                        engine,
                        id,
                        seed,
                        () -> engine.torrents().get(0).progress() >= 1.0);
            }
        }
    }

    /** A file partly downloaded whose file on disk is already its full size: pre-allocated. */
    private static boolean aPartialFileIsFullSize(LibtorrentEngine engine, String id, Path saveDir) throws Exception {
        for (TorrentDetail.FileStatus f : engine.detail(id).files()) {
            if (f.done() <= 0 || f.done() >= f.size()) continue;
            Path onDisk = saveDir.resolve(f.path());
            Path part = saveDir.resolve(f.path() + TorrentStorage.SUFFIX);
            Path file = Files.isRegularFile(part) ? part : onDisk;
            if (Files.isRegularFile(file) && Files.size(file) == f.size()) return true;
        }
        return false;
    }

    @Test
    void anIncompleteFolderIsLeftForTheDestinationOnFinish(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        Path downloads = dir.resolve("downloads");
        Path incomplete = dir.resolve("incomplete");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine engine = downloader(dir, store)) {
            engine.addListener(events::add);
            engine.start();
            Torrent t = engine.add(
                    downloadsTorrent(dir, torrent).withStorage(new AddRequest.Storage(incomplete, false, false)));
            assertEquals(incomplete.toString(), t.savePath(), "starts in the incomplete folder");
            assertEquals(downloads.toString(), engine.storageForTest(t.id()).destination());
            assertTrue(store.loadStorage(t.id()).isPresent(), "the destination is on disk at once");

            awaitDownload("downloaded and moved", engine, t.id(), seed, () -> {
                Torrent now = engine.torrents().get(0);
                return now.progress() >= 1.0 - 1e-6 && now.savePath().equals(downloads.toString());
            });
            assertTrue(Files.isRegularFile(downloads.resolve("pair").resolve("a.bin")));
            assertTrue(
                    Files.isRegularFile(downloads.resolve("pair").resolve("sub").resolve("b.bin")));
            assertFalse(Files.exists(incomplete.resolve("pair").resolve("a.bin")), "moved, not copied");
            await(
                    "nothing owed any more",
                    () -> engine.storageForTest(t.id()).isEmpty()
                            && store.loadStorage(t.id()).isEmpty());
            await(
                    "the move is in the log",
                    () -> events.stream().anyMatch(e -> e.message().equals("Moved pair to " + downloads)));
            assertEquals(downloads.toString(), engine.detail(t.id()).general().savePath());
        }
    }

    /**
     * libtorrent 2.1 renames on disk and finds the file on a recheck, but its resume data reads a
     * renamed file back relative to the save path and torrent_file() never shows the new name:
     * without the engine's own record a restart lost the file and the Files tab showed the old one.
     */
    @Test
    void renamedFilesAndFoldersAreFoundAfterARecheckAndARestart(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String id;
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            id = engine.add(downloadsTorrent(dir, torrent)).id();
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);

            engine.renamePath(id, "pair/a.bin", "x.bin");
            engine.renamePath(id, "pair/sub", "deep");
            await(
                    "renamed on disk",
                    () -> Files.isRegularFile(downloads.resolve("pair/x.bin"))
                            && Files.isRegularFile(downloads.resolve("pair/deep/b.bin")));
            await("the emptied folder went", () -> !Files.exists(downloads.resolve("pair/sub")));
            assertFalse(Files.exists(downloads.resolve("pair/a.bin")));
            assertEquals(List.of("pair/x.bin", "pair/deep/b.bin"), paths(engine, id), "the Files tab's names");
            assertThrows(EngineException.class, () -> engine.renamePath(id, "pair/x.bin", "deep"), "a folder is there");

            engine.recheck(id);
            await("checked", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            assertEquals(1.0, engine.torrents().get(0).progress(), 1e-6);
        }
        try (LibtorrentEngine again = engine(dir, store)) {
            again.start();
            await(
                    "complete under the new names",
                    () -> again.torrents().get(0).progress() >= 1.0 - 1e-6
                            && again.torrents().get(0).state() == TorrentState.SEEDING);
            assertEquals(List.of("pair/x.bin", "pair/deep/b.bin"), paths(again, id));

            again.renamePath(id, "pair", "top");
            await("the root folder renamed", () -> Files.isRegularFile(downloads.resolve("top/deep/b.bin")));
            await("and the old one gone", () -> !Files.exists(downloads.resolve("pair")));
            again.renamePath(id, "top/x.bin", "a.bin");
            assertEquals(List.of("top/a.bin", "top/deep/b.bin"), paths(again, id));
        }
    }

    private static List<String> paths(LibtorrentEngine engine, String id) throws EngineException {
        return engine.detail(id).files().stream()
                .map(TorrentDetail.FileStatus::path)
                .toList();
    }

    /** The content layout is renamed files in the add parameters: the add's own check finds them. */
    @Test
    void theContentLayoutFindsTheFilesWhereItPutsThemAndKeepsThem(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] pair = twoFileTorrent(dir.resolve("made"), "pair");
        Files.createDirectories(downloads.resolve("sub"));
        Files.copy(dir.resolve("made/pair/a.bin"), downloads.resolve("a.bin"));
        Files.copy(dir.resolve("made/pair/sub/b.bin"), downloads.resolve("sub/b.bin"));

        Path made = dir.resolve("made/solo.bin");
        Files.write(made, new byte[40 * 1024]);
        byte[] solo =
                new TorrentBuilder().path(made.toFile()).generate().entry().bencode();
        Files.createDirectories(downloads.resolve("solo"));
        Files.copy(made, downloads.resolve("solo/solo.bin"));

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            String flat = engine.add(downloadsTorrent(dir, pair)
                            .withStorage(AddRequest.Storage.DEFAULT.withLayout(
                                    com.torrenta.engine.FileRenames.Layout.NO_SUBFOLDER)))
                    .id();
            String boxed = engine.add(new AddRequest(
                                    new AddRequest.TorrentFile(solo, "solo.torrent"),
                                    downloads,
                                    false,
                                    false,
                                    false,
                                    AddRequest.StopCondition.NONE,
                                    Map.of(),
                                    TorrentLabels.NONE)
                            .withStorage(AddRequest.Storage.DEFAULT.withLayout(
                                    com.torrenta.engine.FileRenames.Layout.SUBFOLDER)))
                    .id();
            await(
                    "both found complete",
                    () -> engine.torrents().stream().allMatch(t -> t.state() == TorrentState.SEEDING));
            assertEquals(List.of("a.bin", "sub/b.bin"), paths(engine, flat));
            assertEquals(List.of("solo/solo.bin"), paths(engine, boxed));
        }
        try (LibtorrentEngine again = engine(dir, store)) {
            again.start();
            await(
                    "still complete after a restart",
                    () -> again.torrents().stream()
                            .allMatch(t -> t.progress() >= 1.0 - 1e-6 && t.state() == TorrentState.SEEDING));
        }
    }

    /** A rename while the suffix is on: the .part goes on the new name and comes off it at the end. */
    @Test
    void aFileRenamedWhileItCarriesTheSuffixEndsUnderItsNewName(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        Path downloads = dir.resolve("downloads");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            String id = engine.add(
                            downloadsTorrent(dir, torrent).withStorage(new AddRequest.Storage(null, true, false)))
                    .id();
            await("the suffix is on", () -> engine.storageForTest(id).suffixed().size() == 2);
            engine.renamePath(id, "pair/a.bin", "x.bin");
            assertEquals("pair/x.bin", engine.storageForTest(id).renamed().get(0));

            awaitDownload(
                    "downloaded",
                    engine,
                    id,
                    seed,
                    () -> engine.torrents().get(0).progress() >= 1.0 - 1e-6);
            await(
                    "the suffix is off",
                    () -> engine.storageForTest(id).suffixed().isEmpty());
            await("under the new name", () -> Files.isRegularFile(downloads.resolve("pair/x.bin")));
            assertFalse(Files.exists(downloads.resolve("pair/x.bin.part")));
            assertFalse(Files.exists(downloads.resolve("pair/a.bin")));
            assertFalse(Files.exists(downloads.resolve("pair/a.bin.part")));
        }
    }

    @Test
    void theMoveAndTheSuffixSurviveARestart(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        Path downloads = dir.resolve("downloads");
        Path incomplete = dir.resolve("incomplete");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String id;
        try (LibtorrentEngine engine = engine(dir, store)) {
            engine.start();
            AddRequest paused = new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    true,
                    true,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    TorrentLabels.NONE,
                    new AddRequest.Storage(incomplete, true, true));
            id = engine.add(paused).id();
            // The suffix goes on once the check is over, paused or not. No file exists yet (a
            // file is allocated when it is first written to), and the handle's torrent_file()
            // keeps the original names in libtorrent 2.1, so the sidecar is what says so.
            await(
                    "both files suffixed",
                    () -> engine.storageForTest(id).suffixed().size() == 2);
            assertFalse(Files.exists(incomplete.resolve("pair")), "nothing written while paused");
            assertTrue(
                    engine.detail(id).files().stream().noneMatch(f -> f.path().endsWith(".part")),
                    "the detail pane shows the files by their own names");
            await("resume data written", () -> store.contains(id));
        }
        TorrentStorage owed = TorrentStorage.decode(store.loadStorage(id).orElseThrow());
        assertEquals(downloads.toString(), owed.destination());
        assertTrue(owed.partSuffix());
        assertEquals(2, owed.suffixed().size());

        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine again = downloader(dir, store)) {
            again.addListener(events::add);
            again.start();
            assertEquals(owed, again.storageForTest(id), "read back with the resume data");
            Torrent back = again.torrents().get(0);
            assertTrue(back.paused());
            assertEquals(incomplete.toString(), back.savePath());
            // Throttled so there is a moment to look at the files while they are incomplete: on
            // disk under the suffix, in the torrent's own folder (the resume data had dropped the
            // folder; see restoreSuffixes), and at full size from the first piece.
            again.setLimits(id, new com.torrenta.engine.TorrentLimits(48 * 1024, 0, 0, 0, false));
            again.resume(id);
            Path aPart = incomplete.resolve("pair").resolve("a.bin.part");
            Path bPart = incomplete.resolve("pair").resolve("sub").resolve("b.bin.part");
            awaitDownload(
                    "a suffixed file in the torrent's folder, allocated in full",
                    again,
                    id,
                    seed,
                    () -> (Files.isRegularFile(aPart) || Files.isRegularFile(bPart))
                            && aPartialFileIsFullSize(again, id, incomplete));
            assertFalse(Files.exists(incomplete.resolve("a.bin.part")), "not beside the folder");
            again.setLimits(id, com.torrenta.engine.TorrentLimits.NONE);
            awaitDownload("downloaded, renamed and moved", again, id, seed, () -> {
                Torrent now = again.torrents().get(0);
                return now.progress() >= 1.0 - 1e-6 && now.savePath().equals(downloads.toString());
            });
            await(
                    "nothing to take back",
                    () -> again.storageForTest(id).suffixed().isEmpty());
            assertTrue(Files.isRegularFile(downloads.resolve("pair").resolve("a.bin")), "its own name again");
            assertTrue(
                    Files.isRegularFile(downloads.resolve("pair").resolve("sub").resolve("b.bin")));
            assertFalse(Files.exists(downloads.resolve("pair").resolve("a.bin.part")));
            assertFalse(Files.exists(aPart));
            assertTrue(again.detail(id).files().stream().noneMatch(f -> f.path().endsWith(".part")));
            await(
                    "the move is in the log",
                    () -> events.stream().anyMatch(e -> e.message().startsWith("Moved pair to ")));
            // The suffix is still wanted (a file set to ignore and wanted later would get it), so
            // the sidecar stays; the destination is done with.
            assertEquals("", again.storageForTest(id).destination());
        }
    }

    @Test
    void preallocationWritesEachFileAtFullSizeFromItsFirstPiece(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        Path downloads = dir.resolve("downloads");
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine engine = downloader(dir)) {
            engine.start();
            AddRequest sequential = new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    false,
                    true,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    TorrentLabels.NONE,
                    new AddRequest.Storage(null, false, true));
            Torrent t = engine.add(sequential);
            assertTrue(engine.storageForTest(t.id()).isEmpty(), "nothing owed: no sidecar for allocation alone");
            engine.setLimits(t.id(), new com.torrenta.engine.TorrentLimits(48 * 1024, 0, 0, 0, false));
            awaitDownload(
                    "a file at full size while partly downloaded",
                    engine,
                    t.id(),
                    seed,
                    () -> aPartialFileIsFullSize(engine, t.id(), downloads));
            // libtorrent 2.x allocates a file when it is first written to, not at add: nothing
            // of a file no piece has reached yet is on disk.
            assertFalse(Files.isRegularFile(downloads.resolve("pair").resolve("a.bin"))
                    && Files.isRegularFile(
                            downloads.resolve("pair").resolve("sub").resolve("b.bin"))
                    && engine.torrents().get(0).progress() < 0.3);
        }
    }

    @Test
    void aFileThatIsAlreadyCompleteKeepsItsNameWhenTheSuffixIsOn(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] torrent = twoFileTorrent(downloads, "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent t =
                    engine.add(downloadsTorrent(dir, torrent).withStorage(new AddRequest.Storage(null, true, false)));
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            await("checked", () -> engine.detail(t.id()).files().size() == 2);
            assertTrue(Files.isRegularFile(downloads.resolve("pair").resolve("a.bin")));
            assertFalse(Files.exists(downloads.resolve("pair").resolve("a.bin.part")));
            assertTrue(engine.detail(t.id()).files().stream()
                    .noneMatch(f -> f.path().endsWith(".part")));
            assertTrue(engine.storageForTest(t.id()).suffixed().isEmpty());
        }
    }

    /**
     * The application screens a watch folder's add (the content check): the add goes through
     * {@code Host.add}, a refusal there adds nothing, is reported like a failed add, and the file
     * stays where it was for the person to look at.
     */
    @Test
    void aWatchFolderAddGoesThroughTheHostWhichMayRefuseIt(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] pair = twoFileTorrent(downloads, "pair");
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        List<AddRequest> screened = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            com.torrenta.engine.WatchFolders.Host host = new com.torrenta.engine.WatchFolders.Host() {
                @Override
                public Path defaultDownloadDir() {
                    return downloads;
                }

                @Override
                public java.util.Optional<com.torrenta.engine.Category> category(String name) {
                    return java.util.Optional.empty();
                }

                @Override
                public AddRequest.Storage storage(Path destination) {
                    return AddRequest.Storage.DEFAULT;
                }

                @Override
                public void report(EngineEvent event) {
                    events.add(event);
                }

                @Override
                public Torrent add(com.torrenta.engine.Engine e, AddRequest request) throws EngineException {
                    screened.add(request);
                    throw new EngineException("refused by the host");
                }
            };
            try (com.torrenta.engine.WatchFolders watcher = new com.torrenta.engine.WatchFolders(engine, host)) {
                watcher.configure(List.of(new com.torrenta.engine.WatchFolder(
                        inbox.toString(),
                        com.torrenta.engine.WatchFolder.AfterAdd.RENAME,
                        com.torrenta.engine.WatchFolder.SaveTo.DEFAULT,
                        "",
                        "",
                        false)));
                Path dropped = inbox.resolve("pair.torrent");
                Files.write(dropped, pair);
                watcher.poll();
                watcher.poll();
                assertEquals(1, screened.size(), "the add went through the host");
                assertTrue(engine.torrents().isEmpty(), "and added nothing");
                assertTrue(Files.isRegularFile(dropped), "the file stays");
                assertTrue(
                        events.stream().anyMatch(e -> e.message().contains("refused by the host")), events.toString());
            }
        }
    }

    /** An updatable address in a {@code .magnet} file names no torrent until the host looks it up. */
    @Test
    void watchFoldersLookAnUpdatableAddressUpBeforeTheAdd(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        String address = "magnet:?xs=urn:btpk:" + "ab".repeat(32) + "&s=v";
        String hash = "0123456789abcdef0123456789abcdef01234567";
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        List<String> looked = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            com.torrenta.engine.WatchFolders.Host host = new com.torrenta.engine.WatchFolders.Host() {
                @Override
                public Path defaultDownloadDir() {
                    return downloads;
                }

                @Override
                public java.util.Optional<com.torrenta.engine.Category> category(String name) {
                    return java.util.Optional.empty();
                }

                @Override
                public AddRequest.Storage storage(Path destination) {
                    return AddRequest.Storage.DEFAULT;
                }

                @Override
                public void report(EngineEvent event) {
                    events.add(event);
                }

                @Override
                public AddRequest.Source resolve(AddRequest.Source source) {
                    if (!(source instanceof AddRequest.Magnet m) || !m.uri().equals(address)) return source;
                    looked.add(m.uri());
                    return new AddRequest.Magnet("magnet:?xt=urn:btih:" + hash);
                }
            };
            try (com.torrenta.engine.WatchFolders watcher = new com.torrenta.engine.WatchFolders(engine, host)) {
                watcher.configure(List.of(new com.torrenta.engine.WatchFolder(
                        inbox.toString(),
                        com.torrenta.engine.WatchFolder.AfterAdd.DELETE,
                        com.torrenta.engine.WatchFolder.SaveTo.DEFAULT,
                        "",
                        "",
                        true)));
                Files.writeString(inbox.resolve("follow.magnet"), address + "\n");
                watcher.poll();
                watcher.poll();
                assertEquals(List.of(address), looked, "the address went to the host");
                await("the current version added", () -> engine.torrents().size() == 1);
                assertEquals(hash, engine.torrents().get(0).id());

                // The same address again: recognised by the hash it resolves to, not added twice.
                Files.writeString(inbox.resolve("again.magnet"), address + "\n");
                watcher.poll();
                watcher.poll();
                assertEquals(2, looked.size());
                assertEquals(1, engine.torrents().size());
                assertTrue(
                        events.stream().anyMatch(e -> e.message().startsWith("Already in the session")),
                        events.toString());
            }
        }
    }

    @Test
    void watchFoldersAddWhatIsDroppedIn(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        byte[] pair = twoFileTorrent(downloads, "pair");
        byte[] other = twoFileTorrent(downloads, "other");
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        Path later = dir.resolve("later");
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            com.torrenta.engine.WatchFolders.Host host = new com.torrenta.engine.WatchFolders.Host() {
                @Override
                public Path defaultDownloadDir() {
                    return downloads;
                }

                @Override
                public java.util.Optional<com.torrenta.engine.Category> category(String name) {
                    return name.equals("Movies")
                            ? java.util.Optional.of(new com.torrenta.engine.Category("Movies"))
                            : java.util.Optional.empty();
                }

                @Override
                public AddRequest.Storage storage(Path destination) {
                    return AddRequest.Storage.DEFAULT;
                }

                @Override
                public void report(EngineEvent event) {
                    events.add(event);
                }
            };
            List<com.torrenta.engine.WatchFolder> entries = List.of(
                    new com.torrenta.engine.WatchFolder(
                            inbox.toString(),
                            com.torrenta.engine.WatchFolder.AfterAdd.RENAME,
                            com.torrenta.engine.WatchFolder.SaveTo.WATCH_FOLDER,
                            "",
                            "Movies",
                            true),
                    new com.torrenta.engine.WatchFolder(
                            later.toString(),
                            com.torrenta.engine.WatchFolder.AfterAdd.DELETE,
                            com.torrenta.engine.WatchFolder.SaveTo.DEFAULT,
                            "",
                            "",
                            false));
            try (com.torrenta.engine.WatchFolders watcher = new com.torrenta.engine.WatchFolders(engine, host)) {
                watcher.configure(entries);
                assertEquals(List.of(later), watcher.missing(), "a folder that is not there is noted, not fatal");
                assertEquals(1, events.size(), "and said once");
                assertTrue(events.get(0).message().contains("does not exist"));

                // A .torrent: read once it has held its size for two looks, then renamed.
                Path dropped = inbox.resolve("pair.torrent");
                Files.write(dropped, pair);
                assertTrue(watcher.poll(), "the first look only notes the size: come back soon");
                assertTrue(engine.torrents().isEmpty(), "not read half-way");
                assertFalse(watcher.poll(), "the second look adds it");
                await("in the session", () -> engine.torrents().size() == 1);
                Torrent t = engine.torrents().get(0);
                assertEquals("pair", t.name());
                assertTrue(t.paused(), "the entry's start-paused");
                assertEquals(inbox.toString(), t.savePath(), "saved to the watch folder itself");
                assertEquals("Movies", t.category());
                assertFalse(Files.exists(dropped));
                assertTrue(Files.isRegularFile(inbox.resolve("pair.torrent.added")));
                assertTrue(
                        events.stream().anyMatch(e -> e.message().equals("Added pair from watch folder " + inbox)),
                        events.toString());

                // The same torrent again: not added twice, the file still handled.
                Files.write(inbox.resolve("again.torrent"), pair);
                watcher.poll();
                watcher.poll();
                assertEquals(1, engine.torrents().size());
                assertTrue(Files.isRegularFile(inbox.resolve("again.torrent.added")));
                assertTrue(
                        events.stream().anyMatch(e -> e.message().startsWith("Already in the session: again.torrent")));

                // Not a torrent: set aside once, said once, never looked at again.
                Files.write(inbox.resolve("junk.torrent"), "not bencode".getBytes());
                watcher.poll();
                watcher.poll();
                watcher.poll();
                watcher.poll();
                assertTrue(Files.isRegularFile(inbox.resolve("junk.torrent.invalid")));
                assertEquals(
                        1,
                        events.stream()
                                .filter(e -> e.message().startsWith("Not a torrent: junk.torrent"))
                                .count(),
                        events.toString());
                assertEquals(1, engine.torrents().size());

                // A .magnet file: one link per line; comments and blanks are skipped.
                Files.writeString(inbox.resolve("links.magnet"), "# one\n" + MAGNET + "\n\n");
                watcher.poll();
                watcher.poll();
                await(
                        "the magnet in the session",
                        () -> engine.torrents().stream().anyMatch(x -> x.id().equals(MAGNET_ID)));
                assertTrue(Files.isRegularFile(inbox.resolve("links.magnet.added")));

                // The missing folder appears: polled from then on, and this entry deletes.
                Files.createDirectories(later);
                Files.write(later.resolve("other.torrent"), other);
                watcher.poll();
                assertEquals(List.of(), watcher.missing());
                watcher.poll();
                await(
                        "other in the session",
                        () -> engine.torrents().stream().anyMatch(x -> x.name().equals("other")));
                assertFalse(Files.exists(later.resolve("other.torrent")), "deleted, as that entry says");
                assertEquals(
                        downloads.toString(),
                        engine.torrents().stream()
                                .filter(x -> x.name().equals("other"))
                                .findFirst()
                                .orElseThrow()
                                .savePath(),
                        "the download folder");
                assertEquals(3, engine.torrents().size());
                assertFalse(watcher.poll(), "nothing left to look at");
            }
        }
    }

    // ---- creating torrents ---------------------------------------------------------------------

    /** A two-file folder, {@code name/a.bin} (100 KiB) and {@code name/sub/b.bin} (50 KiB). */
    private static Path twoFileFolder(Path parent, String name) throws Exception {
        Path root = parent.resolve(name);
        Files.createDirectories(root.resolve("sub"));
        byte[] a = new byte[100 * 1024];
        byte[] b = new byte[50 * 1024];
        new Random(7).nextBytes(a);
        new Random(8).nextBytes(b);
        Files.write(root.resolve("a.bin"), a);
        Files.write(root.resolve("sub").resolve("b.bin"), b);
        return root;
    }

    private static CreateRequest request(Path path, CreateRequest.Format format, boolean align, boolean seed) {
        return new CreateRequest(
                path,
                format,
                16 * 1024,
                true,
                List.of(
                        List.of("udp://one.example.org:6969/announce", "udp://two.example.org:6969/announce"),
                        List.of("https://three.example.org/announce")),
                List.of("https://seed.example.org/files/"),
                "Made by the test",
                "TEST",
                align,
                seed);
    }

    @Test
    void createsAHybridTorrentOfAFolderThatInspectReadsBack(@TempDir Path dir) throws Exception {
        Path root = twoFileFolder(dir.resolve("src"), "pair");
        List<int[]> calls = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            CreatedTorrent made =
                    engine.create(request(root, CreateRequest.Format.HYBRID, false, false), (done, total) -> {
                        calls.add(new int[] {done, total});
                        return true;
                    });
            assertEquals("pair", made.name());
            assertEquals(40, made.v1().length(), made.v1());
            assertEquals(64, made.v2().length(), made.v2());
            assertEquals(made.v2().substring(0, 40), made.id(), "the engine's id: the v2 hash cut to 20 bytes");
            assertEquals(16 * 1024, made.pieceSize());
            // Every file on a piece boundary: 7 pieces of a.bin, then 4 of b.bin.
            assertEquals(11, made.pieceCount());
            assertEquals(150 * 1024, made.totalSize(), "padding is not content");
            assertTrue(made.magnet().startsWith("magnet:?"), made.magnet());
            assertTrue(made.magnet().contains("one.example.org"), "the trackers travel in the link: " + made.magnet());
            assertTrue(made.seeding().isEmpty() && made.problem().isEmpty(), "not asked to seed");

            assertEquals(11, calls.get(calls.size() - 1)[0], "the last report is the total");
            assertEquals(11, calls.get(calls.size() - 1)[1]);
            assertTrue(calls.stream().allMatch(c -> c[0] >= 1 && c[0] <= c[1]), "done within 1..total");

            TorrentPreview preview = engine.inspect(made.bytes());
            assertEquals("pair", preview.name());
            assertEquals(
                    List.of("pair/a.bin", "pair/sub/b.bin"),
                    preview.files().stream().map(TorrentPreview.FileEntry::path).toList());
            assertEquals(
                    List.of(100 * 1024L, 50 * 1024L),
                    preview.files().stream().map(TorrentPreview.FileEntry::size).toList());
            assertEquals(150 * 1024, preview.size());
            assertEquals(
                    List.of(
                            "udp://one.example.org:6969/announce",
                            "udp://two.example.org:6969/announce",
                            "https://three.example.org/announce"),
                    preview.trackers(),
                    "tier order");
            assertTrue(preview.isPrivate());
            assertEquals("Made by the test", preview.comment());
            assertTrue(preview.createdBy().startsWith("Torrenta "), preview.createdBy());
            assertTrue(preview.v1() && preview.v2(), "a hybrid");
            assertEquals(made.id(), preview.id());

            // The source key is in the info dictionary, so it changes the hashes.
            String text = new String(made.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("6:source4:TEST"), "the source key is written");
            assertTrue(text.contains("8:url-list"), "the web seed is written");
            CreateRequest other = new CreateRequest(
                    root,
                    CreateRequest.Format.HYBRID,
                    16 * 1024,
                    true,
                    List.of(),
                    List.of(),
                    "",
                    "OTHER",
                    false,
                    false);
            assertNotEquals(
                    made.id(), engine.create(other, Engine.CreateProgress.NONE).id());
            assertTrue(engine.torrents().isEmpty(), "creating adds nothing unless asked");
        }
    }

    @Test
    void v1AndV2OnlyTorrentsCarryOnlyTheirOwnHash(@TempDir Path dir) throws Exception {
        Path root = twoFileFolder(dir.resolve("src"), "pair");
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            CreatedTorrent v1 =
                    engine.create(request(root, CreateRequest.Format.V1, false, false), Engine.CreateProgress.NONE);
            assertEquals(40, v1.v1().length());
            assertEquals("", v1.v2());
            assertEquals(v1.v1(), v1.id());
            assertEquals(10, v1.pieceCount(), "files packed back to back: 150 KiB in 16 KiB pieces");
            TorrentPreview p1 = engine.inspect(v1.bytes());
            assertTrue(p1.v1() && !p1.v2());
            assertTrue(v1.magnet().contains("urn:btih:" + v1.v1()), v1.magnet());

            CreatedTorrent aligned =
                    engine.create(request(root, CreateRequest.Format.V1, true, false), Engine.CreateProgress.NONE);
            assertEquals(11, aligned.pieceCount(), "padded to piece boundaries");
            assertEquals("", aligned.v2());
            assertEquals(2, engine.inspect(aligned.bytes()).files().size(), "the pad files are not shown");

            CreatedTorrent v2 =
                    engine.create(request(root, CreateRequest.Format.V2, false, false), Engine.CreateProgress.NONE);
            assertEquals("", v2.v1());
            assertEquals(64, v2.v2().length());
            assertEquals(11, v2.pieceCount());
            TorrentPreview p2 = engine.inspect(v2.bytes());
            assertTrue(!p2.v1() && p2.v2());
            assertTrue(v2.magnet().contains("urn:btmh:"), v2.magnet());
        }
    }

    @Test
    void aCreatedTorrentSeedsFromItsFolderWithoutARecheck(@TempDir Path dir) throws Exception {
        Path folder = dir.resolve("files");
        Files.createDirectories(folder);
        Path payload = folder.resolve("payload.bin");
        byte[] bytes = new byte[300 * 1024];
        new Random(3).nextBytes(bytes);
        Files.write(payload, bytes);
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            CreateRequest request = request(payload, CreateRequest.Format.HYBRID, false, true);
            assertEquals(folder, request.savePath(), "seeded from the parent, so the torrent's name is the file's");
            CreatedTorrent made = engine.create(request, Engine.CreateProgress.NONE);
            assertEquals("payload.bin", made.name());
            assertTrue(made.seeding().isPresent(), made.problem());
            Torrent added = made.seeding().get();
            assertEquals(made.id(), added.id());
            assertEquals(1.0, added.progress(), 1e-9, "trusted as complete: no check");
            assertEquals(folder.toString(), added.savePath());
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            TorrentPreview preview = engine.inspect(made.bytes());
            assertEquals(
                    List.of("payload.bin"),
                    preview.files().stream().map(TorrentPreview.FileEntry::path).toList());
            assertFalse(preview.hasRootFolder());

            // The same torrent again: created all the same; the list holds one per hash, and
            // libtorrent hands back the one already there, silently, so it is "seeding" still.
            CreatedTorrent again = engine.create(request, Engine.CreateProgress.NONE);
            assertEquals(made.id(), again.id());
            assertEquals(java.util.Optional.of(made.id()), again.seeding().map(Torrent::id), again.problem());
            assertEquals(1, engine.torrents().size());
        }
    }

    @Test
    void aCancelledCreationMakesNothing(@TempDir Path dir) throws Exception {
        Path folder = dir.resolve("files");
        Files.createDirectories(folder);
        byte[] bytes = new byte[24 * 1024 * 1024];
        new Random(5).nextBytes(bytes);
        Files.write(folder.resolve("big.bin"), bytes);
        List<int[]> calls = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            CreateRequest request = request(folder.resolve("big.bin"), CreateRequest.Format.HYBRID, false, true);
            EngineException failure = assertThrows(
                    EngineException.class,
                    () -> engine.create(request, (done, total) -> {
                        calls.add(new int[] {done, total});
                        return false; // cancel at the first piece
                    }));
            assertEquals("Cancelled", failure.getMessage());
            assertFalse(calls.isEmpty(), "the listener was asked");
            assertEquals(1536, calls.get(0)[1], "24 MiB in 16 KiB pieces");
            assertTrue(engine.torrents().isEmpty(), "nothing added though seeding was asked");

            assertThrows(
                    EngineException.class,
                    () -> engine.create(CreateRequest.defaults(dir.resolve("nowhere")), Engine.CreateProgress.NONE),
                    "a missing path");
        }
    }

    /** A v1 .torrent of one file, bencoded by hand, so no libtorrent code touches the file. */
    private static byte[] v1TorrentOf(Path file, int pieceLength) throws Exception {
        byte[] data = Files.readAllBytes(file);
        java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
        java.io.ByteArrayOutputStream pieces = new java.io.ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += pieceLength) {
            sha1.reset();
            sha1.update(data, off, Math.min(pieceLength, data.length - off));
            pieces.write(sha1.digest());
        }
        byte[] name = file.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write("d4:infod6:lengthi".getBytes());
        out.write(String.valueOf(data.length).getBytes());
        out.write("e4:name".getBytes());
        out.write((name.length + ":").getBytes());
        out.write(name);
        out.write("12:piece lengthi".getBytes());
        out.write((pieceLength + "e6:pieces").getBytes());
        out.write((pieces.size() + ":").getBytes());
        out.write(pieces.toByteArray());
        out.write("ee".getBytes());
        return out.toByteArray();
    }

    @Test
    void theJavaHasherMatchesLibtorrentsOwnBitForBit(@TempDir Path dir) throws Exception {
        // Sizes that exercise every edge: a full piece plus a partial one with a short last block,
        // a one-piece file whose tree is narrower than a piece, a one-block file; 64 KiB pieces.
        Path root = dir.resolve("src").resolve("mixed");
        Files.createDirectories(root.resolve("sub"));
        byte[] a = new byte[100 * 1024];
        byte[] b = new byte[20 * 1024];
        byte[] c = new byte[3 * 1024];
        new Random(11).nextBytes(a);
        new Random(12).nextBytes(b);
        new Random(13).nextBytes(c);
        Files.write(root.resolve("a.bin"), a);
        Files.write(root.resolve("sub").resolve("b.bin"), b);
        Files.write(root.resolve("c.bin"), c);
        NativeLibrary.ensureAvailable();
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            for (CreateRequest.Format format : CreateRequest.Format.values()) {
                for (boolean align :
                        format == CreateRequest.Format.V1 ? new boolean[] {false, true} : new boolean[] {false}) {
                    CreateRequest request = new CreateRequest(
                            root, format, 64 * 1024, true, List.of(), List.of(), "", "", align, false);
                    CreatedTorrent ours = engine.create(request, Engine.CreateProgress.NONE);
                    // libtorrent's own hashing, safe here: no file reaches its mmap cutoff.
                    org.libtorrent4j.swig.create_flags_t flags = new org.libtorrent4j.swig.create_flags_t();
                    if (format == CreateRequest.Format.V1) flags = flags.or_(TorrentBuilder.V1_ONLY);
                    if (format == CreateRequest.Format.V1 && align) flags = flags.or_(TorrentBuilder.CANONICAL_FILES);
                    if (format == CreateRequest.Format.V2) flags = flags.or_(TorrentBuilder.V2_ONLY);
                    byte[] theirs = new TorrentBuilder()
                            .path(root.toFile())
                            .pieceSize(64 * 1024)
                            .flags(flags)
                            .setPrivate(true)
                            .generate()
                            .entry()
                            .bencode();
                    String label = format + (align ? " aligned" : "");
                    assertArrayEquals(
                            infoOf(theirs), infoOf(ours.bytes()), label + ": the info dictionaries are the same bytes");
                    assertEquals(engine.inspect(theirs).id(), ours.id(), label);
                }
            }
        }
    }

    /** The bencoded {@code info} dictionary: what both hashes are computed over. */
    private static byte[] infoOf(byte[] torrent) {
        return org.libtorrent4j.Entry.bdecode(torrent).dictionary().get("info").bencode();
    }

    @Test
    void aFileBigEnoughToMapIsSeededWithoutLibtorrentTakingTheJvmsSignalHandler(@TempDir Path dir) throws Exception {
        // libtorrent's mmap disk I/O installs a SIGSEGV/SIGBUS guard of its own, over the JVM's,
        // once a file of 40 pieces or more is mapped; the JVM's next safepoint poll then kills the
        // process (exit 139, no error file). The pack sets the mmap cutoff above every file; this
        // is the shape that died: a 2 MiB file in 16 KiB pieces, polled while a thread drives GC.
        Path downloads = dir.resolve("downloads");
        Files.createDirectories(downloads);
        byte[] bytes = new byte[2 * 1024 * 1024];
        new Random(5).nextBytes(bytes);
        Files.write(downloads.resolve("big.bin"), bytes);
        byte[] torrent = v1TorrentOf(downloads.resolve("big.bin"), 16 * 1024);
        NativeLibrary.ensureAvailable();
        java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread collector = new Thread(() -> {
            while (on.get()) {
                System.gc();
                System.runFinalization();
            }
        });
        collector.setDaemon(true);
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            Torrent added = engine.add(AddRequest.defaults(new AddRequest.TorrentFile(torrent, "big"), downloads));
            await("seeding", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);
            collector.start();
            for (int i = 0; i < 40; i++) {
                assertEquals(1, engine.torrents().size());
                assertEquals(1, engine.detail(added.id()).files().size());
                Thread.sleep(25);
            }
        } finally {
            on.set(false);
            collector.join(5_000);
        }
    }

    @Test
    void everyStatsCounterTheEngineReadsExists() throws Exception {
        NativeLibrary.ensureAvailable();
        int[] indices = LibtorrentEngine.Counter.resolve();
        LibtorrentEngine.Counter[] counters = LibtorrentEngine.Counter.values();
        for (int i = 0; i < counters.length; i++) {
            assertTrue(indices[i] >= 0, counters[i].metric + " is not a session_stats metric of this libtorrent");
        }
    }

    @Test
    void statsCountTheBytesALoopbackPairMovesAndTheTotalsSurviveARestart(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        StatsStore statsStore = new StatsStore(dir.resolve("config").resolve(StatsStore.FILE_NAME));
        long size;
        try (LibtorrentEngine seed = seeder(dir, torrent);
                LibtorrentEngine engine =
                        new LibtorrentEngine(() -> dir.resolve("downloads"), store, isolated(ANY_PORT), statsStore)) {
            engine.start();
            SessionStats fresh = engine.stats();
            assertEquals(0, fresh.sessionDownload());
            assertEquals(0, fresh.allTimeDownload(), "no file yet: all time starts at zero");
            Torrent t = engine.add(downloadsTorrent(dir, torrent));
            size = t.size();
            assertEquals(150 * 1024, size);
            awaitDownload(
                    "downloaded",
                    engine,
                    t.id(),
                    seed,
                    () -> engine.torrents().get(0).progress() >= 1.0 - 1e-6);
            // The counters are read once a second, so they trail the transfer by a moment.
            await("the download counter caught up", () -> engine.stats().sessionDownload() >= size);
            await("the seeder's upload counter caught up", () -> seed.stats().sessionUpload() >= size);
            await("the seeder counts its seed", () -> seed.stats().torrents().seeding() == 1);
            // Disk writes trail the payload: a block received may still be in the write cache
            // (CI caught 6 of 10 blocks written, 5 in use, with the download counter complete).
            await(
                    "the files were written",
                    () -> engine.stats().diskBlocksWritten() * SessionStats.BLOCK_BYTES >= size);
            SessionStats s = engine.stats();
            assertEquals(s.sessionDownload(), s.allTimeDownload(), "the first run's all-time is the session");
            assertEquals(s.sessionUpload(), s.allTimeUpload());
            assertTrue(s.totalReceived() >= s.sessionDownload(), "every byte includes the payload");
            assertTrue(s.diskBlocksWritten() * SessionStats.BLOCK_BYTES >= size, "the files were written: " + s);
            assertTrue(s.wasted() >= 0 && s.dhtNodes() >= 0 && s.peersConnected() >= 0, "every counter known: " + s);
            assertTrue(s.torrents().checking() >= 0 && s.torrents().stopped() >= 0, "every gauge known: " + s);
            assertTrue(s.sessionSeconds() >= 0 && s.allTimeSeconds() == s.sessionSeconds());
        }
        StatsStore.Totals saved = statsStore.load();
        assertTrue(saved.download() >= size, "stop wrote the totals: " + saved);
        try (LibtorrentEngine again = new LibtorrentEngine(
                () -> dir.resolve("downloads"), store, com.torrenta.engine.EngineSettings.defaults(), statsStore)) {
            again.start();
            SessionStats back = again.stats();
            assertEquals(0, back.sessionDownload(), "a new session starts from nothing");
            assertEquals(saved.download(), back.allTimeDownload(), "the earlier run's bytes are back");
            assertEquals(saved.upload(), back.allTimeUpload());
            assertTrue(back.allTimeSeconds() >= saved.activeSeconds());
        }
        assertTrue(statsStore.load().activeSeconds() >= saved.activeSeconds(), "the second stop wrote again");
    }

    // ---- queue order and set location ---------------------------------------------------------------

    /**
     * Three torrents without their files, added paused, stand in the download queue in the order
     * they were added; each move shifts the others, as the positions in the snapshots then show.
     */
    @Test
    void queueMovesShiftTheOthers(@TempDir Path dir) throws Exception {
        try (LibtorrentEngine engine = engine(dir)) {
            engine.start();
            List<String> ids = new java.util.ArrayList<>();
            for (String name : List.of("q1", "q2", "q3")) {
                byte[] torrent = twoFileTorrent(dir.resolve("seed"), name);
                ids.add(engine.add(new AddRequest(
                                new AddRequest.TorrentFile(torrent, name + ".torrent"),
                                dir.resolve("downloads"),
                                true,
                                false,
                                false,
                                AddRequest.StopCondition.NONE,
                                Map.of(),
                                TorrentLabels.NONE))
                        .id());
            }
            await(
                    "three in the queue, in add order",
                    () -> positions(engine, ids).equals(List.of(0, 1, 2)));

            engine.queueMove(ids.get(2), Engine.QueueMove.TOP);
            await("q3 to the top", () -> positions(engine, ids).equals(List.of(1, 2, 0)));
            engine.queueMove(ids.get(0), Engine.QueueMove.DOWN);
            await("q1 one down, past q2", () -> positions(engine, ids).equals(List.of(2, 1, 0)));
            engine.queueMove(ids.get(2), Engine.QueueMove.BOTTOM);
            await("q3 to the bottom", () -> positions(engine, ids).equals(List.of(1, 0, 2)));
            engine.queueMove(ids.get(2), Engine.QueueMove.UP);
            await("q3 one up, past q1", () -> positions(engine, ids).equals(List.of(2, 0, 1)));
            engine.queueMove(ids.get(1), Engine.QueueMove.UP);
            await("the first cannot go higher", () -> positions(engine, ids).equals(List.of(2, 0, 1)));
            assertThrows(EngineException.class, () -> engine.queueMove("ff".repeat(20), Engine.QueueMove.TOP));
        }
    }

    /** The torrents' queue positions in the order of {@code ids}. */
    private static List<Integer> positions(LibtorrentEngine engine, List<String> ids) {
        Map<String, Integer> byId = new java.util.HashMap<>();
        for (Torrent t : engine.torrents()) byId.put(t.id(), t.queuePosition());
        return ids.stream().map(id -> byId.getOrDefault(id, -2)).toList();
    }

    /**
     * Set Location on a seed: the files land in the new folder, the torrent seeds on from there
     * without a recheck and the move is in the log; a second move into a folder that already holds
     * the files keeps those and leaves the source where it was.
     */
    @Test
    void setLocationMovesASeedsFilesAndNeverReplacesOnesAlreadyThere(@TempDir Path dir) throws Exception {
        byte[] torrent = twoFileTorrent(dir.resolve("seed"), "pair");
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = seeder(dir, torrent, events::add)) {
            // The add's own check, heard first, so a "checked" after the move would be a recheck.
            await(
                    "the add's check is in the log",
                    () -> events.stream().anyMatch(e -> e.message().equals("pair checked")));
            String id = engine.torrents().get(0).id();
            assertEquals(-1, engine.torrents().get(0).queuePosition(), "a seed is outside the download queue");
            Path moved = dir.resolve("moved");
            int before = events.size();

            engine.moveStorage(id, moved.toString(), true);
            await(
                    "the move is in the log",
                    () -> events.stream().skip(before).anyMatch(e -> e.message().equals("Moved pair to " + moved)));
            Torrent now = engine.torrents().get(0);
            assertEquals(moved.toString(), now.savePath());
            assertEquals(TorrentState.SEEDING, now.state(), "seeding on from the new folder");
            assertEquals(1.0, now.progress(), 1e-9);
            assertFalse(now.moving());
            assertTrue(Files.isRegularFile(moved.resolve("pair").resolve("a.bin")));
            assertTrue(Files.isRegularFile(moved.resolve("pair").resolve("sub").resolve("b.bin")));
            assertFalse(Files.exists(dir.resolve("seed").resolve("pair").resolve("a.bin")), "moved, not copied");
            // A snapshot, then the slice: a sub-list of the live list throws once the alert thread
            // adds to it, which it may do while the message is being built.
            List<EngineEvent> since = List.copyOf(events);
            since = since.subList(before, since.size());
            assertTrue(
                    since.stream().noneMatch(e -> e.message().contains("checked")),
                    "a plain move needs no recheck: " + since);
            assertEquals(moved.toString(), engine.detail(id).general().savePath());

            // The same files already at the next destination: kept as they are, the source left alone.
            Path other = dir.resolve("other");
            Files.createDirectories(other.resolve("pair").resolve("sub"));
            Files.copy(
                    moved.resolve("pair").resolve("a.bin"),
                    other.resolve("pair").resolve("a.bin"));
            Files.copy(
                    moved.resolve("pair").resolve("sub").resolve("b.bin"),
                    other.resolve("pair").resolve("sub").resolve("b.bin"));
            java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
            Files.setLastModifiedTime(other.resolve("pair").resolve("a.bin"), old);
            engine.moveStorage(id, other.toString(), true);
            await("moved again", () -> engine.torrents().get(0).savePath().equals(other.toString()));
            assertEquals(old, Files.getLastModifiedTime(other.resolve("pair").resolve("a.bin")), "not replaced");
            assertTrue(Files.isRegularFile(moved.resolve("pair").resolve("a.bin")), "the source stays");
            await("seeding from the kept files", () -> engine.torrents().get(0).state() == TorrentState.SEEDING);

            // Where it already is: nothing to do, no error.
            engine.moveStorage(id, other.toString(), true);
            assertThrows(EngineException.class, () -> engine.moveStorage(id, "", true));
            assertThrows(EngineException.class, () -> engine.moveStorage("ff".repeat(20), other.toString(), true));
        }
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
