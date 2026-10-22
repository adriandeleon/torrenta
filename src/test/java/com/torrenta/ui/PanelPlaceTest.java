package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelPlaceTest {

    @Test
    void theSidebarIsReadFromWhetherItShowsAndItsSide() {
        assertEquals(SidebarPlace.LEFT, SidebarPlace.of(true, ""));
        assertEquals(SidebarPlace.LEFT, SidebarPlace.of(true, null));
        assertEquals(SidebarPlace.LEFT, SidebarPlace.of(true, "middle"));
        assertEquals(SidebarPlace.RIGHT, SidebarPlace.of(true, " Right "));
        assertEquals(SidebarPlace.HIDDEN, SidebarPlace.of(false, "right"), "hidden whatever the side");
        assertFalse(SidebarPlace.HIDDEN.shown());
        assertTrue(SidebarPlace.RIGHT.shown());
    }

    @Test
    void theSidebarSwitchesSidesAndHiddenStaysHidden() {
        assertEquals(SidebarPlace.RIGHT, SidebarPlace.LEFT.otherSide());
        assertEquals(SidebarPlace.LEFT, SidebarPlace.RIGHT.otherSide());
        assertEquals(SidebarPlace.HIDDEN, SidebarPlace.HIDDEN.otherSide());
    }

    @Test
    void theDetailPaneIsReadFromWhetherItShowsAndItsSide() {
        assertEquals(DetailPlace.BOTTOM, DetailPlace.of(true, ""));
        assertEquals(DetailPlace.BOTTOM, DetailPlace.of(true, "left"));
        assertEquals(DetailPlace.RIGHT, DetailPlace.of(true, "RIGHT"));
        assertEquals(DetailPlace.HIDDEN, DetailPlace.of(false, "right"));
        assertTrue(DetailPlace.RIGHT.beside());
        assertFalse(DetailPlace.BOTTOM.beside());
        assertEquals(DetailPlace.RIGHT, DetailPlace.BOTTOM.otherSide());
        assertEquals(DetailPlace.BOTTOM, DetailPlace.RIGHT.otherSide());
        assertEquals(DetailPlace.HIDDEN, DetailPlace.HIDDEN.otherSide());
    }

    @Test
    void eachPlaceFallsBackToItsOwnDivider() {
        assertEquals(0.4, DetailPlace.BOTTOM.divider("0.4"), 1e-9);
        assertEquals(0.4, DetailPlace.RIGHT.divider(" 0.400 "), 1e-9);
        assertEquals(0.62, DetailPlace.BOTTOM.divider("0.99"), 1e-9, "the pane would vanish");
        assertEquals(0.58, DetailPlace.RIGHT.divider("0.05"), 1e-9, "the list would vanish");
        assertEquals(0.58, DetailPlace.RIGHT.divider("wide"), 1e-9);
        assertEquals(0.62, DetailPlace.BOTTOM.divider(null), 1e-9);
    }
}
