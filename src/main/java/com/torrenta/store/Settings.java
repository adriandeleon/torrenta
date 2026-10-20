package com.torrenta.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.EngineSettings;

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
    /** Settings sync: machine-local, never synced themselves (see {@code SyncKeys}). */
    public static final String SYNC_ENABLED = "sync.enabled";

    public static final String SYNC_URL = "sync.url";
    public static final String SYNC_BRANCH = "sync.branch";
    public static final String SYNC_INTERVAL = "sync.intervalMinutes";
    public static final String SYNC_LAST = "sync.lastEpochMs";
    /** The theme id ({@code editora-dark}, {@code nord-light}, …); the UI maps it and falls back. */
    public static final String THEME = "ui.theme";

    public static final String LAST_UPDATE_CHECK = "updates.lastCheckEpochMs";
    /** The version the user chose to skip; a manual check still reports it. */
    public static final String DISMISSED_UPDATE = "updates.dismissedVersion";
    /** Connection: what EngineSettings carries. Clamped on read; the file is hand-editable. */
    public static final String CONN_PORT = "connection.port";

    public static final String CONN_RANDOM_PORT = "connection.randomPort";
    public static final String CONN_INTERFACE = "connection.interface";
    public static final String CONN_IPV6 = "connection.ipv6";
    public static final String CONN_UPNP = "connection.upnp";
    public static final String CONN_MAX_CONNECTIONS = "connection.maxConnections";
    public static final String CONN_MAX_PER_TORRENT = "connection.maxConnectionsPerTorrent";
    public static final String CONN_UPLOAD_SLOTS = "connection.uploadSlots";
    public static final String PROXY_TYPE = "proxy.type";
    public static final String PROXY_HOST = "proxy.host";
    public static final String PROXY_PORT = "proxy.port";
    public static final String PROXY_USERNAME = "proxy.username";
    /** Stored as typed, in a mode-600 file; a keychain is a later concern. */
    public static final String PROXY_PASSWORD = "proxy.password";

    public static final String PROXY_PEERS = "proxy.peers";

    /** Protocol page. Default trackers are space-separated, since a URL has no spaces. */
    public static final String PROTO_DHT = "protocol.dht";

    public static final String PROTO_PEX = "protocol.pex";
    public static final String PROTO_LSD = "protocol.lsd";
    public static final String PROTO_UTP = "protocol.utp";
    public static final String PROTO_ENCRYPTION = "protocol.encryption";
    public static final String PROTO_ANONYMOUS = "protocol.anonymous";
    public static final String PROTO_DEFAULT_TRACKERS = "protocol.defaultTrackers";

    /** IP filter page. Bans are space-separated ranges or addresses. */
    public static final String IPFILTER_ENABLED = "ipfilter.enabled";

    public static final String IPFILTER_URL = "ipfilter.url";
    public static final String IPFILTER_REFRESH_HOURS = "ipfilter.refreshHours";
    public static final String IPFILTER_FILE = "ipfilter.file";
    public static final String IPFILTER_LAST_REFRESH = "ipfilter.lastRefreshEpochMs";
    public static final String IPFILTER_BANS = "ipfilter.bans";

    public static final String[] IPFILTER_KEYS = {
        IPFILTER_ENABLED, IPFILTER_URL, IPFILTER_REFRESH_HOURS, IPFILTER_FILE, IPFILTER_LAST_REFRESH, IPFILTER_BANS
    };

    public static final String[] PROTOCOL_KEYS = {
        PROTO_DHT, PROTO_PEX, PROTO_LSD, PROTO_UTP, PROTO_ENCRYPTION, PROTO_ANONYMOUS, PROTO_DEFAULT_TRACKERS
    };

    public static final String[] CONNECTION_KEYS = {
        CONN_PORT, CONN_RANDOM_PORT, CONN_INTERFACE, CONN_IPV6, CONN_UPNP, CONN_MAX_CONNECTIONS,
        CONN_MAX_PER_TORRENT, CONN_UPLOAD_SLOTS, PROXY_TYPE, PROXY_HOST, PROXY_PORT, PROXY_USERNAME,
        PROXY_PASSWORD, PROXY_PEERS
    };

    /** The Add dialog's starting positions for its checkboxes. */
    public static final String ADD_START_PAUSED = "add.startPaused";

    public static final String ADD_SEQUENTIAL = "add.sequential";

    private final Path file;
    private final Properties properties = new Properties();
    private Runnable onSaved;

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
        Runnable listener = onSaved;
        if (listener != null) listener.run();
    }

    /** Runs after every successful save, on the saving thread: settings sync listens here. */
    public void setOnSaved(Runnable listener) {
        onSaved = listener;
    }

    /** The entries that travel between machines, sorted. */
    public Map<String, String> syncedEntries() {
        return com.torrenta.sync.SyncKeys.synced(properties);
    }

    /** Replaces the synced entries with {@code merged}; the local ones stay. Not saved here. */
    public void replaceSynced(Map<String, String> merged) {
        for (String key : List.copyOf(properties.stringPropertyNames())) {
            if (com.torrenta.sync.SyncKeys.isSynced(key)) properties.remove(key);
        }
        merged.forEach((key, value) -> {
            if (com.torrenta.sync.SyncKeys.isSynced(key) && value != null) properties.setProperty(key, value);
        });
    }

    public boolean syncEnabled() {
        return readBoolean(SYNC_ENABLED, false);
    }

    public void setSyncEnabled(boolean enabled) {
        properties.setProperty(SYNC_ENABLED, String.valueOf(enabled));
    }

    public String syncUrl() {
        return properties.getProperty(SYNC_URL, "").trim();
    }

    public void setSyncUrl(String url) {
        properties.setProperty(SYNC_URL, url == null ? "" : url.trim());
    }

    public String syncBranch() {
        String branch = properties.getProperty(SYNC_BRANCH, "").trim();
        return branch.isEmpty() ? "main" : branch;
    }

    public void setSyncBranch(String branch) {
        properties.setProperty(SYNC_BRANCH, branch == null ? "" : branch.trim());
    }

    /** Minutes between automatic syncs while the window is focused; 1 to 1440, 15 by default. */
    public int syncIntervalMinutes() {
        return readInt(SYNC_INTERVAL, 15, 1, 1440);
    }

    public void setSyncIntervalMinutes(int minutes) {
        properties.setProperty(SYNC_INTERVAL, String.valueOf(Math.clamp(minutes, 1, 1440)));
    }

    public long syncLastEpochMs() {
        try {
            return Long.parseLong(properties.getProperty(SYNC_LAST, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setSyncLastEpochMs(long epochMs) {
        properties.setProperty(SYNC_LAST, String.valueOf(epochMs));
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

    /** The theme id as written, trimmed; empty when unset. The UI decides what an unknown one means. */
    public String theme() {
        return properties.getProperty(THEME, "").trim();
    }

    public void setTheme(String id) {
        properties.setProperty(THEME, id);
    }

    public void setUpdateCheck(boolean check) {
        properties.setProperty(UPDATE_CHECK, String.valueOf(check));
    }

    public boolean addStartPaused() {
        return readBoolean(ADD_START_PAUSED, false);
    }

    public void setAddStartPaused(boolean on) {
        properties.setProperty(ADD_START_PAUSED, String.valueOf(on));
    }

    public boolean addSequential() {
        return readBoolean(ADD_SEQUENTIAL, false);
    }

    public void setAddSequential(boolean on) {
        properties.setProperty(ADD_SEQUENTIAL, String.valueOf(on));
    }

    public boolean ipFilterEnabled() {
        return readBoolean(IPFILTER_ENABLED, false);
    }

    public String ipFilterUrl() {
        return properties.getProperty(IPFILTER_URL, "").trim();
    }

    public int ipFilterRefreshHours() {
        return readInt(IPFILTER_REFRESH_HOURS, 24, 1, 720);
    }

    public String ipFilterFile() {
        return properties.getProperty(IPFILTER_FILE, "").trim();
    }

    public long ipFilterLastRefresh() {
        try {
            return Long.parseLong(
                    properties.getProperty(IPFILTER_LAST_REFRESH, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setIpFilterLastRefresh(long epochMs) {
        properties.setProperty(IPFILTER_LAST_REFRESH, String.valueOf(epochMs));
    }

    /** The manual bans, as typed: addresses, ranges or CIDRs, space-separated. */
    public java.util.List<String> ipFilterBans() {
        return java.util.Arrays.stream(
                        properties.getProperty(IPFILTER_BANS, "").trim().split("\\s+"))
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    public void setIpFilterBans(java.util.List<String> bans) {
        properties.setProperty(IPFILTER_BANS, String.join(" ", bans));
    }

    /** The connection settings as stored, each clamped to a sane range. */
    public EngineSettings engineSettings() {
        EngineSettings d = EngineSettings.defaults();
        EngineSettings.ProxyType proxy;
        try {
            proxy = EngineSettings.ProxyType.valueOf(
                    properties.getProperty(PROXY_TYPE, "NONE").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            proxy = EngineSettings.ProxyType.NONE;
        }
        EngineSettings.Protocol pd = EngineSettings.Protocol.defaults();
        EngineSettings.Protocol.Encryption encryption;
        try {
            encryption = EngineSettings.Protocol.Encryption.valueOf(properties
                    .getProperty(PROTO_ENCRYPTION, pd.encryption().name())
                    .trim()
                    .toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            encryption = pd.encryption();
        }
        java.util.List<String> trackers = java.util.Arrays.stream(properties
                        .getProperty(PROTO_DEFAULT_TRACKERS, "")
                        .trim()
                        .split("\\s+"))
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
        EngineSettings.Protocol protocol = new EngineSettings.Protocol(
                readBoolean(PROTO_DHT, pd.dht()),
                readBoolean(PROTO_PEX, pd.pex()),
                readBoolean(PROTO_LSD, pd.lsd()),
                readBoolean(PROTO_UTP, pd.utp()),
                encryption,
                readBoolean(PROTO_ANONYMOUS, pd.anonymous()),
                trackers);
        return new EngineSettings(
                readInt(CONN_PORT, d.listenPort(), 1024, 65535),
                readBoolean(CONN_RANDOM_PORT, d.randomPort()),
                properties.getProperty(CONN_INTERFACE, "").trim(),
                readBoolean(CONN_IPV6, d.ipv6()),
                readBoolean(CONN_UPNP, d.upnp()),
                readInt(CONN_MAX_CONNECTIONS, d.maxConnections(), 2, 10_000),
                readInt(CONN_MAX_PER_TORRENT, d.maxConnectionsPerTorrent(), 1, 10_000),
                readInt(CONN_UPLOAD_SLOTS, d.uploadSlots(), 1, 1_000),
                proxy,
                properties.getProperty(PROXY_HOST, "").trim(),
                readInt(PROXY_PORT, d.proxyPort(), 1, 65535),
                properties.getProperty(PROXY_USERNAME, ""),
                properties.getProperty(PROXY_PASSWORD, ""),
                readBoolean(PROXY_PEERS, d.proxyPeers()),
                protocol);
    }

    /** An int between the bounds; anything unreadable or outside them is the default. */
    private int readInt(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, "").trim());
            return value < min || value > max ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Forgets the given keys, so their getters fall back to the defaults. */
    public void reset(String... keys) {
        for (String key : keys) properties.remove(key);
    }

    /** A boolean as a person might have typed it: anything that is not {@code false} is true. */
    private boolean readBoolean(String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        return !"false".equalsIgnoreCase(value.trim());
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
