package com.torrenta.ui;

import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusLineTest {

    private static final ShareLimit NONE = ShareLimit.NONE;
    private static final ShareLimit RATIO_2 = new ShareLimit(2, 0, ShareLimit.Action.PAUSE);

    private static String line(T t, long quiet, ShareLimit limit) {
        return StatusLine.of(t.build(), quiet, limit).text();
    }

    @Test
    void aDownloadLeadsWithItsProgressAndNamesTheSwarm() {
        StatusLine s = StatusLine.of(
                T.downloading().progress(0.62).down(10).peers(38, 12).build(), 0, NONE);
        assertEquals("62%", s.lead());
        assertEquals(" · 38 peers, 12 seeds", s.rest());
        assertEquals(
                "62% · 1 peer", line(T.downloading().progress(0.62).down(10).peers(1, 0), 0, NONE));
        assertEquals("0% · no peers yet", line(T.downloading().down(1), 0, NONE));
        assertEquals(
                "62% · 2 peers · in order",
                line(T.downloading().progress(0.62).down(1).peers(2, 0).sequential(), 0, NONE));
    }

    @Test
    void aStallSaysForHowLongAndWhetherAnyoneIsThere() {
        assertEquals("Stalled 18 min · 97% · no peers", line(T.downloading().progress(0.97), 18 * 60 + 5, NONE));
        assertEquals(
                "Stalled 2 h 5 min · 50% · 23 peers, none sending",
                line(T.downloading().progress(0.5).peers(23, 0), 2 * 3600 + 5 * 60, NONE));
    }

    @Test
    void seedingNamesWhoIsAskingAndTheRatioGoal() {
        assertEquals("Seeding to 2 peers", line(T.seeding().peers(2, 0), 0, NONE));
        assertEquals("Seeding · no one asking", line(T.seeding(), 0, NONE));
        assertEquals(
                "Seeding to 2 peers · ratio 1.84 of 2",
                line(T.seeding().peers(2, 0).uploaded(1840), 0, RATIO_2));
    }

    @Test
    void doneSaysWhyItStopped() {
        assertEquals("Done · stopped", line(T.seeding().paused(), 0, NONE));
        assertEquals(
                "Done · ratio goal 2 reached, stopped",
                line(T.seeding().paused().uploaded(2000), 0, RATIO_2));
        T timed = T.seeding().paused();
        timed.seedingTime = 3600;
        assertEquals(
                "Done · seeding time goal reached, stopped",
                line(timed, 0, new ShareLimit(0, 60, ShareLimit.Action.PAUSE)));
    }

    @Test
    void theQueueSaysWhereAndWhatItWaitsFor() {
        assertEquals(
                "Queued #1 · starts when a download slot frees",
                line(T.downloading().queued(0), 0, NONE));
        assertEquals("Queued #4 · seeds when a slot frees", line(T.seeding().queued(3), 0, NONE));
        assertEquals(
                "Queued · starts when a download slot frees",
                line(T.downloading().queued(-1), 0, NONE));
    }

    @Test
    void theRestingAndBusyStates() {
        assertEquals("Paused by you · 54%", line(T.downloading().progress(0.54).paused(), 0, NONE));
        assertEquals(
                "Checking files · 41% verified",
                line(T.downloading().state(TorrentState.CHECKING).progress(0.41), 0, NONE));
        assertEquals(
                "Fetching metadata · looking for peers",
                line(T.downloading().state(TorrentState.FETCHING_METADATA), 0, NONE));
        assertEquals(
                "Fetching metadata · 4 peers",
                line(T.downloading().state(TorrentState.FETCHING_METADATA).peers(4, 0), 0, NONE));
        assertEquals("Moving files · 100%", line(T.seeding().moving(), 0, NONE));
        assertEquals(
                "Error · No space left on device", line(T.downloading().error("No space left on device"), 0, NONE));
    }

    @Test
    void sortingGroupsByStateWithTroubleFirst() {
        StatusLine error = StatusLine.of(T.downloading().error("x").build(), 0, NONE);
        StatusLine down = StatusLine.of(T.downloading().down(1).build(), 0, NONE);
        StatusLine seed = StatusLine.of(T.seeding().build(), 0, NONE);
        assertTrue(error.compareTo(down) < 0);
        assertTrue(down.compareTo(seed) < 0);
    }
}
