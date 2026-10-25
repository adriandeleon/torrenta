package com.torrenta.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;

/**
 * What can be taken back, newest first: a removal (its files kept), Set Location, a category,
 * tags, a name. The status bar offers the newest for {@link #OFFER_MS}; Shortcut+Z takes the
 * newest back for {@link #KEEP_MS}, after which it is forgotten; at most {@link #SIZE} are kept.
 *
 * <p>The rule is pure: the clock is passed in, and an entry's {@link Undo} runs where the window
 * runs engine calls, on a worker.
 */
final class UndoStack {

    static final int SIZE = 10;
    static final long OFFER_MS = 10_000;
    static final long KEEP_MS = 10 * 60_000;

    /** Takes the thing back; on a worker, and it may be refused (a torrent that is back already). */
    interface Undo {
        void run(Engine engine) throws EngineException;
    }

    /**
     * One thing done.
     *
     * @param label what it was, as the status bar and the Undo menu item say it
     * @param at when, epoch milliseconds
     */
    record Entry(String label, long at, Undo undo) {}

    private final Deque<Entry> entries = new ArrayDeque<>();

    synchronized void push(String label, long now, Undo undo) {
        entries.addFirst(new Entry(label, now, undo));
        while (entries.size() > SIZE) entries.removeLast();
    }

    /** The newest entry still kept, the expired ones forgotten on the way. */
    synchronized Optional<Entry> newest(long now) {
        while (!entries.isEmpty() && now - entries.peekLast().at() >= KEEP_MS) entries.removeLast();
        return Optional.ofNullable(entries.peekFirst());
    }

    /** The newest entry, taken off the stack to be undone. */
    synchronized Optional<Entry> take(long now) {
        Optional<Entry> e = newest(now);
        e.ifPresent(x -> entries.removeFirst());
        return e;
    }

    /** The status bar's Undo shows: the newest entry is younger than {@link #OFFER_MS}. */
    synchronized boolean offered(long now) {
        return newest(now).map(e -> now - e.at() < OFFER_MS).orElse(false);
    }

    synchronized int size() {
        return entries.size();
    }
}
