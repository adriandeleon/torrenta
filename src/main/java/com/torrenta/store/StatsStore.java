package com.torrenta.store;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The all-time totals that outlive a session: bytes down and up, seconds active. One small
 * properties file, {@code stats.properties} in the config folder, this machine's and never
 * synced: it is a record of what this computer moved, not a preference.
 *
 * <p>The engine owns the arithmetic: it loads the totals at start, and writes {@code loaded +
 * this session} every few minutes and at stop, so a crash costs a few minutes of counting and
 * nothing is ever counted twice. Writes are atomic (a temp file beside the target, then a move).
 */
public final class StatsStore {

    private static final Logger LOG = Logger.getLogger(StatsStore.class.getName());

    public static final String FILE_NAME = "stats.properties";

    static final String DOWNLOAD = "download.bytes";
    static final String UPLOAD = "upload.bytes";
    static final String ACTIVE = "active.seconds";

    private final Path file;

    public StatsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** The totals on disk; zero when there is no file yet or it cannot be read (and said once). */
    public Totals load() {
        if (!Files.isRegularFile(file)) return Totals.ZERO;
        try {
            return decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warning("The statistics file " + file + " is unreadable (" + e.getMessage() + "); starting from zero");
            return Totals.ZERO;
        }
    }

    public void save(Totals totals) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), FILE_NAME, ".part");
        try {
            Files.writeString(tmp, encode(totals), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The file's text, fixed so two saves of the same totals are the same bytes. */
    public static String encode(Totals totals) {
        return "# Torrenta all-time statistics; this machine's, never synced.\n"
                + DOWNLOAD + "=" + totals.download() + "\n"
                + UPLOAD + "=" + totals.upload() + "\n"
                + ACTIVE + "=" + totals.activeSeconds() + "\n";
    }

    /** The totals in the text; a missing key is zero, a value that is not a number is an error. */
    public static Totals decode(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return new Totals(number(p, DOWNLOAD), number(p, UPLOAD), number(p, ACTIVE));
    }

    private static long number(Properties p, String key) {
        String value = p.getProperty(key, "0").trim();
        try {
            long n = Long.parseLong(value);
            if (n < 0) throw new IllegalArgumentException(key + " is negative: " + value);
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number: " + value);
        }
    }

    /** What is kept: payload bytes down and up, and seconds with the engine running. */
    public record Totals(long download, long upload, long activeSeconds) {

        public static final Totals ZERO = new Totals(0, 0, 0);

        /** These totals with a session's counts added. */
        public Totals plus(long download, long upload, long activeSeconds) {
            return new Totals(this.download + download, this.upload + upload, this.activeSeconds + activeSeconds);
        }

        public Totals plus(Totals other) {
            return plus(other.download, other.upload, other.activeSeconds);
        }
    }
}
