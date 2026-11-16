package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayStartTest {

    @Test
    void aHiddenStartAsksAgainWhereATrayCanStillComeUpToTheLimit() {
        assertTrue(TrayStart.tryAgain(true, true, 1));
        assertTrue(TrayStart.tryAgain(true, true, TrayStart.RETRIES));
        assertFalse(TrayStart.tryAgain(true, true, TrayStart.RETRIES + 1), "then gives up");
    }

    @Test
    void nothingIsAskedAgainForAShownWindowOrATrayThatWillNotCome() {
        assertFalse(TrayStart.tryAgain(false, true, 1), "the window is up: the person can use it");
        assertFalse(TrayStart.tryAgain(true, false, 1), "AWT's tray is there at once or never");
    }

    @Test
    void theRetriesCoverTheDesktopsStartWithoutWaitingLong() {
        long seconds = TrayStart.RETRY_EVERY.multipliedBy(TrayStart.RETRIES).toSeconds();
        assertTrue(seconds >= 15 && seconds <= 30, seconds + " s");
    }
}
