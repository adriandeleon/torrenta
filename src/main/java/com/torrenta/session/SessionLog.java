package com.torrenta.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.torrenta.engine.EngineEvent;

/**
 * The session's recent events, numbered, for a reader that asks only for what is new since the
 * last it saw (Remote access's {@code log/main}). The engine's events and the session's own lines
 * (share limits, the content check, rules, problems) as the owner hears them; at most {@code
 * capacity}, the oldest let go. The numbers keep counting past what was let go, so a reader that
 * fell behind gets what is left, never an event twice. Thread-safe.
 */
public final class SessionLog {

    /** One event and its number, from 0. */
    public record Entry(long id, EngineEvent event) {}

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long next;

    public SessionLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("a log holds something");
        this.capacity = capacity;
    }

    public synchronized void add(EngineEvent event) {
        entries.addLast(new Entry(next++, event));
        while (entries.size() > capacity) entries.removeFirst();
    }

    /** Every entry numbered above {@code id}, oldest first; -1 for all that are kept. */
    public synchronized List<Entry> after(long id) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.id() > id) out.add(e);
        return out;
    }
}
