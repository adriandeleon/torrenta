package com.torrenta.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.torrenta.engine.EngineEvent;

import static com.torrenta.i18n.Messages.tr;

/**
 * When a line is one the log already has: the same level, the same torrent and the same words,
 * said again within a short while of the last time. libtorrent reports a tracker error once per
 * listen interface, so one unreachable tracker said the same thing seven times per torrent; the
 * event log and the status bar's history keep one line with a count instead.
 *
 * <p>Pure: the logs hand in their recent lines and get back which one, if any, to count.
 */
final class Repeats {

    /** How long after the last time a line was said that the same words still count as it. */
    static final Duration WINDOW = Duration.ofSeconds(10);

    /** How far back a log looks: the interfaces' copies of one error arrive between other lines. */
    static final int LOOK_BACK = 32;

    private Repeats() {}

    /** What makes two lines the same one; {@code torrent} is the id, or null for the session. */
    record Key(EngineEvent.Level level, String torrent, String text) {}

    /**
     * The index in {@code lines} (oldest first) of the line that {@code key}, said at {@code at},
     * repeats; -1 when it is a new line. Searched newest first, at most {@link #LOOK_BACK} lines,
     * and only among lines last said no more than {@link #WINDOW} before {@code at}.
     */
    static <T> int find(List<T> lines, Function<T, Key> keyOf, Function<T, Instant> lastOf, Key key, Instant at) {
        int stop = Math.max(0, lines.size() - LOOK_BACK);
        for (int i = lines.size() - 1; i >= stop; i--) {
            T line = lines.get(i);
            if (keyOf.apply(line).equals(key) && within(lastOf.apply(line), at)) return i;
        }
        return -1;
    }

    /** Whether {@code at} is no more than the window after {@code last}; an earlier {@code at} counts too. */
    static boolean within(Instant last, Instant at) {
        return !Duration.between(last, at).minus(WINDOW).isPositive();
    }

    /** The words as a log shows them: "text ×3" once said more than once. */
    static String text(String text, int count) {
        return count <= 1 ? text : tr("log.repeated", Objects.requireNonNullElse(text, ""), count);
    }
}
