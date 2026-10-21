package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsTest {

    private static Torrent t(boolean paused, boolean sequential) {
        return new Torrent(
                "id",
                "n",
                1,
                0,
                0,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                paused,
                sequential,
                0,
                "",
                List.of(),
                "",
                java.util.List.of(),
                false);
    }

    @Test
    void spaceResumesOnlyWhenEverythingSelectedIsPaused() {
        assertFalse(Actions.shouldResume(List.of()));
        assertTrue(Actions.shouldResume(List.of(t(true, false), t(true, false))));
        assertFalse(Actions.shouldResume(List.of(t(true, false), t(false, false))), "mixed pauses");
        assertFalse(Actions.shouldResume(List.of(t(false, false))));
    }

    @Test
    void sequentialTurnsOnUnlessAllAlreadyAre() {
        assertTrue(Actions.shouldEnableSequential(List.of(t(false, false))));
        assertTrue(Actions.shouldEnableSequential(List.of(t(false, true), t(false, false))));
        assertFalse(Actions.shouldEnableSequential(List.of(t(false, true), t(false, true))));
    }

    @Test
    void outcomes() {
        assertEquals("Paused 1 torrent", Actions.outcome("Paused", 1, List.of()));
        assertEquals("Paused 3 torrents", Actions.outcome("Paused", 3, List.of()));
        assertEquals(
                "Removed 0 torrents, 2 failed: No such torrent: x",
                Actions.outcome("Removed", 0, List.of("No such torrent: x", "other")));
    }

    @Test
    void removalQuestionsSayWhatHappensToTheFiles() {
        assertEquals("Remove this torrent? The downloaded files stay on disk.", Actions.removeQuestion(1, false));
        assertEquals(
                "Remove these 2 torrents and delete the downloaded files? This cannot be undone.",
                Actions.removeQuestion(2, true));
    }

    @Test
    void escapePeelsOneLayerAtATime() {
        assertEquals(Actions.Escape.CLEAR_FILTER, Actions.escape(true, true));
        assertEquals(Actions.Escape.CLEAR_FILTER, Actions.escape(true, false));
        assertEquals(Actions.Escape.CLEAR_SELECTION, Actions.escape(false, true));
        assertEquals(Actions.Escape.NOTHING, Actions.escape(false, false));
    }

    @Test
    void chooseFilesIsForOneMarkedTorrent() {
        java.util.Set<String> marked = java.util.Set.of("a");
        assertTrue(Actions.canChooseFiles(List.of("a"), marked::contains));
        assertFalse(Actions.canChooseFiles(List.of("b"), marked::contains));
        assertFalse(Actions.canChooseFiles(List.of("a", "b"), marked::contains));
        assertFalse(Actions.canChooseFiles(List.of(), marked::contains));
    }

    /** The prompt once split on a literal backslash-s: two URLs on two lines came out as one. */
    @Test
    void trackerUrlsSplitOnAnyWhitespace() {
        assertEquals(
                List.of("udp://a.example:6969/announce", "http://b.example/announce", "udp://c.example:1337"),
                Actions.trackerUrls(
                        "  udp://a.example:6969/announce\nhttp://b.example/announce \t udp://c.example:1337\n"));
        assertEquals(List.of("udp://a.example"), Actions.trackerUrls("udp://a.example udp://a.example"));
        assertEquals(List.of("http://x\\s"), Actions.trackerUrls("http://x\\s"));
        assertEquals(List.of(), Actions.trackerUrls("   \n "));
        assertEquals(List.of(), Actions.trackerUrls(null));
    }
}
