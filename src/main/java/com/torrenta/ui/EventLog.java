package com.torrenta.ui;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.torrenta.engine.EngineEvent;

/**
 * What has happened, newest last, bounded: the engine's events, plus anything the app itself logs
 * at warning or above, so a failed settings write shows up in the same place as a tracker error.
 *
 * <p>A line said again within {@link Repeats#WINDOW} is counted on the line it repeats rather
 * than added ({@link Line#count}), so one tracker's error, which libtorrent reports once per
 * listen interface, is one line.
 *
 * <p>The FX thread owns the list, as it owns every view of it. {@link #add} must be called there;
 * {@link #logBridge} hops onto it through the executor it is given, which in the app is {@code
 * Platform.runLater} and in the tests is "run it now".
 */
public final class EventLog {

    /** Enough to scroll back through a day; above this the oldest go. */
    static final int CAPACITY = 2000;

    /**
     * One line of the log: the event as first said, how many times, and when last.
     *
     * @param event the first time it was said; its {@code at} is the line's time
     * @param count how many times, at least one
     * @param last when it was last said, for the window a repeat must fall in
     */
    public record Line(EngineEvent event, int count, Instant last) {
        Line(EngineEvent event) {
            this(event, 1, event.at());
        }

        public Instant at() {
            return event.at();
        }

        public EngineEvent.Level level() {
            return event.level();
        }

        public String torrent() {
            return event.torrent();
        }

        /** The message, with "×N" once it has been said more than once. */
        public String text() {
            return Repeats.text(event.message(), count);
        }

        Repeats.Key key() {
            return key(event);
        }

        static Repeats.Key key(EngineEvent e) {
            return new Repeats.Key(e.level(), e.torrentId(), e.message());
        }
    }

    private final ObservableList<Line> events = FXCollections.observableArrayList();
    private final ReadOnlyIntegerWrapper errors = new ReadOnlyIntegerWrapper(this, "errors", 0);

    public ObservableList<Line> events() {
        return events;
    }

    /** Errors since the log was last cleared; the status bar shows it when it is not zero. */
    public ReadOnlyIntegerProperty errorsProperty() {
        return errors.getReadOnlyProperty();
    }

    public void add(EngineEvent event) {
        int same = Repeats.find(events, Line::key, Line::last, Line.key(event), event.at());
        if (same >= 0) {
            Line line = events.get(same);
            events.set(same, new Line(line.event(), line.count() + 1, event.at()));
            return;
        }
        events.add(new Line(event));
        if (event.level() == EngineEvent.Level.ERROR) errors.set(errors.get() + 1);
        if (events.size() > CAPACITY) events.remove(0, events.size() - CAPACITY);
    }

    public void clear() {
        events.clear();
        errors.set(0);
    }

    /**
     * A {@code java.util.logging} handler that turns WARNING and SEVERE records into events. Install
     * it on the {@code com.torrenta} logger; it never touches records below WARNING, which stay
     * where they were going.
     */
    public Handler logBridge(Consumer<Runnable> onOwnerThread) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) return;
                EngineEvent.Level level = record.getLevel().intValue() >= Level.SEVERE.intValue()
                        ? EngineEvent.Level.ERROR
                        : EngineEvent.Level.WARNING;
                String message = record.getMessage() == null ? "" : record.getMessage();
                if (record.getThrown() != null) message += ": " + record.getThrown();
                EngineEvent event = new EngineEvent(Instant.ofEpochMilli(record.getMillis()), level, null, "", message);
                onOwnerThread.accept(() -> add(event));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.WARNING);
        return handler;
    }

    /** Installs the bridge on the application's logger namespace. */
    public void installLogBridge(Consumer<Runnable> onOwnerThread) {
        Logger.getLogger("com.torrenta").addHandler(logBridge(onOwnerThread));
    }
}
