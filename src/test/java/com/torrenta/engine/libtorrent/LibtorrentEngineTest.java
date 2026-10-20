package com.torrenta.engine.libtorrent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
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

        try (LibtorrentEngine again = engine(dir, store)) {
            again.start();
            List<Torrent> back = again.torrents();
            assertEquals(1, back.size());
            assertEquals(MAGNET_ID, back.get(0).id());
            assertEquals("Torrenta test", back.get(0).name(), "the magnet's name survives too");
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
                new TorrentBuilder().path(payload.toFile()).generate().entry().bencode());

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

    /** Alerts arrive on their own thread; wait, bounded, rather than sleep and hope. */
    private static void await(String what, BooleanSupplier condition) throws IOException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
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
