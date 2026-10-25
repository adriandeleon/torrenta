package com.torrenta.webapi;

import java.util.Map;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static com.torrenta.webapi.FakeBackend.torrent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The *arr apps decide by these words whether a download is done, stuck or failed: every case. */
class QbitStateTest {

    private static String state(Torrent t) {
        return QbitState.of(t).wire();
    }

    @Test
    void everyStateHasQbittorrentsWord() {
        Map<String, Torrent> cases = Map.ofEntries(
                Map.entry("downloading", torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 100, 0)),
                Map.entry("stalledDL", torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 0, 0)),
                Map.entry("metaDL", torrent("a", "a", TorrentState.FETCHING_METADATA, 0, 0, 0)),
                Map.entry("uploading", torrent("a", "a", TorrentState.SEEDING, 1, 0, 100)),
                Map.entry("stalledUP", torrent("a", "a", TorrentState.SEEDING, 1, 0, 0)),
                Map.entry("checkingDL", torrent("a", "a", TorrentState.CHECKING, 0.3, 0, 0)),
                Map.entry("checkingUP", torrent("a", "a", TorrentState.CHECKING, 1, 0, 0)),
                Map.entry("stoppedDL", torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 0, 0, true, false, "")),
                Map.entry("stoppedUP", torrent("a", "a", TorrentState.SEEDING, 1, 0, 0, true, false, "")),
                Map.entry("queuedDL", torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 0, 0, false, true, "")),
                Map.entry("queuedUP", torrent("a", "a", TorrentState.SEEDING, 1, 0, 0, false, true, "")),
                Map.entry("error", torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 0, 0, true, false, "disk full")),
                Map.entry("unknown", torrent("a", "a", TorrentState.UNKNOWN, 0, 0, 0)));
        cases.forEach((word, t) -> assertEquals(word, state(t), word));
    }

    /** A finished torrent that keeps seeding is still finished: libtorrent's FINISHED is a seed with files left out. */
    @Test
    void finishedIsAnUploadState() {
        assertEquals("stalledUP", state(torrent("a", "a", TorrentState.FINISHED, 1, 0, 0)));
    }

    @Test
    void anErrorOutranksBeingPausedAndAMoveOutranksTheRest() {
        assertEquals("error", state(torrent("a", "a", TorrentState.SEEDING, 1, 0, 0, true, true, "missing file")));
        Torrent t = torrent("a", "a", TorrentState.CHECKING, 1, 0, 0, true, false, "");
        Torrent moving = new Torrent(
                t.id(),
                t.name(),
                t.size(),
                t.done(),
                t.progress(),
                t.state(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "/x",
                true,
                false,
                0,
                "",
                java.util.List.of(),
                "",
                java.util.List.of(),
                false,
                false,
                -1,
                true,
                false);
        assertEquals("moving", state(moving));
    }

    @Test
    void theFiltersSelectAsQbittorrentsDo() {
        Torrent down = torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 100, 0);
        Torrent stalled = torrent("b", "b", TorrentState.DOWNLOADING, 0.5, 0, 0);
        Torrent stopped = torrent("c", "c", TorrentState.DOWNLOADING, 0.5, 0, 0, true, false, "");
        Torrent seed = torrent("d", "d", TorrentState.SEEDING, 1, 0, 50);
        Torrent doneStopped = torrent("e", "e", TorrentState.SEEDING, 1, 0, 0, true, false, "");
        Torrent broken = torrent("f", "f", TorrentState.DOWNLOADING, 0.5, 0, 0, false, false, "disk full");

        assertTrue(filter("downloading").matches(stopped), "a stopped download is still a download");
        assertFalse(filter("downloading").matches(seed));
        assertTrue(filter("seeding").matches(seed));
        assertFalse(filter("seeding").matches(doneStopped), "seeding means not stopped");
        assertTrue(filter("completed").matches(doneStopped), "completed does not");
        assertTrue(filter("stopped").matches(stopped) && filter("paused").matches(doneStopped), "4.x's word too");
        assertFalse(filter("running").matches(stopped));
        assertTrue(filter("resumed").matches(down));
        assertTrue(filter("active").matches(seed) && !filter("active").matches(stalled));
        assertTrue(filter("inactive").matches(stalled));
        assertTrue(filter("stalled_downloading").matches(stalled)
                && !filter("stalled_uploading").matches(stalled));
        assertTrue(filter("errored").matches(broken));
        assertTrue(QbitState.filter("nonsense").isEmpty(), "unknown: qBittorrent's all");
    }

    private static QbitState.Filter filter(String name) {
        return QbitState.filter(name).orElseThrow();
    }
}
