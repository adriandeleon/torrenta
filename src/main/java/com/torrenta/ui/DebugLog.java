package com.torrenta.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * What went wrong, kept somewhere a user can reach it. Termina's.
 *
 * <p>A packaged application has no console. Anything logged, and anything thrown on a thread with
 * no handler, would otherwise vanish — so the one useful sentence about why the engine would not
 * start goes nowhere, and the bug report says "it did not work".
 *
 * <p>Two destinations, for two situations. A bounded in-memory buffer backs the window, which
 * covers "it is misbehaving right now". A file beside the settings covers "it died", since a
 * crashed process cannot show its own window; it starts afresh each session, so it stays the story of
 * this run, and one that passes {@link #MAX_FILE_BYTES} starts again with a line saying so, so a
 * storm of warnings cannot fill the disk. The one before is kept beside it ({@value
 * #PREVIOUS_FILE_NAME}): a crash is reported from the launch after it, and that launch must not erase
 * the log that recorded it. Both go into a problem report (Help → Report a Problem, {@code --report}).
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

    /** The session before this one's file, kept for a problem report. */
    public static final String PREVIOUS_FILE_NAME = "torrenta-session.prev.log";

    /**
     * The most the file holds before it starts again. A problem report reads the last 200,000
     * characters, so what a roll drops is nothing a report would have shown.
     */
    static final long MAX_FILE_BYTES = 4L << 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    private static @Nullable Path file;
    /** Open on {@link #file} for the session; each line is one write, so a crash keeps the last. */
    private static @Nullable OutputStream out;
    /** Bytes in the file since it was started or last started again. */
    private static long written;

    private static long maxBytes = MAX_FILE_BYTES;
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

    /**
     * Also mirror to {@code <configDir>/torrenta-session.log}, started afresh for this run once the last
     * run's is moved aside to {@value #PREVIOUS_FILE_NAME}.
     */
    public static void attachFile(Path configDir) {
        attachFile(configDir, MAX_FILE_BYTES);
    }

    /** {@link #attachFile(Path)}, starting again past {@code limit} bytes: the test's. */
    static synchronized void attachFile(Path configDir, long limit) {
        detachFile();
        try {
            Files.createDirectories(configDir);
            Path f = configDir.resolve(FILE_NAME);
            keepPrevious(f, configDir.resolve(PREVIOUS_FILE_NAME));
            out = Files.newOutputStream(f); // created, or emptied
            file = f;
            written = 0;
            maxBytes = limit;
            // What was captured before the folder was known (the command line, the toolkit) goes first.
            for (String line : ENTRIES) write(line);
        } catch (IOException e) {
            // A log that cannot be written is not worth failing a launch over.
            detachFile();
        }
    }

    /** Stops mirroring and closes the file. */
    static synchronized void detachFile() {
        OutputStream o = out;
        out = null;
        file = null;
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
                // Nothing more is written to it either way.
            }
        }
    }

    /** Moves a non-empty last session's log aside; an empty one (a quiet session) would push out a useful one. */
    static void keepPrevious(Path current, Path previous) {
        try {
            if (Files.isRegularFile(current) && Files.size(current) > 0) {
                Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // This session is still logged; only the last one is lost, as it always was.
        }
    }

    /** This session's log file under {@code configDir}, whether or not it is being written. */
    public static Path fileIn(Path configDir) {
        return configDir.resolve(FILE_NAME);
    }

    /** The last session's log file under {@code configDir}. */
    public static Path previousIn(Path configDir) {
        return configDir.resolve(PREVIOUS_FILE_NAME);
    }

    /** A line of the application's own, stamped: a headless run's events, which no window shows. */
    public static void note(String text) {
        append(stamp(text));
    }

    static synchronized void append(String line) {
        ENTRIES.addLast(line);
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        if (out == null) return;
        try {
            write(line);
        } catch (IOException ignored) {
            // Same again: the in-memory copy is still there.
        }
    }

    private static void write(String line) throws IOException {
        OutputStream o = out;
        Path f = file;
        if (o == null || f == null) return;
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (written > 0 && written + bytes.length > maxBytes) {
            // Started again rather than stopped: a report reads the end, and the end is the crash.
            o.close();
            o = Files.newOutputStream(f);
            out = o;
            byte[] said = (stamp("The log passed " + (maxBytes >> 10)
                                    + " KiB; this session's earlier lines were dropped.")
                            + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            o.write(said);
            written = said.length;
        }
        o.write(bytes); // unbuffered: on disk when this returns
        written += bytes.length;
    }

    /** Everything captured this session, oldest first. */
    public static synchronized String text() {
        return String.join(System.lineSeparator(), ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** The file being mirrored to, or null when there is none. */
    public static synchronized @Nullable Path file() {
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
