package com.torrenta.ui;

import java.time.Instant;
import java.util.List;

import com.torrenta.rss.Article;
import com.torrenta.rss.RssService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssTextTest {

    private static Article article(String title, String description, boolean read) {
        return new Article("f", "g-" + title, title, "https://x/" + title, "", Instant.EPOCH, description, read);
    }

    @Test
    void theFilterMatchesTitleOrDescriptionIgnoringCaseAndUnreadOnlyHidesTheRead() {
        Article a = article("Debian 12.7 netinst", "The small image", false);
        assertTrue(RssText.matches(a, "", false));
        assertTrue(RssText.matches(a, "  ", true));
        assertTrue(RssText.matches(a, "DEBIAN", false));
        assertTrue(RssText.matches(a, "small image", false));
        assertFalse(RssText.matches(a, "ubuntu", false));
        assertFalse(RssText.matches(a.withRead(true), "", true));
        assertTrue(RssText.matches(a.withRead(true), "", false));
    }

    @Test
    void theStatusLineCountsAndNamesFailures() {
        assertEquals("No feeds yet: N adds one", RssText.statusLine(0, 0, 0, List.of()));
        assertEquals("1 feed · nothing unread", RssText.statusLine(1, 0, 0, List.of()));
        assertEquals("3 feeds · 1 unread · refreshing one", RssText.statusLine(3, 1, 1, List.of()));
        assertEquals(
                "3 feeds · 12 unread · refreshing 2 · Tracker: the server answered 404",
                RssText.statusLine(3, 12, 2, List.of("Tracker: the server answered 404")));
        assertEquals("2 feeds · 12 unread · 2 feeds failed", RssText.statusLine(2, 12, 0, List.of("a: x", "b: y")));
    }

    @Test
    void aFeedsStatusAndTheAgoWords() {
        long now = 1_760_000_000_000L;
        assertEquals("refreshing…", RssText.feedStatus(new RssService.Status(0, "", true, 0), now));
        assertEquals("never refreshed", RssText.feedStatus(new RssService.Status(0, "", false, 0), now));
        assertEquals(
                "last error: the server answered 404",
                RssText.feedStatus(new RssService.Status(now, "the server answered 404", false, 0), now));
        assertEquals("refreshed just now", RssText.feedStatus(new RssService.Status(now - 30_000, "", false, 0), now));
        assertEquals("5 min ago", RssText.ago(now - 5 * 60_000, now));
        assertEquals("2 h ago", RssText.ago(now - 2 * 3_600_000, now));
        assertEquals("1 day ago", RssText.ago(now - 24 * 3_600_000, now));
        assertEquals("3 days ago", RssText.ago(now - 3 * 24 * 3_600_000, now));
        assertEquals("just now", RssText.ago(now + 5000, now), "a clock that went back is not the future");
    }

    @Test
    void theFeedLineAndTheMetaLine() {
        assertEquals("Tracker (3)", RssText.feedLine("Tracker", 3, false, false));
        assertEquals("!~Tracker (0)", RssText.feedLine("Tracker", 0, true, true));
        Article magnet =
                new Article("f", "g", "T", "https://x/t", "magnet:?xt=urn:btih:" + "0".repeat(40), null, "", false);
        assertEquals("Tracker · 18 Oct 2026 · magnet link", RssText.meta("Tracker", "18 Oct 2026", magnet));
        assertEquals("Tracker · no torrent", RssText.meta("Tracker", "—", article("T", "", false)));
        Article file = new Article("f", "g", "T", "", "https://x/t.torrent", null, "", false);
        assertEquals("Tracker · .torrent link", RssText.meta("Tracker", "", file));
    }
}
