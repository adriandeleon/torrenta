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
 * <p>The FX thread owns the list, as it owns every view of it. {@link #add} must be called there;
 * {@link #logBridge} hops onto it through the executor it is given, which in the app is {@code
 * Platform.runLater} and in the tests is "run it now".
 */
public final class EventLog {

    /** Enough to scroll back through a day; above this the oldest go. */
    static final int CAPACITY = 2000;

    private final ObservableList<EngineEvent> events = FXCollections.observableArrayList();
    private final ReadOnlyIntegerWrapper errors = new ReadOnlyIntegerWrapper(this, "errors", 0);

    public ObservableList<EngineEvent> events() {
        return events;
    }

    /** Errors since the log was last cleared; the status bar shows it when it is not zero. */
    public ReadOnlyIntegerProperty errorsProperty() {
        return errors.getReadOnlyProperty();
    }

    public void add(EngineEvent event) {
        events.add(event);
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
