package com.torrenta.ui;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import com.torrenta.engine.EngineEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** javafx.base only: the list and the properties need no toolkit. */
class EventLogTest {

    private static EngineEvent event(EngineEvent.Level level, String message) {
        return new EngineEvent(Instant.EPOCH, level, null, "", message);
    }

    @Test
    void countsErrorsAndClears() {
        EventLog log = new EventLog();
        log.add(event(EngineEvent.Level.INFO, "a"));
        log.add(event(EngineEvent.Level.ERROR, "b"));
        log.add(event(EngineEvent.Level.WARNING, "c"));
        log.add(event(EngineEvent.Level.ERROR, "d"));
        assertEquals(4, log.events().size());
        assertEquals(2, log.errorsProperty().get());
        log.clear();
        assertEquals(0, log.events().size());
        assertEquals(0, log.errorsProperty().get());
    }

    @Test
    void theOldestGoWhenFull() {
        EventLog log = new EventLog();
        for (int i = 0; i < EventLog.CAPACITY + 10; i++) log.add(event(EngineEvent.Level.INFO, "e" + i));
        assertEquals(EventLog.CAPACITY, log.events().size());
        assertEquals("e10", log.events().get(0).text());
        assertEquals(
                "e" + (EventLog.CAPACITY + 9),
                log.events().get(EventLog.CAPACITY - 1).text());
    }

    @Test
    void theLogBridgeForwardsWarningsAndAboveOnly() {
        EventLog log = new EventLog();
        var handler = log.logBridge(Runnable::run);
        handler.publish(new LogRecord(Level.INFO, "quiet"));
        handler.publish(new LogRecord(Level.WARNING, "hmm"));
        LogRecord severe = new LogRecord(Level.SEVERE, "bad");
        severe.setThrown(new IllegalStateException("boom"));
        handler.publish(severe);

        assertEquals(2, log.events().size());
        assertEquals(EngineEvent.Level.WARNING, log.events().get(0).level());
        assertEquals("hmm", log.events().get(0).text());
        assertEquals(EngineEvent.Level.ERROR, log.events().get(1).level());
        assertTrue(log.events().get(1).text().contains("boom"));
        assertEquals(1, log.errorsProperty().get());
    }

    @Test
    void oneTrackerErrorPerInterfaceIsOneLineWithACount() {
        EventLog log = new EventLog();
        Instant t = Instant.ofEpochSecond(1000);
        for (int i = 0; i < 7; i++)
            log.add(new EngineEvent(t.plusMillis(i), EngineEvent.Level.WARNING, "abc", "Ubuntu", "tracker down"));
        log.add(new EngineEvent(t, EngineEvent.Level.WARNING, "def", "Debian", "tracker down"));
        log.add(new EngineEvent(t.plusMillis(9), EngineEvent.Level.WARNING, "abc", "Ubuntu", "tracker down"));

        assertEquals(2, log.events().size());
        assertEquals(8, log.events().get(0).count());
        assertEquals("tracker down ×8", log.events().get(0).text());
        assertEquals(t, log.events().get(0).at());
        assertEquals(t.plusMillis(9), log.events().get(0).last());
        assertEquals("tracker down", log.events().get(1).text());
    }

    @Test
    void theSameWordsAfterTheWindowAreANewLine() {
        EventLog log = new EventLog();
        Instant t = Instant.ofEpochSecond(1000);
        log.add(new EngineEvent(t, EngineEvent.Level.INFO, null, "", "IP filter cleared"));
        log.add(new EngineEvent(
                t.plus(Repeats.WINDOW).plusSeconds(1), EngineEvent.Level.INFO, null, "", "IP filter cleared"));
        assertEquals(2, log.events().size());
    }

    @Test
    void aRepeatedErrorIsCountedOnceInTheBadge() {
        EventLog log = new EventLog();
        log.add(event(EngineEvent.Level.ERROR, "disk full"));
        log.add(event(EngineEvent.Level.ERROR, "disk full"));
        assertEquals(1, log.events().size());
        assertEquals(1, log.errorsProperty().get());
    }

    @Test
    void levelLabels() {
        assertEquals("Info", LogWindow.levelLabel(EngineEvent.Level.INFO));
        assertEquals("Warning", LogWindow.levelLabel(EngineEvent.Level.WARNING));
        assertEquals("Error", LogWindow.levelLabel(EngineEvent.Level.ERROR));
    }
}
