package com.torrenta.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The debug log's formatting and bounds. Never {@code install()}ed here: that is the app's, once. */
class DebugLogTest {

    @Test
    void aLoggerNameIsShortenedToItsLastSegment() {
        assertEquals("LibtorrentEngine", DebugLog.shortName("com.torrenta.engine.libtorrent.LibtorrentEngine"));
        assertEquals("bare", DebugLog.shortName("bare"));
        assertEquals("?", DebugLog.shortName(null));
        assertEquals("?", DebugLog.shortName("  "));
        // A trailing dot leaves nothing after it; the name itself is more use than an empty string.
        assertEquals("a.b.", DebugLog.shortName("a.b."));
    }

    @Test
    void aRecordBecomesOneLineWithItsLevelAndSource() {
        LogRecord record = new LogRecord(Level.WARNING, "could not save the settings");
        record.setLoggerName("com.torrenta.store.Settings");
        String line = DebugLog.format(record);
        assertTrue(line.contains("WARNING"), line);
        assertTrue(line.contains("Settings"), line);
        assertTrue(line.contains("could not save the settings"), line);
        assertFalse(line.contains("\n"), line);
    }

    @Test
    void aThrownExceptionKeepsItsStackTrace() {
        // The whole reason to have this: the message alone rarely says where it came from.
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("x.Y");
        record.setThrown(new IllegalStateException("the cause"));
        String line = DebugLog.format(record);
        assertTrue(line.contains("IllegalStateException"), line);
        assertTrue(line.contains("the cause"), line);
        assertTrue(line.contains("DebugLogTest"), line);
    }

    @Test
    void theBufferIsBounded() {
        DebugLog.clear();
        for (int i = 0; i < DebugLog.MAX_ENTRIES + 500; i++) DebugLog.append("line " + i);
        String text = DebugLog.text();
        // Oldest dropped, newest kept — a log that pushed out the crash to keep the startup chatter
        // would be worse than none.
        assertFalse(text.startsWith("line 0\n"), "oldest should have been dropped");
        assertTrue(text.endsWith("line " + (DebugLog.MAX_ENTRIES + 499)), "newest should be kept");
        assertEquals(DebugLog.MAX_ENTRIES, text.lines().count());
        DebugLog.clear();
        assertEquals("", DebugLog.text());
    }

    @Test
    void theLastSessionsLogIsKeptForAReport(@TempDir Path dir) throws IOException {
        // A crash is reported from the launch after it; that launch must not erase its record.
        Path current = Files.writeString(DebugLog.fileIn(dir), "the crash\n");
        Path previous = DebugLog.previousIn(dir);
        DebugLog.keepPrevious(current, previous);
        assertEquals("the crash\n", Files.readString(previous));
        assertFalse(Files.exists(current));

        // A quiet session does not push the useful one out.
        Files.writeString(current, "");
        DebugLog.keepPrevious(current, previous);
        assertEquals("the crash\n", Files.readString(previous));
        assertEquals("torrenta-session.prev.log", previous.getFileName().toString());
    }

    @Test
    void theFileHasEachLineAsItIsAppendedAndKeepsTheEarlierOnesFirst(@TempDir Path dir) throws IOException {
        DebugLog.clear();
        DebugLog.append("before the folder was known");
        try {
            DebugLog.attachFile(dir);
            DebugLog.append("one");
            DebugLog.append("two");
            // Read while it is still open: a crash right now must find these on disk.
            assertEquals(
                    java.util.List.of("before the folder was known", "one", "two"),
                    Files.readAllLines(DebugLog.fileIn(dir)));
        } finally {
            DebugLog.detachFile();
            DebugLog.clear();
        }
        DebugLog.append("after");
        assertEquals(3, Files.readAllLines(DebugLog.fileIn(dir)).size(), "detached, nothing more is written");
    }

    @Test
    void aFilePastItsLimitStartsAgainSayingSo(@TempDir Path dir) throws IOException {
        DebugLog.clear();
        try {
            DebugLog.attachFile(dir, 2048);
            for (int i = 0; i < 1000; i++) DebugLog.append("line " + i + " of a storm of warnings");
            Path file = DebugLog.fileIn(dir);
            long size = Files.size(file);
            assertTrue(size <= 2048, "bounded: " + size);
            var lines = Files.readAllLines(file);
            assertTrue(lines.get(0).contains("earlier lines were dropped"), lines.get(0));
            assertEquals("line 999 of a storm of warnings", lines.getLast(), "the newest line is kept");
            assertTrue(lines.size() > 10, "not just the newest");
            assertEquals(1000, DebugLog.text().lines().count(), "the window's copy keeps every line");
        } finally {
            DebugLog.detachFile();
            DebugLog.clear();
        }
    }

    @Test
    void attachingAgainStartsAfreshAndKeepsTheLastAsThePrevious(@TempDir Path dir) throws IOException {
        DebugLog.clear();
        try {
            DebugLog.attachFile(dir);
            DebugLog.append("first run");
            DebugLog.clear();
            DebugLog.attachFile(dir);
            DebugLog.append("second run");
            assertEquals(java.util.List.of("second run"), Files.readAllLines(DebugLog.fileIn(dir)));
            assertEquals(java.util.List.of("first run"), Files.readAllLines(DebugLog.previousIn(dir)));
        } finally {
            DebugLog.detachFile();
            DebugLog.clear();
        }
    }
}
