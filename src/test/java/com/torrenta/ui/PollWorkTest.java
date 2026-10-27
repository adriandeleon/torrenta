package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollWorkTest {

    @Test
    void aWindowOnScreenDrawsEverythingThatShows() {
        assertEquals(new PollWork(true, true, true), PollWork.of(true, true, true));
        assertEquals(new PollWork(true, false, false), PollWork.of(true, false, false));
    }

    /** Hidden to the tray or minimised: no detail fetch and nothing for the FX thread. */
    @Test
    void aWindowOffScreenDrawsNothingAndFetchesNoDetail() {
        PollWork work = PollWork.of(false, true, false);
        assertFalse(work.window());
        assertFalse(work.detail());
    }

    /** The Statistics window is its own stage: up while the main one is minimised, it still moves. */
    @Test
    void theStatisticsWindowFollowsItsOwnState() {
        PollWork work = PollWork.of(false, true, true);
        assertTrue(work.stats());
        assertFalse(work.window());
        assertFalse(work.detail());
        assertFalse(PollWork.of(true, true, false).stats());
    }

    @Test
    void hiddenAndMinimisedAreBothOffScreen() {
        assertTrue(PollWork.onScreen(true, false));
        assertFalse(PollWork.onScreen(false, false), "hidden to the tray");
        assertFalse(PollWork.onScreen(true, true), "minimised");
        assertFalse(PollWork.onScreen(false, true));
    }

    @Test
    void onlyComingBackOntoTheScreenCatchesUp() {
        assertTrue(PollWork.catchUp(false, true));
        assertFalse(PollWork.catchUp(true, true));
        assertFalse(PollWork.catchUp(true, false));
        assertFalse(PollWork.catchUp(false, false));
    }
}
