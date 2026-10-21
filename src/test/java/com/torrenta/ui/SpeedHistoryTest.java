package com.torrenta.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedHistoryTest {

    private static List<Long> downloads(List<SpeedHistory.Sample> samples) {
        return samples.stream().map(SpeedHistory.Sample::download).toList();
    }

    private static List<Long> times(List<SpeedHistory.Sample> samples) {
        return samples.stream().map(SpeedHistory.Sample::at).toList();
    }

    @Test
    void emptyUntilFed() {
        SpeedHistory h = new SpeedHistory();
        assertTrue(h.isEmpty());
        assertTrue(h.latest().isEmpty());
        assertEquals(List.of(), h.samples(60, 1000));
        assertEquals(List.of(), h.samples(86400, 1000));
    }

    @Test
    void aWindowHoldsItsSecondsOldestFirst() {
        SpeedHistory h = new SpeedHistory();
        for (long t = 1000; t < 1100; t++) h.add(t, t, 2 * t);
        List<SpeedHistory.Sample> last5 = h.samples(5, 1099);
        assertEquals(List.of(1095L, 1096L, 1097L, 1098L, 1099L), times(last5));
        assertEquals(new SpeedHistory.Sample(1099, 1099, 2198), last5.get(4));
        assertEquals(new SpeedHistory.Sample(1099, 1099, 2198), h.latest().orElseThrow());
        assertEquals(100, h.samples(600, 1099).size(), "all hundred within ten minutes");
        assertEquals(60, h.samples(60, 1099).size());
    }

    @Test
    void theFineRingForgetsAfterTenMinutes() {
        SpeedHistory h = new SpeedHistory();
        for (long t = 0; t < 1000; t++) h.add(t, 7, 7);
        List<SpeedHistory.Sample> all = h.samples(SpeedHistory.FINE_SECONDS, 999);
        assertEquals(600, all.size());
        assertEquals(400, all.get(0).at(), "the oldest kept is now - 599");
        // A slot reused by a later second is not mistaken for the earlier one.
        assertEquals(List.of(), h.samples(600, 300), "asking about a time that has been overwritten finds nothing");
    }

    @Test
    void aGapStaysAGap() {
        SpeedHistory h = new SpeedHistory();
        for (long t = 1; t <= 5; t++) h.add(t, 10, 0);
        h.add(10, 20, 0); // the machine slept through 6..9
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 10L), times(h.samples(600, 10)));
        assertEquals(List.of(10L), times(h.samples(3, 10)), "a window over the gap holds only what was recorded");
        assertEquals(List.of(), h.samples(3, 8), "a window inside the gap is empty");
    }

    @Test
    void aSecondCountsOnceAndTimeNeverGoesBack() {
        SpeedHistory h = new SpeedHistory();
        h.add(100, 1, 1);
        h.add(100, 99, 99); // a second poll in the same second
        h.add(99, 50, 50); // the clock stepped back
        assertEquals(List.of(new SpeedHistory.Sample(100, 1, 1)), h.samples(60, 100));
        assertEquals(1, h.samples(3600, 100).size());
        assertEquals(1, h.samples(3600, 100).get(0).download(), "the minute's average counts one sample");
    }

    @Test
    void longerWindowsAreMinuteAverages() {
        SpeedHistory h = new SpeedHistory();
        // Minute 10 (600..659): 60 samples alternating 100 and 300: the average is 200.
        for (long t = 600; t < 660; t++) h.add(t, t % 2 == 0 ? 100 : 300, 60);
        // Minute 11: twenty seconds so far at 1000.
        for (long t = 660; t < 680; t++) h.add(t, 1000, 0);
        List<SpeedHistory.Sample> hour = h.samples(3600, 679);
        assertEquals(List.of(600L, 660L), times(hour), "one point per minute, stamped at the minute's start");
        assertEquals(List.of(200L, 1000L), downloads(hour));
        assertEquals(60, hour.get(0).upload());
        assertEquals(0, hour.get(1).upload(), "the minute in progress is averaged over what it has");
        assertEquals(hour, h.samples(86400, 679), "the day window is the same points, further back");
    }

    @Test
    void aMinuteWindowOnTheHourExcludesMinutesBeforeIt() {
        SpeedHistory h = new SpeedHistory();
        for (long t = 0; t < 7200; t++) h.add(t, 5, 5);
        List<SpeedHistory.Sample> hour = h.samples(3600, 7199);
        assertEquals(60, hour.size());
        assertEquals(3600, hour.get(0).at(), "the first minute kept starts where the window starts");
        assertEquals(7140, hour.get(59).at());
    }

    @Test
    void theCoarseRingForgetsAfterADay() {
        SpeedHistory h = new SpeedHistory();
        // One sample per minute for 25 hours: cheap to feed, enough to wrap the ring.
        for (long minute = 0; minute < 25 * 60; minute++) h.add(minute * 60, minute, 0);
        List<SpeedHistory.Sample> day = h.samples(86400, 25 * 3600 - 1);
        assertEquals(1440, day.size());
        assertEquals(60 * 60, day.get(0).at(), "the oldest minute kept is a day back");
        assertEquals(60, day.get(0).download());
        assertEquals(25 * 60 - 1, day.get(1439).download());
    }

    @Test
    void negativeTimesWorkToo() {
        // Nothing promises epoch seconds; a test clock may start anywhere.
        SpeedHistory h = new SpeedHistory();
        h.add(-100, 1, 2);
        h.add(-99, 3, 4);
        assertEquals(2, h.samples(60, -99).size());
        assertEquals(1, h.samples(3600, -99).size());
    }
}
