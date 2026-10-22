package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rates a graph is drawn from: one sample a second for the last ten minutes, then one average
 * a minute up to a day. Two ring buffers, indexed by the time itself, so adding is O(1) and
 * nothing is ever shifted; a slot whose stamp is not the time asked for is stale and skipped.
 *
 * <p>Fed by the window's poll thread, read by the FX thread: the methods are synchronized, which
 * is all the thread-safety a ring of longs needs. Time is in whole seconds, whatever clock the
 * caller keeps, and must not go backwards: a sample at or before the last one is ignored, so two
 * polls landing in one second count once. A second with no sample (the machine slept, the poll
 * was late) is simply absent, and a graph draws the line across it.
 */
public final class SpeedHistory {

    /** Seconds kept at full resolution. */
    public static final int FINE_SECONDS = 600;
    /** Minutes kept as averages. */
    public static final int COARSE_MINUTES = 24 * 60;
    /** The windows a graph offers, in seconds: a minute, ten, an hour, a day. */
    public static final List<Integer> WINDOWS = List.of(60, 600, 3600, 86400);

    /** The rates at one moment, bytes per second; a coarse sample's {@code at} is its minute's start. */
    public record Sample(long at, long download, long upload) {}

    private final long[] fineAt = new long[FINE_SECONDS];
    private final long[] fineDown = new long[FINE_SECONDS];
    private final long[] fineUp = new long[FINE_SECONDS];
    private final long[] coarseMinute = new long[COARSE_MINUTES];
    private final long[] coarseDown = new long[COARSE_MINUTES];
    private final long[] coarseUp = new long[COARSE_MINUTES];
    private final int[] coarseCount = new int[COARSE_MINUTES];
    private long lastAt = Long.MIN_VALUE;
    private Sample latest;

    public SpeedHistory() {
        java.util.Arrays.fill(fineAt, Long.MIN_VALUE);
        java.util.Arrays.fill(coarseMinute, Long.MIN_VALUE);
    }

    /** Records this second's rates; a second already recorded, or earlier than one, is ignored. */
    public synchronized void add(long atSeconds, long download, long upload) {
        if (atSeconds <= lastAt) return;
        lastAt = atSeconds;
        latest = new Sample(atSeconds, download, upload);
        int slot = slot(atSeconds, FINE_SECONDS);
        fineAt[slot] = atSeconds;
        fineDown[slot] = download;
        fineUp[slot] = upload;
        long minute = Math.floorDiv(atSeconds, 60);
        int m = slot(minute, COARSE_MINUTES);
        if (coarseMinute[m] != minute) {
            coarseMinute[m] = minute;
            coarseDown[m] = 0;
            coarseUp[m] = 0;
            coarseCount[m] = 0;
        }
        coarseDown[m] += download;
        coarseUp[m] += upload;
        coarseCount[m]++;
    }

    /**
     * The samples in the last {@code windowSeconds} up to {@code nowSeconds}, oldest first: every
     * second for a window of ten minutes or less, else one average per minute (the minute in
     * progress averaged over what it has so far). Empty when nothing has been recorded there.
     */
    public synchronized List<Sample> samples(int windowSeconds, long nowSeconds) {
        List<Sample> out = new ArrayList<>();
        if (windowSeconds <= FINE_SECONDS) {
            for (long t = nowSeconds - windowSeconds + 1; t <= nowSeconds; t++) {
                int slot = slot(t, FINE_SECONDS);
                if (fineAt[slot] == t) out.add(new Sample(t, fineDown[slot], fineUp[slot]));
            }
            return out;
        }
        long first = Math.floorDiv(nowSeconds - windowSeconds, 60) + 1;
        long last = Math.floorDiv(nowSeconds, 60);
        for (long minute = first; minute <= last; minute++) {
            int m = slot(minute, COARSE_MINUTES);
            if (coarseMinute[m] != minute || coarseCount[m] == 0) continue;
            out.add(new Sample(minute * 60, coarseDown[m] / coarseCount[m], coarseUp[m] / coarseCount[m]));
        }
        return out;
    }

    /**
     * A window longer than the fine one drawn as finely as the history allows: minute averages up
     * to where the seconds begin, then every second. A torrent selected a moment ago has a line at
     * once rather than a single minute's dot.
     */
    public synchronized List<Sample> blended(int windowSeconds, long nowSeconds) {
        if (windowSeconds <= FINE_SECONDS) return samples(windowSeconds, nowSeconds);
        List<Sample> fine = samples(FINE_SECONDS, nowSeconds);
        long fineStart = fine.isEmpty() ? nowSeconds + 1 : fine.getFirst().at();
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples(windowSeconds, nowSeconds)) {
            // A minute that reaches into the seconds is told by them instead.
            if (s.at() + 60 <= fineStart) out.add(s);
        }
        out.addAll(fine);
        return out;
    }

    /** The last sample added, whatever its age. */
    public synchronized Optional<Sample> latest() {
        return Optional.ofNullable(latest);
    }

    public synchronized boolean isEmpty() {
        return latest == null;
    }

    private static int slot(long t, int size) {
        return (int) Math.floorMod(t, size);
    }
}
