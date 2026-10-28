package com.torrenta.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Which copies of a repeated log line the {@link DebugLog} keeps. A storm of one warning (JavaFX's
 * CSS logs a failed lookup for every cell it styles: 28,000 lines in twelve minutes once) would
 * otherwise push every other line out of the buffer and the file, the first ones included, which
 * are the ones that say how it began. The first {@link #KEPT} copies of a line are written, then
 * one line at each power of ten saying how many have come; the rest are counted, not written.
 * Not thread-safe; the log calls it under its own lock.
 */
final class LogRepeats {

    /** Copies of one line written in full before it is only counted. */
    static final int KEPT = 3;

    /** Distinct lines remembered; past it the memory starts again, so a log of all-different lines costs nothing. */
    static final int REMEMBERED = 1000;

    /** What to do with one more copy of a line. */
    sealed interface Verdict {}

    /** Write it as it is. */
    record Write() implements Verdict {}

    /** Write, in its place, that it has now come {@code count} times. */
    record Tally(long count) implements Verdict {}

    /** Count it and leave it out. */
    record Drop() implements Verdict {}

    private static final Write WRITE = new Write();
    private static final Drop DROP = new Drop();

    private final Map<String, Long> counts = new HashMap<>();

    /** One more copy of the line known by {@code key}: its level, logger and message, without the time. */
    Verdict seen(String key) {
        if (counts.size() >= REMEMBERED && !counts.containsKey(key)) counts.clear();
        long n = counts.merge(key, 1L, Long::sum);
        if (n <= KEPT) return WRITE;
        return isPowerOfTen(n) ? new Tally(n) : DROP;
    }

    /** Forgets every count: the window's Clear, after which a repeat is news again. */
    void clear() {
        counts.clear();
    }

    static boolean isPowerOfTen(long n) {
        if (n < 10) return false;
        while (n % 10 == 0) n /= 10;
        return n == 1;
    }

    /** The line written for a {@link Tally}: the count, then the start of what repeats. */
    static String tallyText(long count, String key) {
        String what = key.length() > 160 ? key.substring(0, 160) + "…" : key;
        return "(" + count + " times so far, the rest not written) " + what;
    }
}
