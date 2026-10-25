package com.torrenta.engine.libtorrent;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.AppInfo;
import com.torrenta.cli.CommandLine;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Connectivity;
import com.torrenta.engine.CreateRequest;
import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.IpRange;
import com.torrenta.engine.Priority;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.TorrentStorage;
import com.torrenta.engine.TrackerHost;
import com.torrenta.engine.WebSeeds;
import com.torrenta.store.StatsStore;
import com.torrenta.store.TorrentStore;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.Address;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.AnnounceEndpoint;
import org.libtorrent4j.AnnounceEntry;
import org.libtorrent4j.AnnounceInfohash;
import org.libtorrent4j.BDecodeNode;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.MoveFlags;
import org.libtorrent4j.PeerInfo;
import org.libtorrent4j.PieceIndexBitfield;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.StorageMode;
import org.libtorrent4j.TcpEndpoint;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.AddTorrentAlert;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.FileCompletedAlert;
import org.libtorrent4j.alerts.FileErrorAlert;
import org.libtorrent4j.alerts.FileRenameFailedAlert;
import org.libtorrent4j.alerts.FileRenamedAlert;
import org.libtorrent4j.alerts.IncomingConnectionAlert;
import org.libtorrent4j.alerts.ListenFailedAlert;
import org.libtorrent4j.alerts.ListenSucceededAlert;
import org.libtorrent4j.alerts.PeerConnectAlert;
import org.libtorrent4j.alerts.PeerDisconnectedAlert;
import org.libtorrent4j.alerts.PeerErrorAlert;
import org.libtorrent4j.alerts.PortmapAlert;
import org.libtorrent4j.alerts.PortmapErrorAlert;
import org.libtorrent4j.alerts.ReadPieceAlert;
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert;
import org.libtorrent4j.alerts.SessionStatsAlert;
import org.libtorrent4j.alerts.SocketType;
import org.libtorrent4j.alerts.StorageMovedAlert;
import org.libtorrent4j.alerts.StorageMovedFailedAlert;
import org.libtorrent4j.alerts.TorrentAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TrackerAlert;
import org.libtorrent4j.alerts.TrackerErrorAlert;
import org.libtorrent4j.alerts.TrackerWarningAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.address;
import org.libtorrent4j.swig.announce_entry;
import org.libtorrent4j.swig.announce_entry_vector;
import org.libtorrent4j.swig.create_file_entry;
import org.libtorrent4j.swig.create_file_entry_vector;
import org.libtorrent4j.swig.create_flags_t;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.entry;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.file_flags_t;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.info_hash_t;
import org.libtorrent4j.swig.ip_filter;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.list_files_listener;
import org.libtorrent4j.swig.load_torrent_limits;
import org.libtorrent4j.swig.peer_flags_t;
import org.libtorrent4j.swig.peer_info;
import org.libtorrent4j.swig.peer_info_vector;
import org.libtorrent4j.swig.resume_data_flags_t;
import org.libtorrent4j.swig.session_handle;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.sha1_hash;
import org.libtorrent4j.swig.sha256_hash;
import org.libtorrent4j.swig.status_flags_t;
import org.libtorrent4j.swig.tcp_endpoint;
import org.libtorrent4j.swig.tcp_endpoint_vector;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_handle_vector;

import static com.torrenta.i18n.Messages.tr;

/**
 * The engine on libtorrent, through libtorrent4j.
 *
 * <p>One {@link SessionManager}, one download directory, one {@link TorrentStore}. Torrents are
 * enumerated from the session each time rather than tracked here, so there is exactly one source of
 * truth and nothing to keep in step.
 *
 * <p>Persistence is libtorrent's resume data, one blob per torrent in the store. It is written when
 * a torrent is added (so a magnet survives a crash before its metadata arrives), when the metadata
 * arrives, when a download finishes, every couple of minutes if anything changed, and on {@link
 * #stop()}, which waits for the writes to land. Resume data is produced asynchronously — a request
 * on the handle, an alert with the answer — so the alert listener is where the store gets written,
 * on libtorrent4j's alert thread.
 *
 * <p>A torrent's category and tags are not libtorrent's: {@code add_torrent_params} has no field
 * for them, so they live in a {@code .labels} sidecar beside the resume data, written on the
 * caller's thread the moment they change, read back with the resume data at start, and held here
 * by id for the snapshots.
 *
 * <p>The incomplete folder and the {@code .part} suffix are the engine's own rules on top of
 * libtorrent: a torrent is added with the incomplete folder as its save path and its destination
 * in a {@code .storage} sidecar ({@link TorrentStorage}); when it finishes, {@code move_storage}
 * takes it to the destination, never over a file already there. The suffix goes on after the
 * check (so files that are already complete keep their names, as qBittorrent does it), comes off
 * as each file completes, and the sidecar remembers which indices carry it.
 */
public final class LibtorrentEngine implements Engine {

    private static final Logger LOG = Logger.getLogger(LibtorrentEngine.class.getName());

    private static final long PERIODIC_FLUSH_SECONDS = 120;
    private static final long STOP_FLUSH_TIMEOUT_MS = 5000;
    /** How often the all-time totals are written; a crash costs at most this much counting. */
    private static final long STATS_SAVE_SECONDS = 300;

    /** Peers a fetch hands to its torrent at most; libtorrent's own peer list does the rest. */
    private static final int MAX_HANDED_OVER = 50;

    /*
     * Every public method that reaches the session is synchronized, and so is stop(): a call in
     * flight on the window's poll thread (or the flusher, the watcher, a virtual thread running an
     * action) finishes before stop() deletes the native session, and a call that arrives after it
     * finds no session and says so. Without that, SessionManager.stop() frees the libtorrent
     * session under a handle the poll was reading, which corrupts memory and killed the JVM at
     * quit, with no error file, every few runs. The alert thread never takes this lock (the
     * listener uses only the alert's own handle), so waiting for resume-data alerts inside stop()
     * cannot deadlock.
     */

    /** Read at every use: the Settings window changes it while the session runs. */
    private final java.util.function.Supplier<Path> downloadDir;

    private final TorrentStore store;
    /** The all-time totals' file; loaded at start, written every few minutes and at stop. */
    private final StatsStore statsStore;
    /** What the stats file held at start; the session's counters are added to it, never to a later write. */
    private volatile StatsStore.Totals baseline = StatsStore.Totals.ZERO;

    private volatile long startedAtMs;
    /** The {@link Counter}s' indices into a stats alert, resolved once the native is loaded; null before start. */
    private volatile int[] counterIndex;
    /** The last stats alert's values, one per {@link Counter}, in its order; written on the alert thread. */
    private volatile long[] counters = new long[Counter.values().length];

    private SessionManager session;
    private ScheduledExecutorService flusher;

    /** The connection settings in force; the session starts with them and takes changes live. */
    private volatile EngineSettings engineSettings;
    /** Kept by the alert thread from listen, port-map, incoming-connection and DHT alerts. */
    private final AtomicReference<Connectivity> connectivity = new AtomicReference<>(Connectivity.NONE);

    /** The filter in force, kept so a start after a set applies it; empty means none. */
    private volatile List<IpRange> ipFilter = List.of();

    private final java.util.concurrent.atomic.AtomicLong blockedPeers = new java.util.concurrent.atomic.AtomicLong();

    /** Resume-data requests whose alert has not arrived yet; {@link #stop()} waits on this. */
    private final AtomicInteger pendingSaves = new AtomicInteger();

    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Ids re-added from the store at start, so their add alert reads "Restored", not "Added". */
    private final Set<String> restoring = ConcurrentHashMap.newKeySet();

    /** Magnets to pause once their metadata arrives: the dialog's "stop after metadata". */
    private final Set<String> stopAfterMetadata = ConcurrentHashMap.newKeySet();

    /**
     * Removed in this session. A resume-data answer can arrive after the torrent is gone — the
     * request was in flight — and writing it would bring the torrent back at the next start.
     */
    private final Set<String> removed = ConcurrentHashMap.newKeySet();

    /**
     * Magnets the Add dialog is fetching the metadata of, by id: in the session, but in no
     * snapshot, event or store until the person adds them for real.
     */
    private final Map<String, Fetch> fetches = new ConcurrentHashMap<>();

    /**
     * Fetches whose add alert has not arrived yet. That alert is the fetch's and must not write a
     * blob, even if the fetch was cancelled before it came; alerts arrive in order, so the next
     * add alert for the id is always the fetch's own.
     */
    private final Set<String> fetchAddAlerts = ConcurrentHashMap.newKeySet();

    /**
     * Hashes of fetches that have ended. Their torrent is torn down asynchronously and its last
     * alerts (the check that follows the metadata) can come after; they stay muted until the same
     * torrent is added for real.
     */
    private final Set<String> endedFetches = ConcurrentHashMap.newKeySet();

    /** Category and tags by id; absent means none. The sidecar in the store is the durable copy. */
    private final Map<String, TorrentLabels> labels = new ConcurrentHashMap<>();

    /** What the files are still owed, by id; absent means nothing. The sidecar is the durable copy. */
    private final Map<String, TorrentStorage> storage = new ConcurrentHashMap<>();

    /**
     * Archives being read for {@link com.torrenta.engine.ArchiveProbe}, by torrent id: the piece
     * length and a reading per file. Kept on the alert thread; a torrent leaves when its last
     * archive has been read.
     */
    private final Map<String, ArchiveReads> archiveReads = new ConcurrentHashMap<>();

    /** The archives found asking for a password, by torrent id; not kept across a restart (read again). */
    private final Map<String, Set<Integer>> encryptedArchives = new ConcurrentHashMap<>();

    /** At most this many archives are read per torrent: a set of volumes is one, and few torrents hold more. */
    private static final int ARCHIVES_PER_TORRENT = 8;

    private record ArchiveReads(int pieceLength, Map<Integer, com.torrenta.engine.ArchiveProbe.Reading> byFile) {}

    /** Moves issued and not yet answered, so a second trigger does not issue another. */
    private final Set<String> moving = ConcurrentHashMap.newKeySet();
    /**
     * The hosts of each torrent's trackers, by id, for the sidebar's Trackers section: from the
     * add parameters (a magnet's, a .torrent's, the resume data's), from any tracker alert, and
     * recomputed when trackers are added or removed through the API. Kept here so the snapshot
     * costs no call for them: the handle's tracker list is a vector copy per torrent per poll.
     */
    private final Map<String, Set<String>> trackerHosts = new ConcurrentHashMap<>();

    /** The tracker URLs that last answered a torrent with an error, by id; a reply clears one. */
    private final Map<String, Set<String>> trackerErrors = new ConcurrentHashMap<>();

    public LibtorrentEngine(Path downloadDir, TorrentStore store) {
        this(() -> downloadDir, store);
    }

    public LibtorrentEngine(java.util.function.Supplier<Path> downloadDir, TorrentStore store) {
        this(downloadDir, store, EngineSettings.defaults());
    }

    public LibtorrentEngine(
            java.util.function.Supplier<Path> downloadDir, TorrentStore store, EngineSettings settings) {
        this(downloadDir, store, settings, new StatsStore(store.dir().resolveSibling(StatsStore.FILE_NAME)));
    }

    /** @param stats where the all-time totals live: {@code stats.properties} in the config folder */
    public LibtorrentEngine(
            java.util.function.Supplier<Path> downloadDir,
            TorrentStore store,
            EngineSettings settings,
            StatsStore stats) {
        this.downloadDir = downloadDir;
        this.store = store;
        this.engineSettings = settings;
        this.statsStore = stats;
    }

