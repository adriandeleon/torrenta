package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRepeatsTest {

    private static final String LOOKUP =
            "WARNING css — Could not resolve '-color-cell-fg' while resolving lookups for '-fx-text-fill'";

    @Test
    void theFirstCopiesAreWrittenThenEachPowerOfTenIsTallied() {
        LogRepeats repeats = new LogRepeats();
        List<Long> tallies = new ArrayList<>();
        int written = 0;
        for (int i = 0; i < 28_000; i++) {
            switch (repeats.seen(LOOKUP)) {
                case LogRepeats.Write w -> written++;
                case LogRepeats.Tally t -> tallies.add(t.count());
                case LogRepeats.Drop d -> {}
            }
        }
        assertEquals(LogRepeats.KEPT, written);
        assertEquals(List.of(10L, 100L, 1000L, 10_000L), tallies, "28,000 copies, seven lines");
    }

    @Test
    void anotherLineIsWrittenInTheMiddleOfAStorm() {
        LogRepeats repeats = new LogRepeats();
        for (int i = 0; i < 500; i++) repeats.seen(LOOKUP);
        assertInstanceOf(LogRepeats.Write.class, repeats.seen("WARNING DBusConnection — something else"));
    }

    @Test
    void clearingMakesARepeatNewsAgain() {
        LogRepeats repeats = new LogRepeats();
        for (int i = 0; i < 50; i++) repeats.seen(LOOKUP);
        repeats.clear();
        assertInstanceOf(LogRepeats.Write.class, repeats.seen(LOOKUP));
    }

    @Test
    void manyDifferentLinesCostABoundedMemory() {
        LogRepeats repeats = new LogRepeats();
        for (int i = 0; i < LogRepeats.REMEMBERED * 3; i++)
            assertInstanceOf(LogRepeats.Write.class, repeats.seen("line " + i), "every one is new");
    }

    @Test
    void powersOfTen() {
        assertTrue(LogRepeats.isPowerOfTen(10));
        assertTrue(LogRepeats.isPowerOfTen(1_000_000));
        assertFalse(LogRepeats.isPowerOfTen(1));
        assertFalse(LogRepeats.isPowerOfTen(20));
        assertFalse(LogRepeats.isPowerOfTen(110));
    }

    @Test
    void aTallySaysHowManyAndWhat() {
        String line = LogRepeats.tallyText(1000, LOOKUP);
        assertTrue(line.startsWith("(1000 times so far"), line);
        assertTrue(line.contains("-color-cell-fg"), line);
        assertTrue(LogRepeats.tallyText(10, "x".repeat(400)).length() < 220, "a long line is cut");
    }

    @Test
    void theDebugLogKeepsTheLinesAroundAStorm() {
        DebugLog.clear();
        DebugLog.publish(record("the first clue"));
        for (int i = 0; i < 5000; i++) DebugLog.publish(record(LOOKUP));
        DebugLog.publish(record("after the storm"));
        String text = DebugLog.text();
        DebugLog.clear();
        assertTrue(text.contains("the first clue"), text);
        assertTrue(text.contains("after the storm"), text);
        assertTrue(text.contains("(1000 times so far"), text);
        assertEquals(2 + LogRepeats.KEPT + 3, text.lines().count(), text);
    }

    private static LogRecord record(String message) {
        LogRecord record = new LogRecord(Level.WARNING, message);
        record.setLoggerName("javafx.css");
        return record;
    }
}
