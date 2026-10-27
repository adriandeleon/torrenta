package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogFitTest {

    /** A 420 px scene in a 450 px window whose content needs 460: the window grows to 490. */
    @Test
    void aWindowShorterThanItsContentGrowsByTheShortfall() {
        assertEquals(490, DialogFit.grownHeight(450, 420, 460, 1000));
    }

    @Test
    void aWindowTallEnoughIsLeftAsItIs() {
        assertEquals(0, DialogFit.grownHeight(450, 420, 420, 1000));
        assertEquals(0, DialogFit.grownHeight(450, 420, 300, 1000), "never shrunk");
        assertEquals(0, DialogFit.grownHeight(450, 420, 420.4, 1000), "half a pixel is rounding");
    }

    @Test
    void neverPastTheScreen() {
        assertEquals(800, DialogFit.grownHeight(450, 420, 2000, 800));
        assertEquals(0, DialogFit.grownHeight(800, 770, 2000, 800), "already the screen's height");
    }

    @Test
    void aWindowWithoutDecorationsHasNoChrome() {
        assertEquals(460, DialogFit.grownHeight(420, 420, 460, 1000));
    }
}
