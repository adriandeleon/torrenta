package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schedule's rule: when a refresh has anything to do. */
class IpFilterControllerTest {

    private static final long HOUR = 3600_000L;
    private static final long NOW = 1_000 * HOUR;
    private static final String URL = "https://example.org/list.p2p.gz";

    @Test
    void aListNeverFetchedOrFetchedLongAgoIsDue() {
        assertTrue(IpFilterController.due(0, 24, NOW));
        assertTrue(IpFilterController.due(NOW - 24 * HOUR, 24, NOW));
        assertFalse(IpFilterController.due(NOW - 23 * HOUR, 24, NOW));
    }

    @Test
    void aFetchInTheFutureMeansTheClockMovedAndIsDue() {
        assertTrue(IpFilterController.due(NOW + HOUR, 24, NOW));
    }

    @Test
    void nothingIsFetchedWithTheFilterOffOrNoUrl() {
        // Off, or on with only a local file and bans: the start's apply has already said it all.
        assertFalse(IpFilterController.fetchDue(false, URL, 0, 24, NOW));
        assertFalse(IpFilterController.fetchDue(true, "", 0, 24, NOW));
        assertFalse(IpFilterController.fetchDue(true, null, 0, 24, NOW));
        assertTrue(IpFilterController.fetchDue(true, URL, 0, 24, NOW));
        assertFalse(IpFilterController.fetchDue(true, URL, NOW - HOUR, 24, NOW));
    }
}
