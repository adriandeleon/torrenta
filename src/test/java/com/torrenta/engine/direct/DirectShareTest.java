package com.torrenta.engine.direct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectShareTest {

    @Test
    void theGlobalLimitIsSharedEquallyAndCappedByADownloadsOwn() {
        assertEquals(0, DirectDownloads.share(0, 0, 3), "no limit at all");
        assertEquals(100_000, DirectDownloads.share(0, 300_000, 3));
        assertEquals(50_000, DirectDownloads.share(50_000, 300_000, 3), "its own is lower");
        assertEquals(100_000, DirectDownloads.share(500_000, 300_000, 3), "its share is lower");
        assertEquals(70_000, DirectDownloads.share(70_000, 0, 3), "its own alone");
        assertEquals(300_000, DirectDownloads.share(0, 300_000, 0), "nobody else: all of it");
        assertEquals(DirectDownloads.MIN_SHARE, DirectDownloads.share(0, 10_000, 100), "never a crawl");
    }

    @Test
    void aRunIsRestartedOnlyWhenItsShareMovesEnough() {
        assertFalse(DirectDownloads.reshare(100_000, 100_000));
        assertFalse(DirectDownloads.reshare(100_000, 95_000), "a twentieth is let be");
        assertTrue(DirectDownloads.reshare(100_000, 50_000));
        assertTrue(DirectDownloads.reshare(0, 100_000), "a limit where there was none");
        assertTrue(DirectDownloads.reshare(100_000, 0), "the limit lifted");
        assertFalse(DirectDownloads.reshare(0, 0));
    }
}
