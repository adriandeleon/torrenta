package com.torrenta.engine;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The share-limit rule: when a seed is done, and for which torrents the question even arises. */
class ShareLimitTest {

    private static Torrent torrent(
            TorrentState state, boolean paused, long size, long downloaded, long uploaded, long seedingSeconds) {
        return new Torrent(
                "id",
                "n",
                size,
                size,
                1.0,
                state,
                0,
                0,
                0,
                0,
                uploaded,
                downloaded,
                0,
                0,
                "",
                "",
                paused,
                false,
                seedingSeconds,
                "",
                List.of());
    }

    @Test
    void ratioIsMeasuredAgainstWhatWasDownloadedOrTheSizeWhenNothingWas() {
        ShareLimit two = new ShareLimit(2.0, 0, ShareLimit.Action.PAUSE);
        assertFalse(two.reached(199, 100, 100, 0));
        assertTrue(two.reached(200, 100, 100, 0), "exactly the ratio counts");
        assertTrue(two.reached(2000, 0, 1000, 0), "added complete: the size stands in for the download");
        assertFalse(two.reached(2000, 0, 0, 0), "nothing to measure against");
    }

    @Test
    void timeIsSeedingSecondsAgainstMinutes() {
        ShareLimit hour = new ShareLimit(0, 60, ShareLimit.Action.REMOVE);
        assertFalse(hour.reached(0, 0, 0, 3599));
        assertTrue(hour.reached(0, 0, 0, 3600));
        assertTrue(new ShareLimit(5, 60, ShareLimit.Action.PAUSE).reached(0, 100, 100, 3600), "either limit is enough");
    }

    @Test
    void zeroIsNoLimit() {
        assertFalse(ShareLimit.NONE.limited());
        assertFalse(ShareLimit.NONE.reached(Long.MAX_VALUE, 1, 1, Long.MAX_VALUE));
        assertEquals(ShareLimit.NONE, new ShareLimit(-1, -5, null), "negatives and null are clamped to none");
    }

    @Test
    void decideAsksOnlyFinishedTorrentsThatAreNotPausedByHand() {
        ShareLimit global = new ShareLimit(1.0, 0, ShareLimit.Action.PAUSE);
        assertEquals(
                Optional.of(ShareLimit.Action.PAUSE),
                ShareLimit.decide(torrent(TorrentState.SEEDING, false, 100, 100, 100, 0), global, null));
        assertEquals(
                Optional.of(ShareLimit.Action.PAUSE),
                ShareLimit.decide(torrent(TorrentState.FINISHED, false, 100, 100, 100, 0), global, null),
                "finished with files left out counts too");
        assertEquals(
                Optional.empty(),
                ShareLimit.decide(torrent(TorrentState.DOWNLOADING, false, 100, 100, 100, 0), global, null),
                "still downloading");
        assertEquals(
                Optional.empty(),
                ShareLimit.decide(torrent(TorrentState.SEEDING, true, 100, 100, 100, 0), global, null),
                "already paused: done once, not every second");
        assertEquals(
                Optional.empty(),
                ShareLimit.decide(torrent(TorrentState.SEEDING, false, 100, 100, 50, 0), global, null),
                "not there yet");
        assertEquals(
                Optional.empty(),
                ShareLimit.decide(torrent(TorrentState.SEEDING, false, 100, 100, 100, 0), ShareLimit.NONE, null));
    }

    @Test
    void aTorrentsOwnLimitReplacesTheGlobalOne() {
        ShareLimit global = new ShareLimit(1.0, 0, ShareLimit.Action.PAUSE);
        ShareLimit own = new ShareLimit(3.0, 0, ShareLimit.Action.REMOVE);
        Torrent atOne = torrent(TorrentState.SEEDING, false, 100, 100, 100, 0);
        assertEquals(Optional.empty(), ShareLimit.decide(atOne, global, own), "the override is higher");
        assertEquals(
                Optional.of(ShareLimit.Action.REMOVE),
                ShareLimit.decide(torrent(TorrentState.SEEDING, false, 100, 100, 300, 0), global, own));
        assertEquals(
                Optional.empty(),
                ShareLimit.decide(atOne, global, ShareLimit.NONE),
                "an override of none means seed forever, whatever the global limit says");
    }

    @Test
    void formatAndParseRoundTrip() {
        ShareLimit limit = new ShareLimit(1.5, 90, ShareLimit.Action.REMOVE);
        assertEquals("1.5,90,REMOVE", limit.format());
        assertEquals(Optional.of(limit), ShareLimit.parse(limit.format()));
        assertEquals(Optional.of(new ShareLimit(2, 0, ShareLimit.Action.PAUSE)), ShareLimit.parse(" 2 , 0 , pause "));
        assertEquals(Optional.empty(), ShareLimit.parse("2,0"));
        assertEquals(Optional.empty(), ShareLimit.parse("two,0,PAUSE"));
        assertEquals(Optional.empty(), ShareLimit.parse("-1,0,PAUSE"));
        assertEquals(Optional.empty(), ShareLimit.parse("1,0,EXPLODE"));
        assertEquals(Optional.empty(), ShareLimit.parse(null));
    }

    @Test
    void ratiosReadAsPeopleWriteThem() {
        assertEquals("2", ShareLimit.formatRatio(2.0));
        assertEquals("1.5", ShareLimit.formatRatio(1.5));
        assertEquals("1.25", ShareLimit.formatRatio(1.25));
        assertEquals("0.33", ShareLimit.formatRatio(1.0 / 3));
        assertEquals("0", ShareLimit.formatRatio(0));
        assertEquals("none", ShareLimit.NONE.toString());
        assertEquals("ratio 2 or 60 min → remove", new ShareLimit(2, 60, ShareLimit.Action.REMOVE).toString());
    }
}
