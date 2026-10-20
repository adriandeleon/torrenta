package com.torrenta.engine;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schedule rule, which is where "it limited me at the wrong hour" would come from. */
class SpeedScheduleTest {

    private static EngineSettings.Speed speed(boolean on, int from, int to, EnumSet<DayOfWeek> days) {
        return new EngineSettings.Speed(1000, 100, 200, 20, false, on, from, to, days, false);
    }

    // 2026-10-19 is a Monday.
    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute);
    }

    @Test
    void aDaytimeWindow() {
        EngineSettings.Speed s = speed(true, 9 * 60, 17 * 60, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY));
        assertTrue(s.scheduleActive(at(19, 9, 0)), "Monday 09:00, start inclusive");
        assertTrue(s.scheduleActive(at(19, 16, 59)));
        assertFalse(s.scheduleActive(at(19, 17, 0)), "end exclusive");
        assertFalse(s.scheduleActive(at(19, 8, 59)));
        assertFalse(s.scheduleActive(at(21, 12, 0)), "Wednesday is not in the set");
    }

    @Test
    void anOvernightWindowBelongsToTheDayItStarted() {
        EngineSettings.Speed s = speed(true, 22 * 60, 8 * 60, EnumSet.of(DayOfWeek.MONDAY));
        assertTrue(s.scheduleActive(at(19, 23, 30)), "Monday night");
        assertTrue(s.scheduleActive(at(20, 3, 0)), "Tuesday 03:00 is still Monday's window");
        assertFalse(s.scheduleActive(at(20, 8, 0)), "ends Tuesday 08:00");
        assertFalse(s.scheduleActive(at(20, 23, 30)), "Tuesday night is not Monday's");
        assertFalse(s.scheduleActive(at(19, 3, 0)), "Monday 03:00 would be Sunday's window, which is off");
    }

    @Test
    void offMeansNeverAndTheHandSwitchWins() {
        EngineSettings.Speed off = speed(false, 0, 24 * 60 - 1, EnumSet.allOf(DayOfWeek.class));
        assertFalse(off.scheduleActive(at(19, 12, 0)));
        assertFalse(off.alternativeActive(at(19, 12, 0)));
        assertTrue(off.withAlternativeOn(true).alternativeActive(at(19, 12, 0)));
        assertEquals(1000 * 1024, off.effectiveDownloadBytes(at(19, 12, 0)));
        assertEquals(200 * 1024, off.withAlternativeOn(true).effectiveDownloadBytes(at(19, 12, 0)));
        assertEquals(
                0,
                new EngineSettings.Speed(0, 0, 0, 0, false, false, 0, 0, EnumSet.noneOf(DayOfWeek.class), false)
                        .effectiveUploadBytes(at(19, 12, 0)),
                "0 is none");
    }
}
