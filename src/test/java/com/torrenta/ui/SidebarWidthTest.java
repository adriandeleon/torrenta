package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarWidthTest {

    @Test
    void aWidthStaysBetweenTheBounds() {
        assertEquals(240, SidebarWidth.clamp(240, 1200));
        assertEquals(SidebarWidth.MIN, SidebarWidth.clamp(40, 1200), "too narrow");
        assertEquals(SidebarWidth.MAX, SidebarWidth.clamp(900, 2000), "too wide");
        assertEquals(SidebarWidth.DEFAULT, SidebarWidth.clamp(Double.NaN, 1200), "unreadable");
    }

    @Test
    void theListKeepsItsRoomInANarrowWindow() {
        assertEquals(700 - SidebarWidth.LIST_ROOM, SidebarWidth.clamp(450, 700));
        assertEquals(SidebarWidth.MIN, SidebarWidth.clamp(300, 400), "the minimum wins over the list's room");
    }

    @Test
    void beforeLayoutOnlyTheFixedBoundsApply() {
        assertEquals(450, SidebarWidth.clamp(450, 0));
        assertEquals(SidebarWidth.MAX, SidebarWidth.clamp(600, -1));
    }

    @Test
    void aDragTowardsTheListWidens() {
        assertEquals(260, SidebarWidth.dragged(200, 60, false, 1200), "on the left, dragging right");
        assertEquals(260, SidebarWidth.dragged(200, -60, true, 1200), "on the right, dragging left");
        assertEquals(170, SidebarWidth.dragged(200, 30, true, 1200), "on the right, dragging right");
        assertEquals(SidebarWidth.MIN, SidebarWidth.dragged(200, -500, false, 1200));
    }

    @Test
    void theGripIsTheEdgeThatFacesTheList() {
        assertTrue(SidebarWidth.onGrip(198, 200, false));
        assertTrue(SidebarWidth.onGrip(200, 200, false));
        assertFalse(SidebarWidth.onGrip(3, 200, false), "the far edge is not it");
        assertTrue(SidebarWidth.onGrip(3, 200, true));
        assertFalse(SidebarWidth.onGrip(198, 200, true));
        assertFalse(SidebarWidth.onGrip(100, 200, false), "the middle is the entries'");
    }
}
