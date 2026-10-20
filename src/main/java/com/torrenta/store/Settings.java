package com.torrenta.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User preferences, stored as a plain properties file.
 *
 * <p>Properties rather than JSON because it needs no dependency and because a settings file is one
 * someone will reasonably open in a text editor. Every getter falls back to a default, so a
 * hand-edited file with a typo degrades to the default for that one key instead of failing to load.
 * Values are checked on read as well as on write: the file is meant to be hand-edited.
 */
public final class Settings {

    private static final Logger LOG = Logger.getLogger(Settings.class.getName());

    public static final String FILE_NAME = "settings.properties";
    public static final String DOWNLOAD_DIR = "download.dir";
    /** The transfer list's columns: order, widths, visibility. See {@code ui.ColumnLayout}. */
    public static final String TABLE_COLUMNS = "table.columns";
    /** The detail pane: shown, which tab, and the divider position (0..1 of the window). */
    public static final String DETAIL_VISIBLE = "detail.visible";

    public static final String DETAIL_TAB = "detail.tab";
    public static final String DETAIL_DIVIDER = "detail.divider";
    /** Ask GitHub for a newer release after startup, at most once a day. */
    public static final String UPDATE_CHECK = "updates.check";

    public static final String LAST_UPDATE_CHECK = "updates.lastCheckEpochMs";
    /** The version the user chose to skip; a manual check still reports it. */
    public static final String DISMISSED_UPDATE = "updates.dismissedVersion";

    private final Path file;
    private final Properties properties = new Properties();

    public Settings(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** The platform's Downloads folder, which is where every other client puts things too. */
    public static Path defaultDownloadDir() {
        return Path.of(System.getProperty("user.home"), "Downloads");
    }

    /** Missing or unreadable file: defaults, and a line in the log. Never an exception. */
    public void load() {
        properties.clear();
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + file + "; using defaults", e);
            properties.clear();
        }
    }

    /** Written beside the file and moved into place, so a crash mid-write never truncates it. */
    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "settings", ".part");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, "Torrenta settings. Edited by the app; hand edits are honoured.");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** A raw value, for keys whose shape another class owns. */
    public String get(String key, String fallback) {
        return properties.getProperty(key, fallback);
    }

    public void set(String key, String value) {
        properties.setProperty(key, value);
    }

    /** For callers that have nowhere to report a failed write but the log: a UI change listener. */
    public void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
        }
    }

    public boolean updateCheck() {
        return !"false"
                .equalsIgnoreCase(properties.getProperty(UPDATE_CHECK, "true").trim());
    }

    public long lastUpdateCheck() {
        try {
            return Long.parseLong(properties.getProperty(LAST_UPDATE_CHECK, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setLastUpdateCheck(long epochMs) {
        properties.setProperty(LAST_UPDATE_CHECK, String.valueOf(epochMs));
    }

    public String dismissedUpdate() {
        return properties.getProperty(DISMISSED_UPDATE, "");
    }

    public void setDismissedUpdate(String version) {
        properties.setProperty(DISMISSED_UPDATE, version == null ? "" : version);
    }

    public Path downloadDir() {
        String value = properties.getProperty(DOWNLOAD_DIR, "");
        if (value.isBlank()) return defaultDownloadDir();
        try {
            return Path.of(value).toAbsolutePath();
        } catch (RuntimeException e) {
            LOG.warning(DOWNLOAD_DIR + " is not a path: " + value + "; using the default");
            return defaultDownloadDir();
        }
    }

    public void downloadDir(Path dir) {
        properties.setProperty(DOWNLOAD_DIR, dir.toAbsolutePath().toString());
    }
}
