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

import com.torrenta.automation.AutomationSettings;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.webapi.WebApiConfig;

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
    /** Downloads start here and move to their save path when complete; the folder is this machine's. */
    public static final String INCOMPLETE_ENABLED = "download.incompleteEnabled";

    public static final String INCOMPLETE_DIR = "download.incompleteDir";
    /** Incomplete files carry {@code .part} until each is done. */
    public static final String PART_SUFFIX = "download.partSuffix";
    /** Every file allocated in full when the torrent starts. */
    public static final String PREALLOCATE = "download.preallocate";

    /** The curl that downloads links that are not torrents; empty for the one on the PATH. This machine's. */
    public static final String DIRECT_CURL = "download.curl";
    /** Downloads from links running at once; the rest wait their turn. */
    public static final String DIRECT_ACTIVE = "download.linksAtOnce";

    public static final String[] DOWNLOAD_KEYS = {
        DOWNLOAD_DIR, INCOMPLETE_ENABLED, INCOMPLETE_DIR, PART_SUFFIX, PREALLOCATE, DIRECT_CURL, DIRECT_ACTIVE
    };
    /** The transfer list's columns: order, widths, visibility. See {@code ui.ColumnLayout}. */
    public static final String TABLE_COLUMNS = "table.columns";
    /** The same for comfortable rows, which want different columns: each density keeps its own. */
    public static final String TABLE_COLUMNS_COMFORTABLE = "table.columns.comfortable";
    /** Row density, {@code comfortable} or {@code compact}; the UI maps it and falls back. */
    public static final String UI_DENSITY = "ui.density";
    /**
     * The interface language: {@code system} (the default, follow the operating system) or one of
     * the codes {@code com.torrenta.i18n.Messages} bundles; applied at start, so a change relabels
     * on restart. A preference, so it syncs.
     */
    public static final String UI_LANGUAGE = "ui.language";
    /** The Search tab's results columns, the same shape. */
    public static final String SEARCH_COLUMNS = "search.columns";
    /** The declarative search providers switched off, by id, space-separated; syncs. */
    public static final String SEARCH_DISABLED = "search.disabled";
    /** The detail pane: shown, which tab, and the divider position (0..1 of the window). */
    public static final String DETAIL_VISIBLE = "detail.visible";

    public static final String DETAIL_TAB = "detail.tab";
    public static final String DETAIL_DIVIDER = "detail.divider";
    /**
     * The main window's normal bounds ({@code x,y,width,height}) and whether it was maximised over
     * them; this machine's screens, so never synced.
     */
    public static final String WINDOW_BOUNDS = "ui.window.bounds";

    public static final String WINDOW_MAXIMIZED = "ui.window.maximized";
    /** Where the detail pane shows, {@code right} (the default) or {@code bottom}; kept while it is collapsed. */
    public static final String DETAIL_SIDE = "detail.side";
    /** The divider while the pane is on the right: the list's share of the width. */
    public static final String DETAIL_DIVIDER_RIGHT = "detail.dividerRight";
    /** The speed graphs' span in seconds: 60, 600, 3600 or 86400; shared by the Statistics window and the Speed tab. */
    public static final String STATS_WINDOW = "stats.window";
    /** Ask GitHub for a newer release after startup, at most once a day. */
    public static final String UPDATE_CHECK = "updates.check";
    /** Settings sync: machine-local, never synced themselves (see {@code SyncKeys}). */
    public static final String SYNC_ENABLED = "sync.enabled";

    public static final String SYNC_URL = "sync.url";
    public static final String SYNC_BRANCH = "sync.branch";
    public static final String SYNC_INTERVAL = "sync.intervalMinutes";
    public static final String SYNC_LAST = "sync.lastEpochMs";
    /**
     * Remote access, qBittorrent's WebUI API ({@code com.torrenta.webapi}): machine-local, never
     * synced, the password kept only as its PBKDF2 hash.
     */
    public static final String WEBAPI_ENABLED = "webapi.enabled";

    public static final String WEBAPI_ADDRESS = "webapi.address";
    public static final String WEBAPI_PORT = "webapi.port";
    public static final String WEBAPI_USERNAME = "webapi.username";
    public static final String WEBAPI_PASSWORD = "webapi.password";
    public static final String WEBAPI_HTTPS_FILE = "webapi.httpsFile";
    public static final String WEBAPI_HTTPS_PASSWORD = "webapi.httpsPassword";
    /** Names besides addresses and {@code localhost} the {@code Host} header may carry, by spaces. */
    public static final String WEBAPI_DOMAINS = "webapi.domains";
    /**
     * Folders besides the download folder and the categories' where a client may put files, one per
     * line: {@code savepath} outside all of them is refused.
     */
    public static final String WEBAPI_ROOTS = "webapi.roots";
    /** A web UI written for qBittorrent's API (VueTorrent, say), served at {@code /}; empty for none. */
    public static final String WEBAPI_ROOT_FOLDER = "webapi.rootFolder";
    /** Whether the icon toolbar under the menu bar shows; View → Toolbar flips it. */
    public static final String UI_TOOLBAR = "ui.toolbar";
    /** Whether the sidebar of filters shows beside the list; View → Sidebar flips it. */
    public static final String UI_SIDEBAR = "ui.sidebar";
    /**
     * The sidebar's side, {@code left} or {@code right}; kept while it is hidden. Not under
     * {@code ui.sidebar.}, which is the sidebar's choices and never syncs: this is a preference.
     */
    public static final String UI_SIDEBAR_SIDE = "ui.sidebarSide";
    /**
     * The sidebar's choices, one key per section ({@code ui.sidebar.status}, {@code .category},
     * {@code .tag}, {@code .tracker}), each as {@code ui.SidebarFilter} formats it; this machine's.
     */
    public static final String UI_SIDEBAR_PREFIX = "ui.sidebar.";
    /** The sections the sidebar has folded, comma-separated axis names. */
    public static final String UI_SIDEBAR_COLLAPSED = "ui.sidebar.collapsed";
    /** The sidebar's width in px, as its edge was last dragged; this machine's, like its choices. */
    public static final String UI_SIDEBAR_WIDTH = "ui.sidebar.width";
    /** The sections a choice or a fold can name. */
    public static final java.util.List<String> SIDEBAR_AXES =
            java.util.List.of("view", "status", "category", "tag", "tracker");
    /** Rows coloured by state (glyph, sentence, progress bar); off leaves the glyphs uncoloured. */
    public static final String UI_STATE_COLORS = "ui.stateColors";
    /** The theme id ({@code editora-dark}, {@code nord-light}, …); the UI maps it and falls back. */
    public static final String THEME = "ui.theme";
    /**
     * The menu bar drawn in the window's own title bar ({@code StageStyle.EXTENDED}); off by default,
     * read once when the window is built, so it applies on restart. {@code ui.ExtendedWindow} says
     * where it is allowed.
     */
    public static final String UI_EXTENDED_WINDOW = "ui.extendedWindow";
    /** A tray (menu-bar) icon with the rates and a menu; on by default, where the desktop has one. */
    public static final String UI_TRAY = "ui.tray";
    /**
     * The palette's chord as the first releases chose it, {@code alt-x} or {@code shortcut-shift-p}.
     * Read only as {@code keys.palette} when that line is absent (see {@link #keyBindings()}), and
     * dropped by the first {@link #replaceKeyBindings}.
     */
    public static final String UI_PALETTE_KEY = "ui.paletteKey";
    /**
     * The user's key bindings, one line per command: {@code keys.recheck=Shortcut+Shift+K}, in
     * {@code KeyCombination} notation. Termina's format; see {@link #keyBindings()}.
     */
    public static final String KEYS_PREFIX = "keys.";
    /** A binding's value meaning "no chord at all", as against blank or absent, which is the default. */
    public static final String UNBOUND = "none";
    /** Closing the window hides it behind the tray icon and keeps the torrents going; on by default. */
    public static final String UI_CLOSE_TO_TRAY = "ui.closeToTray";
    /** Register as the handler for .torrent files and magnet links at start; on by default. This machine's. */
    public static final String OS_HANDLER = "os.handler";
    /** The dock or launcher badge with the download activity; on by default. This machine's. */
    public static final String OS_BADGE = "os.badge";

    /**
     * Which engine profile to use at the next start: {@code local} (the default, libtorrent in
     * this process) or the id of a remote daemon in {@code profiles.json}. This machine's, never
     * synced: the daemon is reached from here.
     */
    public static final String ENGINE_PROFILE = "engine.profile";

    public static final String LAST_UPDATE_CHECK = "updates.lastCheckEpochMs";
    /** The version the user chose to skip; a manual check still reports it. */
    public static final String DISMISSED_UPDATE = "updates.dismissedVersion";
    /** Connection: what EngineSettings carries. Clamped on read; the file is hand-editable. */
    public static final String CONN_PORT = "connection.port";

    public static final String CONN_RANDOM_PORT = "connection.randomPort";
    public static final String CONN_INTERFACE = "connection.interface";

    /** Opt-in: ask an address-echo server where traffic through the binding and around it leaves. */
    public static final String CONN_EXIT_CHECK = "connection.exitCheck";

    /** The address-echo server's URL; {@code net.ExitCheck.DEFAULT_URL} when unset. */
    public static final String CONN_EXIT_CHECK_URL = "connection.exitCheckUrl";

    /** Pause the running torrents while the leak check says Exposed; resume them when protected. */
    public static final String CONN_PAUSE_WHEN_EXPOSED = "connection.pauseWhenExposed";

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

    /** What being on battery does: {@code NONE}, {@code ALTERNATIVE} or {@code PAUSE} ({@code session.SpeedMode.Rule}). */
    public static final String SPEED_ON_BATTERY = "speed.onBattery";

    /** What a metered network does: as {@link #SPEED_ON_BATTERY}. */
    public static final String SPEED_ON_METERED = "speed.onMetered";

    /**
     * The automatic decision last applied to {@link #SPEED_ALT_ON} ({@code session.SpeedMode}), so
     * the switch is flipped only when that decision changes; this machine's.
     */
    public static final String SPEED_AUTO_WAS = "speed.autoWas";

    /** A condition's pause holds the torrents now; this machine's. */
    public static final String SPEED_CONDITION_PAUSING = "speed.conditionPausing";

    /** The torrents a condition's pause holds, comma-separated ids, to resume exactly those; this machine's. */
    public static final String SPEED_CONDITION_HELD = "speed.conditionHeld";

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
        SPEED_COUNT_OVERHEAD,
        SPEED_ON_BATTERY,
        SPEED_ON_METERED
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

    /** {@code torrent.<info-hash>.pickFiles}: added before its file list, the files still to choose. */
    private static final String PICK_FILES_SUFFIX = ".pickFiles";

    /**
     * {@code torrent.<info-hash>.unsafeContent}: a magnet added without a dialog, its files to be
     * checked once they are known; the value is {@code ui.UnsafeContent.Pending}'s.
     */
    private static final String UNSAFE_PENDING_SUFFIX = ".unsafeContent";

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
        PROXY_PASSWORD, PROXY_PEERS, CONN_EXIT_CHECK, CONN_EXIT_CHECK_URL, CONN_PAUSE_WHEN_EXPOSED
    };

    /** The Add dialog's starting positions for its checkboxes. */
    public static final String ADD_START_PAUSED = "add.startPaused";

    public static final String ADD_SEQUENTIAL = "add.sequential";
    public static final String ADD_CONTENT_LAYOUT = "add.contentLayout";

    /** Inputs are added with the defaults above and the Add dialog does not open. Off by default. */
    public static final String ADD_WITHOUT_ASKING = "add.withoutAsking";

    /**
     * What an add without a dialog does with files the content check flags: {@code ADD},
     * {@code SKIP} (the default) or {@code REFUSE}; read through {@code ui.UnsafeContent.byName}.
     */
    public static final String ADD_UNSAFE_CONTENT = "add.unsafeContent";

    /**
     * A new torrent's files already on disk in another torrent: {@code OFF}, {@code LINK},
     * {@code ASK} (the default) or {@code COPY}; read through {@code engine.DedupMode.byName}.
     */
    public static final String ADD_DEDUP = "add.dedup";

    /**
     * The Add dialog looks at a private torrent's swarm too. Off by default: a private tracker
     * counts every announce, and the probe announces before the person has added the torrent.
     */
    public static final String ADD_PROBE_PRIVATE = "add.probePrivate";

    /** Where the Create Torrent dialog last saved a {@code .torrent}; a folder of this machine, never synced. */
    public static final String CREATE_LAST_DIR = "create.lastDir";

    /**
     * The trackers the Create Torrent dialog starts with: the ones it last created with, as typed
     * (one per line, a blank line between tiers); unset, the Protocol page's default trackers.
     */
    public static final String CREATE_DEFAULT_TRACKERS = "create.defaultTrackers";

    /**
     * The command lines run on an event, with placeholders ({@code EventCommand}); blank for none.
     * They name programs on this machine, so the {@code automation.} prefix never syncs.
     */
    public static final String AUTOMATION_ON_ADDED = "automation.onAdded";

    public static final String AUTOMATION_ON_FINISHED = "automation.onFinished";
    public static final String AUTOMATION_ON_SEEDING_DONE = "automation.onSeedingDone";
    /** Desktop notifications, one switch per event; only {@code notify.finished} is on by default. */
    public static final String NOTIFY_ADDED = "notify.added";

    public static final String NOTIFY_FINISHED = "notify.finished";
    public static final String NOTIFY_SEEDING_DONE = "notify.seedingDone";
    public static final String NOTIFY_ERROR = "notify.error";

    /** Minutes between feed refreshes, 5 to 1440, 30 by default; syncs (the feeds and articles do not). */
    public static final String RSS_REFRESH_MINUTES = "rss.refreshMinutes";
    /** Whether new articles' torrents are added without asking; off, and unused until the rules exist. */
    public static final String RSS_AUTO_DOWNLOAD = "rss.autoDownload";
    /** The RSS tab's article columns, the same format as {@link #TABLE_COLUMNS}. */
    public static final String RSS_COLUMNS = "rss.columns";

    public static final String[] RSS_KEYS = {RSS_REFRESH_MINUTES, RSS_AUTO_DOWNLOAD};

    public static final String[] AUTOMATION_KEYS = {
        AUTOMATION_ON_ADDED,
        AUTOMATION_ON_FINISHED,
        AUTOMATION_ON_SEEDING_DONE,
        NOTIFY_ADDED,
        NOTIFY_FINISHED,
        NOTIFY_SEEDING_DONE,
        NOTIFY_ERROR
    };

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

    /** Remote access as set; off, on {@code 127.0.0.1:8080}, until someone changes it. */
    public WebApiConfig webApi() {
        String https = properties.getProperty(WEBAPI_HTTPS_FILE, "").trim();
        java.util.Set<String> domains = new java.util.LinkedHashSet<>();
        for (String d : properties.getProperty(WEBAPI_DOMAINS, "").trim().split("[\\s,;]+"))
            if (!d.isEmpty()) domains.add(d.toLowerCase(java.util.Locale.ROOT));
        return new WebApiConfig(
                readBoolean(WEBAPI_ENABLED, false),
                properties
                        .getProperty(WEBAPI_ADDRESS, WebApiConfig.DEFAULT_ADDRESS)
                        .trim(),
                readInt(WEBAPI_PORT, WebApiConfig.DEFAULT_PORT, 1, 65535),
                properties.getProperty(WEBAPI_USERNAME, WebApiConfig.DEFAULT_USERNAME),
                properties.getProperty(WEBAPI_PASSWORD, "").trim(),
                https.isEmpty() ? null : Path.of(https),
                properties.getProperty(WEBAPI_HTTPS_PASSWORD, ""),
                domains);
    }

    public void setWebApiEnabled(boolean enabled) {
        properties.setProperty(WEBAPI_ENABLED, String.valueOf(enabled));
    }

    public void setWebApiAddress(String address) {
        properties.setProperty(WEBAPI_ADDRESS, address == null ? "" : address.trim());
    }

    public void setWebApiPort(int port) {
        properties.setProperty(WEBAPI_PORT, String.valueOf(port));
    }

    public void setWebApiUsername(String username) {
        properties.setProperty(WEBAPI_USERNAME, username == null ? "" : username);
    }

    /** {@code webapi.Passwords.hash}'s text, never a password. */
    public void setWebApiPasswordHash(String hash) {
        properties.setProperty(WEBAPI_PASSWORD, hash == null ? "" : hash);
    }

    /** A PKCS#12 file and its password; a blank file is plain HTTP. */
    public void setWebApiHttps(String file, String password) {
        properties.setProperty(WEBAPI_HTTPS_FILE, file == null ? "" : file.trim());
        properties.setProperty(WEBAPI_HTTPS_PASSWORD, password == null ? "" : password);
    }

    public void setWebApiDomains(String domains) {
        properties.setProperty(WEBAPI_DOMAINS, domains == null ? "" : domains.trim());
    }

    /** The folders added by hand, as written; a line that is not a path is left out. */
    public List<Path> webApiRoots() {
        List<Path> out = new java.util.ArrayList<>();
        for (String line : properties.getProperty(WEBAPI_ROOTS, "").split("\\R")) {
            if (line.isBlank()) continue;
            try {
                out.add(Path.of(line.trim()));
            } catch (java.nio.file.InvalidPathException e) {
                // Typed by hand; one bad line does not take the others with it.
            }
        }
        return out;
    }

    public java.util.Optional<Path> webApiRootFolder() {
        String value = properties.getProperty(WEBAPI_ROOT_FOLDER, "").trim();
        if (value.isEmpty()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Path.of(value));
        } catch (java.nio.file.InvalidPathException e) {
            return java.util.Optional.empty();
        }
    }

    public void setWebApiRootFolder(String folder) {
        properties.setProperty(WEBAPI_ROOT_FOLDER, folder == null ? "" : folder.trim());
    }

    public void setWebApiRoots(String lines) {
        properties.setProperty(WEBAPI_ROOTS, lines == null ? "" : lines.strip());
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

    /** The speed graphs' window: the step at or above what the file says, ten minutes by default. */
    public int statsWindowSeconds() {
        int w = readInt(STATS_WINDOW, 600, 1, 86400);
        if (w <= 60) return 60;
        if (w <= 600) return 600;
        if (w <= 3600) return 3600;
        return 86400;
    }

    public void setStatsWindowSeconds(int seconds) {
        properties.setProperty(STATS_WINDOW, String.valueOf(seconds));
    }

    /** Minutes between feed refreshes; 5 to 1440, 30 by default. */
    public int rssRefreshMinutes() {
        return readInt(RSS_REFRESH_MINUTES, 30, 5, 1440);
    }

    public void setRssRefreshMinutes(int minutes) {
        properties.setProperty(RSS_REFRESH_MINUTES, String.valueOf(Math.clamp(minutes, 5, 1440)));
    }

    /** Off until the auto-download rules exist; the key is kept for them. */
    public boolean rssAutoDownload() {
        return readBoolean(RSS_AUTO_DOWNLOAD, false);
    }

    public void setRssAutoDownload(boolean on) {
        properties.setProperty(RSS_AUTO_DOWNLOAD, String.valueOf(on));
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

    /** The row density as written, trimmed; empty when unset. */
    public String density() {
        return properties.getProperty(UI_DENSITY, "").trim();
    }

    public void setDensity(String id) {
        properties.setProperty(UI_DENSITY, id);
    }

    /** The interface language as written, trimmed; {@code system} when unset. */
    public String uiLanguage() {
        String value = properties.getProperty(UI_LANGUAGE, "system").trim();
        return value.isEmpty() ? "system" : value;
    }

    public void setUiLanguage(String code) {
        properties.setProperty(UI_LANGUAGE, code == null || code.isBlank() ? "system" : code.trim());
    }

    public boolean stateColors() {
        return readBoolean(UI_STATE_COLORS, true);
    }

    public void setStateColors(boolean on) {
        properties.setProperty(UI_STATE_COLORS, String.valueOf(on));
    }

    /**
     * The user's key bindings, by command id with the prefix stripped: {@code recheck ->
     * Shortcut+Shift+K}, or {@code recheck -> none} for a command the user has unbound. A command
     * that is absent, or whose line is blank, keeps its default.
     *
     * <p>Strings rather than chords, because this class knows nothing of the toolkit. Nothing is
     * validated here: the window resolves the whole table at once ({@code ui.Keybindings}), and a
     * line that does not parse, names no command, or takes a chord another line already took is
     * dropped there, clamped on read like every other setting.
     *
     * <p>{@code ui.paletteKey=shortcut-shift-p}, the first releases' palette choice, reads as {@code
     * palette -> Shortcut+Shift+P} while there is no {@code keys.palette} line.
     */
    public Map<String, String> keyBindings() {
        Map<String, String> bindings = new java.util.TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(KEYS_PREFIX) || name.length() == KEYS_PREFIX.length()) continue;
            String value = properties.getProperty(name).trim();
            if (!value.isEmpty()) bindings.put(name.substring(KEYS_PREFIX.length()), value);
        }
        if (!bindings.containsKey("palette")
                && "shortcut-shift-p"
                        .equalsIgnoreCase(
                                properties.getProperty(UI_PALETTE_KEY, "").trim())) {
            bindings.put("palette", "Shortcut+Shift+P");
        }
        return bindings;
    }

    /**
     * Replaces every binding at once, so a rebind that takes a chord from another command is one
     * change: the window never applies a table with the chord on both. Not saved here. The old
     * palette choice goes with the rest, since the table now says what the palette is on.
     */
    public void replaceKeyBindings(Map<String, String> bindings) {
        for (String name : List.copyOf(properties.stringPropertyNames())) {
            if (name.startsWith(KEYS_PREFIX)) properties.remove(name);
        }
        properties.remove(UI_PALETTE_KEY);
        if (bindings == null) return;
        new java.util.TreeMap<>(bindings).forEach((id, chord) -> {
            if (id != null && !id.isBlank() && chord != null && !chord.isBlank()) {
                properties.setProperty(KEYS_PREFIX + id.trim(), chord.trim());
            }
        });
    }

    public boolean sidebar() {
        return readBoolean(UI_SIDEBAR, true);
    }

    /** The profile id for the next start; {@code local} when none is set. */
    public String engineProfile() {
        String id = properties.getProperty(ENGINE_PROFILE, "").trim();
        return id.isEmpty() ? com.torrenta.engine.EngineProfile.LOCAL_ID : id;
    }

    public void setEngineProfile(String id) {
        if (id == null || id.isBlank() || id.equals(com.torrenta.engine.EngineProfile.LOCAL_ID)) {
            properties.remove(ENGINE_PROFILE);
        } else {
            properties.setProperty(ENGINE_PROFILE, id.trim());
        }
    }

    public boolean extendedWindow() {
        return readBoolean(UI_EXTENDED_WINDOW, false);
    }

    public void setExtendedWindow(boolean on) {
        properties.setProperty(UI_EXTENDED_WINDOW, String.valueOf(on));
    }

    public boolean tray() {
        return readBoolean(UI_TRAY, true);
    }

    public void setTray(boolean on) {
        properties.setProperty(UI_TRAY, String.valueOf(on));
    }

    public boolean closeToTray() {
        return readBoolean(UI_CLOSE_TO_TRAY, true);
    }

    public void setCloseToTray(boolean on) {
        properties.setProperty(UI_CLOSE_TO_TRAY, String.valueOf(on));
    }

    public boolean osHandler() {
        return readBoolean(OS_HANDLER, true);
    }

    public void setOsHandler(boolean on) {
        properties.setProperty(OS_HANDLER, String.valueOf(on));
    }

    public boolean osBadge() {
        return readBoolean(OS_BADGE, true);
    }

    public void setOsBadge(boolean on) {
        properties.setProperty(OS_BADGE, String.valueOf(on));
    }

    public void setSidebar(boolean shown) {
        properties.setProperty(UI_SIDEBAR, String.valueOf(shown));
    }

    /** The sidebar's side as written, trimmed; empty when unset. The UI maps it and falls back. */
    /** The main window's last normal bounds as stored, or empty for none. */
    public String windowBounds() {
        return properties.getProperty(WINDOW_BOUNDS, "").trim();
    }

    public boolean windowMaximized() {
        return readBoolean(WINDOW_MAXIMIZED, false);
    }

    public void setWindow(String bounds, boolean maximized) {
        properties.setProperty(WINDOW_BOUNDS, bounds);
        properties.setProperty(WINDOW_MAXIMIZED, String.valueOf(maximized));
    }

    public String sidebarSide() {
        return properties.getProperty(UI_SIDEBAR_SIDE, "").trim();
    }

    public void setSidebarSide(String side) {
        properties.setProperty(UI_SIDEBAR_SIDE, side);
    }

    public boolean detailVisible() {
        return readBoolean(DETAIL_VISIBLE, true);
    }

    public void setDetailVisible(boolean shown) {
        properties.setProperty(DETAIL_VISIBLE, String.valueOf(shown));
    }

    /** The detail pane's place as written, trimmed; empty when unset. The UI maps it and falls back. */
    public String detailSide() {
        return properties.getProperty(DETAIL_SIDE, "").trim();
    }

    public void setDetailSide(String side) {
        properties.setProperty(DETAIL_SIDE, side);
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

    /** The sidebar's width as saved, or {@code fallback} when unset or unreadable; the UI clamps it. */
    public double sidebarWidth(double fallback) {
        return readDouble(UI_SIDEBAR_WIDTH, fallback, 0, 10_000);
    }

    public void setSidebarWidth(double width) {
        properties.setProperty(UI_SIDEBAR_WIDTH, String.format(java.util.Locale.ROOT, "%.0f", width));
    }

    private static String checkAxis(String axis) {
        if (!SIDEBAR_AXES.contains(axis)) throw new IllegalArgumentException("Not a sidebar axis: " + axis);
        return axis;
    }

    public void setAddStartPaused(boolean on) {
        properties.setProperty(ADD_START_PAUSED, String.valueOf(on));
    }

    /** The folder the last created {@code .torrent} went to, as written; empty when none yet. */
    public String createLastDir() {
        return properties.getProperty(CREATE_LAST_DIR, "").trim();
    }

    public void setCreateLastDir(Path dir) {
        properties.setProperty(CREATE_LAST_DIR, dir.toString());
    }

    /**
     * The Create Torrent dialog's starting trackers, as its text area shows them: the ones last
     * created with, else the Protocol page's default trackers on one tier, else empty.
     */
    public String createDefaultTrackers() {
        String own = properties.getProperty(CREATE_DEFAULT_TRACKERS, "");
        if (!own.isBlank()) return own;
        return String.join("\n", engineSettings().protocol().defaultTrackers());
    }

    public void setCreateDefaultTrackers(String text) {
        properties.setProperty(CREATE_DEFAULT_TRACKERS, text == null ? "" : text);
    }

    public boolean addWithoutAsking() {
        return readBoolean(ADD_WITHOUT_ASKING, false);
    }

    public void setAddWithoutAsking(boolean on) {
        properties.setProperty(ADD_WITHOUT_ASKING, String.valueOf(on));
    }

    /**
     * Whether a torrent was added from the Add dialog before its file list arrived, with its files
     * still to be chosen. Not saved here.
     */
    public boolean filesToPick(String torrentId) {
        return readBoolean(TORRENT_PREFIX + torrentId + PICK_FILES_SUFFIX, false);
    }

    /** The saved views, in their order ({@link SavedView#read}). */
    public List<SavedView> savedViews() {
        Map<String, String> keys = new java.util.HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(SavedView.PREFIX) || key.equals(SavedView.ORDER))
                keys.put(key, properties.getProperty(key));
        }
        return SavedView.read(keys);
    }

    /** Replaces the saved views: their keys written, a view's keys no longer there removed. */
    public void setSavedViews(List<SavedView> views) {
        for (String key : List.copyOf(properties.stringPropertyNames()))
            if (key.startsWith(SavedView.PREFIX)) properties.remove(key);
        properties.putAll(SavedView.keys(views));
    }

    public void setFilesToPick(String torrentId, boolean pending) {
        String key = TORRENT_PREFIX + torrentId + PICK_FILES_SUFFIX;
        if (pending) properties.setProperty(key, "true");
        else properties.remove(key);
    }

    public boolean exitCheck() {
        return readBoolean(CONN_EXIT_CHECK, false);
    }

    /** The URL as set; empty means the offered one ({@code net.ExitCheck.DEFAULT_URL}). */
    public String exitCheckUrl() {
        return properties.getProperty(CONN_EXIT_CHECK_URL, "").trim();
    }

    public boolean pauseWhenExposed() {
        return readBoolean(CONN_PAUSE_WHEN_EXPOSED, false);
    }

    public String addUnsafeContent() {
        return properties.getProperty(ADD_UNSAFE_CONTENT, "SKIP").trim();
    }

    public void setAddUnsafeContent(String value) {
        properties.setProperty(ADD_UNSAFE_CONTENT, value);
    }

    public String addDedup() {
        return properties.getProperty(ADD_DEDUP, "ASK").trim();
    }

    public void setAddDedup(String value) {
        properties.setProperty(ADD_DEDUP, value);
    }

    public boolean addProbePrivate() {
        return readBoolean(ADD_PROBE_PRIVATE, false);
    }

    public void setAddProbePrivate(boolean on) {
        properties.setProperty(ADD_PROBE_PRIVATE, String.valueOf(on));
    }

    /** Every torrent still waiting for its content check, by id, with the mark's value. */
    public java.util.Map<String, String> unsafeContentPending() {
        java.util.Map<String, String> pending = new java.util.HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(TORRENT_PREFIX) && key.endsWith(UNSAFE_PENDING_SUFFIX)) {
                String id = key.substring(TORRENT_PREFIX.length(), key.length() - UNSAFE_PENDING_SUFFIX.length());
                pending.put(id, properties.getProperty(key));
            }
        }
        return pending;
    }

    /** Marks a torrent for its content check once its files are known; null clears the mark. */
    public void setUnsafeContentPending(String torrentId, String value) {
        String key = TORRENT_PREFIX + torrentId + UNSAFE_PENDING_SUFFIX;
        if (value != null) properties.setProperty(key, value);
        else properties.remove(key);
    }

    public boolean addSequential() {
        return readBoolean(ADD_SEQUENTIAL, false);
    }

    public void setAddSequential(boolean on) {
        properties.setProperty(ADD_SEQUENTIAL, String.valueOf(on));
    }

    /** How a new torrent's files sit under the save path; the torrent's own unless set. */
    public com.torrenta.engine.FileRenames.Layout addContentLayout() {
        return com.torrenta.engine.FileRenames.Layout.byName(properties.getProperty(ADD_CONTENT_LAYOUT));
    }

    public void setAddContentLayout(com.torrenta.engine.FileRenames.Layout layout) {
        properties.setProperty(ADD_CONTENT_LAYOUT, layout.name());
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

    /** The ids of the definition-file providers switched off in Settings → Search. */
    public java.util.Set<String> disabledSearchDefinitions() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String id : properties.getProperty(SEARCH_DISABLED, "").trim().split("\\s+")) {
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    public void setDisabledSearchDefinitions(java.util.Collection<String> ids) {
        String joined = String.join(" ", new java.util.TreeSet<>(ids)).trim();
        if (joined.isEmpty()) properties.remove(SEARCH_DISABLED);
        else properties.setProperty(SEARCH_DISABLED, joined);
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

    public boolean incompleteEnabled() {
        return readBoolean(INCOMPLETE_ENABLED, false);
    }

    public void setIncompleteEnabled(boolean on) {
        properties.setProperty(INCOMPLETE_ENABLED, String.valueOf(on));
    }

    /** The incomplete folder; {@code incomplete} under the download folder until set. */
    public Path incompleteDir() {
        String value = properties.getProperty(INCOMPLETE_DIR, "");
        if (value.isBlank()) return downloadDir().resolve("incomplete");
        try {
            return Path.of(value).toAbsolutePath();
        } catch (RuntimeException e) {
            LOG.warning(INCOMPLETE_DIR + " is not a path: " + value + "; using the default");
            return downloadDir().resolve("incomplete");
        }
    }

    public void setIncompleteDir(Path dir) {
        properties.setProperty(INCOMPLETE_DIR, dir.toAbsolutePath().toString());
    }

    public boolean partSuffix() {
        return readBoolean(PART_SUFFIX, false);
    }

    public void setPartSuffix(boolean on) {
        properties.setProperty(PART_SUFFIX, String.valueOf(on));
    }

    public boolean preallocate() {
        return readBoolean(PREALLOCATE, false);
    }

    public void setPreallocate(boolean on) {
        properties.setProperty(PREALLOCATE, String.valueOf(on));
    }

    /** How links that are not torrents are downloaded: the curl to run, and how many at once (1–20). */
    public com.torrenta.engine.direct.DirectDownloads.Options directOptions() {
        return new com.torrenta.engine.direct.DirectDownloads.Options(
                properties.getProperty(DIRECT_CURL, ""), readInt(DIRECT_ACTIVE, 3, 1, 20));
    }

    public void setDirectCurl(String program) {
        properties.setProperty(DIRECT_CURL, program == null ? "" : program.strip());
    }

    public void setDirectActive(int n) {
        properties.setProperty(DIRECT_ACTIVE, String.valueOf(Math.max(1, Math.min(20, n))));
    }

    /** Where a torrent saved to {@code destination} starts, by the incomplete-folder settings. */
    public com.torrenta.engine.DownloadPlan plan(Path destination) {
        return com.torrenta.engine.DownloadPlan.of(destination, incompleteEnabled(), incompleteDir());
    }

    /** The storage choices for a new torrent saved to {@code destination}. */
    public com.torrenta.engine.AddRequest.Storage storage(Path destination) {
        return com.torrenta.engine.AddRequest.Storage.of(plan(destination), partSuffix(), preallocate());
    }

    /** What runs and what notifies on which event; command lines stripped, blank for none. */
    public AutomationSettings automation() {
        AutomationSettings d = AutomationSettings.DEFAULTS;
        return new AutomationSettings(
                properties.getProperty(AUTOMATION_ON_ADDED, ""),
                properties.getProperty(AUTOMATION_ON_FINISHED, ""),
                properties.getProperty(AUTOMATION_ON_SEEDING_DONE, ""),
                readBoolean(NOTIFY_ADDED, d.notifyAdded()),
                readBoolean(NOTIFY_FINISHED, d.notifyFinished()),
                readBoolean(NOTIFY_SEEDING_DONE, d.notifySeedingDone()),
                readBoolean(NOTIFY_ERROR, d.notifyError()));
    }

    /** The command line run on an event, or empty; only the three kinds that run one have a key. */
    public String eventCommand(EngineEvent.Kind kind) {
        return automation().command(kind);
    }

    /** Sets, or with a blank line forgets, the command run on an event. Not saved here. */
    public void setEventCommand(EngineEvent.Kind kind, String line) {
        String key = commandKey(kind);
        if (line == null || line.isBlank()) properties.remove(key);
        else properties.setProperty(key, line.strip());
    }

    public boolean notifies(EngineEvent.Kind kind) {
        return automation().notifies(kind);
    }

    public void setNotifies(EngineEvent.Kind kind, boolean on) {
        properties.setProperty(notifyKey(kind), String.valueOf(on));
    }

    private static String commandKey(EngineEvent.Kind kind) {
        return switch (kind) {
            case ADDED -> AUTOMATION_ON_ADDED;
            case FINISHED -> AUTOMATION_ON_FINISHED;
            case SEEDING_DONE -> AUTOMATION_ON_SEEDING_DONE;
            default -> throw new IllegalArgumentException("no command runs on " + kind);
        };
    }

    private static String notifyKey(EngineEvent.Kind kind) {
        return switch (kind) {
            case ADDED -> NOTIFY_ADDED;
            case FINISHED -> NOTIFY_FINISHED;
            case SEEDING_DONE -> NOTIFY_SEEDING_DONE;
            case ERROR -> NOTIFY_ERROR;
            default -> throw new IllegalArgumentException("no notification for " + kind);
        };
    }
}
