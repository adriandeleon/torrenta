package com.torrenta.search;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateTextTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void absoluteShapes() {
        assertEquals(Instant.parse("2022-10-03T12:35:22Z"), DateText.parse("2022-10-03T12:35:22Z", NOW), "ISO");
        assertEquals(
                Instant.parse("2022-10-03T10:35:22Z"),
                DateText.parse("2022-10-03T12:35:22+02:00", NOW),
                "ISO with offset");
        assertEquals(
                Instant.parse("2022-10-03T12:35:22Z"), DateText.parse("2022-10-03T12:35:22", NOW), "ISO local is UTC");
        assertEquals(
                Instant.parse("2026-10-18T09:12:00Z"),
                DateText.parse("Sun, 18 Oct 2026 09:12:00 +0000", NOW),
                "RFC 1123");
        assertEquals(
                Instant.parse("2026-09-13T00:00:00Z"), DateText.parse("2026-09-13", NOW), "a date is midnight UTC");
        assertEquals(Instant.parse("2026-09-13T08:30:00Z"), DateText.parse("2026-09-13 08:30", NOW));
        assertEquals(Instant.parse("2026-09-13T08:30:15Z"), DateText.parse("2026-09-13 08:30:15", NOW));
        assertEquals(Instant.parse("2026-09-13T00:00:00Z"), DateText.parse("2026/09/13", NOW));
        assertEquals(Instant.parse("2026-09-13T00:00:00Z"), DateText.parse("Sep 13, 2026", NOW), "LinuxTracker's");
        assertEquals(Instant.parse("2026-09-03T00:00:00Z"), DateText.parse("3 September 2026", NOW));
        assertEquals(Instant.parse("2026-09-03T00:00:00Z"), DateText.parse("3 Sep 2026", NOW));
    }

    @Test
    void epochSecondsAndMilliseconds() {
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), DateText.parse("1700000000", NOW));
        assertEquals(Instant.ofEpochMilli(1_700_000_000_123L), DateText.parse("1700000000123", NOW));
    }

    @Test
    void relativeShapesAreAnchoredOnTheClockGiven() {
        assertEquals(NOW.minusSeconds(2 * 86_400), DateText.parse("2 days ago", NOW));
        assertEquals(NOW.minusSeconds(3_600), DateText.parse("an hour ago", NOW));
        assertEquals(NOW.minusSeconds(5 * 60), DateText.parse("5 minutes ago", NOW));
        assertEquals(NOW.minusSeconds(5 * 60), DateText.parse("5 mins ago", NOW));
        assertEquals(NOW.minusSeconds(14 * 86_400), DateText.parse("2 weeks ago", NOW));
        assertEquals(Instant.parse("2026-07-20T12:00:00Z"), DateText.parse("3 months ago", NOW));
        assertEquals(Instant.parse("2024-10-20T12:00:00Z"), DateText.parse("2 years ago", NOW));
        assertEquals(NOW.minusSeconds(86_400), DateText.parse("yesterday", NOW));
        assertEquals(NOW, DateText.parse("today", NOW));
        assertEquals(NOW, DateText.parse("just now", NOW));
    }

    @Test
    void aPatternOfTheDefinitionsOwn() {
        assertEquals(Instant.parse("2026-09-13T00:00:00Z"), DateText.parse("13.09.2026", "dd.MM.uuuu", NOW));
        assertEquals(
                Instant.parse("2026-09-13T14:05:00Z"), DateText.parse("13/09/2026 14:05", "dd/MM/uuuu HH:mm", NOW));
        assertNull(DateText.parse("2026-09-13", "dd.MM.uuuu", NOW), "the pattern given is the one tried");
        assertNull(DateText.parse("13.09.2026", "not a [pattern", NOW), "a bad pattern is no date, not a throw");
        assertEquals(
                Instant.parse("2026-09-13T00:00:00Z"),
                DateText.parse("2026-09-13", "", NOW),
                "empty means the usual shapes");
    }

    @Test
    void anythingElseIsNull() {
        assertNull(DateText.parse(null, NOW));
        assertNull(DateText.parse("", NOW));
        assertNull(DateText.parse("—", NOW));
        assertNull(DateText.parse("soon", NOW));
        assertNull(DateText.parse("13/09/2026", NOW), "day-first or month-first is anyone's guess; a format says");
        assertNull(DateText.parse("2 fortnights ago", NOW));
    }
}
