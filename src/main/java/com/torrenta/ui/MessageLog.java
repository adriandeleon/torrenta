package com.torrenta.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.torrenta.engine.EngineEvent;

/**
 * What the status bar has said this session, newest first, bounded. Editora's.
 *
 * <p>The status bar shows one line and the next one replaces it, so "Update check failed" is gone
 * the moment a torrent finishes. This keeps the lines so a click on the status bar can show what
 * scrolled past. Session only, never written anywhere; blank lines (which clear the status) are
 * not recorded. The engine's events have a log of their own ({@link EventLog}, with the torrent
 * and a level filter); this is the status bar's history, whatever put the words there.
 *
 * <p>The same words at the same level within {@link Repeats#WINDOW} of their last time are one
 * entry with a count, moved to the top with the new time.
 *
 * <p>Pure: no toolkit, so the cap, the skipping and the order are unit-tested.
 */
public final class MessageLog {

    /** Enough to scroll back through a session; above this the oldest go. */
    public static final int MAX_ENTRIES = 200;

    /**
     * One line: when it was last said, what, how loudly (the status bar colours the loud ones),
     * and how many times.
     */
    public record Entry(Instant at, String words, EngineEvent.Level level, int count) {
        public Entry {
            level = level == null ? EngineEvent.Level.INFO : level;
            count = Math.max(1, count);
        }

        public Entry(Instant at, String words, EngineEvent.Level level) {
            this(at, words, level, 1);
        }

        /** The words, with "×N" once they have been said more than once. */
        public String text() {
            return Repeats.text(words, count);
        }

        Repeats.Key key() {
            return new Repeats.Key(level, null, words);
        }
    }

    // Oldest first; evicted from the head, appended at the tail.
    private final List<Entry> entries = new ArrayList<>();

    /** Records {@code text} at {@code at}; does nothing for a null or blank line. */
    public void add(String text, EngineEvent.Level level, Instant at) {
        if (text == null || text.isBlank()) return;
        Entry next = new Entry(at, text, level);
        int same = Repeats.find(entries, Entry::key, Entry::at, next.key(), at);
        if (same >= 0) next = new Entry(at, text, level, entries.remove(same).count() + 1);
        entries.add(next);
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    /** Records an ordinary line, now. */
    public void add(String text) {
        add(text, EngineEvent.Level.INFO, Instant.now());
    }

    /** A snapshot, newest first: the order a person reads a history in. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries);
        Collections.reverse(out);
        return out;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
