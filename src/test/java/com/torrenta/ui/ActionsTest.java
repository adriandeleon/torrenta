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
    void aTypedCommandActsOnEveryMatchWhileTheBoxFiltersAndOnTheSelectionOtherwise() {
        List<Torrent> selection = List.of(t(true, false));
        List<Torrent> matches = List.of(t(false, false), t(false, true), t(true, true));
        assertEquals(matches, Actions.targets(selection, matches, true));
        assertEquals(selection, Actions.targets(selection, matches, false));
        assertEquals(List.of(), Actions.targets(List.of(), matches, false), "no filter, no selection: nothing");
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
    void superSeedingIsForTheCompleteOnesAndTurnsOnUnlessAllHaveIt() {
        Torrent seed = T.seeding().build();
        Torrent superSeed = T.seeding().superSeeding().build();
        Torrent downloading = T.downloading().build();
        assertEquals(List.of(seed), Actions.superSeedTargets(List.of(seed, downloading)));
        assertTrue(Actions.shouldEnableSuperSeeding(List.of(seed, superSeed)));
        assertFalse(Actions.shouldEnableSuperSeeding(List.of(superSeed)));
    }

    @Test
    void anExportIsNamedAfterTheTorrentSafelyAndNeverOverAnotherFile() {
        assertEquals("Big Buck Bunny.torrent", Actions.exportFileName("Big Buck Bunny"));
        assertEquals("a_b_c_.torrent", Actions.exportFileName("a/b:c?"));
        assertEquals("hidden.torrent", Actions.exportFileName("..hidden. "));
        assertEquals("torrent.torrent", Actions.exportFileName("..."));
        assertEquals("a.torrent", Actions.unusedName("a.torrent", java.util.Set.of("b.torrent")));
        assertEquals("a (3).torrent", Actions.unusedName("a.torrent", java.util.Set.of("a.torrent", "a (2).torrent")));
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
        assertEquals(
                "Remove this torrent and move the downloaded files to the trash?",
                Actions.removeQuestion(1, true, true));
        assertEquals(
                "Remove this torrent? The downloaded files stay on disk.",
                Actions.removeQuestion(1, false, true),
                "no files, no trash");
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

    /**
     * Ban Peer bans the host, never the port or the scheme: libtorrent's Peers tab shows
     * {@code tcp://host:port}, and the scheme once went into the bans, where the filter skipped it.
     */
    @Test
    void aPeerIsBannedByItsHostAlone() {
        assertEquals("127.0.0.3", Actions.peerHost("tcp://127.0.0.3:42549"));
        assertEquals("::1", Actions.peerHost("utp://[::1]:6881"));
        assertEquals("10.0.0.2", Actions.peerHost("10.0.0.2:6881"));
        assertEquals("::1", Actions.peerHost("[::1]:6881"));
        assertEquals("2001:db8::7", Actions.peerHost("[2001:db8::7]:51413"));
        assertEquals("2001:db8::7", Actions.peerHost("2001:db8::7"));
        assertEquals("10.0.0.2", Actions.peerHost("10.0.0.2"));
        assertEquals("::1", Actions.peerHost("[::1"));
    }
}
