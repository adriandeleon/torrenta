package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DetailSplitTest {

    @Test
    void aSavedPositionInRangeIsRestored() {
        assertEquals(0.5, DetailSplit.position("0.5"));
        assertEquals(0.621, DetailSplit.position(" 0.621 "));
    }

    /** 0.935 is what the empty state's push saved: the pane's tab strip and nothing under it. */
    @Test
    void aCollapsedOrNonsensePositionFallsBackToTheDefault() {
        assertEquals(DetailSplit.DEFAULT, DetailSplit.position("0.935"));
        assertEquals(DetailSplit.DEFAULT, DetailSplit.position("0.9"));
        assertEquals(DetailSplit.DEFAULT, DetailSplit.position("0.05"));
        assertEquals(DetailSplit.DEFAULT, DetailSplit.position("half"));
        assertEquals(DetailSplit.DEFAULT, DetailSplit.position(""));
        assertEquals(DetailSplit.DEFAULT, DetailSplit.position(null));
    }
}
