package com.torrenta;

import java.time.LocalTime;

import com.torrenta.engine.EngineEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeadlessTest {

    /** One line a reader can grep: the time, the level padded to the widest, the words. */
    @Test
    void aLineIsTheTimeTheLevelAndTheWords() {
        LocalTime at = LocalTime.of(16, 8, 22);
        assertEquals("16:08:22 INFO    Added Film", Headless.line(at, EngineEvent.Level.INFO, "Added Film"));
        assertEquals(
                "16:08:22 WARNING Added Film without the file",
                Headless.line(at, EngineEvent.Level.WARNING, "Added Film without the file"));
        assertEquals("16:08:22 ERROR   Port in use", Headless.line(at, EngineEvent.Level.ERROR, "Port in use"));
    }
}