    @Override
    public synchronized void start() throws EngineException {
        if (session != null) return;
        NativeLibrary.ensureAvailable();
        Path dir = downloadDir.get();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new EngineException(tr("engine.error.downloadFolder", dir), e);
        }
        SessionManager s;
        try {
            connectivity.set(Connectivity.NONE.dht(engineSettings.protocol().dht()));
            s = new SessionManager();
            // Listening before starting: the session opens its listen sockets as it starts, and a
            // listen_failed posted then was lost to a listener added afterwards (a seeder that
            // never listened, with neither answer recorded).
            s.addListener(new Listener());
            s.start(new SessionParams(toPack(engineSettings)));
        } catch (LinkageError e) {
            // The JNI static initialiser wraps a failed System.load in a LinkageError whose own
            // message is a pointer to the project page; the cause says what actually went wrong.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EngineException(tr("engine.error.load", cause.getMessage()), e);
        }
        session = s;
        // The counters' places in a stats alert are the library's to say, and asking is native.
        counterIndex = Counter.resolve();
        counters = new long[Counter.values().length];
        baseline = statsStore.load();
        startedAtMs = System.currentTimeMillis();
        if (!ipFilter.isEmpty()) s.swig().set_ip_filter(toFilter(ipFilter));
        emit(EngineEvent.session(EngineEvent.Level.INFO, tr("engine.event.started", description())));
        restore(s);

        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, PERIODIC_FLUSH_SECONDS, PERIODIC_FLUSH_SECONDS, TimeUnit.SECONDS);
        flusher.scheduleAtFixedRate(this::saveStats, STATS_SAVE_SECONDS, STATS_SAVE_SECONDS, TimeUnit.SECONDS);
    }

    /** Re-adds every stored torrent. One bad blob costs that torrent and a log line, not the rest. */
    private void restore(SessionManager s) {
        Map<String, byte[]> stored;
        try {
            stored = store.loadAll();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read the torrent store " + store.dir(), e);
            return;
        }
        restoring.addAll(stored.keySet());
        for (Map.Entry<String, byte[]> entry : stored.entrySet()) {
            restoreLabels(entry.getKey());
            restoreStorage(entry.getKey());
            error_code ec = new error_code();
            add_torrent_params params = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(entry.getValue()), ec);
            if (ec.value() != 0) {
                LOG.warning("Resume data for " + entry.getKey() + " is unreadable (" + ec.message() + "); skipped");
                continue;
            }
            restoreNames(entry.getKey(), params);
            error_code added = new error_code();
            s.swig().add_torrent(params, added);
            if (added.value() != 0) {
                LOG.warning("Could not bring back " + entry.getKey() + ": " + added.message());
                continue;
            }
            rememberTrackers(entry.getKey(), new AddTorrentParams(params).getTrackers());
        }
    }

    /** The sidecar, if there is one; an unreadable one costs the labels, not the torrent. */
    private void restoreLabels(String id) {
        try {
            store.loadLabels(id).ifPresent(bytes -> {
                try {
                    TorrentLabels l = TorrentLabels.decode(bytes);
                    if (!l.isEmpty()) labels.put(id, l);
                } catch (IOException e) {
                    LOG.warning("Labels for " + id + " are unreadable (" + e.getMessage() + "); skipped");
                }
            });
        } catch (IOException e) {
            LOG.warning("Labels for " + id + " could not be read (" + e.getMessage() + "); skipped");
        }
    }

    /** The storage sidecar, if there is one; an unreadable one costs the move, not the torrent. */
    private void restoreStorage(String id) {
        try {
            store.loadStorage(id).ifPresent(bytes -> {
                try {
                    TorrentStorage owed = TorrentStorage.decode(bytes);
                    if (!owed.isEmpty()) storage.put(id, owed);
                } catch (IOException e) {
                    LOG.warning("Storage record for " + id + " is unreadable (" + e.getMessage() + "); skipped");
                }
            });
        } catch (IOException e) {
            LOG.warning("Storage record for " + id + " could not be read (" + e.getMessage() + "); skipped");
        }
    }

    /**
     * libtorrent 2.1 writes a renamed file into the resume data relative to the torrent's root
     * folder ({@code a.bin.part}) but reads it back relative to the save path, so a multi-file
     * torrent restarted with renamed files (the suffix, a name a person gave, the content
     * layout) would look for them beside its folder, not in it, and find nothing. The sidecar
     * knows every name; the full ones are put back here, before the add.
     */
    private void restoreNames(String id, add_torrent_params params) {
        TorrentStorage owed = storage.get(id);
        if (owed == null || (owed.suffixed().isEmpty() && owed.renamed().isEmpty())) return;
        org.libtorrent4j.swig.torrent_info info = params.ti_ptr();
        if (info == null || !info.is_valid()) return;
        org.libtorrent4j.swig.int_string_map renamed =
                new org.libtorrent4j.swig.int_string_map(params.get_renamed_files());
        org.libtorrent4j.swig.file_storage files = info.files_impl();
        Set<Integer> indices = new java.util.TreeSet<>(owed.suffixed());
        indices.addAll(owed.renamed().keySet());
        for (int index : indices) {
            if (index < 0 || index >= files.num_files()) continue;
            renamed.put(index, owed.onDisk(index, slashes(files.file_path(index))));
        }
        params.set_renamed_files(renamed);
        Reference.reachabilityFence(info);
    }

    /** A path inside a torrent with forward slashes, as the interface and the sidecar keep them. */
    private static String slashes(String path) {
        return path.replace('\\', '/');
    }

    @Override
    public String description() {
        return "libtorrent " + LibTorrent.version() + " (libtorrent4j " + LibTorrent.libtorrent4jVersion() + ")";
    }

    /** Everything: the window was written against this engine. */
    @Override
    public Capabilities capabilities() {
        return Capabilities.ALL;
    }

    @Override
    public String downloadDir() {
        return downloadDir.get().toString();
    }

    @Override
    public synchronized Torrent add(String input) throws EngineException {
        AddRequest.Source source;
        if (CommandLine.isMagnet(input)) {
            source = new AddRequest.Magnet(input);
        } else {
            File file = new File(input);
            if (!file.isFile()) throw new EngineException(tr("engine.error.noSuchFile", input));
            try {
                source = new AddRequest.TorrentFile(Files.readAllBytes(file.toPath()), input);
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.couldNotRead", input, e.getMessage()), e);
            }
        }
        return add(AddRequest.defaults(source, downloadDir.get()));
    }

    @Override
    public synchronized Torrent add(AddRequest request) throws EngineException {
        return add(request, (Fetch) null);
    }

    @Override
    public synchronized Torrent add(AddRequest request, MetadataFetch from) throws EngineException {
        if (!(from instanceof Fetch fetch)) return Engine.super.add(request, from);
        fetch.cancel();
        return add(request, fetch);
    }

    /** {@code from}: an ended fetch whose peers the torrent starts with, if it is the same one. */
    private Torrent add(AddRequest request, Fetch from) throws EngineException {
        SessionManager s = running();
        AddTorrentParams params;
        TorrentInfo info = null;
        switch (request.source()) {
            case AddRequest.Magnet magnet -> {
                try {
                    params = AddTorrentParams.parseMagnetUri(magnet.uri());
                } catch (RuntimeException e) {
                    throw new EngineException(tr("engine.error.badMagnet"), e);
                }
            }
            case AddRequest.TorrentFile file -> {
                params = load(file.bytes(), file.origin());
                info = params.getTorrentInfo();
            }
        }
        // Into the incomplete folder when there is one; the destination goes in the sidecar and
        // move_storage takes the files there when the torrent finishes.
        AddRequest.Storage choices = request.storage();
        Path destination = request.savePath().toAbsolutePath().normalize();
        Path start = choices.incompletePath() == null
                ? destination
                : choices.incompletePath().toAbsolutePath().normalize();
        params.setSavePath(start.toString());
        if (choices.preallocate()) params.setStorageMode(StorageMode.STORAGE_MODE_ALLOCATE);
        params.setMaxConnections(engineSettings.maxConnectionsPerTorrent());

        // A .torrent already has its metadata, so "stop after metadata" means "start paused".
        boolean pauseNow = request.startPaused()
                || (info != null && request.stopCondition() == AddRequest.StopCondition.METADATA_RECEIVED);

        torrent_flags_t flags = params.getFlags();
        if (pauseNow) flags = flags.or_(Flags.PAUSED).and_(Flags.AUTO_MANAGED.inv());
        if (request.sequential()) flags = flags.or_(Flags.SEQUENTIAL_DOWNLOAD);
        if (request.skipCheck()) flags = flags.or_(Flags.SEED_MODE);
        if (request.stopCondition() == AddRequest.StopCondition.FILES_CHECKED) flags = flags.or_(Flags.STOP_WHEN_READY);
        if (!engineSettings.protocol().pex()) flags = flags.or_(Flags.DISABLE_PEX);
        params.setFlags(flags);

        appendDefaultTrackers(params);

        if (info != null && !request.filePriorities().isEmpty()) {
            org.libtorrent4j.Priority[] priorities = new org.libtorrent4j.Priority[info.numFiles()];
            for (int i = 0; i < priorities.length; i++) {
                priorities[i] = toLibtorrent(request.filePriorities().getOrDefault(i, Priority.NORMAL));
            }
            params.filePriorities(priorities);
        }

        // The layout as renamed files in the add parameters, so the add's own check already looks
        // for them under the new names (renaming through the handle during that check was lost).
        Map<Integer, String> laidOut = Map.of();
        Map<Integer, String> originals = Map.of();
        if (info != null && choices.layout() != FileRenames.Layout.ORIGINAL) {
            originals = originalNames(info.files());
            laidOut = FileRenames.layout(originals, info.name(), choices.layout());
            if (!laidOut.isEmpty()) {
                org.libtorrent4j.swig.int_string_map renamed = new org.libtorrent4j.swig.int_string_map();
                laidOut.forEach(renamed::put);
                params.swig().set_renamed_files(renamed);
            }
        }

        String id = params.getInfoHashes().getBest().toHex();
        String v1 =
                params.getInfoHashes().hasV1() ? params.getInfoHashes().getV1().toHex() : id;
        endedFetches.remove(id);
        endedFetches.remove(v1);
        // The fetch is keyed by the link's hash: the v1 one for a v1 link of a hybrid torrent.
        tcp_endpoint_vector known = null;
        if (from != null && (from.id.equals(id) || from.id.equals(v1)) && !from.found.isEmpty()) {
            known = new tcp_endpoint_vector();
            for (Endpoint peer : from.found) {
                error_code bad = new error_code();
                address ip = address.from_string(peer.address(), bad);
                if (bad.value() == 0) known.add(new tcp_endpoint(ip, peer.port()));
            }
            params.swig().setPeers(known);
        }
        if (info == null && request.stopCondition() == AddRequest.StopCondition.METADATA_RECEIVED)
            stopAfterMetadata.add(id);
        rememberTrackers(id, params.getTrackers());

        // The synchronous add, so the caller gets a handle (and an error) straight away; the
        // add_torrent_alert that follows is what writes the first resume data.
        error_code ec = new error_code();
        torrent_handle raw = s.swig().add_torrent(params.swig(), ec);
        Reference.reachabilityFence(known);
        if (ec.value() != 0) {
            stopAfterMetadata.remove(id);
            throw new EngineException(ec.message());
        }
        TorrentHandle handle = new TorrentHandle(raw);
        if (!request.labels().isEmpty()) writeLabels(id, request.labels());
        TorrentStorage owed = new TorrentStorage(
                        start.equals(destination) ? "" : destination.toString(), choices.partSuffix(), Set.of())
                .withRenamed(laidOut, originals);
        if (!owed.isEmpty()) writeStorage(id, owed);

        return snapshot(handle);
    }

    /** The default trackers on a tier of their own, after the torrent's; duplicates stay out. */
    private void appendDefaultTrackers(AddTorrentParams params) {
        List<String> defaults = engineSettings.protocol().defaultTrackers();
        if (defaults.isEmpty()) return;
        List<String> trackers = new ArrayList<>(params.getTrackers());
        List<Integer> tiers = new ArrayList<>(params.getTrackerTiers());
        int tier = tiers.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (String url : defaults) {
            if (!trackers.contains(url)) {
                trackers.add(url);
                tiers.add(tier);
            }
        }
        params.setTrackers(trackers);
        params.setTrackerTiers(tiers);
    }

    /** Memory and sidecar together, under the store's lock; nothing owed means no sidecar. */
    private void writeStorage(String id, TorrentStorage owed) throws EngineException {
        synchronized (store) {
            try {
                if (owed.isEmpty()) {
                    storage.remove(id);
                    store.deleteStorage(id);
                } else {
                    storage.put(id, owed);
                    store.saveStorage(id, owed.encode());
                }
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.storageRecord", id, e.getMessage()), e);
            }
        }
    }

    /** The alert thread's version: a failed write is a log line, the torrent carries on. */
    private void writeStorageQuietly(String id, TorrentStorage owed) {
        try {
            writeStorage(id, owed);
        } catch (EngineException e) {
            LOG.warning(e.getMessage());
        }
    }

    /**
     * Puts the suffix on every wanted file that is not complete and takes it off every one that
     * is, by the torrent's current file progress; after the check, so a file that was already
     * there keeps its name. Renames are disk jobs, confirmed later by {@code file_renamed_alert}.
     * No metadata, nothing to name: a magnet comes back here once its metadata has been checked.
     */
    private void applySuffixes(String id, TorrentHandle h) {
        TorrentStorage owed = storage.get(id);
        if (owed == null || !owed.partSuffix()) return;
        TorrentInfo info = h.status(StatusQuery.FLAGS).hasMetadata() ? h.torrentFile() : null;
        if (info == null || !info.isValid()) return;
        try {
            FileStorage files = info.files();
            long[] progress = h.fileProgress();
            org.libtorrent4j.Priority[] priorities = h.filePriorities();
            Set<Integer> suffixed = new java.util.TreeSet<>(owed.suffixed());
            for (int i = 0; i < files.numFiles(); i++) {
                if (files.padFileAt(i) || files.fileSize(i) <= 0) continue;
                boolean wanted = i >= priorities.length || priorities[i] != org.libtorrent4j.Priority.IGNORE;
                boolean complete = i < progress.length && progress[i] >= files.fileSize(i);
                // The name it has now: torrent_file() shows the torrent's own, never a rename.
                String path = owed.name(i, slashes(files.filePath(i)));
                if (suffixed.contains(i)) {
                    if (complete) {
                        h.renameFile(i, path);
                        suffixed.remove(i);
                    }
                } else if (wanted && !complete && !path.endsWith(TorrentStorage.SUFFIX)) {
                    h.renameFile(i, TorrentStorage.suffixed(path));
                    suffixed.add(i);
                }
            }
            if (!suffixed.equals(owed.suffixed())) writeStorageQuietly(id, owed.withSuffixed(suffixed));
        } finally {
            Reference.reachabilityFence(info);
        }
    }

    /** Takes the suffix off one file that has just completed, if it carries it. */
    private void unsuffix(String id, TorrentHandle h, int index) {
        TorrentStorage owed = storage.get(id);
        if (owed == null || !owed.suffixed().contains(index)) return;
        TorrentInfo info = h.status(StatusQuery.FLAGS).hasMetadata() ? h.torrentFile() : null;
        if (info == null || !info.isValid()) return;
        try {
            FileStorage files = info.files();
            if (index < files.numFiles()) h.renameFile(index, owed.name(index, slashes(files.filePath(index))));
        } finally {
            Reference.reachabilityFence(info);
        }
        Set<Integer> left = new java.util.TreeSet<>(owed.suffixed());
        left.remove(index);
        writeStorageQuietly(id, owed.withSuffixed(left));
    }

    /**
     * Moves a finished torrent to its destination, once. {@code dont_replace}: a file already at
     * the destination is kept and the torrent uses it, so a complete copy is never written over.
     * The answer is a {@code storage_moved} alert, where the record is cleared.
     */
    private void maybeMove(String id, TorrentHandle h) {
        TorrentStorage owed = storage.get(id);
        if (owed == null || owed.destination().isEmpty()) return;
        TorrentStatus status = h.status(StatusQuery.FLAGS);
        if (!status.isFinished()) return;
        if (samePath(status.swig().getSave_path(), owed.destination())) {
            writeStorageQuietly(id, owed.withDestination(""));
            return;
        }
        if (!moving.add(id)) return;
        h.moveStorage(owed.destination(), MoveFlags.DONT_REPLACE);
    }

    private static boolean samePath(String a, String b) {
        try {
            return Path.of(a)
                    .toAbsolutePath()
                    .normalize()
                    .equals(Path.of(b).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    /** The torrent's storage record, for the test; {@link TorrentStorage#NONE} when nothing is owed. */
    TorrentStorage storageForTest(String id) {
        return storage.getOrDefault(id, TorrentStorage.NONE);
    }

    /** The port the session listens on, for a test that connects two engines over loopback. */
    int listenPortForTest() throws EngineException {
        return running().swig().listen_port();
    }

    /**
     * Asks the session to open its listen sockets again, for a loopback test whose seeder never
     * listened: on Windows CI a session has come up with no listen socket and no listen alert of
     * either kind, its torrents working, the port 0 for two minutes.
     */
    void reopenSocketsForTest() throws EngineException {
        running().swig().reopen_network_sockets();
    }

    /** Tells a fetch about a peer on this machine, for the test that hands its peers over. */
    void connectFetchForTest(MetadataFetch fetch, int port) throws EngineException {
        TorrentHandle h = ((Fetch) fetch).handle(running());
        if (h == null) throw new EngineException(tr("engine.error.fetchEnded"));
        h.swig().connect_peer(new tcp_endpoint(Address.parseIp("127.0.0.1").swig(), port));
    }

    /** {@link #forgetPeersForTest} for a fetch, the hidden torrent behind a magnet's file list. */
    void forgetFetchPeersForTest(MetadataFetch fetch) throws EngineException {
        TorrentHandle h = ((Fetch) fetch).handle(running());
        if (h == null) throw new EngineException(tr("engine.error.fetchEnded"));
        h.swig().clear_peers();
    }

    /** How many of the torrent's peers it dialled itself, for the same test. */
    int dialledPeersForTest(String id) throws EngineException {
        return dialledPeers(handle(id)).size();
    }

    /** Tells the torrent about a peer on this machine, for the same test. */
    void connectPeerForTest(String id, int port) throws EngineException {
        handle(id)
                .swig()
                .connect_peer(new tcp_endpoint(Address.parseIp("127.0.0.1").swig(), port));
    }

    /**
     * Forgets the torrent's peer list, for a loopback test whose seeder refused it a few times:
     * libtorrent stops dialling a peer after three failed attempts, and another connect_peer to
     * the same address does not bring it back; a cleared list takes it as a new peer.
     */
    void forgetPeersForTest(String id) throws EngineException {
        handle(id).swig().clear_peers();
    }

    /** Tells the torrent about a peer at any address, for the test of where dials leave from. */
    void connectPeerForTest(String id, String address, int port) throws EngineException {
        handle(id).swig().connect_peer(new tcp_endpoint(Address.parseIp(address).swig(), port));
    }

    /**
     * Retries a failed peer connection after a second instead of libtorrent's minute, for the
     * loopback tests: {@code connect_peer} only adds the address to the peer list, and an address
     * whose last attempt failed is not dialled again for {@code min_reconnect_time}, so one early
     * refusal would otherwise outlast any test deadline.
     */
    /**
     * The session's peer connections as they start and end, for a loopback test that fails to
     * say why it never got a peer: off (null) unless a test asks, and only the last few kept.
     */
    private volatile java.util.Deque<String> peerLog;

    private static final int PEER_LOG_LINES = 40;

    /** Alert thread. One line of the peer log, when a test keeps one. */
    private void logPeer(String line) {
        java.util.Deque<String> log = peerLog;
        if (log == null) return;
        log.addLast(java.time.Instant.now() + " " + line);
        while (log.size() > PEER_LOG_LINES) log.pollFirst();
    }

    /** Starts the peer log; a second call keeps the lines already there. */
    void recordPeersForTest() {
        if (peerLog == null) peerLog = new java.util.concurrent.ConcurrentLinkedDeque<>();
    }

    List<String> peerLogForTest() {
        java.util.Deque<String> log = peerLog;
        return log == null ? List.of() : List.copyOf(log);
    }

    void reconnectQuicklyForTest() throws EngineException {
        SettingsPack pack = new SettingsPack();
        pack.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1);
        pack.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 3);
        running().applySettings(pack);
    }

    @Override
    public synchronized String category(String id) throws EngineException {
        return labelsOf(id).category();
    }

    @Override
    public synchronized void setCategory(String id, String category) throws EngineException {
        writeLabels(id, labelsOf(id).withCategory(category));
    }

    @Override
    public synchronized List<String> tags(String id) throws EngineException {
        return labelsOf(id).tags();
    }

    @Override
    public synchronized void setTags(String id, List<String> tags) throws EngineException {
        writeLabels(id, labelsOf(id).withTags(tags));
    }

    @Override
    public synchronized void rename(String id, String name) throws EngineException {
        writeLabels(id, labelsOf(id).withName(name));
    }

    /** The torrent's labels, none if it has none; an id the session lacks is an error. */
    private TorrentLabels labelsOf(String id) throws EngineException {
        handle(id);
        return labels.getOrDefault(id, TorrentLabels.NONE);
    }

    /** Memory and sidecar together, under the store's lock; no labels means no sidecar. */
    private void writeLabels(String id, TorrentLabels l) throws EngineException {
        synchronized (store) {
            try {
                if (l.isEmpty()) {
                    labels.remove(id);
                    store.deleteLabels(id);
                } else {
                    labels.put(id, l);
                    store.saveLabels(id, l.encode());
                }
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.labels", id, e.getMessage()), e);
            }
        }
    }

    @Override
    public TorrentPreview inspect(byte[] torrent) throws EngineException {
        NativeLibrary.ensureAvailable();
        AddTorrentParams params = load(torrent, tr("engine.error.theTorrent"));
        try {
            TorrentInfo info = params.getTorrentInfo();
            FileStorage files = info.files();
            List<TorrentPreview.FileEntry> entries = new ArrayList<>(files.numFiles());
            for (int i = 0; i < files.numFiles(); i++) {
                if (files.padFileAt(i)) continue;
                entries.add(new TorrentPreview.FileEntry(i, files.filePath(i).replace('\\', '/'), files.fileSize(i)));
            }
            // The real files' bytes, not totalSize(): a hybrid torrent's padding files are not content.
            long size =
                    entries.stream().mapToLong(TorrentPreview.FileEntry::size).sum();
            return new TorrentPreview(
                    params.getInfoHashes().getBest().toHex(),
                    params.getName().isEmpty() ? info.name() : params.getName(),
                    size,
                    List.copyOf(entries),
                    info.isPrivate(),
                    List.copyOf(params.getTrackers()),
                    params.getComment() == null ? "" : params.getComment(),
                    params.getCreatedBy() == null ? "" : params.getCreatedBy(),
                    params.getCreationDate(),
                    info.swig().v1(),
                    info.swig().v2());
        } finally {
            Reference.reachabilityFence(params);
        }
    }

    /**
     * Not synchronized: the hashing can take minutes and needs no session, and the polls must go
     * on meanwhile; the add at the end takes the lock as every add does. libtorrent lists the
     * files and lays them out ({@code create_torrent}), {@link PieceHasher} hashes them in Java,
     * and libtorrent writes the file: its own {@code set_piece_hashes} goes through the
     * memory-mapped disk I/O whose fault guard displaces the JVM's signal handler (see
     * {@link #toPack}), and it cannot be stopped, while this one can.
     */
    @Override
    public CreatedTorrent create(CreateRequest request, CreateProgress progress) throws EngineException {
        NativeLibrary.ensureAvailable();
        Path path = request.path().toAbsolutePath().normalize();
        if (!Files.exists(path)) throw new EngineException(tr("engine.error.notFound", path));
        Path parent = path.getParent();
        if (parent == null) throw new EngineException(tr("engine.error.wholeDisk"));
        String name = path.getFileName().toString();

        byte[] bytes;
        int pieces;
        int pieceLength;
        try {
            create_flags_t flags = CreateFlags.of(request);
            create_file_entry_vector listed = libtorrent.list_files_ex(path.toString(), new EveryFile(), flags);
            if (listed.size() == 0) throw new EngineException(tr("engine.error.nothingToShare", path));
            create_torrent ct = new create_torrent(listed, request.pieceSize(), flags);
            Reference.reachabilityFence(listed);
            pieces = ct.num_pieces();
            pieceLength = ct.piece_length();

            List<PieceHasher.Entry> layout = new ArrayList<>();
            for (int i = 0; i < ct.end_file(); i++) {
                create_file_entry e = ct.file_at(i);
                boolean pad = e.getFlags().and_(CreateFlags.PAD_FILE).non_zero();
                layout.add(new PieceHasher.Entry(pad ? null : parent.resolve(e.getFilename()), e.getSize(), pad));
            }
            boolean v1 = !ct.is_v2_only();
            boolean v2 = !ct.is_v1_only();
            try {
                PieceHasher.hash(layout, pieceLength, v1, v2, pieces, new PieceHasher.Sink() {
                    @Override
                    public void v1(int piece, byte[] sha1) {
                        ct.set_hash(piece, new sha1_hash(Vectors.bytes2byte_vector(sha1)));
                    }

                    @Override
                    public void v2(int file, int piece, byte[] sha256) {
                        ct.set_hash2(file, piece, new sha256_hash(Vectors.bytes2byte_vector(sha256)));
                    }

                    @Override
                    public boolean progress(int done, int total) {
                        return progress.hashed(done, total);
                    }
                });
            } catch (PieceHasher.Cancelled e) {
                throw new EngineException(tr("engine.error.cancelled"));
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.readFiles", e.getMessage()), e);
            }

            if (!request.comment().isEmpty()) ct.set_comment(request.comment());
            ct.set_creator(AppInfo.NAME + " " + AppInfo.VERSION);
            ct.set_priv(request.isPrivate());
            int tier = 0;
            for (List<String> urls : request.trackers()) {
                for (String url : urls) ct.add_tracker(url, tier);
                tier++;
            }
            for (String seed : request.webSeeds()) ct.add_url_seed(seed);

            entry torrent = ct.generate();
            if (!request.source().isEmpty()) {
                // libtorrent has no setter for it: written into the info dictionary by hand, which
                // is why the hashes below are read off the final bytes rather than the builder.
                torrent.dict().get("info").set("source", request.source());
            }
            bytes = Vectors.byte_vector2bytes(torrent.bencode());
            Reference.reachabilityFence(torrent);
            Reference.reachabilityFence(ct);
        } catch (RuntimeException e) {
            throw new EngineException(tr("engine.error.createTorrent", e.getMessage()), e);
        }

        AddTorrentParams params = load(bytes, name);
        String id;
        String v1;
        String v2;
        long size;
        String magnet;
        try {
            id = params.getInfoHashes().getBest().toHex();
            v1 = params.getInfoHashes().hasV1() ? params.getInfoHashes().getV1().toHex() : "";
            v2 = params.getInfoHashes().hasV2()
                    ? params.getInfoHashes().swig().getV2().to_hex()
                    : "";
            FileStorage files = params.getTorrentInfo().files();
            size = 0;
            for (int i = 0; i < files.numFiles(); i++) if (!files.padFileAt(i)) size += files.fileSize(i);
            magnet = libtorrent.make_magnet_uri(params.swig());
        } finally {
            Reference.reachabilityFence(params);
        }

        java.util.Optional<Torrent> seeding = java.util.Optional.empty();
        String problem = "";
        if (request.startSeeding()) {
            AddRequest add = new AddRequest(
                    new AddRequest.TorrentFile(bytes, name),
                    request.savePath(),
                    false,
                    false,
                    true,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    TorrentLabels.NONE,
                    AddRequest.Storage.DEFAULT);
            try {
                seeding = java.util.Optional.of(add(add));
            } catch (EngineException e) {
                problem = e.getMessage();
            }
        }
        return new CreatedTorrent(bytes, id, name, v1, v2, pieces, pieceLength, size, magnet, seeding, problem);
    }

    /** Every file under the path is in: libtorrent's listing asks about each. */
    private static final class EveryFile extends list_files_listener {
        @Override
        public boolean pred(String filename) {
            return true;
        }
    }

    @Override
    public synchronized MetadataFetch fetchMetadata(String magnet) throws EngineException {
        SessionManager s = running();
        AddTorrentParams params;
        try {
            params = AddTorrentParams.parseMagnetUri(magnet);
        } catch (RuntimeException e) {
            throw new EngineException(tr("engine.error.badMagnet"), e);
        }
        String id = params.getInfoHashes().getBest().toHex();
        if (find(s, id) != null) throw new EngineException(tr("engine.error.alreadyInList"));
        // Upload mode writes nothing; the folder is only somewhere for libtorrent to look. Out of
        // the queue's hands, so a full download queue does not hold the file list back.
        params.setSavePath(downloadDir.get().toString());
        torrent_flags_t flags = params.getFlags()
                .or_(Flags.UPLOAD_MODE)
                .and_(Flags.AUTO_MANAGED.inv())
                .and_(Flags.PAUSED.inv());
        if (!engineSettings.protocol().pex()) flags = flags.or_(Flags.DISABLE_PEX);
        params.setFlags(flags);
        appendDefaultTrackers(params);

        Fetch fetch = new Fetch(id, magnet);
        if (fetches.putIfAbsent(id, fetch) != null) throw new EngineException(tr("engine.error.alreadyFetching"));
        fetchAddAlerts.add(id);
        error_code ec = new error_code();
        s.swig().add_torrent(params.swig(), ec);
        if (ec.value() != 0) {
            fetches.remove(id, fetch);
            throw new EngineException(ec.message());
        }
        return fetch;
    }

    /** One fetch: the dialog polls {@link #peers()} and waits on {@link #torrent()}. */
    private final class Fetch implements MetadataFetch {
        private final String id;
        private final String magnet;
        private final java.util.concurrent.CompletableFuture<byte[]> torrent =
                new java.util.concurrent.CompletableFuture<>();
        /**
         * The peers it dialled, as each connection succeeds (a {@code peer_connect_alert}), for
         * {@link #add(AddRequest, MetadataFetch)}. Recorded as they come rather than read at the
         * end: once the metadata is in, the fetch is upload-only and a seed drops it at once.
         */
        private final Set<Endpoint> dialled = ConcurrentHashMap.newKeySet();

        /** What the new torrent starts with; fixed when the fetch ends. */
        private volatile List<Endpoint> found = List.of();

        void dialled(Endpoint peer) {
            if (dialled.size() < MAX_HANDED_OVER) dialled.add(peer);
        }

        Fetch(String id, String magnet) {
            this.id = id;
            this.magnet = magnet;
        }

        /** Under the engine's lock, like every call from outside that reaches the session. */
        @Override
        public int peers() {
            synchronized (LibtorrentEngine.this) {
                SessionManager s = session;
                if (torrent.isDone() || s == null || !s.isRunning()) return 0;
                TorrentHandle h = handle(s);
                return h == null ? 0 : h.status().numPeers();
            }
        }

        @Override
        public java.util.concurrent.CompletableFuture<byte[]> torrent() {
            return torrent;
        }

        @Override
        public void cancel() {
            synchronized (LibtorrentEngine.this) {
                end(null, new java.util.concurrent.CancellationException("Cancelled"));
            }
        }

        /** Alert thread: the metadata is in. The .torrent is the info plus the link's trackers. */
        void arrived(TorrentHandle handle) {
            byte[] bytes;
            try {
                TorrentInfo info = handle.torrentFile();
                AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnet);
                params.setTorrentInfo(info);
                // A v2 torrent's piece layers come from peers as it downloads; the file would
                // otherwise not be written at all.
                bytes = Vectors.byte_vector2bytes(libtorrent
                        .write_torrent_file(params.swig(), libtorrent.getAllow_missing_piece_layer())
                        .bencode());
                Reference.reachabilityFence(info);
            } catch (RuntimeException e) {
                end(null, e);
                return;
            }
            end(bytes, null);
        }

        /** This fetch's torrent in the session, as a handle Java owns; null once it has left. */
        private TorrentHandle handle(SessionManager s) {
            session_handle sh = s.swig();
            torrent_handle_vector vector = sh.get_torrents();
            try {
                for (int i = 0; i < vector.size(); i++) {
                    if (fetchOf(vector.get(i)) != this) continue;
                    TorrentHandle h = new TorrentHandle(sh.find_torrent(bestHash(vector.get(i))));
                    return h.isValid() ? h : null;
                }
                return null;
            } finally {
                Reference.reachabilityFence(vector);
            }
        }

        /** Out of the session first, then out of the map, so no snapshot sees it in between. */
        synchronized void end(byte[] bytes, Throwable failure) {
            if (torrent.isDone()) return;
            SessionManager s = session;
            endedFetches.add(id);
            if (s != null && s.isRunning()) {
                TorrentHandle h = handle(s);
                if (h != null) {
                    endedFetches.add(id(h));
                    dialledPeers(h).forEach(this::dialled);
                    s.remove(h);
                }
            }
            fetches.remove(id, this);
            found = List.copyOf(dialled);
            if (bytes != null) torrent.complete(bytes);
            else torrent.completeExceptionally(failure);
        }
    }

    /**
     * A {@code .torrent} as add parameters. libtorrent 2.1 keeps the trackers, comment, creator
     * and web seeds outside {@code torrent_info}, which holds the info dictionary alone; its
     * {@code load_torrent_*} functions are what read a whole file. Building a {@code TorrentInfo}
     * from the bytes instead loses the trackers silently, which is how this was found.
     */
    private static AddTorrentParams load(byte[] bytes, String origin) throws EngineException {
        try {
            BDecodeNode node = BDecodeNode.bdecode(bytes);
            error_code ec = new error_code();
            add_torrent_params raw = libtorrent.load_torrent_parsed(node.swig(), ec, new load_torrent_limits());
            if (ec.value() != 0) throw new EngineException(tr("engine.error.badTorrentWhy", origin, ec.message()));
            AddTorrentParams params = new AddTorrentParams(raw);
            TorrentInfo info = params.getTorrentInfo();
            if (info == null || !info.isValid()) throw new EngineException(tr("engine.error.badTorrent", origin));
            return params;
        } catch (RuntimeException e) {
            throw new EngineException(tr("engine.error.badTorrent", origin), e);
        }
    }

    private static TorrentInfo decode(byte[] bytes, String origin) throws EngineException {
        try {
            TorrentInfo info = new TorrentInfo(bytes);
            if (!info.isValid()) throw new EngineException(tr("engine.error.badTorrent", origin));
            return info;
        } catch (RuntimeException e) {
            throw new EngineException(tr("engine.error.badTorrent", origin), e);
        }
    }

    private static org.libtorrent4j.Priority toLibtorrent(Priority priority) {
        return switch (priority) {
            case IGNORE -> org.libtorrent4j.Priority.IGNORE;
            case LOW -> org.libtorrent4j.Priority.LOW;
            case NORMAL -> org.libtorrent4j.Priority.DEFAULT;
            case HIGH -> org.libtorrent4j.Priority.TOP_PRIORITY;
        };
    }

    @Override
    public synchronized void remove(String id, boolean deleteFiles) throws EngineException {
        SessionManager s = running();
        TorrentHandle handle = find(s, id);
        if (handle == null) throw new EngineException(tr("engine.error.noSuchTorrent", id));
        // Named here, before the handle dies: the removed alert carries only the hash.
        String name = nameOf(handle);
        if (deleteFiles) s.remove(handle, SessionHandle.DELETE_FILES);
        else s.remove(handle);
        emit(EngineEvent.info(
                id, name, deleteFiles ? tr("engine.event.removedWithFiles", name) : tr("engine.event.removed", name)));
        // Forgotten before libtorrent confirms: a crash in between loses nothing worse than a
        // torrent that comes back once, which beats one that cannot be removed. Marked and deleted
        // under the store's lock, the same lock the alert thread's write takes around its check,
        // so a write that already passed the check cannot land after this delete.
        synchronized (store) {
            removed.add(id);
            labels.remove(id);
            storage.remove(id);
            archiveReads.remove(id);
            encryptedArchives.remove(id);
            moving.remove(id);
            trackerHosts.remove(id);
            trackerErrors.remove(id);
            try {
                store.delete(id);
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.resumeStayed", e.getMessage()), e);
            }
        }
    }

    @Override
    public synchronized void pause(String id) throws EngineException {
        TorrentHandle h = handle(id);
        // Out of the queue's hands first: an auto-managed torrent that is paused gets resumed by
        // the queue at its next pass, which is not what "pause" means to a person.
        h.unsetFlags(Flags.AUTO_MANAGED);
        h.pause();
    }

    @Override
    public synchronized void resume(String id) throws EngineException {
        TorrentHandle h = handle(id);
        h.setFlags(Flags.AUTO_MANAGED);
        h.resume();
    }

    @Override
    public synchronized void forceResume(String id) throws EngineException {
        TorrentHandle h = handle(id);
        h.unsetFlags(Flags.AUTO_MANAGED);
        h.resume();
    }

    @Override
    public synchronized void recheck(String id) throws EngineException {
        handle(id).forceRecheck();
    }

    @Override
    public synchronized void reannounce(String id) throws EngineException {
        handle(id).forceReannounce();
    }

    @Override
    public synchronized void setSequential(String id, boolean sequential) throws EngineException {
        TorrentHandle h = handle(id);
        if (sequential) h.setFlags(Flags.SEQUENTIAL_DOWNLOAD);
        else h.unsetFlags(Flags.SEQUENTIAL_DOWNLOAD);
    }

    @Override
    public synchronized void renamePath(String id, String path, String name) throws EngineException {
        TorrentHandle h = handle(id);
        TorrentStatus status = h.status(StatusQuery.FLAGS);
        if (!status.hasMetadata()) throw new EngineException(tr("engine.error.renameNoMetadata"));
        TorrentInfo info = h.torrentFile();
        try {
            Map<Integer, String> originals = originalNames(info.files());
            TorrentStorage owed = storage.getOrDefault(id, TorrentStorage.NONE);
            Map<Integer, String> names = new java.util.TreeMap<>();
            originals.forEach((index, original) -> names.put(index, owed.name(index, original)));
            java.util.Optional<String> problem = FileRenames.problem(names, path, name);
            if (problem.isPresent()) throw new EngineException(problem.get());
            Map<Integer, String> changes = FileRenames.plan(names, path, name);
            TorrentStorage next = owed.withRenamed(changes, originals);
            // Recorded first: the rename alerts, and a restart, read the names from the record.
            writeStorage(id, next);
            changes.forEach((index, renamed) -> h.renameFile(index, next.onDisk(index, originals.get(index))));
            String shown = displayName(id, status.name());
            emit(EngineEvent.info(id, shown, tr("engine.event.pathRenamed", shown, path, name.strip())));
        } finally {
            Reference.reachabilityFence(info);
        }
    }

    /**
     * The folders above a renamed file's old place that are empty now, from the deepest up,
     * stopping at the save path (never removed) and at the first folder that still holds anything.
     */
    private static void removeEmptyFolders(String savePath, String oldName) {
        try {
            Path save = Path.of(savePath).toAbsolutePath().normalize();
            Path dir = save.resolve(oldName).normalize().getParent();
            while (dir != null && dir.startsWith(save) && !dir.equals(save)) {
                try (var entries = Files.list(dir)) {
                    if (entries.findAny().isPresent()) return;
                }
                Files.delete(dir);
                dir = dir.getParent();
            }
        } catch (IOException | RuntimeException e) {
            LOG.fine("empty folders after a rename stay: " + e.getMessage());
        }
    }

    /** Every real file's own path by index, forward slashes; padding files left out. */
    private static Map<Integer, String> originalNames(FileStorage files) {
        Map<Integer, String> out = new java.util.TreeMap<>();
        for (int i = 0; i < files.numFiles(); i++) {
            if (!files.padFileAt(i)) out.put(i, slashes(files.filePath(i)));
        }
        return out;
    }

    @Override
    public synchronized void setSuperSeeding(String id, boolean on) throws EngineException {
        TorrentHandle h = handle(id);
        if (on) h.setFlags(Flags.SUPER_SEEDING);
        else h.unsetFlags(Flags.SUPER_SEEDING);
        requestSave(h, false);
    }

    @Override
    public synchronized void queueMove(String id, QueueMove move) throws EngineException {
        TorrentHandle h = handle(id);
        switch (move) {
            case TOP -> h.queuePositionTop();
            case UP -> h.queuePositionUp();
            case DOWN -> h.queuePositionDown();
            case BOTTOM -> h.queuePositionBottom();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A torrent still on its way through the incomplete folder is not moved now: its
     * destination in the {@code .storage} sidecar is rewritten, and {@code maybeMove} takes the
     * files there when it completes, as it would have. Otherwise {@code move_storage} runs with
     * {@code dont_replace} (a file already at the destination is kept and used), or with {@code
     * reset_save_path} when the files are said to be there already, which rechecks them; the
     * {@code storage_moved} alert then writes the resume data, so the new path survives a restart.
     */
    @Override
    public synchronized void moveStorage(String id, String destination, boolean moveFiles) throws EngineException {
        TorrentHandle h = handle(id);
        if (destination == null || destination.isBlank()) throw new EngineException(tr("engine.error.noDestination"));
        Path target;
        try {
            target = Path.of(destination.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new EngineException(tr("engine.error.notAFolder", destination), e);
        }
        TorrentStorage owed = storage.get(id);
        if (owed != null && !owed.destination().isEmpty()) {
            TorrentStatus status = h.status(StatusQuery.FLAGS);
            if (!status.isFinished()) {
                writeStorage(id, owed.withDestination(target.toString()));
                String name = displayName(id, status.name());
                emit(EngineEvent.info(id, name, tr("engine.event.moveWhenComplete", name, target)));
                return;
            }
        }
        if (samePath(h.status(StatusQuery.FLAGS).swig().getSave_path(), target.toString())) return;
        if (!moving.add(id)) throw new EngineException(tr("engine.error.movingAlready"));
        h.moveStorage(target.toString(), moveFiles ? MoveFlags.DONT_REPLACE : MoveFlags.RESET_SAVE_PATH);
    }

    @Override
    public synchronized TorrentLimits limits(String id) throws EngineException {
        TorrentHandle h = handle(id);
        torrent_handle raw = h.swig();
        return new TorrentLimits(
                noneAsZero(raw.download_limit()),
                noneAsZero(raw.upload_limit()),
                noneAsZero(raw.max_connections()),
                noneAsZero(raw.max_uploads()),
                firstAndLastFirst(h));
    }

    @Override
    public synchronized void setLimits(String id, TorrentLimits limits) throws EngineException {
        TorrentHandle h = handle(id);
        torrent_handle raw = h.swig();
        // 0 is "none" on both sides for the rates; the counts take -1 for "no limit of its own".
        raw.set_download_limit(limits.downloadLimit());
        raw.set_upload_limit(limits.uploadLimit());
        raw.set_max_connections(limits.maxConnections() <= 0 ? -1 : limits.maxConnections());
        raw.set_max_uploads(limits.maxUploads() <= 0 ? -1 : limits.maxUploads());
        // On: the edge pieces go to the top. Off: back to their file's own priority, not the
        // default, so a file the user raised keeps its ends raised.
        edgePieces(h)
                .forEach((piece, own) -> h.piecePriority(
                        piece, limits.firstAndLastFirst() ? org.libtorrent4j.Priority.TOP_PRIORITY : own));
        // A limit is part of the resume data; write it now rather than at the next periodic flush.
        requestSave(h, false);
    }

    /**
     * libtorrent answers "no limit" as 0 or -1 for the rates and as {@code (1 << 24) - 1} for the
     * counts (what {@code set_max_connections(-1)} stores); all of them are 0 to the interface.
     */
    private static int noneAsZero(int value) {
        return value <= 0 || value >= (1 << 24) - 1 ? 0 : value;
    }

    /** Whether every wanted file's first and last piece is at top priority; false without metadata. */
    private static boolean firstAndLastFirst(TorrentHandle h) {
        Map<Integer, org.libtorrent4j.Priority> edges = edgePieces(h);
        if (edges.isEmpty()) return false;
        org.libtorrent4j.Priority[] priorities = h.piecePriorities();
        for (int piece : edges.keySet()) {
            if (piece >= priorities.length || priorities[piece] != org.libtorrent4j.Priority.TOP_PRIORITY) return false;
        }
        return true;
    }

    /**
     * The first and last piece of every file that is wanted (not a padding file, not ignored),
     * each with its file's priority (the higher one where two files share a piece), in order.
     * Empty until the metadata is there.
     */
    private static Map<Integer, org.libtorrent4j.Priority> edgePieces(TorrentHandle h) {
        TorrentInfo info = h.status(StatusQuery.FLAGS).hasMetadata() ? h.torrentFile() : null;
        if (info == null || !info.isValid() || info.pieceLength() <= 0) return Map.of();
        try {
            FileStorage storage = info.files();
            org.libtorrent4j.Priority[] priorities = h.filePriorities();
            Map<Integer, org.libtorrent4j.Priority> edges = new java.util.TreeMap<>();
            long pieceLength = info.pieceLength();
            for (int i = 0; i < storage.numFiles(); i++) {
                if (storage.padFileAt(i) || storage.fileSize(i) <= 0) continue;
                org.libtorrent4j.Priority own =
                        i < priorities.length ? priorities[i] : org.libtorrent4j.Priority.DEFAULT;
                if (own == org.libtorrent4j.Priority.IGNORE) continue;
                long offset = storage.fileOffset(i);
                for (long piece : new long[] {offset / pieceLength, (offset + storage.fileSize(i) - 1) / pieceLength}) {
                    edges.merge((int) piece, own, (a, b) -> a.swig() >= b.swig() ? a : b);
                }
            }
            return edges;
        } finally {
            // The file storage points into the info; keep it until the reads are done.
            Reference.reachabilityFence(info);
        }
    }

    @Override
    public synchronized byte[] exportTorrent(String id) throws EngineException {
        TorrentHandle h = handle(id);
        if (!h.status(StatusQuery.FLAGS).hasMetadata()) throw new EngineException(tr("engine.error.exportNoMetadata"));
        TorrentInfo info = h.torrentFile();
        try {
            AddTorrentParams params = new AddTorrentParams();
            // write_torrent_file writes the info section as it was loaded, so renames (the .part
            // suffix, a file renamed by hand) never reach the exported file and the hash holds.
            params.setTorrentInfo(info);
            List<String> urls = new ArrayList<>();
            List<Integer> tiers = new ArrayList<>();
            for (AnnounceEntry tracker : trackers(h)) {
                urls.add(tracker.url());
                tiers.add(tracker.tier());
            }
            params.setTrackers(urls);
            params.setTrackerTiers(tiers);
            params.setUrlSeeds(h.urlSeeds());
            return Vectors.byte_vector2bytes(libtorrent
                    .write_torrent_file(params.swig(), libtorrent.getAllow_missing_piece_layer())
                    .bencode());
        } catch (RuntimeException e) {
            throw new EngineException(tr("engine.error.exportWrite", e.getMessage()), e);
        } finally {
            Reference.reachabilityFence(info);
        }
    }

    @Override
    public synchronized String magnetLink(String id) throws EngineException {
        return handle(id).makeMagnetUri();
    }

    @Override
    public synchronized TorrentDetail detail(String id) throws EngineException {
        TorrentHandle h = handle(id);
        TorrentStatus status = h.status(StatusQuery.ALL);

        // Files: progress and priority by index; padding files hidden, indices kept.
        List<TorrentDetail.FileStatus> files = new ArrayList<>();
        TorrentInfo info = status.hasMetadata() ? h.torrentFile() : null;
        if (info != null && info.isValid()) {
            FileStorage storage = info.files();
            // The names a person or the layout gave: torrent_file() shows the torrent's own.
            TorrentStorage owed = this.storage.getOrDefault(id, TorrentStorage.NONE);
            long[] progress = h.fileProgress();
            org.libtorrent4j.Priority[] priorities = h.filePriorities();
            for (int i = 0; i < storage.numFiles(); i++) {
                if (storage.padFileAt(i)) continue;
                long done = i < progress.length ? progress[i] : 0;
                Priority priority = i < priorities.length ? fromLibtorrent(priorities[i]) : Priority.NORMAL;
                long size = storage.fileSize(i);
                // An empty file touches no piece; asking libtorrent for its range answers with a neighbour's.
                int first = size == 0 ? -1 : storage.pieceIndexAtFile(i);
                int last = size == 0 ? -1 : storage.lastPieceIndexAtFile(i);
                files.add(new TorrentDetail.FileStatus(
                        i, owed.name(i, slashes(storage.filePath(i))), size, done, priority, first, last));
            }
        }

        List<TorrentDetail.Peer> peers = new ArrayList<>();
        for (PeerInfo p : peers(h)) {
            peers.add(new TorrentDetail.Peer(
                    p.ip(),
                    p.client() == null ? "" : p.client(),
                    PeerFlags.text(p.flags(), p.source(), p.connectionType()),
                    p.progress(),
                    p.downSpeed(),
                    p.upSpeed(),
                    p.totalDownload(),
                    p.totalUpload()));
        }

        List<TorrentDetail.Tracker> trackers = new ArrayList<>();
        for (AnnounceEntry entry : trackers(h)) trackers.add(tracker(entry));

        // Counted only while downloading: a seed has no use for it, and libtorrent answers a paused
        // torrent with an empty vector anyway.
        int[] availability = status.isSeeding() || !status.hasMetadata() ? new int[0] : h.pieceAvailability();

        PieceIndexBitfield bits = status.pieces();
        boolean[] pieces = new boolean[Math.max(0, bits.size())];
        for (int i = 0; i < pieces.length; i++) pieces[i] = bits.getBit(i);

        TorrentDetail.General general = new TorrentDetail.General(
                id,
                status.swig().getSave_path(),
                info != null && info.isPrivate(),
                // status.numPieces() is how many it has, not how many there are.
                info != null ? info.numPieces() : pieces.length,
                info == null ? 0 : info.pieceLength(),
                bits.count(),
                status.distributedCopies(),
                status.allTimeDownload(),
                status.allTimeUpload(),
                status.totalFailedBytes() + status.totalRedundantBytes(),
                // libtorrent4j hands durations over in milliseconds; the record promises seconds.
                status.activeDuration() / 1000,
                status.seedingDuration() / 1000,
                status.nextAnnounce() / 1000,
                status.numConnections(),
                status.currentTracker() == null ? "" : status.currentTracker(),
                status.getInfoHashes().swig().has_v1()
                        ? status.getInfoHashes().swig().getV1().to_hex()
                        : "",
                status.getInfoHashes().swig().has_v2()
                        ? status.getInfoHashes().swig().getV2().to_hex()
                        : "");
        // The bitfield and the file storage point into these two; keep them until the end.
        Reference.reachabilityFence(status);
        Reference.reachabilityFence(info);
        return new TorrentDetail(
                general,
                files,
                peers,
                trackers,
                pieces,
                availability,
                h.urlSeeds(),
                encryptedArchives.getOrDefault(id, Set.of()));
    }

    /**
     * The torrent's trackers as copies Java owns: libtorrent4j's {@code trackers()} wraps pointers
     * into a vector it drops at once (see {@link #handles}).
     */
    private static List<AnnounceEntry> trackers(TorrentHandle h) {
        announce_entry_vector vector = h.swig().trackers();
        try {
            List<AnnounceEntry> out = new ArrayList<>(vector.size());
            for (int i = 0; i < vector.size(); i++) out.add(new AnnounceEntry(new announce_entry(vector.get(i))));
            return out;
        } finally {
            Reference.reachabilityFence(vector);
        }
    }

    /** A peer to dial again: the address as libtorrent writes it, and its listening port. */
    private record Endpoint(String address, int port) {}

    /**
     * The peers this torrent connected out to. A peer that connected in did so from a port it
     * does not listen on, so only the dialled ones can be dialled again. Read while the vector
     * is fenced: each element points into it.
     */
    private static List<Endpoint> dialledPeers(TorrentHandle h) {
        peer_info_vector vector = new peer_info_vector();
        h.swig().get_peer_info(vector);
        try {
            List<Endpoint> out = new ArrayList<>();
            for (int i = 0; i < vector.size(); i++) {
                peer_info p = vector.get(i);
                if (!p.getFlags().and_(PeerConstants.LOCAL_CONNECTION).non_zero()) continue;
                tcp_endpoint remote = p.remote_endpoint();
                out.add(new Endpoint(remote.address().to_string(), remote.port()));
            }
            return List.copyOf(out);
        } finally {
            Reference.reachabilityFence(vector);
        }
    }

    /** The connected peers as copies Java owns, for the same reason as {@link #trackers}. */
    private static List<PeerInfo> peers(TorrentHandle h) {
        peer_info_vector vector = new peer_info_vector();
        h.swig().get_peer_info(vector);
        try {
            List<PeerInfo> out = new ArrayList<>(vector.size());
            for (int i = 0; i < vector.size(); i++) out.add(new PeerInfo(new peer_info(vector.get(i))));
            return out;
        } finally {
            Reference.reachabilityFence(vector);
        }
    }

    /** The worst of the tracker's endpoints decides, as a person would read it. */
    private static TorrentDetail.Tracker tracker(AnnounceEntry entry) {
        String status = "not contacted";
        String message = "";
        int fails = 0;
        boolean anyWorking = false;
        boolean anyUpdating = false;
        boolean anyFailed = false;
        for (AnnounceEndpoint endpoint : entry.endpoints()) {
            for (AnnounceInfohash hash : List.of(endpoint.infohashV1(), endpoint.infohashV2())) {
                // is_working() is also true of a slot never tried, and a v1 torrent's v2 slot never
                // is: without start_sent every tracker of every v1 torrent read "working".
                if (hash.isWorking() && hash.swig().getStart_sent()) anyWorking = true;
                if (hash.updating()) anyUpdating = true;
                if (hash.fails() > 0) {
                    anyFailed = true;
                    fails = Math.max(fails, hash.fails());
                }
                String m = hash.message();
                if (m != null && !m.isEmpty() && message.isEmpty()) message = m;
            }
        }
        if (anyWorking) status = "working";
        else if (anyUpdating) status = "updating";
        else if (anyFailed) status = "error";
        return new TorrentDetail.Tracker(entry.url(), entry.tier(), status, message, fails);
    }

    @Override
    public synchronized void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException {
        handle(id).filePriority(fileIndex, toLibtorrent(priority));
    }

    @Override
    public synchronized void addTrackers(String id, List<String> urls) throws EngineException {
        TorrentHandle h = handle(id);
        List<AnnounceEntry> existing = trackers(h);
        int tier = existing.stream().mapToInt(AnnounceEntry::tier).max().orElse(-1) + 1;
        for (String url : urls) {
            String u = url.trim();
            if (u.isEmpty() || existing.stream().anyMatch(e -> e.url().equals(u))) continue;
            AnnounceEntry entry = new AnnounceEntry(u);
            entry.tier((short) tier);
            h.addTracker(entry);
            existing.add(entry);
        }
        trackerHosts.put(id, hostsOf(existing));
    }

    /** The hosts of the entries, as a set of this engine's own, for {@link #trackerHosts}. */
    private static Set<String> hostsOf(List<AnnounceEntry> entries) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (AnnounceEntry e : entries) {
            String host = TrackerHost.of(e.url());
            if (!host.isEmpty()) out.add(host);
        }
        return out;
    }

    /** Any thread, no session call: the hosts of the URLs join the torrent's. */
    private void rememberTrackers(String id, List<String> urls) {
        if (urls.isEmpty()) return;
        Set<String> hosts = trackerHosts.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        for (String url : urls) {
            String host = TrackerHost.of(url);
            if (!host.isEmpty()) hosts.add(host);
        }
    }

    @Override
    public synchronized void removeTracker(String id, String url) throws EngineException {
        TorrentHandle h = handle(id);
        List<AnnounceEntry> kept = new ArrayList<>();
        for (AnnounceEntry e : trackers(h)) {
            if (!e.url().equals(url)) kept.add(e);
        }
        h.replaceTrackers(kept);
        trackerHosts.put(id, hostsOf(kept));
        Set<String> errors = trackerErrors.get(id);
        if (errors != null) errors.remove(url);
    }

    @Override
    public synchronized List<String> webSeeds(String id) throws EngineException {
        return List.copyOf(handle(id).urlSeeds());
    }

    @Override
    public synchronized void addWebSeeds(String id, List<String> urls) throws EngineException {
        List<String> invalid = WebSeeds.invalid(urls);
        if (!invalid.isEmpty()) throw new EngineException(tr("engine.error.notAWebSeed", String.join(", ", invalid)));
        TorrentHandle h = handle(id);
        List<String> fresh = WebSeeds.fresh(h.urlSeeds(), urls);
        if (fresh.isEmpty()) return;
        for (String url : fresh) h.addUrlSeed(url);
        requestSave(h, false);
    }

    @Override
    public synchronized void removeWebSeed(String id, String url) throws EngineException {
        TorrentHandle h = handle(id);
        if (!h.urlSeeds().contains(url)) return;
        h.removeUrlSeed(url);
        requestSave(h, false);
    }

    private static Priority fromLibtorrent(org.libtorrent4j.Priority priority) {
        if (priority == org.libtorrent4j.Priority.IGNORE) return Priority.IGNORE;
        if (priority == org.libtorrent4j.Priority.LOW) return Priority.LOW;
        if (priority == org.libtorrent4j.Priority.TOP_PRIORITY) return Priority.HIGH;
        return Priority.NORMAL;
    }

    private TorrentHandle handle(String id) throws EngineException {
        TorrentHandle handle = find(running(), id);
        if (handle == null) throw new EngineException(tr("engine.error.noSuchTorrent", id));
        return handle;
    }

    @Override
    public synchronized List<Torrent> torrents() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return List.of();
        List<Torrent> out = new ArrayList<>();
        for (TorrentHandle handle : handles(s)) out.add(snapshot(handle));
        return out;
    }

    @Override
    public synchronized Rates rates() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return Rates.ZERO;
        return new Rates(s.downloadRate(), s.uploadRate());
    }

    /**
     * Built from the last stats alert, so it never reaches the session and is cheap from any
     * thread: libtorrent4j asks the session for its counters once a second and the listener keeps
     * the values. A counter the library has no metric for is {@link SessionStats#UNKNOWN}.
     */
    /** Read off the alert thread's copy: never the session, so no lock. */
    @Override
    public Connectivity connectivity() {
        return connectivity.get();
    }

    @Override
    public SessionStats stats() {
        int[] idx = counterIndex;
        long[] c = counters;
        StatsStore.Totals base = baseline;
        if (idx == null) {
            return new SessionStats(
                    0,
                    0,
                    base.download(),
                    base.upload(),
                    0,
                    base.activeSeconds(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    SessionStats.Torrents.NONE);
        }
        long down = Math.max(0, value(c, idx, Counter.RECV_PAYLOAD));
        long up = Math.max(0, value(c, idx, Counter.SENT_PAYLOAD));
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAtMs) / 1000);
        long redundant = value(c, idx, Counter.RECV_REDUNDANT);
        long failed = value(c, idx, Counter.RECV_FAILED);
        long queuedDown = value(c, idx, Counter.QUEUED_DOWNLOAD);
        long queuedSeed = value(c, idx, Counter.QUEUED_SEEDING);
        return new SessionStats(
                down,
                up,
                base.download() + down,
                base.upload() + up,
                seconds,
                base.activeSeconds() + seconds,
                value(c, idx, Counter.RECV_BYTES),
                value(c, idx, Counter.SENT_BYTES),
                redundant < 0 || failed < 0 ? SessionStats.UNKNOWN : redundant + failed,
                value(c, idx, Counter.PEERS_CONNECTED),
                value(c, idx, Counter.PEERS_HALF_OPEN),
                value(c, idx, Counter.DHT_NODES),
                value(c, idx, Counter.BLOCKS_READ),
                value(c, idx, Counter.BLOCKS_WRITTEN),
                value(c, idx, Counter.BLOCKS_IN_USE),
                value(c, idx, Counter.QUEUED_DISK_JOBS),
                new SessionStats.Torrents(
                        value(c, idx, Counter.CHECKING),
                        value(c, idx, Counter.DOWNLOADING),
                        value(c, idx, Counter.SEEDING),
                        queuedDown < 0 || queuedSeed < 0 ? SessionStats.UNKNOWN : queuedDown + queuedSeed,
                        value(c, idx, Counter.STOPPED),
                        value(c, idx, Counter.ERRORED)));
    }

    private static long value(long[] counters, int[] index, Counter counter) {
        return index[counter.ordinal()] < 0 ? SessionStats.UNKNOWN : counters[counter.ordinal()];
    }

    /** Writes the loaded totals plus this session's; from the flusher and from {@link #stop()}. */
    private void saveStats() {
        if (counterIndex == null) return;
        SessionStats s = stats();
        try {
            statsStore.save(new StatsStore.Totals(s.allTimeDownload(), s.allTimeUpload(), s.allTimeSeconds()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the statistics file " + statsStore.file(), e);
        }
    }

    /** Asks every torrent for fresh resume data, if it changed. Answers land through the alert. */
    @Override
    public synchronized void flush() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        for (TorrentHandle handle : handles(s)) requestSave(handle, true);
    }

    @Override
    public synchronized void applySettings(EngineSettings settings) throws EngineException {
        engineSettings = settings;
        connectivity.updateAndGet(c -> c.dht(settings.protocol().dht()));
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        s.applySettings(toPack(settings));
        // PEX is a per-torrent flag and default trackers are per-torrent lists: carried to what is
        // already in the session, so a change does not wait for the next add.
        for (TorrentHandle handle : handles(s)) {
            if (settings.protocol().pex()) handle.unsetFlags(Flags.DISABLE_PEX);
            else handle.setFlags(Flags.DISABLE_PEX);
        }
        if (!settings.protocol().defaultTrackers().isEmpty()) {
            for (TorrentHandle handle : handles(s)) {
                try {
                    addTrackers(id(handle), settings.protocol().defaultTrackers());
                } catch (EngineException e) {
                    LOG.fine("default trackers not added to " + id(handle) + ": " + e.getMessage());
                }
            }
        }
        emit(EngineEvent.session(EngineEvent.Level.INFO, tr("engine.event.settingsApplied", settings)));
    }

    /** The session's connection limit as libtorrent has it; for the test. */
    int connectionsLimitForTest() throws EngineException {
        return running().settings().connectionsLimit();
    }

    /** {@link EngineSettings} onto libtorrent's pack. Enum constants are SWIG objects: after load only. */
    private static SettingsPack toPack(EngineSettings s) {
        SettingsPack pack = new SettingsPack();
        pack.listenInterfaces(s.listenInterfaces());
        // Dialled peers too, not only the listening sockets: empty, they follow the system's route.
        pack.setString(settings_pack.string_types.outgoing_interfaces.swigValue(), s.outgoingInterfaces());
        pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), s.upnp());
        pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), s.upnp());
        pack.connectionsLimit(s.maxConnections());
        pack.setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), s.uploadSlots());
        // Never memory-map a file. libtorrent's mmap disk I/O guards its mapped reads with a
        // SIGSEGV/SIGBUS handler of its own, installed in place of the JVM's the first time a
        // file big enough to map (40 pieces by default) is touched; a fault that is not its own,
        // such as the JVM's next safepoint poll, is reset to the default action and the process
        // dies. With the cutoff above every file, the same disk I/O reads with pread, handler-free.
        pack.setInteger(settings_pack.int_types.mmap_file_size_cutoff.swigValue(), Integer.MAX_VALUE);

        settings_pack.proxy_type_t type =
                switch (s.proxy()) {
                    case NONE -> settings_pack.proxy_type_t.none;
                    case SOCKS5 ->
                        s.proxyAuthenticated()
                                ? settings_pack.proxy_type_t.socks5_pw
                                : settings_pack.proxy_type_t.socks5;
                    case HTTP ->
                        s.proxyAuthenticated() ? settings_pack.proxy_type_t.http_pw : settings_pack.proxy_type_t.http;
                };
        pack.setInteger(settings_pack.int_types.proxy_type.swigValue(), type.swigValue());
        pack.setString(
                settings_pack.string_types.proxy_hostname.swigValue(),
                s.proxyHost() == null ? "" : s.proxyHost().trim());
        pack.setInteger(settings_pack.int_types.proxy_port.swigValue(), s.proxyPort());
        pack.setString(
                settings_pack.string_types.proxy_username.swigValue(),
                s.proxyUsername() == null ? "" : s.proxyUsername());
        pack.setString(
                settings_pack.string_types.proxy_password.swigValue(),
                s.proxyPassword() == null ? "" : s.proxyPassword());
        pack.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), s.proxyPeers());
        pack.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true);
        // Resolve host names through the proxy, so a DNS lookup does not leak what the proxy hides.
        pack.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true);

        EngineSettings.Protocol p = s.protocol();
        pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), p.dht());
        pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), p.lsd());
        pack.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), p.utp());
        pack.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), p.utp());
        pack.setBoolean(settings_pack.bool_types.anonymous_mode.swigValue(), p.anonymous());
        settings_pack.enc_policy policy =
                switch (p.encryption()) {
                    case REQUIRE -> settings_pack.enc_policy.pe_forced;
                    case PREFER -> settings_pack.enc_policy.pe_enabled;
                    case OFF -> settings_pack.enc_policy.pe_disabled;
                };
        settings_pack.enc_level level =
                switch (p.encryption()) {
                    case REQUIRE -> settings_pack.enc_level.pe_rc4;
                    case PREFER -> settings_pack.enc_level.pe_both;
                    case OFF -> settings_pack.enc_level.pe_plaintext;
                };
        pack.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), policy.swigValue());
        pack.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), policy.swigValue());
        pack.setInteger(settings_pack.int_types.allowed_enc_level.swigValue(), level.swigValue());

        EngineSettings.Speed speed = s.speed();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        pack.downloadRateLimit(speed.effectiveDownloadBytes(now));
        pack.uploadRateLimit(speed.effectiveUploadBytes(now));
        pack.setBoolean(settings_pack.bool_types.rate_limit_ip_overhead.swigValue(), speed.countOverhead());

        // The queue: 0 is "no limit" to a person and -1 to libtorrent, where 0 would park everything.
        EngineSettings.Queue queue = s.queue();
        pack.activeDownloads(unlimitedAsMinusOne(queue.activeDownloads()));
        pack.activeSeeds(unlimitedAsMinusOne(queue.activeSeeds()));
        pack.activeLimit(unlimitedAsMinusOne(queue.activeLimit()));
        pack.setBoolean(settings_pack.bool_types.dont_count_slow_torrents.swigValue(), queue.ignoreSlowTorrents());
        // libtorrent reads these only to rank seeds for the queue (one past its limits yields its
        // slot first); pausing or removing by them is the window's job, from its poll.
        ShareLimit share = queue.share();
        pack.setInteger(
                settings_pack.int_types.share_ratio_limit.swigValue(),
                share.ratio() > 0 ? (int) Math.round(share.ratio() * 100) : Integer.MAX_VALUE);
        pack.setInteger(
                settings_pack.int_types.seed_time_limit.swigValue(),
                share.seedingMinutes() > 0 ? share.seedingMinutes() * 60 : Integer.MAX_VALUE);
        return pack;
    }

    private static int unlimitedAsMinusOne(int count) {
        return count <= 0 ? -1 : count;
    }

    /** The session's active counts as libtorrent has them (downloads, seeds, all); for the test. */
    int[] activeLimitsForTest() throws EngineException {
        SettingsPack current = running().settings();
        return new int[] {current.activeDownloads(), current.activeSeeds(), current.activeLimit()};
    }

    @Override
    public synchronized boolean refreshLimits(java.time.LocalDateTime now) throws EngineException {
        SessionManager s = session;
        EngineSettings.Speed speed = engineSettings.speed();
        boolean alternative = speed.alternativeActive(now);
        if (s == null || !s.isRunning()) return alternative;
        int down = speed.effectiveDownloadBytes(now);
        int up = speed.effectiveUploadBytes(now);
        SettingsPack current = s.settings();
        if (current.downloadRateLimit() != down || current.uploadRateLimit() != up) {
            SettingsPack pack = new SettingsPack();
            pack.downloadRateLimit(down);
            pack.uploadRateLimit(up);
            s.applySettings(pack);
            emit(EngineEvent.session(
                    EngineEvent.Level.INFO,
                    tr(
                            alternative ? "engine.event.speedLimitsAlternative" : "engine.event.speedLimits",
                            limitText(down),
                            limitText(up))));
        }
        return alternative;
    }

    /** A rate limit in bytes per second as the log says it: KiB/s, or "none" for 0. */
    private static String limitText(int bytesPerSecond) {
        return bytesPerSecond == 0 ? tr("engine.event.noLimit") : tr("engine.event.kibps", bytesPerSecond / 1024);
    }

    /** The session's limits as libtorrent has them, bytes per second; for the test. */
    int[] rateLimitsForTest() throws EngineException {
        SettingsPack current = running().settings();
        return new int[] {current.downloadRateLimit(), current.uploadRateLimit()};
    }

    @Override
    public synchronized void setIpFilter(List<IpRange> ranges) throws EngineException {
        boolean had = !ipFilter.isEmpty();
        ipFilter = List.copyOf(ranges);
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        s.swig().set_ip_filter(toFilter(ipFilter));
        // Clearing a filter that was never set (the start, with the filter off) is not news.
        if (had || !ipFilter.isEmpty())
            emit(EngineEvent.session(
                    EngineEvent.Level.INFO,
                    ipFilter.isEmpty()
                            ? tr("engine.event.ipFilterCleared")
                            : tr("engine.event.ipFilter", ipFilter.size())));
    }

    @Override
    public synchronized long blockedPeers() {
        return blockedPeers.get();
    }

    /** Whether the session's filter refuses the address; for the test. */
    boolean isBlockedForTest(String ip) throws EngineException {
        return running().swig().get_ip_filter().access(Address.parseIp(ip).swig()) != 0;
    }

    private static ip_filter toFilter(List<IpRange> ranges) {
        ip_filter filter = new ip_filter();
        long blocked = ip_filter.access_flags.blocked.swigValue();
        for (IpRange r : ranges) {
            filter.add_rule(
                    Address.parseIp(r.first()).swig(), Address.parseIp(r.last()).swig(), blocked);
        }
        return filter;
    }

    /** Whether DHT is on in the session; for the test. */
    boolean dhtEnabledForTest() throws EngineException {
        return running().settings().isEnableDht();
    }

    @Override
    public void addListener(Consumer<EngineEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Consumer<EngineEvent> listener) {
        listeners.remove(listener);
    }

    private void emit(EngineEvent event) {
        for (Consumer<EngineEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Event listener failed", e);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (session == null) return;
        if (flusher != null) flusher.shutdownNow();
        saveStats();
        for (Fetch fetch : List.copyOf(fetches.values()))
            fetch.end(null, new EngineException(tr("engine.error.stopped")));
        // Flush with the disk cache, then wait for the alerts, bounded: libtorrent answers every
        // request (success or "not modified"), but a wedged disk must not hold the quit.
        for (TorrentHandle handle : handles(session)) requestSave(handle, true, true);
        long deadline = System.currentTimeMillis() + STOP_FLUSH_TIMEOUT_MS;
        while (pendingSaves.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (pendingSaves.get() > 0) {
            LOG.warning(pendingSaves.get() + " resume-data write(s) did not finish before stop");
        }
        session.stop();
        session = null;
        connectivity.set(Connectivity.NONE);
        emit(EngineEvent.session(EngineEvent.Level.INFO, tr("engine.event.stopped")));
    }

    private void requestSave(TorrentHandle handle, boolean onlyIfModified) {
        requestSave(handle, onlyIfModified, false);
    }

    private void requestSave(TorrentHandle handle, boolean onlyIfModified, boolean flushDisk) {
        if (!handle.isValid()) return;
        resume_data_flags_t flags = TorrentHandle.SAVE_INFO_DICT;
        if (onlyIfModified) flags = flags.or_(TorrentHandle.ONLY_IF_MODIFIED);
        if (flushDisk) flags = flags.or_(TorrentHandle.FLUSH_DISK_CACHE);
        pendingSaves.incrementAndGet();
        handle.saveResumeData(flags);
    }

    /**
     * Every torrent in the session, as handles Java owns.
     *
     * <p>A SWIG vector's {@code get(i)} is a pointer into the vector, and the vector's finalizer
     * frees it: a handle kept past the vector is a use-after-free that crashed the JVM on CI. So
     * each element is only read for its hash, and the handle handed out is the session's own copy
     * from {@code find_torrent}; the vector is kept reachable until that is done. Metadata fetches
     * are left out.
     */
    private List<TorrentHandle> handles(SessionManager s) {
        session_handle session = s.swig();
        torrent_handle_vector vector = session.get_torrents();
        try {
            List<TorrentHandle> out = new ArrayList<>(vector.size());
            for (int i = 0; i < vector.size(); i++) {
                // A magnet the Add dialog is fetching, or one on its way out, is not the person's.
                sha1_hash best = bestHash(vector.get(i));
                if (fetchOf(vector.get(i)) != null || endedFetches.contains(best.to_hex())) continue;
                TorrentHandle handle = new TorrentHandle(session.find_torrent(best));
                if (handle.isValid()) out.add(handle);
            }
            return out;
        } finally {
            Reference.reachabilityFence(vector);
        }
    }

    private static TorrentHandle find(SessionManager s, String id) {
        session_handle session = s.swig();
        torrent_handle_vector vector = session.get_torrents();
        try {
            for (int i = 0; i < vector.size(); i++) {
                sha1_hash best = bestHash(vector.get(i));
                if (!best.to_hex().equals(id)) continue;
                TorrentHandle handle = new TorrentHandle(session.find_torrent(best));
                return handle.isValid() ? handle : null;
            }
            return null;
        } finally {
            Reference.reachabilityFence(vector);
        }
    }

    /**
     * The fetch a handle belongs to, if any, by either hash: a v1 magnet of a hybrid torrent has a
     * new best hash (the v2 one) once its metadata is in, and is still the same fetch.
     */
    private Fetch fetchOf(torrent_handle raw) {
        if (fetches.isEmpty()) return null;
        info_hash_t hashes = raw.info_hashes();
        try {
            Fetch fetch = fetches.get(hashes.get_best().to_hex());
            return fetch != null || !hashes.has_v1()
                    ? fetch
                    : fetches.get(hashes.getV1().to_hex());
        } finally {
            Reference.reachabilityFence(hashes);
        }
    }

    /** The best info-hash as libtorrent keys it (v2 truncated to 20 bytes), owned by Java. */
    private static sha1_hash bestHash(torrent_handle handle) {
        info_hash_t hashes = handle.info_hashes();
        try {
            return hashes.get_best();
        } finally {
            Reference.reachabilityFence(hashes);
        }
    }

    private SessionManager running() throws EngineException {
        SessionManager s = session;
        if (s == null || !s.isRunning()) throw new EngineException(tr("engine.error.notRunning"));
        return s;
    }

    /** The best info-hash (v2 if the torrent has one, else v1) in hex: the id everywhere. */
    private static String id(TorrentHandle handle) {
        return bestHash(handle.swig()).to_hex();
    }

    /**
     * Alert thread. A torrent whose files are known and still to download: the first volume of
     * each wanted archive (and a 7z's end, where its header is) gets a deadline that asks
     * libtorrent for a {@code read_piece_alert} when the piece is in — at once, from disk, if it
     * already is. {@link #onPieceRead} takes it from there. A finished torrent restored at start
     * is not read, or a startup would read pieces of every seed; a new one is, finished or not (a
     * small torrent can be done before its metadata alert is even handled).
     *
     * @param restored the torrent was re-added from the store at start
     */
    private void probeArchives(TorrentHandle h, boolean restored) {
        if (!h.isValid()) return;
        TorrentInfo info = h.torrentFile();
        if (info == null) return;
        String id = id(h);
        if (archiveReads.containsKey(id) || (restored && h.status().isFinished())) return;
        FileStorage files = info.files();
        int pieceLength = info.pieceLength();
        org.libtorrent4j.Priority[] priorities = h.filePriorities();
        Map<Integer, com.torrenta.engine.ArchiveProbe.Reading> byFile = new ConcurrentHashMap<>();
        for (int i = 0; i < files.numFiles() && byFile.size() < ARCHIVES_PER_TORRENT; i++) {
            if (files.padFileAt(i) || files.fileSize(i) == 0) continue;
            if (i < priorities.length && priorities[i] == org.libtorrent4j.Priority.IGNORE) continue;
            String path = slashes(files.filePath(i));
            if (!com.torrenta.engine.ArchiveProbe.firstVolume(path)) continue;
            byFile.put(
                    i,
                    new com.torrenta.engine.ArchiveProbe.Reading(
                            i, path, files.fileOffset(i), files.fileSize(i), pieceLength));
        }
        Reference.reachabilityFence(info);
        if (byFile.isEmpty()) return;
        archiveReads.put(id, new ArchiveReads(pieceLength, byFile));
        Set<Integer> pieces = new java.util.TreeSet<>();
        for (com.torrenta.engine.ArchiveProbe.Reading r : byFile.values()) pieces.addAll(r.missing());
        // A deadline also raises the piece to the top of the picker: the verdict comes first.
        for (int piece : pieces) h.setPieceDeadline(piece, 500, TorrentHandle.ALERT_WHEN_AVAILABLE);
    }

    /**
     * Alert thread. A piece a deadline asked for: whatever head or tail of an archive it covers is
     * gathered, and an archive whose bytes are all in gets its verdict. An encrypted one is kept
     * for {@link #detail} (the Overview's card) and said once in the log.
     */
    private void onPieceRead(ReadPieceAlert alert) {
        if (alert.error().isError() || !alert.handle().isValid()) return;
        String id = id(alert.handle());
        ArchiveReads reads = archiveReads.get(id);
        if (reads == null) return;
        int piece = alert.piece();
        List<com.torrenta.engine.ArchiveProbe.Reading> wanting = reads.byFile().values().stream()
                .filter(r -> r.missing().contains(piece))
                .toList();
        if (wanting.isEmpty()) return;
        byte[] data = NativeBytes.copy(alert.bufferPtr(), alert.size());
        for (com.torrenta.engine.ArchiveProbe.Reading r : wanting) {
            if (!r.accept(piece, data, reads.pieceLength())) continue;
            reads.byFile().remove(r.fileIndex());
            if (r.verdict() == com.torrenta.engine.ArchiveProbe.Verdict.ENCRYPTED) {
                encryptedArchives
                        .computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet())
                        .add(r.fileIndex());
                String file = fileName(alert.handle(), r.fileIndex());
                emit(EngineEvent.warning(
                        id, nameOf(alert.handle()), tr("engine.event.archiveEncrypted", nameOf(alert.handle()), file)));
            }
        }
        if (reads.byFile().isEmpty()) archiveReads.remove(id);
    }

    /** A file's name inside the torrent, for a log line; its index while the info is not there. */
    private static String fileName(TorrentHandle h, int index) {
        TorrentInfo info = h.torrentFile();
        if (info == null) return String.valueOf(index);
        String path = slashes(info.files().filePath(index));
        Reference.reachabilityFence(info);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * The name a person gave it, else its own, else the hash while there is none; empty for a
     * handle that is already gone.
     */
    private String nameOf(TorrentHandle handle) {
        if (!handle.isValid()) return "";
        TorrentStatus status = handle.status(StatusQuery.FLAGS);
        String id = status.getInfoHashes().getBest().toHex();
        return displayName(id, status.name());
    }

    private String displayName(String id, String own) {
        String given = labels.getOrDefault(id, TorrentLabels.NONE).name();
        if (!given.isEmpty()) return given;
        return own == null || own.isEmpty() ? id : own;
    }

    /**
     * Initialised on first use, never when this class loads: {@code TorrentHandle}'s constants are
     * SWIG objects, and touching one before {@link NativeLibrary#ensureAvailable()} has run makes
     * the JNI class fail to initialise — and then every later use of it, for the life of the JVM.
     */
    /**
     * The {@code session_stats} metrics the engine reads, by libtorrent's names. Strings only, so
     * the enum is safe to load before the native is; the indices come from {@link #resolve} at
     * start. A name the library does not know resolves to -1 and shows as a dash.
     */
    enum Counter {
        RECV_PAYLOAD("net.recv_payload_bytes"),
        SENT_PAYLOAD("net.sent_payload_bytes"),
        RECV_BYTES("net.recv_bytes"),
        SENT_BYTES("net.sent_bytes"),
        RECV_REDUNDANT("net.recv_redundant_bytes"),
        RECV_FAILED("net.recv_failed_bytes"),
        PEERS_CONNECTED("peer.num_peers_connected"),
        PEERS_HALF_OPEN("peer.num_peers_half_open"),
        DHT_NODES("dht.dht_nodes"),
        BLOCKS_READ("disk.num_blocks_read"),
        BLOCKS_WRITTEN("disk.num_blocks_written"),
        BLOCKS_IN_USE("disk.disk_blocks_in_use"),
        QUEUED_DISK_JOBS("disk.queued_disk_jobs"),
        CHECKING("ses.num_checking_torrents"),
        DOWNLOADING("ses.num_downloading_torrents"),
        SEEDING("ses.num_seeding_torrents"),
        QUEUED_DOWNLOAD("ses.num_queued_download_torrents"),
        QUEUED_SEEDING("ses.num_queued_seeding_torrents"),
        STOPPED("ses.num_stopped_torrents"),
        ERRORED("ses.num_error_torrents");

        final String metric;

        Counter(String metric) {
            this.metric = metric;
        }

        /** Each counter's index into a stats alert, in ordinal order; native, so after the load. */
        static int[] resolve() {
            Counter[] all = values();
            int[] out = new int[all.length];
            for (int i = 0; i < all.length; i++) out[i] = libtorrent.find_metric_idx_ex(all[i].metric);
            return out;
        }
    }

    private static final class StatusQuery {
        /** The default status query leaves the strings out (they cost a copy per call); ask. */
        static final status_flags_t FLAGS = TorrentHandle.QUERY_NAME.or_(TorrentHandle.QUERY_SAVE_PATH);

        /** For the detail pane: the pieces bitfield too, which costs a copy per piece. */
        static final status_flags_t ALL =
                FLAGS.or_(TorrentHandle.QUERY_PIECES).or_(TorrentHandle.QUERY_DISTRIBUTED_COPIES);
    }

    /** Same rule as {@link StatusQuery}: {@code peer_info}'s flag constants are SWIG objects. */
    private static final class PeerConstants {
        static final peer_flags_t LOCAL_CONNECTION = peer_info.local_connection;
    }

    /** Same rule as {@link StatusQuery}: {@code TorrentFlags}' constants are SWIG objects. */
    private static final class Flags {
        static final torrent_flags_t PAUSED = TorrentFlags.PAUSED;
        static final torrent_flags_t AUTO_MANAGED = TorrentFlags.AUTO_MANAGED;
        static final torrent_flags_t SEQUENTIAL_DOWNLOAD = TorrentFlags.SEQUENTIAL_DOWNLOAD;
        static final torrent_flags_t SEED_MODE = TorrentFlags.SEED_MODE;
        static final torrent_flags_t STOP_WHEN_READY = TorrentFlags.STOP_WHEN_READY;
        static final torrent_flags_t DISABLE_PEX = TorrentFlags.DISABLE_PEX;
        static final torrent_flags_t UPLOAD_MODE = TorrentFlags.UPLOAD_MODE;
        static final torrent_flags_t SUPER_SEEDING = TorrentFlags.SUPER_SEEDING;
    }

    /**
     * Same rule as {@link StatusQuery}: {@code create_torrent}'s and {@code file_storage}'s
     * constants are SWIG objects.
     */
    private static final class CreateFlags {
        static final create_flags_t V1_ONLY = create_torrent.v1_only;
        static final create_flags_t V2_ONLY = create_torrent.v2_only;
        static final create_flags_t CANONICAL_FILES = create_torrent.canonical_files;
        static final file_flags_t PAD_FILE = file_storage.flag_pad_file;

        /** No flag is a hybrid; padding is libtorrent's canonical layout, implied by v2. */
        static create_flags_t of(CreateRequest request) {
            create_flags_t flags = new create_flags_t();
            switch (request.format()) {
                case V1 -> {
                    flags = flags.or_(V1_ONLY);
                    if (request.alignFiles()) flags = flags.or_(CANONICAL_FILES);
                }
                case V2 -> flags = flags.or_(V2_ONLY);
                case HYBRID -> {}
            }
            return flags;
        }
    }

    private static boolean has(torrent_flags_t flags, torrent_flags_t flag) {
        return flags.and_(flag).non_zero();
    }

    private Torrent snapshot(TorrentHandle handle) {
        TorrentStatus status = handle.status(StatusQuery.FLAGS);
        torrent_flags_t flags = status.flags();
        String id = status.getInfoHashes().getBest().toHex();
        String name = displayName(id, status.name());
        String tracker = status.currentTracker();
        TorrentLabels l = labels.getOrDefault(id, TorrentLabels.NONE);
        ErrorCode error = status.errorCode();
        Set<String> hosts = trackerHosts.get(id);
        Set<String> errors = trackerErrors.get(id);
        return new Torrent(
                id,
                name,
                status.totalWanted(),
                status.totalWantedDone(),
                status.progress(),
                state(status.state()),
                status.downloadPayloadRate(),
                status.uploadPayloadRate(),
                status.numPeers(),
                status.numSeeds(),
                status.allTimeUpload(),
                status.allTimeDownload(),
                // Epoch milliseconds from libtorrent4j (its time2millis); the record promises seconds.
                status.addedTime() / 1000,
                status.completedTime() / 1000,
                tracker == null ? "" : tracker,
                status.swig().getSave_path(),
                // Paused by a person, not parked by the queue: the queue pauses auto-managed ones.
                has(flags, Flags.PAUSED) && !has(flags, Flags.AUTO_MANAGED),
                has(flags, Flags.SEQUENTIAL_DOWNLOAD),
                // Milliseconds from libtorrent4j; the record promises seconds. Finished rather than
                // seeding: a torrent with files left out never becomes a seed, but it is done.
                status.finishedDuration() / 1000,
                l.category(),
                l.tags(),
                error != null && error.isError() ? error.getMessage() : "",
                hosts == null ? List.of() : hosts.stream().sorted().toList(),
                errors != null && !errors.isEmpty(),
                // Parked by the queue: paused, but auto-managed, so libtorrent resumes it itself.
                has(flags, Flags.PAUSED) && has(flags, Flags.AUTO_MANAGED),
                status.queuePosition(),
                status.isMovingStorage(),
                has(flags, Flags.SUPER_SEEDING));
    }

    /** libtorrent's seven states folded into the list's six. Package-private for the test. */
    static TorrentState state(TorrentStatus.State state) {
        return switch (state) {
            case CHECKING_FILES, CHECKING_RESUME_DATA -> TorrentState.CHECKING;
            case DOWNLOADING_METADATA -> TorrentState.FETCHING_METADATA;
            case DOWNLOADING -> TorrentState.DOWNLOADING;
            case FINISHED -> TorrentState.FINISHED;
            case SEEDING -> TorrentState.SEEDING;
            case UNKNOWN -> TorrentState.UNKNOWN;
        };
    }

    /** Runs on libtorrent4j's alert thread. Nothing here may block on the session. */
    private final class Listener implements AlertListener {

        private final int[] types = {
            AlertType.ADD_TORRENT.swig(),
            AlertType.SESSION_STATS.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_CHECKED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_COMPLETED.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.FILE_RENAMED.swig(),
            AlertType.FILE_RENAME_FAILED.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.TRACKER_WARNING.swig(),
            AlertType.TRACKER_REPLY.swig(),
            AlertType.TRACKER_ANNOUNCE.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.PORTMAP.swig(),
            AlertType.PORTMAP_ERROR.swig(),
            AlertType.INCOMING_CONNECTION.swig(),
            AlertType.DHT_BOOTSTRAP.swig(),
            AlertType.PEER_BLOCKED.swig(),
            AlertType.PEER_CONNECT.swig(),
            AlertType.STORAGE_MOVED.swig(),
            AlertType.STORAGE_MOVED_FAILED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.READ_PIECE.swig(),
            AlertType.PEER_DISCONNECTED.swig(),
            AlertType.PEER_ERROR.swig(),
        };

        @Override
        public int[] types() {
            return types;
        }

        @Override
        public void alert(Alert<?> alert) {
            try {
                switch (alert.type()) {
                    case ADD_TORRENT -> onAdded((AddTorrentAlert) alert);
                    case SESSION_STATS -> onStats((SessionStatsAlert) alert);
                    case METADATA_RECEIVED -> {
                        TorrentAlert<?> t = (TorrentAlert<?>) alert;
                        Fetch fetch = t.handle().isValid() ? fetchOf(t.handle().swig()) : null;
                        if (fetch != null) {
                            fetch.arrived(t.handle());
                            return;
                        }
                        requestSave(t.handle(), false);
                        if (t.handle().isValid() && stopAfterMetadata.remove(id(t.handle()))) {
                            t.handle().unsetFlags(Flags.AUTO_MANAGED);
                            t.handle().pause();
                            torrentEvent(t, EngineEvent.Level.INFO, "engine.event.metadataStopped");
                        } else {
                            torrentEvent(t, EngineEvent.Level.INFO, "engine.event.metadata");
                        }
                        probeArchives(t.handle(), false);
                    }
                    case TORRENT_FINISHED -> {
                        TorrentAlert<?> t = (TorrentAlert<?>) alert;
                        requestSave(t.handle(), false);
                        torrentEvent(t, EngineEvent.Level.INFO, EngineEvent.Kind.FINISHED, "engine.event.finished");
                        if (t.handle().isValid()) {
                            // Names first, then the move: both are disk jobs, done in this order.
                            applySuffixes(id(t.handle()), t.handle());
                            maybeMove(id(t.handle()), t.handle());
                        }
                    }
                    case TORRENT_CHECKED -> {
                        TorrentAlert<?> t = (TorrentAlert<?>) alert;
                        torrentEvent(t, EngineEvent.Level.INFO, "engine.event.checked");
                        if (t.handle().isValid()) {
                            applySuffixes(id(t.handle()), t.handle());
                            // Finished at the check (the files were there, or a move was owed
                            // from before a restart): the finished alert does not come again.
                            maybeMove(id(t.handle()), t.handle());
                        }
                    }
                    case FILE_COMPLETED -> {
                        FileCompletedAlert t = (FileCompletedAlert) alert;
                        if (t.handle().isValid()) unsuffix(id(t.handle()), t.handle(), t.index());
                    }
                    case STORAGE_MOVED -> {
                        StorageMovedAlert t = (StorageMovedAlert) alert;
                        if (t.handle().isValid()) {
                            String id = id(t.handle());
                            moving.remove(id);
                            TorrentStorage owed = storage.get(id);
                            if (owed != null) writeStorageQuietly(id, owed.withDestination(""));
                            // The save path is part of the resume data: written now, not later.
                            requestSave(t.handle(), false);
                        }
                        torrentEvent(t, EngineEvent.Level.INFO, "engine.event.moved", t.storagePath());
                    }
                    case STORAGE_MOVED_FAILED -> {
                        StorageMovedFailedAlert t = (StorageMovedFailedAlert) alert;
                        if (t.handle().isValid()) moving.remove(id(t.handle()));
                        torrentEvent(
                                t,
                                EngineEvent.Level.ERROR,
                                t.filePath().isEmpty() ? "engine.event.moveFailed" : "engine.event.moveFailedFile",
                                t.error().getMessage(),
                                t.filePath());
                    }
                    case TORRENT_ERROR -> {
                        TorrentErrorAlert t = (TorrentErrorAlert) alert;
                        torrentEvent(
                                t,
                                EngineEvent.Level.ERROR,
                                EngineEvent.Kind.ERROR,
                                t.filename().isEmpty() ? "engine.event.torrentError" : "engine.event.torrentErrorFile",
                                t.error().getMessage(),
                                t.filename());
                    }
                    case FILE_RENAMED -> {
                        FileRenamedAlert t = (FileRenamedAlert) alert;
                        // No line per file: the suffix going on and off is the engine's own doing,
                        // and a person's rename says itself once, from renamePath. A folder that
                        // the rename emptied goes, as it would in a file manager.
                        if (t.handle().isValid())
                            removeEmptyFolders(
                                    t.handle().status(StatusQuery.FLAGS).swig().getSave_path(),
                                    t.swig().old_name());
                    }
                    case FILE_RENAME_FAILED -> {
                        FileRenameFailedAlert t = (FileRenameFailedAlert) alert;
                        torrentEvent(
                                t,
                                EngineEvent.Level.ERROR,
                                "engine.event.fileRenameFailed",
                                String.valueOf(t.getIndex()),
                                t.getError().getMessage());
                    }
                    case FILE_ERROR -> {
                        FileErrorAlert t = (FileErrorAlert) alert;
                        torrentEvent(
                                t,
                                EngineEvent.Level.ERROR,
                                EngineEvent.Kind.ERROR,
                                "engine.event.fileError",
                                t.error().getMessage(),
                                t.filename());
                    }
                    case TRACKER_ERROR -> {
                        TrackerErrorAlert t = (TrackerErrorAlert) alert;
                        String reason = t.errorMessage().isEmpty() ? t.error().getMessage() : t.errorMessage();
                        onTracker(t, true);
                        torrentEvent(
                                t,
                                EngineEvent.Level.WARNING,
                                EngineEvent.Kind.ERROR,
                                "engine.event.tracker",
                                t.trackerUrl(),
                                reason);
                    }
                    case TRACKER_WARNING -> {
                        TrackerWarningAlert t = (TrackerWarningAlert) alert;
                        onTracker(t, false);
                        torrentEvent(t, EngineEvent.Level.WARNING, "engine.event.tracker", t.trackerUrl(), t.message());
                    }
                    case TRACKER_REPLY -> onTracker((TrackerAlert<?>) alert, false);
                    case TRACKER_ANNOUNCE -> {
                        // An announce says nothing about the outcome; only the host is noted.
                        TrackerAlert<?> t = (TrackerAlert<?>) alert;
                        if (t.handle().isValid()) rememberTrackers(id(t.handle()), List.of(t.trackerUrl()));
                    }
                    case LISTEN_SUCCEEDED -> {
                        ListenSucceededAlert a = (ListenSucceededAlert) alert;
                        // Peers connect over TCP or uTP on the same port; TCP's answer stands for both.
                        if (a.socketType() == SocketType.TCP)
                            connectivity.updateAndGet(c -> c.listened(a.port(), true));
                        emit(EngineEvent.session(
                                EngineEvent.Level.INFO,
                                tr(
                                        "engine.event.listening",
                                        a.address() + ":" + a.port(),
                                        String.valueOf(a.socketType()))));
                    }
                    case LISTEN_FAILED -> {
                        ListenFailedAlert a = (ListenFailedAlert) alert;
                        if (a.socketType() == SocketType.TCP)
                            connectivity.updateAndGet(c -> c.listened(a.port() > 0 ? a.port() : c.port(), false));
                        emit(EngineEvent.session(
                                EngineEvent.Level.ERROR,
                                tr(
                                        "engine.event.listenFailed",
                                        a.listenInterface() + ":" + a.port(),
                                        String.valueOf(a.socketType()),
                                        a.error().getMessage())));
                    }
                    case PORTMAP -> {
                        PortmapAlert a = (PortmapAlert) alert;
                        connectivity.updateAndGet(c -> c.mapped(true));
                        emit(EngineEvent.session(
                                EngineEvent.Level.INFO,
                                tr(
                                        "engine.event.portMapped",
                                        String.valueOf(a.externalPort()),
                                        String.valueOf(a.mapTransport()),
                                        String.valueOf(a.mapProtocol()))));
                    }
                    case PORTMAP_ERROR -> {
                        PortmapErrorAlert a = (PortmapErrorAlert) alert;
                        connectivity.updateAndGet(c -> c.mapped(false));
                        emit(EngineEvent.session(
                                EngineEvent.Level.WARNING,
                                tr(
                                        "engine.event.portMapFailed",
                                        String.valueOf(a.mapTransport()),
                                        a.error().getMessage())));
                    }
                    // The proof the port is reachable: a peer found it. Not logged, there are many.
                    case INCOMING_CONNECTION -> {
                        String from = ((IncomingConnectionAlert) alert)
                                .endpoint()
                                .address()
                                .toString();
                        connectivity.updateAndGet(c -> c.connectedFrom(from));
                    }
                    case DHT_BOOTSTRAP -> connectivity.updateAndGet(Connectivity::bootstrapped);
                    // Counted, not logged: a busy blocklist refuses peers by the hundred.
                    case PEER_BLOCKED -> blockedPeers.incrementAndGet();
                    case PEER_CONNECT -> {
                        PeerConnectAlert c = (PeerConnectAlert) alert;
                        logPeer("connect " + c.direction() + " " + c.endpoint());
                        onPeerConnected(c);
                    }
                    case PEER_DISCONNECTED -> {
                        PeerDisconnectedAlert d = (PeerDisconnectedAlert) alert;
                        logPeer("disconnected " + d.endpoint() + " " + d.operation() + ": "
                                + d.error().getMessage() + " (" + d.reason() + ")");
                    }
                    case PEER_ERROR -> {
                        PeerErrorAlert e = (PeerErrorAlert) alert;
                        logPeer("error " + e.endpoint() + " " + e.operation() + ": "
                                + e.error().getMessage());
                    }
                    case SAVE_RESUME_DATA -> onSaved((SaveResumeDataAlert) alert);
                    case SAVE_RESUME_DATA_FAILED -> onSaveFailed((SaveResumeDataFailedAlert) alert);
                    case READ_PIECE -> onPieceRead((ReadPieceAlert) alert);
                    default -> {}
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Alert handling failed: " + alert.what(), e);
            }
        }

        /**
         * The add params written straight away. For a magnet that is the link's content — hashes,
         * name, trackers, save path — which is exactly what brings it back after a crash; the real
         * resume data replaces it when the metadata arrives.
         */
        /** Once a second, by libtorrent4j's own request: the counters are copied out, nothing else. */
        private void onStats(SessionStatsAlert alert) {
            int[] idx = counterIndex;
            if (idx == null) return;
            long[] values = new long[idx.length];
            for (int i = 0; i < idx.length; i++) values[i] = idx[i] < 0 ? SessionStats.UNKNOWN : alert.value(idx[i]);
            counters = values;
        }

        private void onAdded(AddTorrentAlert alert) {
            if (fetchAddAlerts.remove(alert.params().getInfoHashes().getBest().toHex())) return;
            if (alert.error().isError()) {
                String name = alert.params().getName();
                emit(EngineEvent.error(
                        null,
                        name,
                        tr("engine.event.addFailed", name, alert.error().getMessage())));
                return;
            }
            write(alert.params());
            // The add params carry the trackers only for a magnet; for a .torrent they live inside
            // the info and the params' own list is empty, so a blob written from the params alone
            // would bring the torrent back without its trackers. libtorrent's own resume data has
            // them: ask for it now, and it replaces the blob shortly after.
            requestSave(alert.handle(), false);
            String id = alert.params().getInfoHashes().getBest().toHex();
            // A torrent restored at start is not new to the person; nothing runs on it.
            boolean restored = restoring.remove(id);
            if (restored) torrentEvent(alert, EngineEvent.Level.INFO, "engine.event.restored");
            else torrentEvent(alert, EngineEvent.Level.INFO, EngineEvent.Kind.ADDED, "engine.event.added");
            probeArchives(alert.handle(), restored);
        }

        /**
         * A tracker answered: its host is one of the torrent's (a tracker learnt from a peer
         * exchange is first seen here), and the URL is in error or out of it.
         */
        private void onTracker(TrackerAlert<?> alert, boolean failed) {
            TorrentHandle handle = alert.handle();
            if (!handle.isValid()) return;
            String id = id(handle);
            rememberTrackers(id, List.of(alert.trackerUrl()));
            if (failed) {
                trackerErrors
                        .computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet())
                        .add(alert.trackerUrl());
            } else {
                Set<String> errors = trackerErrors.get(id);
                if (errors != null) errors.remove(alert.trackerUrl());
            }
        }

        /** A fetch's outgoing connections are remembered for the torrent it becomes; nothing else. */
        private void onPeerConnected(PeerConnectAlert alert) {
            if (fetches.isEmpty() || alert.direction() != PeerConnectAlert.Direction.OUT) return;
            TorrentHandle handle = alert.handle();
            Fetch fetch = handle.isValid() ? fetchOf(handle.swig()) : null;
            if (fetch == null) return;
            TcpEndpoint peer = alert.endpoint();
            fetch.dialled(new Endpoint(peer.address().toString(), peer.port()));
        }

        /** {@code key}'s text has the torrent's name as {0}; {@code more} fills {1} on. */
        private void torrentEvent(TorrentAlert<?> alert, EngineEvent.Level level, String key, Object... more) {
            torrentEvent(alert, level, EngineEvent.Kind.OTHER, key, more);
        }

        private void torrentEvent(
                TorrentAlert<?> alert, EngineEvent.Level level, EngineEvent.Kind kind, String key, Object... more) {
            TorrentHandle handle = alert.handle();
            if (!handle.isValid() || fetchOf(handle.swig()) != null) return;
            String id = id(handle);
            // A torrent being torn down reads an all-zero hash; an ended fetch is not the person's.
            if (id.chars().allMatch(c -> c == '0') || endedFetches.contains(id)) return;
            String name = nameOf(handle);
            Object[] args = new Object[more.length + 1];
            args[0] = name;
            System.arraycopy(more, 0, args, 1, more.length);
            emit(EngineEvent.of(kind, level, id, name, tr(key, args)));
        }

        private void onSaved(SaveResumeDataAlert alert) {
            pendingSaves.decrementAndGet();
            write(alert.params());
        }

        private void onSaveFailed(SaveResumeDataFailedAlert alert) {
            pendingSaves.decrementAndGet();
            // "Resume data not modified" is the normal answer to an only-if-modified request.
            if (alert.error().isError()) {
                LOG.fine("Resume data not written for " + alert.handle().infoHash() + ": " + alert.message());
            }
        }

        private void write(AddTorrentParams params) {
            String id = params.getInfoHashes().getBest().toHex();
            // Check and write under one lock: see remove(). Without it CI saw the remove test's
            // blob come back, written by a save that had passed the check before the delete.
            synchronized (store) {
                if (removed.contains(id)) return;
                try {
                    store.save(id, AddTorrentParams.writeResumeDataBuf(params));
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not write resume data for " + id, e);
                }
            }
        }
    }
}
