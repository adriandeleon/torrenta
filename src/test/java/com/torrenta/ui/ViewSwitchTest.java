package com.torrenta.ui;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewSwitchTest {
    @Test
    void nextWalksTheStripAndWrapsToTheFirst() {
        assertEquals(View.SEARCH, ViewSwitch.next(View.TRANSFERS));
        assertEquals(View.RSS, ViewSwitch.next(View.SEARCH));
        assertEquals(View.TRANSFERS, ViewSwitch.next(View.RSS));
    }

    @Test
    void previousWalksBackAndWrapsToTheLast() {
        assertEquals(View.RSS, ViewSwitch.previous(View.TRANSFERS));
        assertEquals(View.TRANSFERS, ViewSwitch.previous(View.SEARCH));
        assertEquals(View.SEARCH, ViewSwitch.previous(View.RSS));
    }

    @Test
    void numeralsAreOneBasedInStripOrder() {
        assertEquals(Optional.of(View.TRANSFERS), ViewSwitch.numbered(1));
        assertEquals(Optional.of(View.SEARCH), ViewSwitch.numbered(2));
        assertEquals(Optional.of(View.RSS), ViewSwitch.numbered(3));
        assertEquals(Optional.empty(), ViewSwitch.numbered(0));
        assertEquals(Optional.empty(), ViewSwitch.numbered(4));
        assertEquals(1, View.TRANSFERS.number());
        assertEquals(3, View.RSS.number());
    }

    @Test
    void parsesAnIdLoosely() {
        assertEquals(Optional.of(View.SEARCH), ViewSwitch.parse("search"));
        assertEquals(Optional.of(View.RSS), ViewSwitch.parse(" RSS "));
        assertEquals(Optional.empty(), ViewSwitch.parse("feeds"));
        assertEquals(Optional.empty(), ViewSwitch.parse(null));
    }
}
