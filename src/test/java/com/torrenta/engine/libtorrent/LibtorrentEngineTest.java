package com.torrenta.engine.libtorrent;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libtorrent4j.TorrentStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void startsAddsAMagnetAndStops(@TempDir Path downloads) throws EngineException {
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads)) {
            engine.start();
            assertTrue(engine.description().startsWith("libtorrent "), engine.description());

            Torrent added = engine.add(MAGNET);
            assertEquals("0123456789abcdef0123456789abcdef01234567", added.id());
            assertEquals("Torrenta test", added.name());
            assertNotEquals(TorrentState.UNKNOWN, added.state());

            List<Torrent> all = engine.torrents();
            assertEquals(1, all.size());
            assertEquals(added.id(), all.get(0).id());
        }
    }

    @Test
    void badInputsAreErrorsNotCrashes(@TempDir Path downloads) throws EngineException {
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads)) {
            engine.start();
            assertThrows(EngineException.class, () -> engine.add("magnet:?xt=urn:btih:nope"));
            assertThrows(
                    EngineException.class,
                    () -> engine.add(downloads.resolve("missing.torrent").toString()));
        }
    }

    @Test
    void notStartedIsAnErrorNotANullPointer(@TempDir Path downloads) {
        LibtorrentEngine engine = new LibtorrentEngine(downloads);
        assertThrows(EngineException.class, () -> engine.add(MAGNET));
        assertTrue(engine.torrents().isEmpty());
    }

    @Test
    void everyLibtorrentStateMapsSomewhere() {
        for (TorrentStatus.State state : TorrentStatus.State.values()) {
            assertTrue(LibtorrentEngine.state(state) != null, state.name());
        }
        assertEquals(TorrentState.FETCHING_METADATA, LibtorrentEngine.state(TorrentStatus.State.DOWNLOADING_METADATA));
        assertEquals(TorrentState.CHECKING, LibtorrentEngine.state(TorrentStatus.State.CHECKING_RESUME_DATA));
    }
}
