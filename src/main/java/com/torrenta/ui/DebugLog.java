package com.torrenta.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What went wrong, kept somewhere a user can reach it. Termina's.
 *
 * <p>A packaged application has no console. Anything logged, and anything thrown on a thread with
 * no handler, would otherwise vanish — so the one useful sentence about why the engine would not
 * start goes nowhere, and the bug report says "it did not work".
 *
 * <p>Two destinations, for two situations. A bounded in-memory buffer backs the window, which
 * covers "it is misbehaving right now". A file beside the settings covers "it died", since a
 * crashed process cannot show its own window; it is truncated per session, so it stays the story of
 * this run rather than growing without limit.
 *
 * <p>This is the whole application's record and takes every logger at WARNING and above; the
 * {@link EventLog}'s bridge takes the same records from {@code com.torrenta} alone into the event
 * log, where a person reads them beside the engine's events. The two do not know about each other.
 */
public final class DebugLog {

    /** Enough to hold a session's worth of trouble without holding a session's worth of memory. */
    static final int MAX_ENTRIES = 2000;

    /** The file's name under the config folder. */
    public static final String FILE_NAME = "torrenta-session.log";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    private static Path file;
    private static boolean installed;

    private DebugLog() {}

    /** Captures j.u.l output and uncaught exceptions. Call once, as early as possible; a second call does nothing. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Logger root = Logger.getLogger("");
        root.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) return;
                append(format(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            append(stamp("UNCAUGHT on " + thread.getName() + ": " + stackTrace(error)));
            // Still to stderr, for anyone who did launch from a terminal.
            error.printStackTrace();
        });
    }

    /** Also mirror to {@code <configDir>/torrenta-session.log}, truncated for this run. */
    public static synchronized void attachFile(Path configDir) {
        try {
            Files.createDirectories(configDir);
            file = configDir.resolve(FILE_NAME);
            Files.writeString(file, "", StandardCharsets.UTF_8);
            // What was captured before the folder was known (the command line, the toolkit) goes first.
            for (String line : ENTRIES) write(line);
        } catch (IOException e) {
            // A log that cannot be written is not worth failing a launch over.
            file = null;
        }
    }

    /** A line of the application's own, stamped: a headless run's events, which no window shows. */
    public static void note(String text) {
        append(stamp(text));
    }

    static synchronized void append(String line) {
        ENTRIES.addLast(line);
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        if (file == null) return;
        try {
            write(line);
        } catch (IOException ignored) {
            // Same again: the in-memory copy is still there.
        }
    }

    private static void write(String line) throws IOException {
        Files.writeString(
                file,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Everything captured this session, oldest first. */
    public static synchronized String text() {
        return String.join(System.lineSeparator(), ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** The file being mirrored to, or null when there is none. */
    public static synchronized Path file() {
        return file;
    }

    /** Renders a record as one line, with a stack trace after it when there is one. */
    static String format(LogRecord record) {
        StringBuilder out = new StringBuilder(stamp(
                record.getLevel().getName() + " " + shortName(record.getLoggerName()) + " — " + record.getMessage()));
        if (record.getThrown() != null) {
            out.append(System.lineSeparator()).append(stackTrace(record.getThrown()));
        }
        return out.toString();
    }

    private static String stamp(String message) {
        return LocalTime.now().format(TIME) + "  " + message;
    }

    /**
     * The last segment of a logger name.
     *
     * <p>Full names are mostly package, and the package is the same for every line that matters —
     * so the part that identifies the source is the part that gets pushed off the end.
     */
    static String shortName(String loggerName) {
        if (loggerName == null || loggerName.isBlank()) return "?";
        int dot = loggerName.lastIndexOf('.');
        return dot < 0 || dot == loggerName.length() - 1 ? loggerName : loggerName.substring(dot + 1);
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString().stripTrailing();
    }
}
