package com.torrenta.ui;

import java.time.Instant;
import java.util.List;

import com.torrenta.engine.EngineEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The status bar's history: pure, no toolkit. */
class MessageLogTest {

    private static void add(MessageLog log, String text, long second) {
        log.add(text, EngineEvent.Level.INFO, Instant.ofEpochSecond(second));
    }

    @Test
    void readsNewestFirst() {
        MessageLog log = new MessageLog();
        add(log, "first", 1);
        add(log, "second", 2);
        add(log, "third", 3);
        List<MessageLog.Entry> entries = log.entries();
        assertEquals(3, entries.size());
        assertEquals("third", entries.get(0).text());
        assertEquals("second", entries.get(1).text());
        assertEquals("first", entries.get(2).text());
        assertEquals(Instant.ofEpochSecond(3), entries.get(0).at());
    }

    @Test
    void aBlankLineClearsTheStatusBarAndIsNotAMessage() {
        MessageLog log = new MessageLog();
        add(log, null, 1);
        add(log, "", 2);
        add(log, "   ", 3);
        assertTrue(log.isEmpty());
        assertEquals(0, log.entries().size());
    }

    @Test
    void theOldestGoWhenFull() {
        MessageLog log = new MessageLog();
        int total = MessageLog.MAX_ENTRIES + 50;
        for (int i = 0; i < total; i++) add(log, "m" + i, i);
        assertEquals(MessageLog.MAX_ENTRIES, log.size());
        List<MessageLog.Entry> entries = log.entries();
        assertEquals("m" + (total - 1), entries.get(0).text());
        assertEquals("m50", entries.get(entries.size() - 1).text());
    }

    @Test
    void aLevelIsKeptAndAMissingOneIsInfo() {
        MessageLog log = new MessageLog();
        log.add("bad", EngineEvent.Level.ERROR, Instant.EPOCH);
        log.add("odd", null, Instant.EPOCH);
        assertEquals(EngineEvent.Level.INFO, log.entries().get(0).level());
        assertEquals(EngineEvent.Level.ERROR, log.entries().get(1).level());
    }

    @Test
    void clearEmptiesTheLog() {
        MessageLog log = new MessageLog();
        add(log, "a", 1);
        add(log, "b", 2);
        log.clear();
        assertTrue(log.isEmpty());
        assertEquals(0, log.size());
    }
}
