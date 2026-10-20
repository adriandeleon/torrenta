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
import com.torrenta.engine.ShareLimit;

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
    /** Whether the icon toolbar under the menu bar shows; View → Toolbar flips it. */
    public static final String UI_TOOLBAR = "ui.toolbar";
    /** Whether the sidebar of filters shows beside the list; View → Sidebar flips it. */
    public static final String UI_SIDEBAR = "ui.sidebar";
    /**
     * The sidebar's choices, one key per section ({@code ui.sidebar.status}, {@code .category},
     * {@code .tag}, {@code .tracker}), each as {@code ui.SidebarFilter} formats it; this machine's.
     */
    public static final String UI_SIDEBAR_PREFIX = "ui.sidebar.";
    /** The sections the sidebar has folded, comma-separated axis names. */
    public static final String UI_SIDEBAR_COLLAPSED = "ui.sidebar.collapsed";
    /** The sections a choice or a fold can name. */
    public static final java.util.List<String> SIDEBAR_AXES = java.util.List.of("status", "category", "tag", "tracker");
    /** Rows coloured by state (glyph, sentence, progress bar); off leaves the glyphs uncoloured. */
    public static final String UI_STATE_COLORS = "ui.stateColors";
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

    /** Speed page. KiB/s, 0 for none; the schedule as HH:mm and a comma list of day names. */
    public static final String SPEED_DOWNLOAD = "speed.download";

    public static final String SPEED_UPLOAD = "speed.upload";
    public static final String SPEED_ALT_DOWNLOAD = "speed.altDownload";
    public static final String SPEED_ALT_UPLOAD = "speed.altUpload";
    public static final String SPEED_ALT_ON = "speed.altOn";
    public static final String SPEED_SCHEDULE_ON = "speed.schedule.on";
    public static final String SPEED_SCHEDULE_FROM = "speed.schedule.from";
    public static final String SPEED_SCHEDULE_TO = "speed.schedule.to";
    public static final String SPEED_SCHEDULE_DAYS = "speed.schedule.days";
    public static final String SPEED_COUNT_OVERHEAD = "speed.countOverhead";

    public static final String[] SPEED_KEYS = {
        SPEED_DOWNLOAD,
        SPEED_UPLOAD,
        SPEED_ALT_DOWNLOAD,
        SPEED_ALT_UPLOAD,
        SPEED_ALT_ON,
        SPEED_SCHEDULE_ON,
        SPEED_SCHEDULE_FROM,
        SPEED_SCHEDULE_TO,
        SPEED_SCHEDULE_DAYS,
        SPEED_COUNT_OVERHEAD
    };

    /** Queue and share limits page. Active counts are 0 for none; the ratio is a decimal, 0 for none. */
    public static final String QUEUE_ACTIVE_DOWNLOADS = "queue.activeDownloads";

    public static final String QUEUE_ACTIVE_SEEDS = "queue.activeSeeds";
    public static final String QUEUE_ACTIVE_LIMIT = "queue.activeLimit";
    public static final String QUEUE_IGNORE_SLOW = "queue.ignoreSlowTorrents";
    public static final String SHARE_RATIO = "share.ratio";
    public static final String SHARE_SEED_MINUTES = "share.seedMinutes";
    public static final String SHARE_ACTION = "share.action";

    public static final String[] QUEUE_KEYS = {
        QUEUE_ACTIVE_DOWNLOADS,
        QUEUE_ACTIVE_SEEDS,
        QUEUE_ACTIVE_LIMIT,
        QUEUE_IGNORE_SLOW,
        SHARE_RATIO,
        SHARE_SEED_MINUTES,
        SHARE_ACTION
    };

    /**
     * A torrent's own share limit, {@code torrent.<info-hash>.share}, as {@link ShareLimit#format()}
     * writes it. Machine-local (the torrents are), so {@code torrent.} never syncs.
     */
    public static final String TORRENT_PREFIX = "torrent.";

    private static final String SHARE_SUFFIX = ".share";

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

    public boolean toolbar() {
        return readBoolean(UI_TOOLBAR, true);
    }

    public void setToolbar(boolean shown) {
        properties.setProperty(UI_TOOLBAR, String.valueOf(shown));
    }

    public boolean stateColors() {
        return readBoolean(UI_STATE_COLORS, true);
    }

    public void setStateColors(boolean on) {
        properties.setProperty(UI_STATE_COLORS, String.valueOf(on));
    }

    public boolean sidebar() {
        return readBoolean(UI_SIDEBAR, true);
    }

    public void setSidebar(boolean shown) {
        properties.setProperty(UI_SIDEBAR, String.valueOf(shown));
    }

    /**
     * The sidebar's choice on one axis as written; empty when unset. The UI parses it, on that
     * axis, and falls back to All.
     *
     * @param axis one of {@link #SIDEBAR_AXES}
     */
    public String sidebarChoice(String axis) {
        return properties.getProperty(UI_SIDEBAR_PREFIX + checkAxis(axis), "").trim();
    }

    public void setSidebarChoice(String axis, String filter) {
        properties.setProperty(UI_SIDEBAR_PREFIX + checkAxis(axis), filter == null ? "" : filter.trim());
    }

    /** The folded sections, each one of {@link #SIDEBAR_AXES}; anything else in the file is dropped. */
    public java.util.Set<String> sidebarCollapsed() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : properties.getProperty(UI_SIDEBAR_COLLAPSED, "").split(",")) {
            String axis = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (SIDEBAR_AXES.contains(axis)) out.add(axis);
        }
        return out;
    }

    public void setSidebarCollapsed(java.util.Collection<String> axes) {
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String axis : SIDEBAR_AXES) if (axes.contains(axis)) kept.add(axis);
        properties.setProperty(UI_SIDEBAR_COLLAPSED, String.join(",", kept));
    }

    private static String checkAxis(String axis) {
        if (!SIDEBAR_AXES.contains(axis)) throw new IllegalArgumentException("Not a sidebar axis: " + axis);
        return axis;
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
        EngineSettings.Speed sd = EngineSettings.Speed.defaults();
        EngineSettings.Speed speed = new EngineSettings.Speed(
                readInt(SPEED_DOWNLOAD, sd.download(), 0, 10_000_000),
                readInt(SPEED_UPLOAD, sd.upload(), 0, 10_000_000),
                readInt(SPEED_ALT_DOWNLOAD, sd.alternativeDownload(), 0, 10_000_000),
                readInt(SPEED_ALT_UPLOAD, sd.alternativeUpload(), 0, 10_000_000),
                readBoolean(SPEED_ALT_ON, sd.alternativeOn()),
                readBoolean(SPEED_SCHEDULE_ON, sd.scheduleOn()),
                readMinutes(SPEED_SCHEDULE_FROM, sd.scheduleFrom()),
                readMinutes(SPEED_SCHEDULE_TO, sd.scheduleTo()),
                readDays(SPEED_SCHEDULE_DAYS, sd.scheduleDays()),
                readBoolean(SPEED_COUNT_OVERHEAD, sd.countOverhead()));
        EngineSettings.Queue qd = EngineSettings.Queue.defaults();
        EngineSettings.Queue queue = new EngineSettings.Queue(
                readInt(QUEUE_ACTIVE_DOWNLOADS, qd.activeDownloads(), 0, 10_000),
                readInt(QUEUE_ACTIVE_SEEDS, qd.activeSeeds(), 0, 10_000),
                readInt(QUEUE_ACTIVE_LIMIT, qd.activeLimit(), 0, 10_000),
                readBoolean(QUEUE_IGNORE_SLOW, qd.ignoreSlowTorrents()),
                shareLimit());
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
                protocol,
                speed,
                queue);
    }

    /** The global share limit: ratio 0 to 1000, minutes 0 to a year, an unreadable action pauses. */
    public ShareLimit shareLimit() {
        ShareLimit.Action action;
        try {
            action = ShareLimit.Action.valueOf(
                    properties.getProperty(SHARE_ACTION, "PAUSE").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            action = ShareLimit.Action.PAUSE;
        }
        return new ShareLimit(
                readDouble(SHARE_RATIO, 0, 0, 1000), readInt(SHARE_SEED_MINUTES, 0, 0, 366 * 24 * 60), action);
    }

    /** A torrent's own share limit, if it has one. */
    public java.util.Optional<ShareLimit> shareOverride(String torrentId) {
        return ShareLimit.parse(properties.getProperty(TORRENT_PREFIX + torrentId + SHARE_SUFFIX));
    }

    /** Sets, or with {@code null} forgets, a torrent's own share limit. Not saved here. */
    public void setShareOverride(String torrentId, ShareLimit limit) {
        String key = TORRENT_PREFIX + torrentId + SHARE_SUFFIX;
        if (limit == null) properties.remove(key);
        else properties.setProperty(key, limit.format());
    }

    /** Every torrent's own share limit, by id. */
    public Map<String, ShareLimit> shareOverrides() {
        Map<String, ShareLimit> out = new java.util.HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(TORRENT_PREFIX) || !key.endsWith(SHARE_SUFFIX)) continue;
            String id = key.substring(TORRENT_PREFIX.length(), key.length() - SHARE_SUFFIX.length());
            ShareLimit.parse(properties.getProperty(key)).ifPresent(limit -> out.put(id, limit));
        }
        return out;
    }

    /** A decimal between the bounds; anything unreadable or outside them is the default. */
    private double readDouble(String key, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(properties.getProperty(key, "").trim());
            return Double.isNaN(value) || value < min || value > max ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code HH:mm} as minutes after midnight; anything unreadable is the default. */
    private int readMinutes(String key, int fallback) {
        return parseMinutes(properties.getProperty(key, "")).orElse(fallback);
    }

    public static java.util.OptionalInt parseMinutes(String text) {
        if (text == null) return java.util.OptionalInt.empty();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*$").matcher(text);
        if (!m.matches()) return java.util.OptionalInt.empty();
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return java.util.OptionalInt.empty();
        return java.util.OptionalInt.of(h * 60 + min);
    }

    public static String formatMinutes(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** A comma list of day names, in any case, three letters or full; unreadable names are dropped. */
    private java.util.Set<java.time.DayOfWeek> readDays(String key, java.util.Set<java.time.DayOfWeek> fallback) {
        String text = properties.getProperty(key);
        if (text == null) return fallback;
        java.util.Set<java.time.DayOfWeek> days = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        for (String part : text.split(",")) {
            String name = part.trim().toUpperCase(java.util.Locale.ROOT);
            if (name.isEmpty()) continue;
            for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
                if (day.name().equals(name) || day.name().startsWith(name) && name.length() >= 3) days.add(day);
            }
        }
        return days;
    }

    public static String formatDays(java.util.Set<java.time.DayOfWeek> days) {
        return days.stream()
                .sorted()
                .map(d -> d.name().substring(0, 3))
                .collect(java.util.stream.Collectors.joining(","));
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
