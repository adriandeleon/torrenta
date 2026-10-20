package com.torrenta.ui;

import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowStateTest {

    @Test
    void anErrorOutranksEverything() {
        assertEquals(
                RowState.ERROR,
                RowState.of(T.downloading().error("disk full").paused().build(), 500));
    }

    @Test
    void movingOutranksTheTransferState() {
        assertEquals(RowState.MOVING, RowState.of(T.seeding().moving().build(), 0));
    }

    @Test
    void checkingShowsEvenWhenPaused() {
        assertEquals(
                RowState.CHECKING,
                RowState.of(
                        T.downloading().state(TorrentState.CHECKING).paused().build(), 0));
    }

    @Test
    void aPausedDownloadIsPausedAndAPausedSeedIsDone() {
        assertEquals(
                RowState.PAUSED,
                RowState.of(T.downloading().progress(0.5).paused().build(), 0));
        assertEquals(RowState.DONE, RowState.of(T.seeding().paused().build(), 0));
    }

    @Test
    void theQueueIsNotAPause() {
        assertEquals(RowState.QUEUED, RowState.of(T.downloading().queued(0).build(), 0));
        assertEquals(RowState.QUEUED, RowState.of(T.seeding().queued(3).build(), 0));
    }

    @Test
    void aDownloadStallsAfterAMinuteOfSilence() {
        assertEquals(RowState.DOWNLOADING, RowState.of(T.downloading().build(), RowState.STALL_AFTER_SECONDS - 1));
        assertEquals(RowState.STALLED, RowState.of(T.downloading().build(), RowState.STALL_AFTER_SECONDS));
    }

    @Test
    void metadataAndSeedingMapStraight() {
        assertEquals(
                RowState.METADATA,
                RowState.of(
                        T.downloading().state(TorrentState.FETCHING_METADATA).build(), 900));
        assertEquals(RowState.SEEDING, RowState.of(T.seeding().build(), 0));
        assertEquals(
                RowState.SEEDING,
                RowState.of(T.seeding().state(TorrentState.FINISHED).build(), 0));
    }

    @Test
    void quietMeansMeantToDownloadAndReceivingNothing() {
        assertTrue(RowState.quiet(T.downloading().build()));
        assertFalse(RowState.quiet(T.downloading().down(1).build()), "receiving");
        assertFalse(RowState.quiet(T.downloading().paused().build()), "stopped by a person");
        assertFalse(RowState.quiet(T.downloading().queued(0).build()), "stopped by the queue");
        assertFalse(RowState.quiet(T.seeding().build()), "nothing left to receive");
        assertFalse(RowState.quiet(
                T.downloading().state(TorrentState.FETCHING_METADATA).build()));
    }

    @Test
    void onlyTheRestingStatesAreDimmed() {
        for (RowState s : RowState.values()) {
            boolean expected = s == RowState.PAUSED || s == RowState.QUEUED || s == RowState.DONE;
            assertEquals(expected, s.inactive(), s.name());
        }
    }

    @Test
    void theSortOrderPutsTroubleFirst() {
        assertTrue(RowState.ERROR.compareTo(RowState.DOWNLOADING) < 0);
        assertTrue(RowState.STALLED.compareTo(RowState.DOWNLOADING) < 0);
        assertTrue(RowState.DOWNLOADING.compareTo(RowState.SEEDING) < 0);
        assertTrue(RowState.SEEDING.compareTo(RowState.DONE) < 0);
    }
}
