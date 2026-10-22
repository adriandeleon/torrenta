package com.torrenta.ui;

import java.time.Instant;
import java.util.List;

import com.torrenta.engine.EngineEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatsTest {

    private record Said(Repeats.Key key, Instant last) {}

    private static Said said(String torrent, String text, long second) {
        return new Said(new Repeats.Key(EngineEvent.Level.WARNING, torrent, text), Instant.ofEpochSecond(second));
    }

    private static int find(List<Said> lines, Repeats.Key key, long second) {
        return Repeats.find(lines, Said::key, Said::last, key, Instant.ofEpochSecond(second));
    }

    private static final Repeats.Key TRACKER =
            new Repeats.Key(EngineEvent.Level.WARNING, "abc", "tracker udp://tracker.invalid — Host not found");

    @Test
    void theSameWordsForTheSameTorrentAtTheSameLevelAreARepeat() {
        List<Said> lines = List.of(said("abc", TRACKER.text(), 100));
        assertEquals(0, find(lines, TRACKER, 100));
        assertEquals(0, find(lines, TRACKER, 100 + Repeats.WINDOW.toSeconds()));
    }

    @Test
    void anotherTorrentLevelOrTextIsANewLine() {
        List<Said> lines = List.of(said("abc", TRACKER.text(), 100));
        assertEquals(-1, find(lines, new Repeats.Key(EngineEvent.Level.WARNING, "def", TRACKER.text()), 100));
        assertEquals(-1, find(lines, new Repeats.Key(EngineEvent.Level.ERROR, "abc", TRACKER.text()), 100));
        assertEquals(-1, find(lines, new Repeats.Key(EngineEvent.Level.WARNING, "abc", "something else"), 100));
        // The session's own lines have no torrent; they repeat each other, not a torrent's.
        assertEquals(-1, find(lines, new Repeats.Key(EngineEvent.Level.WARNING, null, TRACKER.text()), 100));
    }

    @Test
    void theSameWordsLongAfterTheLastTimeAreANewLine() {
        List<Said> lines = List.of(said("abc", TRACKER.text(), 100));
        assertEquals(-1, find(lines, TRACKER, 101 + Repeats.WINDOW.toSeconds()));
    }

    @Test
    void theWindowRunsFromTheLastTimeSoABurstStaysOneLine() {
        assertTrue(Repeats.within(Instant.ofEpochSecond(50), Instant.ofEpochSecond(50)));
        assertTrue(Repeats.within(Instant.ofEpochSecond(50), Instant.ofEpochSecond(60)));
        assertFalse(Repeats.within(Instant.ofEpochSecond(50), Instant.ofEpochSecond(61)));
        // A clock that stepped back is still the same burst.
        assertTrue(Repeats.within(Instant.ofEpochSecond(50), Instant.ofEpochSecond(40)));
    }

    @Test
    void aRepeatIsFoundBehindOtherLinesButNotBeyondTheLookBack() {
        List<Said> lines = new java.util.ArrayList<>();
        lines.add(said("abc", TRACKER.text(), 100));
        lines.add(said("def", "other", 100));
        assertEquals(0, find(lines, TRACKER, 101));
        for (int i = 0; i < Repeats.LOOK_BACK; i++) lines.add(said("def", "filler " + i, 100));
        assertEquals(-1, find(lines, TRACKER, 101));
    }

    @Test
    void theNewestOfTwoCopiesIsTheOneCounted() {
        List<Said> lines = List.of(said("abc", TRACKER.text(), 90), said("abc", TRACKER.text(), 99));
        assertEquals(1, find(lines, TRACKER, 100));
    }

    @Test
    void anEmptyLogHasNothingToRepeat() {
        assertEquals(-1, Repeats.find(List.<Said>of(), Said::key, Said::last, TRACKER, Instant.EPOCH));
    }

    @Test
    void theCountIsShownOnlyOnceThereIsMoreThanOne() {
        assertEquals("IP filter cleared", Repeats.text("IP filter cleared", 1));
        assertEquals("IP filter cleared ×2", Repeats.text("IP filter cleared", 2));
        assertEquals("x ×1200", Repeats.text("x", 1200));
    }
}
