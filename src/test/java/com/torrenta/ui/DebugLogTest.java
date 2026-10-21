package com.torrenta.ui;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

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
}
