package com.torrenta.session;

import java.util.List;

import com.torrenta.engine.EngineEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A reader asks for what is new since its last; the oldest go past the capacity. */
class SessionLogTest {

    private static EngineEvent event(String text) {
        return EngineEvent.session(EngineEvent.Level.INFO, text);
    }

    private static List<String> texts(List<SessionLog.Entry> entries) {
        return entries.stream().map(e -> e.event().message()).toList();
    }

    @Test
    void aReaderGetsWhatIsNewSinceItsLastNumber() {
        SessionLog log = new SessionLog(10);
        log.add(event("a"));
        log.add(event("b"));
        List<SessionLog.Entry> all = log.after(-1);
        assertEquals(List.of("a", "b"), texts(all));
        assertEquals(List.of(0L, 1L), all.stream().map(SessionLog.Entry::id).toList());
        log.add(event("c"));
        assertEquals(List.of("c"), texts(log.after(1)));
        assertEquals(List.of(), log.after(2));
    }

    /** The numbers go on past what was let go: a reader that fell behind never sees an event twice. */
    @Test
    void theOldestGoAndTheNumbersKeepCounting() {
        SessionLog log = new SessionLog(2);
        for (String s : List.of("a", "b", "c", "d")) log.add(event(s));
        assertEquals(List.of("c", "d"), texts(log.after(-1)));
        assertEquals(
                List.of(2L, 3L), log.after(0).stream().map(SessionLog.Entry::id).toList());
    }
}
