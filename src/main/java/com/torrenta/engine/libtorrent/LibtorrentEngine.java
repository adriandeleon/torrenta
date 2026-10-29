package com.torrenta.engine.libtorrent;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.torrenta.engine.ContentIndex;
import com.torrenta.engine.ContentKeys;
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
import com.torrenta.engine.SwarmPreview;
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
import org.jspecify.annotations.Nullable;
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
import org.libtorrent4j.alerts.ExternalIpAlert;
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
import org.libtorrent4j.alerts.PieceFinishedAlert;
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
    /**
     * The periodic flush is spread over its window in this many slices, one every
     * {@code PERIODIC_FLUSH_SECONDS / FLUSH_SLICES} seconds: each torrent is still asked once per
     * window, but a few thousand do not answer into the alert queue in one burst.
     */
    static final int FLUSH_SLICES = 12;

    /**
     * Alerts libtorrent queues between two pops before it drops new ones (2,000 by default in
     * 2.1; resume-data answers are critical and get three times that, a finished torrent's high
     * and gets twice). A flush or a stop asks every torrent at once, and libtorrent4j subscribes
     * to every category but the logs, so a few thousand torrents filled the default and the
     * metadata and finished alerts dropped behind it went unheard. The limit costs nothing until
     * it is reached.
     */
    static final int ALERT_QUEUE_SIZE = 20_000;

    /** A burst of dropped alerts is said once, then not again for this long. */
    static final long DROP_WARNING_MS = 60_000;

    private static final long STOP_FLUSH_TIMEOUT_MS = 5000;
    /** How often the all-time totals are written; a crash costs at most this much counting. */
    private static final long STATS_SAVE_SECONDS = 300;

    /**
     * libtorrent's message for {@code errors::upload_upload_connection}, the one sign of a seed
     * that dropped a probe at the handshake. libtorrent4j exposes no error category or enum to
     * compare with; the text is libtorrent's own, never translated, and pinned with the version
     * (a {@code .torrent} probe's test meets its seeder only this way).
     */
    private static final String UPLOAD_TO_UPLOAD = "upload to upload connection";

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
    private volatile int @Nullable [] counterIndex;
    /** The last stats alert's values, one per {@link Counter}, in its order; written on the alert thread. */
    private volatile long[] counters = new long[Counter.values().length];

    private @Nullable SessionManager session;
    private @Nullable ScheduledExecutorService flusher;

    /** The connection settings in force; the session starts with them and takes changes live. */
    private volatile EngineSettings engineSettings;
    /**
     * The pair the session last said is in force ({@link #refreshLimits}), which the pack is built
     * with; the person's switch until the session first says.
     */
    private volatile boolean alternativeInForce;
    /** Kept by the alert thread from listen, port-map, incoming-connection and DHT alerts. */
    private final AtomicReference<Connectivity> connectivity = new AtomicReference<>(Connectivity.NONE);
    /**
     * The complete files of v2 torrents by root, kept by the alert thread: a torrent is indexed
     * again when a file completes (libtorrent posts that for every whole file as it loads a
     * torrent, so a start rebuilds it), when it finishes, is checked, renamed or moved, and is
     * forgotten when it is removed.
     */
    private final ContentIndex contentIndex = new ContentIndex();

    /**
     * A filter set before {@link #start()}, which applies it and lets it go: once a session has
     * one, libtorrent holds it, and a blocklist kept here as well was 40 MiB for the process's life.
     */
    private @Nullable List<IpRange> pendingIpFilter;
    /** Whether the session has a non-empty filter, so clearing one is news and clearing none is not. */
    private boolean ipFilterInForce;
    /** Each set's turn, so a filter built late (outside the lock) never replaces a newer one. */
    private final java.util.concurrent.atomic.AtomicLong ipFilterRequests =
            new java.util.concurrent.atomic.AtomicLong();
    /** The newest turn set or held, under the lock. */
    private long ipFilterApplied;
    /** Set once a session has started: native types may be built from then on, on any thread. */
    private volatile boolean nativeLoaded;

    private final java.util.concurrent.atomic.AtomicLong blockedPeers = new java.util.concurrent.atomic.AtomicLong();

    /** Resume-data requests whose alert has not arrived yet; {@link #stop()} waits on this. */
    private final AtomicInteger pendingSaves = new AtomicInteger();

    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Torrents brought back from a blob that held every piece. Its finished alert, which
     * libtorrent posts once the resume data is checked, has nothing to add to that blob: asking
     * for resume data then rewrote every seed in the store at every start.
     */
    private final Set<String> restoredWhole = ConcurrentHashMap.newKeySet();

    /**
     * Each torrent's own name, by id, from the snapshots and the add alerts, so an event on the
     * alert thread (tracker warnings come by the thousand) names it without a status call. Written
     * under the monitor and on the alert thread, read on the alert thread.
     */
    private final Map<String, String> ownNames = new ConcurrentHashMap<>();

    /** When dropped alerts were last said; the alert thread's alone. */
    private long lastDropWarningMs = Long.MIN_VALUE;

    /** The periodic flush's next slice; the flusher's alone, under the monitor. */
    private long flushTick;

    /** Ids re-added from the store at start, so their add alert reads "Restored", not "Added". */
    private final Set<String> restoring = ConcurrentHashMap.newKeySet();

    /**
     * Torrents brought back from the store this session (at start, by undo, by an import). Their
     * finished alert, posted when libtorrent loads one already complete, is no download finishing:
     * run-on-finished and the notification must not run for every seed at every start.
     */
    private final Set<String> broughtBack = ConcurrentHashMap.newKeySet();

    /** Magnets to pause once their metadata arrives: the dialog's "stop after metadata". */
    private final Set<String> stopAfterMetadata = ConcurrentHashMap.newKeySet();

    /**
     * Removed in this session, with the removed torrent's own handle. A resume-data answer can
     * arrive after the torrent is gone — the request was in flight — and writing it would bring
     * the torrent back at the next start. Matched by handle, not by id alone: the same torrent
     * added again is a new torrent to libtorrent, whose alerts carry its own handle and are
     * written (an id-only mark kept a re-added torrent out of the store for good, so it was gone
     * after a restart), while a late answer about the one removed is still dropped rather than
     * put over the new one's blob. Read and written under the store's lock.
     */
    private final Map<String, torrent_handle> removed = new ConcurrentHashMap<>();

    /** Every read through a handle's {@code torrent_info}, and every remove, go through this. */
    private final InfoReads infoReads = new InfoReads();

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

    /**
     * The session's own handle of every torrent the last {@link #handles} saw, by id, so the
     * once-a-second poll asks {@code find_torrent} (a round trip to the network thread) only for
     * a torrent it has not met. Read and written under the engine's monitor only; the alert
     * thread never touches it. A torrent whose id changed (a v1 magnet of a hybrid, once its
     * metadata is in) is met again under the new one and the old entry goes with the next poll.
     */
    private final Map<String, TorrentHandle> known = new java.util.HashMap<>();

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
        this.alternativeInForce = settings.speed().alternativeOn();
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
            s.start(new SessionParams(toPack(engineSettings, alternativeInForce)));
        } catch (LinkageError e) {
            // The JNI static initialiser wraps a failed System.load in a LinkageError whose own
            // message is a pointer to the project page; the cause says what actually went wrong.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EngineException(tr("engine.error.load", cause.getMessage()), e);
        }
        known.clear();
        session = s;
        nativeLoaded = true;
        // The counters' places in a stats alert are the library's to say, and asking is native.
        counterIndex = Counter.resolve();
        counters = new long[Counter.values().length];
        baseline = statsStore.load();
        startedAtMs = System.currentTimeMillis();
        List<IpRange> pending = pendingIpFilter;
        pendingIpFilter = null;
        if (pending != null && !pending.isEmpty()) {
            ip_filter filter = toFilter(pending);
            s.swig().set_ip_filter(filter);
            filter.delete();
            ipFilterInForce = true;
        }
        emit(EngineEvent.session(EngineEvent.Level.INFO, tr("engine.event.started", description())));
        restore(s);

        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-flush");
            t.setDaemon(true);
            return t;
        });
        long slice = PERIODIC_FLUSH_SECONDS / FLUSH_SLICES;
        flusher.scheduleWithFixedDelay(this::flushSlice, slice, slice, TimeUnit.SECONDS);
        flusher.scheduleWithFixedDelay(this::saveStats, STATS_SAVE_SECONDS, STATS_SAVE_SECONDS, TimeUnit.SECONDS);
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
        for (Map.Entry<String, byte[]> entry : stored.entrySet()) addFromStore(s, entry.getKey(), entry.getValue());
    }

    /**
     * One torrent back from what the store holds: its sidecars read, its resume data added as it
     * is (no recheck), its renamed files put back first. The caller marks it {@link #restoring},
     * so its add alert reads "Restored" and nothing that runs on an add runs. False, and said in
     * the log, when it could not be brought back.
     */
    private boolean addFromStore(SessionManager s, String id, byte[] resume) {
        restoreLabels(id);
        restoreStorage(id);
        error_code ec = new error_code();
        add_torrent_params params = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(resume), ec);
        if (ec.value() != 0) {
            LOG.warning("Resume data for " + id + " is unreadable (" + ec.message() + "); skipped");
            return false;
        }
        restoreNames(id, params);
        org.libtorrent4j.swig.torrent_info info = params.ti_ptr();
        if (info != null
                && info.is_valid()
                && holdsEveryPiece((int) params.get_have_pieces().count(), info.num_pieces())) restoredWhole.add(id);
        else restoredWhole.remove(id);
        Reference.reachabilityFence(info);
        error_code added = new error_code();
        s.swig().add_torrent(params, added);
        if (added.value() != 0) {
            restoredWhole.remove(id);
            LOG.warning("Could not bring back " + id + ": " + added.message());
            return false;
        }
        rememberTrackers(id, new AddTorrentParams(params).getTrackers());
        return true;
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
                    // Added to stop once its file list is in, and restarted before it came.
                    if (owed.stopOnMetadata()) stopAfterMetadata.add(id);
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
     *
     * <p>That includes every file the resume data names and the sidecar does not: a file whose
     * suffix came off, or that was given its own name back, is still a rename to libtorrent, and
     * its own name is the full one. Left as libtorrent wrote it, a restart found nothing and the
     * file was downloaded again. A record that still says suffixed where the plain file is on
     * disk, whole, is what that left behind: it is corrected, and the pieces are checked.
     */
    private void restoreNames(String id, add_torrent_params params) {
        TorrentStorage owed = storage.getOrDefault(id, TorrentStorage.NONE);
        org.libtorrent4j.swig.int_string_map renamed =
                new org.libtorrent4j.swig.int_string_map(params.get_renamed_files());
        if (renamed.isEmpty() && owed.suffixed().isEmpty() && owed.renamed().isEmpty()) return;
        org.libtorrent4j.swig.torrent_info info = params.ti_ptr();
        if (info == null || !info.is_valid()) return;
        org.libtorrent4j.swig.file_storage files = info.files_impl();
        java.util.function.IntFunction<String> original = index -> slashes(files.file_path(index));
        Set<Integer> cameOff = owed.suffixCameOff(Path.of(params.getSave_path()), original, files::file_size);
        if (!cameOff.isEmpty()) {
            Set<Integer> left = new java.util.TreeSet<>(owed.suffixed());
            left.removeAll(cameOff);
            owed = owed.withSuffixed(left);
            writeStorageQuietly(id, owed);
            // The pieces recorded are the .part's, if any: with none, the add checks the files.
            params.set_have_pieces(new org.libtorrent4j.swig.bitfield());
            params.set_verified_pieces(new org.libtorrent4j.swig.bitfield());
            params.set_unfinished_pieces(new org.libtorrent4j.swig.int_bitfield_map());
            params.setFlags(params.getFlags().and_(org.libtorrent4j.TorrentFlags.SEED_MODE.inv()));
            List<String> leftOver = new ArrayList<>();
            for (int index : cameOff) leftOver.add(TorrentStorage.suffixed(owed.name(index, original.apply(index))));
            LOG.warning("Torrent " + id + ": found " + cameOff.size()
                    + " file(s) complete under their own name that the record had as .part; checking them."
                    + " Any of these left in " + params.getSave_path() + " can be deleted: " + leftOver);
        }
        Set<Integer> indices = new java.util.TreeSet<>(renamed.keySet());
        indices.addAll(owed.suffixed());
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
    public synchronized Torrent add(AddRequest request, SwarmProbe from) throws EngineException {
        if (!(from instanceof Fetch fetch)) return Engine.super.add(request, from);
        fetch.cancel();
        return add(request, fetch);
    }

    /** {@code from}: an ended fetch whose peers the torrent starts with, if it is the same one. */
    private Torrent add(AddRequest request, @Nullable Fetch from) throws EngineException {
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
        // Recorded before the add, as the stop above is: a torrent with no files on disk is
        // checked at once, and the checked alert, on its own thread, reads this to put the
        // suffix on and owe the move. Recorded after, it could find nothing (a slow CI runner
        // did), and that alert does not come again.
        TorrentStorage before = storage.get(id);
        TorrentStorage owed = new TorrentStorage(
                        start.equals(destination) ? "" : destination.toString(), choices.partSuffix(), Set.of())
                .withRenamed(laidOut, originals)
                .withFound(info == null ? Map.of() : choices.found())
                .withStopOnMetadata(stopAfterMetadata.contains(id));
        try {
            if (!owed.isEmpty()) writeStorage(id, owed);
        } catch (EngineException e) {
            stopAfterMetadata.remove(id);
            throw e;
        }

        // The synchronous add, so the caller gets a handle (and an error) straight away; the
        // add_torrent_alert that follows is what writes the first resume data.
        error_code ec = new error_code();
        torrent_handle raw = s.swig().add_torrent(params.swig(), ec);
        Reference.reachabilityFence(known);
        if (ec.value() != 0) {
            stopAfterMetadata.remove(id);
            if (!owed.isEmpty()) writeStorageQuietly(id, before == null ? TorrentStorage.NONE : before);
            throw new EngineException(ec.message());
        }
        TorrentHandle handle = new TorrentHandle(raw);
        if (!request.labels().isEmpty()) writeLabels(id, request.labels());

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
        Set<Integer> suffixed = infoReads.read(h, owed.suffixed(), info -> {
            FileStorage files = info.files();
            long[] progress = h.fileProgress();
            org.libtorrent4j.Priority[] priorities = h.filePriorities();
            Set<Integer> now = new java.util.TreeSet<>(owed.suffixed());
            for (int i = 0; i < files.numFiles(); i++) {
                if (files.padFileAt(i) || files.fileSize(i) <= 0) continue;
                boolean wanted = i >= priorities.length || priorities[i] != org.libtorrent4j.Priority.IGNORE;
                boolean complete = i < progress.length && progress[i] >= files.fileSize(i);
                // The name it has now: torrent_file() shows the torrent's own, never a rename.
                String path = owed.name(i, slashes(files.filePath(i)));
                if (now.contains(i)) {
                    if (complete) {
                        h.renameFile(i, path);
                        now.remove(i);
                    }
                } else if (wanted && !complete && !path.endsWith(TorrentStorage.SUFFIX)) {
                    h.renameFile(i, TorrentStorage.suffixed(path));
                    now.add(i);
                }
            }
            return now;
        });
        if (!suffixed.equals(owed.suffixed())) writeStorageQuietly(id, owed.withSuffixed(suffixed));
    }

    /** Takes the suffix off one file that has just completed, if it carries it. */
    private void unsuffix(String id, TorrentHandle h, int index) {
        TorrentStorage owed = storage.get(id);
        if (owed == null || !owed.suffixed().contains(index)) return;
        boolean known = infoReads.read(h, false, info -> {
            FileStorage files = info.files();
            if (index < files.numFiles()) h.renameFile(index, owed.name(index, slashes(files.filePath(index))));
            return true;
        });
        if (!known) return;
        Set<Integer> left = new java.util.TreeSet<>(owed.suffixed());
        left.remove(index);
        writeStorageQuietly(id, owed.withSuffixed(left));
    }

    /**
     * Puts the torrent's complete files into the content index under the names they have on disk
     * (a person's or the layout's rename included), replacing what it had of the torrent. Alert
     * thread. A file with neither a v2 root nor a v1 key is left out; a file still carrying the {@code
     * .part} suffix is about to be renamed back, and that rename indexes it again.
     */
    private void indexContent(String id, TorrentHandle h) {
        Optional<List<ContentIndex.Entry>> complete;
        try {
            complete = infoReads.read(h, Optional.empty(), info -> Optional.of(completeFiles(id, h, info)));
        } catch (RuntimeException e) {
            // An index that misses a torrent costs a download, never the alert thread.
            LOG.log(Level.FINE, "Could not index " + id, e);
            return;
        }
        if (complete.isEmpty()) {
            contentIndex.remove(id);
            return;
        }
        // Under the store's lock, against remove(): an alert that came in just before the
        // torrent went must not put it back.
        synchronized (store) {
            if (!removedOne(id, h.swig())) contentIndex.put(id, complete.get());
        }
    }

    /** The torrent's complete files as index entries, under the names they have on disk. Inside a read. */
    private List<ContentIndex.Entry> completeFiles(String id, TorrentHandle h, TorrentInfo info) {
        FileStorage files = info.files();
        long[] progress = h.fileProgress();
        Path save = Path.of(h.savePath());
        TorrentStorage owed = storage.getOrDefault(id, TorrentStorage.NONE);
        boolean v2 = info.swig().v2();
        boolean v1 = info.swig().v1();
        List<ContentIndex.Entry> complete = new ArrayList<>();
        for (int i = 0; i < files.numFiles(); i++) {
            long size = files.fileSize(i);
            if (files.padFileAt(i) || size <= 0 || i >= progress.length || progress[i] < size) continue;
            if (owed.suffixed().contains(i)) continue;
            String root = v2 ? root(files, i) : "";
            String v1Key = v1 ? v1Key(info, files, i) : "";
            if (root.isEmpty() && v1Key.isEmpty()) continue;
            Path onDisk = save.resolve(owed.name(i, slashes(files.filePath(i))))
                    .toAbsolutePath()
                    .normalize();
            if (!root.isEmpty()) complete.add(new ContentIndex.Entry(root, id, i, onDisk, size));
            if (!v1Key.isEmpty()) complete.add(new ContentIndex.Entry(v1Key, id, i, onDisk, size));
        }
        return complete;
    }

    /** A file's v2 Merkle root, hex; empty for a v1-only torrent's file or an empty one. */
    /** The file's {@link ContentKeys#v1 v1 key}, or empty when its pieces are not its own. */
    private static String v1Key(TorrentInfo info, FileStorage files, int index) {
        long offset = files.fileOffset(index);
        long size = files.fileSize(index);
        long pieceLength = info.pieceLength();
        // The padding after a file (a hybrid's, BEP 47's) is zeros that finish its last piece.
        long padded = size;
        if (index + 1 < files.numFiles() && files.padFileAt(index + 1)) padded += files.fileSize(index + 1);
        if (!ContentKeys.ownPieces(offset, padded, pieceLength, files.totalSize())) return "";
        int[] span = ContentKeys.pieces(offset, padded, pieceLength);
        List<String> hashes = new ArrayList<>(span[1] - span[0] + 1);
        for (int p = span[0]; p <= span[1]; p++) hashes.add(info.hashForPiece(p).toHex());
        return ContentKeys.v1(pieceLength, size, hashes);
    }

    private static String root(FileStorage files, int index) {
        org.libtorrent4j.swig.sha256_hash root = files.swig().root(index);
        return root.is_all_zeros() ? "" : root.to_hex();
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

    /** The session's alert queue limit, for the test. */
    int alertQueueSizeForTest() throws EngineException {
        return running().settings().alertQueueSize();
    }

    /** A queue small enough to overflow, for the test of what a full queue says. */
    synchronized void alertQueueSizeForTest(int size) throws EngineException {
        running().applySettings(new SettingsPack().alertQueueSize(size));
    }

    /** Resume-data requests not answered yet, for the test. */
    int pendingSavesForTest() {
        return pendingSaves.get();
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
    void connectFetchForTest(SwarmProbe fetch, int port) throws EngineException {
        TorrentHandle h = ((Fetch) fetch).handle(running());
        if (h == null) throw new EngineException(tr("engine.error.fetchEnded"));
        h.swig().connect_peer(new tcp_endpoint(Address.parseIp("127.0.0.1").swig(), port));
    }

    /** {@link #forgetPeersForTest} for a fetch, the hidden torrent behind a magnet's file list. */
    void forgetFetchPeersForTest(SwarmProbe fetch) throws EngineException {
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
     * A DHT on loopback, for the updatable-torrent tests: libtorrent keeps loopback and same-/24
     * nodes out of its routing table and searches unless told otherwise, and expects node ids that
     * match an address; then the other session's DHT as the one node to start from.
     */
    synchronized void dhtOnLoopbackForTest(int otherPort) throws EngineException {
        SessionManager s = running();
        SettingsPack pack = new SettingsPack();
        for (settings_pack.bool_types key : new settings_pack.bool_types[] {
            settings_pack.bool_types.dht_restrict_routing_ips,
            settings_pack.bool_types.dht_restrict_search_ips,
            settings_pack.bool_types.dht_enforce_node_id,
            settings_pack.bool_types.dht_prefer_verified_node_ids,
            settings_pack.bool_types.dht_ignore_dark_internet
        }) pack.setBoolean(key.swigValue(), false);
        s.applySettings(pack);
        if (otherPort > 0) new SessionHandle(s.swig()).addDhtNode(new org.libtorrent4j.Pair<>("127.0.0.1", otherPort));
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
    private volatile java.util.@Nullable Deque<String> peerLog;

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

    @Override
    public Map<String, com.torrenta.engine.Following> followings() {
        Map<String, com.torrenta.engine.Following> out = new java.util.TreeMap<>();
        labels.forEach((id, l) -> {
            if (l.following() != null) out.put(id, l.following());
        });
        return out;
    }

    @Override
    public synchronized void setFollowing(String id, com.torrenta.engine.@Nullable Following following)
            throws EngineException {
        writeLabels(id, labelsOf(id).withFollowing(following));
    }

    /** A DHT get of an updatable address in flight: the best answer so far, and whether it is final. */
    private static final class MutableLookup {
        volatile long seq = -1;
        volatile byte @Nullable [] value;
        final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
    }

    /** By key and salt in hex: what the {@code dht_mutable_item_alert} handler fills in. */
    private final Map<String, MutableLookup> mutableLookups = new ConcurrentHashMap<>();

    private static String lookupKey(byte[] key, byte[] salt) {
        return java.util.HexFormat.of().formatHex(key) + ":"
                + java.util.HexFormat.of().formatHex(salt);
    }

    /**
     * Posted under the lock (a get in flight for the same address is joined), waited for outside
     * it: a DHT get takes seconds. libtorrent answers with each better item it meets and marks the
     * last one authoritative; the highest sequence number is kept.
     */
    @Override
    public java.util.Optional<MutableItem> lookupMutable(
            com.torrenta.engine.MutableSource source, java.time.Duration timeout)
            throws EngineException, InterruptedException {
        String key = lookupKey(source.publicKey(), source.salt());
        MutableLookup lookup;
        synchronized (this) {
            SessionManager s = running();
            // Off by the setting is said; a DHT still starting finds nothing, and is asked again.
            if (!engineSettings.protocol().dht()) throw new EngineException(tr("engine.error.dhtOff"));
            boolean[] fresh = {false};
            lookup = mutableLookups.computeIfAbsent(key, k -> {
                fresh[0] = true;
                return new MutableLookup();
            });
            if (fresh[0]) new SessionHandle(s.swig()).dhtGetItem(source.publicKey(), source.salt());
        }
        try {
            lookup.done.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // What came in so far is the answer.
        } catch (java.util.concurrent.ExecutionException e) {
            throw new EngineException(e.getMessage(), e);
        } finally {
            mutableLookups.remove(key, lookup);
        }
        byte[] value = lookup.value;
        if (value == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(
                    new MutableItem(lookup.seq, com.torrenta.engine.transmission.Bencode.decode(value)));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** By key and salt in hex: a put in flight, completed by its {@code dht_put_alert}. */
    private final Map<String, java.util.concurrent.CompletableFuture<MutablePut>> mutablePuts =
            new ConcurrentHashMap<>();

    /** Ed25519 from libtorrent's own code, so the secret is in the form its signing takes. */
    @Override
    public com.torrenta.engine.MutableKey newMutableKey() throws EngineException {
        synchronized (this) {
            running(); // the native is loaded by now
        }
        org.libtorrent4j.Pair<byte[], byte[]> pair =
                org.libtorrent4j.Ed25519.createKeypair(org.libtorrent4j.Ed25519.createSeed());
        return new com.torrenta.engine.MutableKey(pair.first, pair.second);
    }

    /**
     * Posted under the lock, waited for outside it. libtorrent looks the address up first and
     * signs one above the highest number it met, which the alert gives back with the nodes that
     * stored it; there is no way to choose the number. One put per address at a time.
     */
    @Override
    public MutablePut putMutable(
            com.torrenta.engine.MutableKey key, byte[] salt, byte[] value, java.time.Duration timeout)
            throws EngineException, InterruptedException {
        String k = lookupKey(key.publicKey(), salt);
        java.util.concurrent.CompletableFuture<MutablePut> put = new java.util.concurrent.CompletableFuture<>();
        synchronized (this) {
            SessionManager s = running();
            if (!engineSettings.protocol().dht()) throw new EngineException(tr("engine.error.dhtOff"));
            if (mutablePuts.putIfAbsent(k, put) != null) throw new EngineException(tr("engine.error.putInFlight"));
            new SessionHandle(s.swig())
                    .dhtPutItem(key.publicKey(), key.secretKey(), org.libtorrent4j.Entry.bdecode(value), salt);
        }
        try {
            return put.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new EngineException(tr("engine.error.putTimedOut"));
        } catch (java.util.concurrent.ExecutionException e) {
            throw new EngineException(e.getMessage(), e);
        } finally {
            mutablePuts.remove(k, put);
        }
    }

    /** Alert thread: a put finished; an immutable one's key is all zeros and matches nothing. */
    private void onMutablePut(org.libtorrent4j.alerts.DhtPutAlert alert) {
        java.util.concurrent.CompletableFuture<MutablePut> put =
                mutablePuts.get(lookupKey(alert.publicKey(), alert.salt()));
        if (put != null) put.complete(new MutablePut(alert.seq(), alert.swig().getNum_success()));
    }

    /** Alert thread: an item met under an address being looked up. */
    private void onMutableItem(org.libtorrent4j.alerts.DhtMutableItemAlert alert) {
        MutableLookup lookup = mutableLookups.get(lookupKey(alert.key(), alert.salt()));
        if (lookup == null) return;
        long seq = alert.seq();
        byte[] value;
        try {
            value = alert.item().bencode();
        } catch (RuntimeException e) {
            value = null;
        }
        // An empty answer (nobody had it) bencodes to nothing worth keeping.
        if (value != null && value.length > 1 && seq > lookup.seq) {
            lookup.value = value;
            lookup.seq = seq;
        }
        if (alert.swig().getAuthoritative()) lookup.done.complete(null);
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
            boolean v2 = info.swig().v2();
            boolean v1 = info.swig().v1();
            for (int i = 0; i < files.numFiles(); i++) {
                if (files.padFileAt(i)) continue;
                entries.add(new TorrentPreview.FileEntry(
                        i,
                        files.filePath(i).replace('\\', '/'),
                        files.fileSize(i),
                        v2 ? root(files, i) : "",
                        v1 ? v1Key(info, files, i) : ""));
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
        return new CreatedTorrent(
                bytes,
                id,
                name,
                v1,
                v2,
                pieces,
                pieceLength,
                size,
                magnet,
                seeding,
                Objects.requireNonNullElse(problem, ""));
    }

    /** Every file under the path is in: libtorrent's listing asks about each. */
    private static final class EveryFile extends list_files_listener {
        @Override
        public boolean pred(String filename) {
            return true;
        }
    }

    /**
     * A magnet's probe fetches the metadata first, then looks; a {@code .torrent}'s has it and
     * looks at once. Either is a hidden torrent ({@link Fetch}), the same from then on.
     */
    @Override
    public synchronized SwarmProbe probe(AddRequest.Source source, boolean privateToo) throws EngineException {
        SessionManager s = running();
        AddTorrentParams params;
        String magnet = null;
        byte[] bytes = null;
        switch (source) {
            case AddRequest.Magnet m -> {
                magnet = m.uri();
                try {
                    params = AddTorrentParams.parseMagnetUri(magnet);
                } catch (RuntimeException e) {
                    throw new EngineException(tr("engine.error.badMagnet"), e);
                }
            }
            case AddRequest.TorrentFile f -> {
                bytes = f.bytes();
                params = load(bytes, f.origin());
            }
        }
        String id = params.getInfoHashes().getBest().toHex();
        if (find(s, id) != null) throw new EngineException(tr("engine.error.alreadyInList"));
        if (bytes != null) {
            TorrentInfo info = params.getTorrentInfo();
            boolean isPrivate = info != null && info.isPrivate();
            Reference.reachabilityFence(info);
            if (isPrivate && !privateToo) {
                // Never joins: the file list is the .torrent's own, and the swarm is not looked at.
                Fetch kept = new Fetch(id, null, null, privateToo);
                kept.end(bytes, null);
                return kept;
            }
        }
        // Upload mode writes nothing; the folder is only somewhere for libtorrent to look, and an
        // empty one of its own, so that the check at the metadata, while the probe stays to look
        // at the swarm, never hashes a copy already in the download folder. Out of the queue's
        // hands, so a full download queue does not hold the file list back.
        Path probeDir;
        try {
            probeDir = Files.createTempDirectory("torrenta-probe-");
        } catch (java.io.IOException e) {
            throw new EngineException(e.getMessage(), e);
        }
        params.setSavePath(probeDir.toString());
        torrent_flags_t flags = params.getFlags()
                .or_(Flags.UPLOAD_MODE)
                .and_(Flags.AUTO_MANAGED.inv())
                .and_(Flags.PAUSED.inv());
        if (!engineSettings.protocol().pex()) flags = flags.or_(Flags.DISABLE_PEX);
        params.setFlags(flags);
        appendDefaultTrackers(params);

        Fetch fetch = new Fetch(id, magnet, probeDir, privateToo);
        if (fetches.putIfAbsent(id, fetch) != null) {
            deleteQuietly(probeDir);
            throw new EngineException(tr("engine.error.alreadyFetching"));
        }
        fetchAddAlerts.add(id);
        error_code ec = new error_code();
        s.swig().add_torrent(params.swig(), ec);
        if (ec.value() != 0) {
            fetches.remove(id, fetch);
            deleteQuietly(probeDir);
            throw new EngineException(ec.message());
        }
        // A .torrent's file list is in from the start; no metadata alert will say so.
        if (bytes != null) fetch.listed(bytes);
        return fetch;
    }

    /** A probe's folder: empty, since upload mode writes nothing; anything else is left alone. */
    private static void deleteQuietly(@Nullable Path dir) {
        if (dir == null) return;
        try {
            Files.deleteIfExists(dir);
        } catch (java.io.IOException e) {
            LOG.log(Level.FINE, "Probe folder left behind: " + dir, e);
        }
    }

    /**
     * One fetch: the dialog polls {@link #peers()}, waits on {@link #torrent()}, and once the
     * file list is in polls {@link #swarm} while the fetch stays to look.
     */
    private final class Fetch implements SwarmProbe {
        private final String id;
        /** The link a magnet's probe fetches from; null for a {@code .torrent}'s. */
        private final @Nullable String magnet;
        /** Its own empty save path; null for a probe that never joined. */
        private final @Nullable Path probeDir;
        /** A private torrent is looked at too ({@link Engine#probe}). */
        private final boolean privateToo;

        private final long started = System.currentTimeMillis();
        private final java.util.concurrent.CompletableFuture<byte[]> torrent =
                new java.util.concurrent.CompletableFuture<>();
        /** Out of the session; the future may have completed long before, with the metadata. */
        private volatile boolean ended;
        /** When the file list came in; 0 before. */
        private volatile long metadataAt;
        /**
         * Seeds met, by address: a seed that dials in comes from a new port each time. A seed
         * drops an upload-only peer, which the probe is once its file list is in, at the handshake
         * ({@link #UPLOAD_TO_UPLOAD}), before libtorrent's peer list marks it; so a peer is counted
         * when it shows its pieces or when it drops the probe that way.
         */
        private final Set<String> seedsMet = ConcurrentHashMap.newKeySet();

        /** Alert thread: a peer dropped the probe because both ends only upload. */
        void seedMet(String address) {
            seedsMet.add(address);
        }
        /** Pieces some peer met was seen to hold, gathered over the fetch's life. Under the engine's lock. */
        private final BitSet seen = new BitSet();
        /**
         * The peers it dialled, as each connection succeeds (a {@code peer_connect_alert}), for
         * {@link #add(AddRequest, SwarmProbe)}. Recorded as they come rather than read at the
         * end: once the metadata is in, the fetch is upload-only and a seed drops it at once.
         */
        private final Set<Endpoint> dialled = ConcurrentHashMap.newKeySet();

        /** What the new torrent starts with; fixed when the fetch ends. */
        private volatile List<Endpoint> found = List.of();

        void dialled(Endpoint peer) {
            if (dialled.size() < MAX_HANDED_OVER) dialled.add(peer);
        }

        Fetch(String id, @Nullable String magnet, @Nullable Path probeDir, boolean privateToo) {
            this.id = id;
            this.magnet = magnet;
            this.probeDir = probeDir;
            this.privateToo = privateToo;
        }

        /** The file list is in: it stays to look at the swarm from now. */
        void listed(byte[] bytes) {
            metadataAt = System.currentTimeMillis();
            torrent.complete(bytes);
        }

        /** Under the engine's lock, like every call from outside that reaches the session. */
        @Override
        public int peers() {
            synchronized (LibtorrentEngine.this) {
                SessionManager s = session;
                if (ended || s == null || !s.isRunning()) return 0;
                TorrentHandle h = handle(s);
                return h == null ? 0 : h.status().numPeers();
            }
        }

        @Override
        public Optional<SwarmPreview> swarm(Set<Integer> wanted) {
            synchronized (LibtorrentEngine.this) {
                SessionManager s = session;
                if (ended || s == null || !s.isRunning()) return Optional.empty();
                TorrentHandle h = handle(s);
                return h == null ? Optional.empty() : Optional.of(look(h, wanted));
            }
        }

        /**
         * The swarm as the fetch sees it now. Seeds are the most anyone knows of: the trackers'
         * scrapes (libtorrent keeps the highest of them), its peer list, which keeps a seed that
         * dropped the fetch, and the seeds met. With no seed, the pieces are what the peers met
         * were seen to hold, gathered over the fetch's life, since a peer that leaves took its
         * pieces with it only for now. The peers are read in place, the vector fenced.
         */
        private SwarmPreview look(TorrentHandle h, Set<Integer> wanted) {
            TorrentStatus status = h.status();
            boolean metadata = status.hasMetadata();
            peer_info_vector vector = new peer_info_vector();
            h.swig().get_peer_info(vector);
            try {
                for (int i = 0; i < vector.size(); i++) {
                    peer_info p = vector.get(i);
                    String address = p.remote_endpoint().address().to_string();
                    if (p.getFlags().and_(PeerConstants.SEED).non_zero()) {
                        seedsMet.add(address);
                        continue;
                    }
                    if (!metadata) continue;
                    org.libtorrent4j.swig.bitfield pieces = p.get_pieces();
                    if (pieces.size() > 0 && pieces.all_set()) {
                        seedsMet.add(address);
                        continue;
                    }
                    for (int j = seen.nextClearBit(0); j < pieces.size(); j = seen.nextClearBit(j + 1)) {
                        if (pieces.get_bit(j)) seen.set(j);
                    }
                }
            } finally {
                Reference.reachabilityFence(vector);
            }
            int seeds = Math.max(
                    Math.max(status.numComplete(), status.listSeeds()), Math.max(seedsMet.size(), status.numSeeds()));
            // Not list_peers: that is every address heard of, from the DHT too, never contacted.
            int others = Math.max(status.numIncomplete(), status.numPeers() - status.numSeeds());
            if (!metadata) {
                return new SwarmPreview(started, 0, seeds, Math.max(0, others), status.numPeers(), 0, 0, 0, 0);
            }
            int othersKnown = Math.max(0, others);
            return infoReads.read(
                    h, new SwarmPreview(started, 0, seeds, othersKnown, status.numPeers(), 0, 0, 0, 0), info -> {
                        FileStorage files = info.files();
                        int pieces = info.numPieces();
                        // A seed somewhere holds every piece, whether or not it was met.
                        BitSet held = new BitSet();
                        if (seeds > 0) held.set(0, pieces);
                        else held.or(seen);
                        BitSet want = new BitSet();
                        for (int index : wanted) {
                            if (index < 0 || index >= files.numFiles() || files.padFileAt(index)) continue;
                            // An empty file touches no piece; libtorrent answers with a neighbour's.
                            if (files.fileSize(index) == 0) continue;
                            want.set(files.pieceIndexAtFile(index), files.lastPieceIndexAtFile(index) + 1);
                        }
                        int wantedPieces = want.cardinality();
                        want.and(held);
                        return new SwarmPreview(
                                started,
                                metadataAt,
                                seeds,
                                othersKnown,
                                status.numPeers(),
                                pieces,
                                Math.min(pieces, held.cardinality()),
                                wantedPieces,
                                want.cardinality());
                    });
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

        /**
         * Alert thread: the metadata is in. The .torrent is the info plus the link's trackers. The
         * fetch stays to look at the swarm, downloading nothing, unless the torrent is private: a
         * private tracker counts every announce, and the person has not added it yet. libtorrent
         * itself never hands a private torrent's metadata to a magnet (measured: a loopback fetch
         * from a libtorrent seed of one never completes), so this is for other clients that do.
         */
        void arrived(TorrentHandle handle) {
            byte[] bytes;
            boolean isPrivate;
            try {
                // Only a magnet's probe waits for its file list; a .torrent's has it.
                AddTorrentParams params =
                        AddTorrentParams.parseMagnetUri(Objects.requireNonNull(magnet, "a probe without a link"));
                // set_ti copies the info into the params, which then own it.
                isPrivate = infoReads.read(handle, false, info -> {
                    params.setTorrentInfo(info);
                    return info.isPrivate();
                });
                if (params.getTorrentInfo() == null)
                    throw new IllegalStateException("the metadata went before it was read");
                // A v2 torrent's piece layers come from peers as it downloads; the file would
                // otherwise not be written at all.
                bytes = Vectors.byte_vector2bytes(libtorrent
                        .write_torrent_file(params.swig(), libtorrent.getAllow_missing_piece_layer())
                        .bencode());
                Reference.reachabilityFence(params);
            } catch (RuntimeException e) {
                end(null, e);
                return;
            }
            if (isPrivate && !privateToo) {
                end(bytes, null);
                return;
            }
            listed(bytes);
        }

        /** This fetch's torrent in the session, as a handle Java owns; null once it has left. */
        private @Nullable TorrentHandle handle(SessionManager s) {
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

        /**
         * Out of the session first, then out of the map, so no snapshot sees it in between. The
         * future completes here unless the metadata completed it already.
         */
        synchronized void end(byte @Nullable [] bytes, @Nullable Throwable failure) {
            if (ended) return;
            ended = true;
            SessionManager s = session;
            endedFetches.add(id);
            if (s != null && s.isRunning()) {
                TorrentHandle h = handle(s);
                if (h != null) {
                    endedFetches.add(id(h));
                    dialledPeers(h).forEach(this::dialled);
                    infoReads.remove(h, () -> s.remove(h));
                }
            }
            fetches.remove(id, this);
            found = List.copyOf(dialled);
            if (bytes != null) torrent.complete(bytes);
            else torrent.completeExceptionally(Objects.requireNonNull(failure, "ended with neither"));
            deleteQuietly(probeDir);
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
        infoReads.remove(handle, () -> {
            if (deleteFiles) s.remove(handle, SessionHandle.DELETE_FILES);
            else s.remove(handle);
        });
        known.remove(id);
        emit(EngineEvent.info(
                id, name, deleteFiles ? tr("engine.event.removedWithFiles", name) : tr("engine.event.removed", name)));
        FileLayout kept = fileLayout;
        if (kept != null && kept.id().equals(id)) fileLayout = null;
        // Forgotten before libtorrent confirms: a crash in between loses nothing worse than a
        // torrent that comes back once, which beats one that cannot be removed. Marked and deleted
        // under the store's lock, the same lock the alert thread's write takes around its check,
        // so a write that already passed the check cannot land after this delete.
        synchronized (store) {
            removed.put(id, handle.swig());
            labels.remove(id);
            storage.remove(id);
            contentIndex.remove(id);
            archiveReads.remove(id);
            encryptedArchives.remove(id);
            moving.remove(id);
            trackerHosts.remove(id);
            trackerErrors.remove(id);
            ownNames.remove(id);
            restoredWhole.remove(id);
            failWaits(id, tr("engine.error.streamGone", name));
            try {
                store.delete(id);
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.resumeStayed", e.getMessage()), e);
            }
        }
    }

    @Override
    public synchronized java.util.Optional<OnDisk> onDisk(String id) throws EngineException {
        TorrentHandle h = handle(id);
        Path save = Path.of(h.savePath()).toAbsolutePath().normalize();
        List<Path> files = infoReads.read(h, List.of(), info -> {
            FileStorage fs = info.files();
            TorrentStorage owed = storage.getOrDefault(id, TorrentStorage.NONE);
            List<Path> listed = new ArrayList<>();
            for (int i = 0; i < fs.numFiles(); i++) {
                if (fs.padFileAt(i)) continue;
                listed.add(save.resolve(owed.onDisk(i, slashes(fs.filePath(i)))).normalize());
            }
            return listed;
        });
        // Where the pieces of skipped files wait: named by the same hash the id is.
        return java.util.Optional.of(new OnDisk(save, files, List.of(save.resolve("." + id + ".parts"))));
    }

    /**
     * Saves answered, by id, for a {@link #detach} waiting on its own torrent's: the alert thread
     * counts one down once the blob is written (or libtorrent said it was not).
     */
    private final Map<String, java.util.concurrent.CountDownLatch> saveWaits = new ConcurrentHashMap<>();

    /**
     * Asks for the torrent's resume data with the disk cache flushed and waits for it, bounded as
     * {@link #stop} is, so what is kept is the torrent as it is now: the periodic blob can be two
     * minutes old, and pieces it does not know of would be downloaded again. The store then holds
     * everything; it is read, and the torrent removed as {@link #remove} does.
     */
    @Override
    public synchronized Detached detach(String id) throws EngineException {
        SessionManager s = running();
        TorrentHandle handle = find(s, id);
        if (handle == null) throw new EngineException(tr("engine.error.noSuchTorrent", id));
        String name = nameOf(handle);
        int queue = handle.status().queuePosition();
        java.util.concurrent.CountDownLatch saved = new java.util.concurrent.CountDownLatch(1);
        saveWaits.put(id, saved);
        try {
            requestSave(handle, false, true);
            if (!saved.await(STOP_FLUSH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
                LOG.warning("Resume data for " + id + " did not come in time; the undo keeps the last one written");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            saveWaits.remove(id);
        }
        byte[] resume;
        byte[] labelBytes;
        byte[] storageBytes;
        try {
            resume = store.load(id).orElseThrow(() -> new EngineException(tr("engine.error.noUndoData", name)));
            labelBytes = store.loadLabels(id).orElse(new byte[0]);
            storageBytes = store.loadStorage(id).orElse(new byte[0]);
        } catch (IOException e) {
            throw new EngineException(tr("engine.error.noUndoData", name), e);
        }
        remove(id, false);
        return new Detached(id, name, resume, labelBytes, storageBytes, queue);
    }

    /**
     * Another client's torrent: its resume data read by libtorrent's own reader, the metainfo put
     * back, the save path set, then written into the store as this engine's own resume data and
     * added from there, as at start: no check unless libtorrent finds the files do not match.
     */
    @Override
    public synchronized String adopt(Adoption a) throws EngineException {
        SessionManager s = running();
        error_code ec = new error_code();
        add_torrent_params read = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(a.resume()), ec);
        if (ec.value() != 0) throw new EngineException(tr("engine.error.importUnreadable", a.name(), ec.message()));
        if (a.check()) {
            // Nothing taken on trust: with no pieces recorded, the add checks the files on disk, as
            // an add of a .torrent whose data is already there does. Not force_recheck.
            read.set_have_pieces(new org.libtorrent4j.swig.bitfield());
            read.set_verified_pieces(new org.libtorrent4j.swig.bitfield());
            read.set_unfinished_pieces(new org.libtorrent4j.swig.int_bitfield_map());
            read.setFlags(read.getFlags().and_(org.libtorrent4j.TorrentFlags.SEED_MODE.inv()));
        }
        AddTorrentParams params = new AddTorrentParams(read);
        if (a.metainfo() != null) {
            try {
                params.setTorrentInfo(new TorrentInfo(a.metainfo()));
            } catch (IllegalArgumentException e) {
                throw new EngineException(tr("engine.error.importUnreadable", a.name(), e.getMessage()), e);
            }
        }
        params.setSavePath(a.savePath());
        String id = params.getInfoHashes().getBest().toHex();
        if (find(s, id) != null) throw new EngineException(tr("engine.error.importAlreadyHere", a.name()));
        byte[] blob = AddTorrentParams.writeResumeDataBuf(params);
        synchronized (store) {
            try {
                store.save(id, blob);
                if (!a.labels().isEmpty()) store.saveLabels(id, a.labels().encode());
                // The other client's renames, where this engine keeps them: put back before the add.
                if (!a.renamed().isEmpty())
                    store.saveStorage(id, new TorrentStorage("", false, Set.of(), a.renamed()).encode());
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.importUnreadable", a.name(), e.getMessage()), e);
            }
        }
        restoring.add(id);
        if (!addFromStore(s, id, blob)) {
            restoring.remove(id);
            throw new EngineException(tr("engine.error.importUnreadable", a.name(), ""));
        }
        return id;
    }

    /** Writes the detached torrent back into the store and adds it from there, as at start. */
    @Override
    public synchronized void reattach(Detached torrent) throws EngineException {
        SessionManager s = running();
        String id = torrent.id();
        if (find(s, id) != null) throw new EngineException(tr("engine.error.alreadyBack", torrent.name()));
        synchronized (store) {
            try {
                store.save(id, torrent.resume());
                if (torrent.labels().length > 0) store.saveLabels(id, torrent.labels());
                if (torrent.storage().length > 0) store.saveStorage(id, torrent.storage());
            } catch (IOException e) {
                throw new EngineException(tr("engine.error.couldNotReattach", torrent.name(), e.getMessage()), e);
            }
        }
        restoring.add(id);
        if (!addFromStore(s, id, torrent.resume())) {
            restoring.remove(id);
            throw new EngineException(tr("engine.error.couldNotReattach", torrent.name(), ""));
        }
        TorrentHandle back = find(s, id);
        if (back != null && torrent.queuePosition() >= 0) back.queuePositionSet(torrent.queuePosition());
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
        Map<Integer, String> originals = infoReads.read(h, Map.of(), info -> originalNames(info.files()));
        if (originals.isEmpty()) throw new EngineException(tr("engine.error.renameNoMetadata"));
        TorrentStorage owed = storage.getOrDefault(id, TorrentStorage.NONE);
        Map<Integer, String> names = new java.util.TreeMap<>();
        originals.forEach((index, original) -> names.put(index, owed.name(index, original)));
        java.util.Optional<String> problem = FileRenames.problem(names, path, name);
        if (problem.isPresent()) throw new EngineException(problem.get());
        Map<Integer, String> changes = FileRenames.plan(names, path, name);
        TorrentStorage next = owed.withRenamed(changes, originals);
        // Recorded first: the rename alerts, and a restart, read the names from the record.
        writeStorage(id, next);
        changes.forEach((index, renamed) -> h.renameFile(
                index, next.onDisk(index, Objects.requireNonNull(originals.get(index), "a file the plan named"))));
        String shown = displayName(id, status.name());
        emit(EngineEvent.info(id, shown, tr("engine.event.pathRenamed", shown, path, name.strip())));
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
    private boolean firstAndLastFirst(TorrentHandle h) {
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
    private Map<Integer, org.libtorrent4j.Priority> edgePieces(TorrentHandle h) {
        return infoReads.read(h, Map.of(), info -> {
            if (info.pieceLength() <= 0) return Map.of();
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
        });
    }

    @Override
    public synchronized byte[] exportTorrent(String id) throws EngineException {
        TorrentHandle h = handle(id);
        if (!h.status(StatusQuery.FLAGS).hasMetadata()) throw new EngineException(tr("engine.error.exportNoMetadata"));
        try {
            AddTorrentParams params = new AddTorrentParams();
            // write_torrent_file writes the info section as it was loaded, so renames (the .part
            // suffix, a file renamed by hand) never reach the exported file and the hash holds.
            // set_ti copies the info into the params, which then own it.
            boolean copied = infoReads.read(h, false, info -> {
                params.setTorrentInfo(info);
                return true;
            });
            if (!copied) throw new EngineException(tr("engine.error.exportNoMetadata"));
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
        }
    }

    @Override
    public synchronized String magnetLink(String id) throws EngineException {
        return handle(id).makeMagnetUri();
    }

    @Override
    public synchronized TorrentDetail detail(String id, Set<TorrentDetail.Part> parts) throws EngineException {
        TorrentHandle h = handle(id);
        boolean withPieces = parts.contains(TorrentDetail.Part.PIECES);
        TorrentStatus status = h.status(withPieces ? StatusQuery.ALL : StatusQuery.FLAGS);

        // Files: progress and priority by index; padding files hidden, indices kept. The layout
        // (paths, sizes, piece ranges) is the torrent's own and never changes, so it is read once
        // per torrent shown; each poll asks only for the two arrays that move.
        List<TorrentDetail.FileStatus> files = new ArrayList<>();
        Optional<InfoFacts> facts = status.hasMetadata()
                ? infoReads.read(
                        h,
                        Optional.empty(),
                        info -> Optional.of(new InfoFacts(
                                layout(id, info), info.isPrivate(), info.numPieces(), info.pieceLength())))
                : Optional.empty();
        int fileCount = -1;
        if (facts.isPresent()) {
            FileLayout layout = facts.get().layout();
            fileCount = layout.index().length;
            if (parts.contains(TorrentDetail.Part.FILES)) {
                // The names a person or the layout gave: torrent_file() shows the torrent's own.
                TorrentStorage owed = this.storage.getOrDefault(id, TorrentStorage.NONE);
                long[] progress = h.fileProgress();
                org.libtorrent4j.Priority[] priorities = h.filePriorities();
                files = new ArrayList<>(fileCount);
                for (int n = 0; n < fileCount; n++) {
                    int i = layout.index()[n];
                    long done = i < progress.length ? progress[i] : 0;
                    Priority priority = i < priorities.length ? fromLibtorrent(priorities[i]) : Priority.NORMAL;
                    files.add(new TorrentDetail.FileStatus(
                            i,
                            owed.name(i, layout.path()[n]),
                            layout.size()[n],
                            done,
                            priority,
                            layout.first()[n],
                            layout.last()[n],
                            owed.found().get(i)));
                }
            }
        }

        List<TorrentDetail.Peer> peers = new ArrayList<>();
        if (parts.contains(TorrentDetail.Part.PEERS)) {
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
        }

        List<TorrentDetail.Tracker> trackers = new ArrayList<>();
        if (parts.contains(TorrentDetail.Part.TRACKERS))
            for (AnnounceEntry entry : trackers(h)) trackers.add(tracker(entry));

        // Counted only while downloading: a seed has no use for it, and libtorrent answers a paused
        // torrent with an empty vector anyway.
        int[] availability =
                !withPieces || status.isSeeding() || !status.hasMetadata() ? new int[0] : h.pieceAvailability();

        boolean[] pieces = new boolean[0];
        int piecesHave = status.numPieces();
        if (withPieces) {
            PieceIndexBitfield bits = status.pieces();
            pieces = new boolean[Math.max(0, bits.size())];
            for (int i = 0; i < pieces.length; i++) pieces[i] = bits.getBit(i);
            piecesHave = bits.count();
        }

        TorrentDetail.General general = new TorrentDetail.General(
                id,
                status.swig().getSave_path(),
                facts.isPresent() && facts.get().isPrivate(),
                // status.numPieces() is how many it has, not how many there are.
                facts.isPresent() ? facts.get().pieces() : pieces.length,
                facts.isPresent() ? facts.get().pieceLength() : 0,
                piecesHave,
                // Distributed copies are counted only with the pieces (StatusQuery.ALL).
                withPieces ? status.distributedCopies() : -1,
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
        // The bitfield points into it; keep it until the end.
        Reference.reachabilityFence(status);
        return new TorrentDetail(
                general,
                files,
                peers,
                trackers,
                pieces,
                availability,
                parts.contains(TorrentDetail.Part.WEB_SEEDS) ? h.urlSeeds() : List.of(),
                parts.contains(TorrentDetail.Part.FILES) ? encryptedArchives.getOrDefault(id, Set.of()) : Set.of(),
                "",
                parts,
                fileCount);
    }

    /**
     * The real files of one torrent as {@code torrent_info} lays them out, padding left out, in
     * index order: paths with forward slashes, sizes, and the pieces each touches ({@code -1} for
     * an empty file). Fixed for an info-hash: renames are {@link TorrentStorage}'s, applied over it.
     */
    private record FileLayout(String id, int[] index, String[] path, long[] size, int[] first, int[] last) {}

    /** What {@link #detail} reads of the info, inside one read. */
    private record InfoFacts(FileLayout layout, boolean isPrivate, int pieces, int pieceLength) {}

    /**
     * The layout of the last torrent {@link #detail} read files for: the pane shows one at a time.
     * Volatile because the alert thread drops it (a rename, a removal) without the lock.
     */
    private volatile @Nullable FileLayout fileLayout;

    /** The torrent's {@link FileLayout}, read from {@code info} unless it is the one kept. Under the lock, inside a read. */
    private FileLayout layout(String id, TorrentInfo info) {
        FileLayout kept = fileLayout;
        if (kept != null && kept.id().equals(id)) return kept;
        FileStorage storage = info.files();
        int total = storage.numFiles();
        int[] index = new int[total];
        String[] path = new String[total];
        long[] size = new long[total];
        int[] first = new int[total];
        int[] last = new int[total];
        int n = 0;
        for (int i = 0; i < total; i++) {
            if (storage.padFileAt(i)) continue;
            index[n] = i;
            path[n] = slashes(storage.filePath(i));
            size[n] = storage.fileSize(i);
            // An empty file touches no piece; asking libtorrent for its range answers with a neighbour's.
            first[n] = size[n] == 0 ? -1 : storage.pieceIndexAtFile(i);
            last[n] = size[n] == 0 ? -1 : storage.lastPieceIndexAtFile(i);
            n++;
        }
        Reference.reachabilityFence(info); // the storage points into it
        FileLayout read = new FileLayout(
                id,
                Arrays.copyOf(index, n),
                Arrays.copyOf(path, n),
                Arrays.copyOf(size, n),
                Arrays.copyOf(first, n),
                Arrays.copyOf(last, n));
        fileLayout = read;
        return read;
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

    /**
     * What streams wait on, by torrent id and piece: completed by the {@code piece_finished_alert},
     * failed when the torrent goes or the engine stops.
     */
    private final Map<String, Map<Integer, java.util.concurrent.CompletableFuture<Void>>> pieceWaits =
            new ConcurrentHashMap<>();

    @Override
    public synchronized com.torrenta.engine.StreamFile stream(String id, int fileIndex) throws EngineException {
        TorrentHandle h = handle(id);
        String shown = nameOf(h);
        Optional<Stream> read = infoReads.read(h, Optional.empty(), info -> {
            FileStorage files = info.files();
            if (fileIndex < 0 || fileIndex >= files.numFiles() || files.padFileAt(fileIndex))
                throw new EngineException(tr("engine.error.streamNoFile", fileIndex, shown));
            String original = slashes(files.filePath(fileIndex));
            String name = storage.getOrDefault(id, TorrentStorage.NONE).name(fileIndex, original);
            return Optional.of(new Stream(
                    id,
                    fileIndex,
                    original,
                    name.substring(name.lastIndexOf('/') + 1),
                    files.fileOffset(fileIndex),
                    files.fileSize(fileIndex),
                    info.pieceLength()));
        });
        if (read.isEmpty()) throw new EngineException(tr("engine.error.streamNoMetadata", shown));
        // A file played is a file wanted: a skipped file's pieces go into the part file, not the
        // file read from (measured: the wait ended, the file was not there). Low, so the rest of
        // it does not jump the queue; libtorrent moves what the part file has over.
        org.libtorrent4j.Priority[] priorities = h.filePriorities();
        if (fileIndex < priorities.length && priorities[fileIndex] == org.libtorrent4j.Priority.IGNORE) {
            h.filePriority(fileIndex, org.libtorrent4j.Priority.LOW);
            requestSave(h, false);
        }
        return read.get();
    }

    /**
     * A file read while it downloads. Each call that reaches the session goes through the engine's
     * lock, as every call does; the wait itself is outside it, on futures the alert thread completes.
     */
    private final class Stream implements com.torrenta.engine.StreamFile {
        private final String id;
        private final int index;
        private final String original;
        private final String name;
        private final long offset;
        private final long size;
        private final long pieceLength;

        Stream(String id, int index, String original, String name, long offset, long size, long pieceLength) {
            this.id = id;
            this.index = index;
            this.original = original;
            this.name = name;
            this.offset = offset;
            this.size = size;
            this.pieceLength = pieceLength;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long chunkEnd(long from) {
            int piece = com.torrenta.engine.PieceMath.span(offset, pieceLength, from, from)
                    .first();
            return com.torrenta.engine.PieceMath.lastByteOf(piece, offset, size, pieceLength);
        }

        @Override
        public boolean await(long from, long to, java.time.Duration timeout)
                throws EngineException, InterruptedException {
            List<java.util.concurrent.CompletableFuture<Void>> waits =
                    askFor(id, com.torrenta.engine.PieceMath.span(offset, pieceLength, from, to));
            if (waits.isEmpty()) return true;
            try {
                java.util.concurrent.CompletableFuture.allOf(
                                waits.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof EngineException ee ? ee : new EngineException(e.getMessage(), e);
            }
        }

        @Override
        public byte[] read(long from, long to) throws EngineException, InterruptedException {
            int piece = com.torrenta.engine.PieceMath.span(offset, pieceLength, from, from)
                    .first();
            byte[] bytes = readPiece(id, piece);
            // Where the range starts in the piece: the file need not start on a piece's edge.
            long at = offset + from - piece * pieceLength;
            int length = (int) (to - from + 1);
            if (at < 0 || at + length > bytes.length) throw new EngineException(tr("engine.error.streamGone", name));
            return java.util.Arrays.copyOfRange(bytes, (int) at, (int) at + length);
        }

        @Override
        public long offset() {
            return offset;
        }

        @Override
        public long pieceLength() {
            return pieceLength;
        }

        @Override
        public java.util.BitSet have(int first, int last) throws EngineException {
            return streamHave(id, first, last);
        }

        @Override
        public void deadlines(Map<Integer, Integer> millisByPiece) throws EngineException {
            streamDeadlines(id, millisByPiece);
        }

        @Override
        public void clearDeadlines(int first, int last) throws EngineException {
            streamClear(id, first, last);
        }
    }

    /**
     * Pieces being read for a stream, by torrent id and piece: completed by the {@code
     * read_piece_alert} with the piece's bytes, failed when it says it could not.
     */
    private final Map<String, Map<Integer, java.util.concurrent.CompletableFuture<byte[]>>> pieceReads =
            new ConcurrentHashMap<>();

    /**
     * A piece's bytes as libtorrent has them, its write buffer included: asked under the lock,
     * waited for outside it. Reads of one piece at once share one request.
     */
    private byte[] readPiece(String id, int piece) throws EngineException, InterruptedException {
        java.util.concurrent.CompletableFuture<byte[]> f;
        synchronized (this) {
            TorrentHandle h = handle(id);
            Map<Integer, java.util.concurrent.CompletableFuture<byte[]>> reads =
                    pieceReads.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
            boolean[] asked = {false};
            f = reads.computeIfAbsent(piece, p -> {
                asked[0] = true;
                return new java.util.concurrent.CompletableFuture<>();
            });
            if (asked[0]) h.readPiece(piece);
        }
        try {
            return f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Map<Integer, java.util.concurrent.CompletableFuture<byte[]>> reads = pieceReads.get(id);
            if (reads != null) reads.remove(piece, f);
            throw new EngineException(tr("engine.error.streamGone", id), e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof EngineException ee ? ee : new EngineException(e.getMessage(), e);
        }
    }

    private synchronized java.util.BitSet streamHave(String id, int first, int last) throws EngineException {
        TorrentHandle h = handle(id);
        java.util.BitSet out = new java.util.BitSet();
        for (int p = Math.max(0, first); p <= last; p++) if (h.havePiece(p)) out.set(p);
        return out;
    }

    private synchronized void streamDeadlines(String id, Map<Integer, Integer> millisByPiece) throws EngineException {
        TorrentHandle h = handle(id);
        millisByPiece.forEach((piece, millis) -> {
            if (!h.havePiece(piece)) h.setPieceDeadline(piece, millis);
        });
    }

    private synchronized void streamClear(String id, int first, int last) throws EngineException {
        TorrentHandle h = handle(id);
        for (int p = Math.max(0, first); p <= last; p++) h.resetPieceDeadline(p);
    }

    /**
     * The pieces of {@code span} the torrent lacks, each asked for with a deadline (ahead of
     * everything else; a skipped file's piece is raised to the top priority by it) and its future
     * registered before the second look, so a piece finished in between is not waited on forever.
     */
    private synchronized List<java.util.concurrent.CompletableFuture<Void>> askFor(
            String id, com.torrenta.engine.PieceMath.Span span) throws EngineException {
        TorrentHandle h = handle(id);
        Map<Integer, java.util.concurrent.CompletableFuture<Void>> waits =
                pieceWaits.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        List<java.util.concurrent.CompletableFuture<Void>> out = new ArrayList<>();
        for (int piece = span.first(); piece <= span.last(); piece++) {
            if (h.havePiece(piece)) continue;
            java.util.concurrent.CompletableFuture<Void> f =
                    waits.computeIfAbsent(piece, p -> new java.util.concurrent.CompletableFuture<>());
            h.setPieceDeadline(piece, 0);
            if (h.havePiece(piece)) {
                waits.remove(piece, f);
                f.complete(null);
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /** The streams waiting on a torrent hear it is gone. */
    private void failWaits(String id, String why) {
        Map<Integer, java.util.concurrent.CompletableFuture<Void>> waits = pieceWaits.remove(id);
        if (waits != null) for (var f : waits.values()) f.completeExceptionally(new EngineException(why));
        Map<Integer, java.util.concurrent.CompletableFuture<byte[]>> reads = pieceReads.remove(id);
        if (reads != null) for (var f : reads.values()) f.completeExceptionally(new EngineException(why));
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

    /** Kept by the alert thread; reading it never reaches the session, so no lock. */
    @Override
    public ContentIndex contentIndex() {
        return contentIndex;
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

    /** The flusher's: {@link #flush()} for one slice of the torrents, the next slice next time. */
    private synchronized void flushSlice() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        long tick = flushTick++;
        for (TorrentHandle handle : handles(s, id -> inFlushSlice(id, tick, FLUSH_SLICES))) requestSave(handle, true);
    }

    /** Whether the torrent is asked at this tick: each id falls in one slice of {@code slices}. */
    static boolean inFlushSlice(String id, long tick, int slices) {
        return Math.floorMod(id.hashCode(), slices) == Math.floorMod(tick, slices);
    }

    @Override
    public synchronized void applySettings(EngineSettings settings) throws EngineException {
        engineSettings = settings;
        connectivity.updateAndGet(c -> c.dht(settings.protocol().dht()));
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        s.applySettings(toPack(settings, alternativeInForce));
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

    /**
     * The bound interface changed under the session: the settings again, and the listen sockets
     * reopened, since an unchanged {@code listen_interfaces} string is not looked at twice.
     */
    @Override
    public synchronized void rebind() throws EngineException {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        s.applySettings(toPack(engineSettings, alternativeInForce));
        s.swig().reopen_network_sockets();
    }

    /** The session's connection limit as libtorrent has it; for the test. */
    int connectionsLimitForTest() throws EngineException {
        return running().settings().connectionsLimit();
    }

    /**
     * {@link EngineSettings} onto libtorrent's pack, with the speed pair the session last said is in
     * force. Enum constants are SWIG objects: after load only.
     */
    private static SettingsPack toPack(EngineSettings s, boolean alternativeInForce) {
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
        pack.setInteger(settings_pack.int_types.alert_queue_size.swigValue(), ALERT_QUEUE_SIZE);

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
        pack.downloadRateLimit(speed.downloadBytes(alternativeInForce));
        pack.uploadRateLimit(speed.uploadBytes(alternativeInForce));
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
    public synchronized boolean refreshLimits(boolean alternative) throws EngineException {
        alternativeInForce = alternative;
        SessionManager s = session;
        EngineSettings.Speed speed = engineSettings.speed();
        if (s == null || !s.isRunning()) return alternative;
        int down = speed.downloadBytes(alternative);
        int up = speed.uploadBytes(alternative);
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

    /**
     * Not {@code synchronized} as a whole: building the native filter (two address parses and a
     * rule per range, 300,000 ranges for a common list) touches nothing of the session's, and
     * holding the lock for it stalled the once-a-second poll. Only the set itself takes the lock.
     */
    @Override
    public void setIpFilter(List<IpRange> ranges) throws EngineException {
        long turn = ipFilterRequests.incrementAndGet();
        ip_filter built = nativeLoaded ? toFilter(ranges) : null;
        try {
            synchronized (this) {
                if (turn < ipFilterApplied) return;
                ipFilterApplied = turn;
                SessionManager s = session;
                if (s == null || !s.isRunning()) {
                    pendingIpFilter = List.copyOf(ranges);
                    return;
                }
                pendingIpFilter = null;
                // The session came up while this one was waiting for the lock.
                if (built == null) built = toFilter(ranges);
                s.swig().set_ip_filter(built);
                // Clearing a filter that was never set (the start, with the filter off) is not news.
                boolean had = ipFilterInForce;
                ipFilterInForce = !ranges.isEmpty();
                if (had || ipFilterInForce)
                    emit(EngineEvent.session(
                            EngineEvent.Level.INFO,
                            ranges.isEmpty()
                                    ? tr("engine.event.ipFilterCleared")
                                    : tr("engine.event.ipFilter", ranges.size())));
            }
        } finally {
            // set_ip_filter copies it; the native copy is libtorrent's, this one goes now rather
            // than whenever its finalizer runs.
            if (built != null) built.delete();
        }
    }

    @Override
    public synchronized long blockedPeers() {
        return blockedPeers.get();
    }

    /** Whether the engine holds a list of ranges of its own; for the test. */
    synchronized boolean holdsIpFilterForTest() {
        return pendingIpFilter != null;
    }

    /** Whether the session's filter refuses the address; for the test. */
    boolean isBlockedForTest(String ip) throws EngineException {
        return running().swig().get_ip_filter().access(Address.parseIp(ip).swig()) != 0;
    }

    /**
     * The native filter for the ranges. Straight to SWIG's {@code from_string} with one error code,
     * and each address deleted once {@code add_rule} has copied it: {@link Address#parseIp} made
     * two finalizable objects per end, 1.2 million of them for a common list.
     */
    private static ip_filter toFilter(List<IpRange> ranges) {
        ip_filter filter = new ip_filter();
        long blocked = ip_filter.access_flags.blocked.swigValue();
        error_code ec = new error_code();
        try {
            for (IpRange r : ranges) {
                address first = parseAddress(r.first(), ec);
                try {
                    address last = parseAddress(r.last(), ec);
                    try {
                        filter.add_rule(first, last, blocked);
                    } finally {
                        last.delete();
                    }
                } finally {
                    first.delete();
                }
            }
        } catch (RuntimeException e) {
            filter.delete();
            throw e;
        } finally {
            ec.delete();
        }
        return filter;
    }

    private static address parseAddress(String literal, error_code ec) {
        address parsed = address.from_string(literal, ec);
        if (ec.value() != 0) {
            parsed.delete();
            throw new IllegalArgumentException(literal + ": " + ec.message());
        }
        return parsed;
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
        SessionManager s = session;
        if (s == null) return;
        if (flusher != null) flusher.shutdownNow();
        saveStats();
        for (Fetch fetch : List.copyOf(fetches.values()))
            fetch.end(null, new EngineException(tr("engine.error.stopped")));
        // Flush with the disk cache, then wait for the alerts, bounded: libtorrent answers every
        // request (success or "not modified"), but a wedged disk must not hold the quit.
        for (TorrentHandle handle : handles(s)) requestSave(handle, true, true);
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
        java.util.Set<String> streamed = new java.util.HashSet<>(pieceWaits.keySet());
        streamed.addAll(pieceReads.keySet());
        for (String id : streamed) failWaits(id, tr("engine.error.stopped"));
        s.stop();
        session = null;
        known.clear();
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
     * from {@code find_torrent}; the vector is kept reachable until that is done. That copy is kept
     * in {@link #known} from one poll to the next, so {@code find_torrent} (a round trip to the
     * network thread per torrent, every second) is asked only for a torrent not met before, or one
     * whose kept handle went invalid or now has another id. Metadata fetches are left out.
     */
    private List<TorrentHandle> handles(SessionManager s) {
        return handles(s, id -> true);
    }

    /**
     * The same, for the ids the filter takes; the others cost no {@code find_torrent}, and stay
     * in {@link #known}, since the session still lists them.
     */
    private List<TorrentHandle> handles(SessionManager s, java.util.function.Predicate<String> wanted) {
        assert Thread.holdsLock(this) : "the handle map is the monitor's";
        session_handle session = s.swig();
        torrent_handle_vector vector = session.get_torrents();
        try {
            int size = vector.size();
            List<TorrentHandle> out = new ArrayList<>(size);
            Set<String> seen = new java.util.HashSet<>(size * 2);
            for (int i = 0; i < size; i++) {
                torrent_handle raw = vector.get(i);
                // A magnet the Add dialog is fetching, or one on its way out, is not the person's.
                sha1_hash best = bestHash(raw);
                String id = best.to_hex();
                if (fetchOf(raw) != null || endedFetches.contains(id)) continue;
                seen.add(id);
                if (!wanted.test(id)) continue;
                // is_valid and info_hashes read the handle's own torrent; no round trip.
                TorrentHandle handle = known.get(id);
                if (handle == null || !handle.isValid() || !id(handle).equals(id)) {
                    handle = new TorrentHandle(session.find_torrent(best));
                    if (!handle.isValid()) continue;
                    known.put(id, handle);
                }
                out.add(handle);
            }
            known.keySet().retainAll(seen);
            return out;
        } finally {
            Reference.reachabilityFence(vector);
        }
    }

    /**
     * The torrent whose id this is, as the session's own handle: one {@code find_torrent} on the
     * hash the id spells, no walk of the list. libtorrent finds a torrent by its v1 hash or its
     * truncated v2 one, so a hybrid's v1 hash would find it too; only its id does here, as before.
     * Metadata fetches are found like any torrent, as they always were.
     */
    private static @Nullable TorrentHandle find(SessionManager s, String id) {
        if (!isHashHex(id)) return null;
        TorrentHandle handle = new TorrentHandle(s.swig().find_torrent(sha1_hash.from_hex(id)));
        return handle.isValid() && id(handle).equals(id) ? handle : null;
    }

    /** Forty lower-case hex digits, the only shape {@code to_hex} writes and so the only id. */
    private static boolean isHashHex(String id) {
        if (id.length() != 40) return false;
        for (int i = 0; i < 40; i++) {
            char c = id.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    /**
     * Whether {@code about} is a torrent removed under {@code id}, so what an alert says of it is
     * not stored. Store's lock held. libtorrent compares handles by the torrent they point at, so
     * one that outlived its torrent still matches the handle kept at the remove, and never the
     * torrent added again under the same id.
     */
    private boolean removedOne(String id, torrent_handle about) {
        torrent_handle gone = removed.get(id);
        return gone != null && gone.eq(about);
    }

    /** The session's own handle of a torrent, kept by a test to answer for it after its remove. */
    synchronized Object handleForTest(String id) throws EngineException {
        return handle(id).swig();
    }

    /**
     * Runs {@code during} inside a read of the torrent's info, as the alert thread's reads run;
     * false, without running it, when the read finds no info. For tests.
     */
    boolean readInfoForTest(Object handle, Runnable during) {
        return infoReads.read(new TorrentHandle((torrent_handle) handle), false, info -> {
            during.run();
            return true;
        });
    }

    /**
     * Writes {@code resume} as the alert thread would a resume-data answer about {@code handle}:
     * the late answer of a torrent that was removed, which libtorrent cannot be made to send on cue.
     */
    void answerForTest(Object handle, byte[] resume) {
        error_code ec = new error_code();
        add_torrent_params read = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(resume), ec);
        if (ec.value() != 0) throw new IllegalArgumentException(ec.message());
        new Listener().write(new AddTorrentParams(read), (torrent_handle) handle);
    }

    /** The ids the poll's handle map holds, for the test that it keeps none that went. */
    synchronized Set<String> knownHandlesForTest() {
        return Set.copyOf(known.keySet());
    }

    /**
     * The fetch a handle belongs to, if any, by either hash: a v1 magnet of a hybrid torrent has a
     * new best hash (the v2 one) once its metadata is in, and is still the same fetch.
     */
    private @Nullable Fetch fetchOf(torrent_handle raw) {
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
        String id = id(h);
        if (archiveReads.containsKey(id)) return;
        Optional<ArchiveReads> read = infoReads.read(h, Optional.empty(), info -> {
            if (restored && h.status().isFinished()) return Optional.empty();
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
            return Optional.of(new ArchiveReads(pieceLength, byFile));
        });
        if (read.isEmpty() || read.get().byFile().isEmpty()) return;
        Map<Integer, com.torrenta.engine.ArchiveProbe.Reading> byFile =
                read.get().byFile();
        archiveReads.put(id, read.get());
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
        if (!alert.handle().isValid()) return;
        String id = id(alert.handle());
        int piece = alert.piece();
        // A stream's read first: the bytes, or why there are none.
        Map<Integer, java.util.concurrent.CompletableFuture<byte[]>> streamReads = pieceReads.get(id);
        java.util.concurrent.CompletableFuture<byte[]> streamRead =
                streamReads == null ? null : streamReads.remove(piece);
        if (streamRead != null) {
            if (alert.error().isError())
                streamRead.completeExceptionally(
                        new EngineException(alert.error().getMessage()));
            else streamRead.complete(NativeBytes.copy(alert.bufferPtr(), alert.size()));
        }
        if (alert.error().isError()) return;
        ArchiveReads reads = archiveReads.get(id);
        if (reads == null) return;
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
    private String fileName(TorrentHandle h, int index) {
        String path = infoReads.read(h, "", info -> slashes(info.files().filePath(index)));
        if (path.isEmpty()) return String.valueOf(index);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * The name a person gave it, else its own, else the hash while there is none; empty for a
     * handle that is already gone.
     */
    private String nameOf(TorrentHandle handle) {
        if (!handle.isValid()) return "";
        // The hash is the handle's own, no call; the name is a status round trip, known already.
        String known = ownNames.get(id(handle));
        if (known != null) return displayName(id(handle), known);
        TorrentStatus status = handle.status(StatusQuery.FLAGS);
        String id = status.getInfoHashes().getBest().toHex();
        return displayName(id, status.name());
    }

    /** A blob whose piece list is whole: the torrent it brings back is a seed already. */
    static boolean holdsEveryPiece(int have, int pieces) {
        return pieces > 0 && have == pieces;
    }

    /**
     * Whether a finished alert asks for resume data. Not for a torrent brought back from a blob
     * that held every piece and that has downloaded nothing since (-1: not brought back): the
     * blob already says so. A recheck that found the pieces again (file times that differ, as on
     * NTFS) leaves the torrent modified, and the periodic flush and the stop write it.
     */
    static boolean savesOnFinish(boolean restoredWhole, long downloadedThisSession) {
        return !(restoredWhole && downloadedThisSession == 0);
    }

    /** Whether dropped alerts are said now: the first time, and then once per {@link #DROP_WARNING_MS}. */
    static boolean dropWarningDue(long lastMs, long nowMs) {
        return lastMs == Long.MIN_VALUE || nowMs - lastMs >= DROP_WARNING_MS;
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
        static final peer_flags_t SEED = peer_info.seed;
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
        // Not for one removed while the session still lists it: the prune would be undone. By
        // handle, as removedOne does, so the same torrent added again is named as any other.
        torrent_handle gone = removed.get(id);
        if (gone == null || !gone.eq(handle.swig())) ownNames.put(id, status.name() == null ? "" : status.name());
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
            AlertType.PIECE_FINISHED.swig(),
            AlertType.DHT_MUTABLE_ITEM.swig(),
            AlertType.DHT_PUT.swig(),
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
            AlertType.EXTERNAL_IP.swig(),
            AlertType.PEER_BLOCKED.swig(),
            AlertType.PEER_CONNECT.swig(),
            AlertType.STORAGE_MOVED.swig(),
            AlertType.STORAGE_MOVED_FAILED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.READ_PIECE.swig(),
            AlertType.PEER_DISCONNECTED.swig(),
            AlertType.PEER_ERROR.swig(),
            AlertType.ALERTS_DROPPED.swig(),
        };

        @Override
        public int[] types() {
            return types;
        }

        @Override
        public void alert(Alert<?> alert) {
            try {
                if (ofAFetch(alert)) return;
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
                        // A magnet's name was the link's, or none: the metadata's is asked for once.
                        if (t.handle().isValid()) ownNames.remove(id(t.handle()));
                        if (t.handle().isValid() && stopAfterMetadata.remove(id(t.handle()))) {
                            t.handle().unsetFlags(Flags.AUTO_MANAGED);
                            t.handle().pause();
                            String stopped = id(t.handle());
                            TorrentStorage owed = storage.get(stopped);
                            if (owed != null && owed.stopOnMetadata())
                                writeStorageQuietly(stopped, owed.withStopOnMetadata(false));
                            torrentEvent(t, EngineEvent.Level.INFO, "engine.event.metadataStopped");
                        } else {
                            torrentEvent(t, EngineEvent.Level.INFO, "engine.event.metadata");
                        }
                        probeArchives(t.handle(), false);
                    }
                    case TORRENT_FINISHED -> {
                        TorrentAlert<?> t = (TorrentAlert<?>) alert;
                        String finished = t.handle().isValid() ? id(t.handle()) : "";
                        long downloaded = t.handle().isValid() && broughtBack.contains(finished)
                                ? t.handle().status().totalPayloadDownload()
                                : -1;
                        if (savesOnFinish(restoredWhole.remove(finished), downloaded)) requestSave(t.handle(), false);
                        // Brought back complete, not finished now: no download, so nothing to run on.
                        // One brought back part done that finishes this session has downloaded.
                        boolean wasComplete = downloaded == 0;
                        if (!wasComplete)
                            torrentEvent(t, EngineEvent.Level.INFO, EngineEvent.Kind.FINISHED, "engine.event.finished");
                        if (t.handle().isValid()) {
                            // Names first, then the move: both are disk jobs, done in this order.
                            applySuffixes(id(t.handle()), t.handle());
                            maybeMove(id(t.handle()), t.handle());
                            indexContent(id(t.handle()), t.handle());
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
                            // After a recheck too, which is the one thing that says a file is
                            // no longer complete: nothing else is posted for that.
                            indexContent(id(t.handle()), t.handle());
                        }
                    }
                    case PIECE_FINISHED -> {
                        // Only while a stream waits: a downloading torrent says this for every piece.
                        if (pieceWaits.isEmpty()) return;
                        PieceFinishedAlert t = (PieceFinishedAlert) alert;
                        if (!t.handle().isValid()) return;
                        Map<Integer, java.util.concurrent.CompletableFuture<Void>> waits =
                                pieceWaits.get(id(t.handle()));
                        var f = waits == null ? null : waits.remove(t.pieceIndex());
                        if (f != null) f.complete(null);
                    }
                    case FILE_COMPLETED -> {
                        FileCompletedAlert t = (FileCompletedAlert) alert;
                        if (t.handle().isValid()) {
                            unsuffix(id(t.handle()), t.handle(), t.index());
                            indexContent(id(t.handle()), t.handle());
                        }
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
                            indexContent(id, t.handle());
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
                        fileLayout = null; // read again, under whatever name libtorrent has now
                        if (t.handle().isValid()) {
                            removeEmptyFolders(
                                    t.handle().status(StatusQuery.FLAGS).swig().getSave_path(),
                                    t.swig().old_name());
                            indexContent(id(t.handle()), t.handle());
                        }
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
                    case EXTERNAL_IP ->
                        onExternalAddress(
                                ((ExternalIpAlert) alert).externalAddress().toString());
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
                        onPeerDisconnected(d);
                    }
                    case PEER_ERROR -> {
                        PeerErrorAlert e = (PeerErrorAlert) alert;
                        logPeer("error " + e.endpoint() + " " + e.operation() + ": "
                                + e.error().getMessage());
                    }
                    case SAVE_RESUME_DATA -> onSaved((SaveResumeDataAlert) alert);
                    case SAVE_RESUME_DATA_FAILED -> onSaveFailed((SaveResumeDataFailedAlert) alert);
                    case READ_PIECE -> onPieceRead((ReadPieceAlert) alert);
                    case DHT_MUTABLE_ITEM -> onMutableItem((org.libtorrent4j.alerts.DhtMutableItemAlert) alert);
                    case DHT_PUT -> onMutablePut((org.libtorrent4j.alerts.DhtPutAlert) alert);
                    case ALERTS_DROPPED -> onDropped((org.libtorrent4j.alerts.AlertsDroppedAlert) alert);
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
        /**
         * A fetch is the dialog's, not the person's, and stays past its metadata to look at the
         * swarm: its check, its trackers and its errors must not reach the list's state or the log
         * (the check's handler would apply suffixes and owed moves by its id). Only its metadata,
         * its add alert (swallowed by id) and its peers are heard.
         */
        private boolean ofAFetch(Alert<?> alert) {
            if (fetches.isEmpty() || !(alert instanceof TorrentAlert<?> t)) return false;
            switch (alert.type()) {
                case METADATA_RECEIVED, ADD_TORRENT, PEER_CONNECT, PEER_DISCONNECTED, PEER_ERROR -> {
                    return false;
                }
                default -> {}
            }
            TorrentHandle h = t.handle();
            return h.isValid() && fetchOf(h.swig()) != null;
        }

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
            String id = alert.params().getInfoHashes().getBest().toHex();
            rememberName(id, alert.params());
            // A torrent restored at start is not new to the person; nothing runs on it.
            boolean restored = restoring.remove(id);
            // One from the store (a start, an import, an undo) has its blob there already, and asking
            // for resume data now would replace it: libtorrent answers a request made before its
            // resume check is done with no pieces at all, and a paused torrent, never modified
            // again, kept that blob, so the next start found files and no resume data and wanted
            // a full check that a paused torrent never runs (one import in thirty on Linux).
            if (!restored) {
                write(alert.params(), alert.handle().swig());
                // The add params carry the trackers only for a magnet; for a .torrent they live
                // inside the info and the params' own list is empty, so a blob written from the
                // params alone would bring the torrent back without its trackers. libtorrent's own
                // resume data has them: ask for it now, and it replaces the blob shortly after.
                requestSave(alert.handle(), false);
            }
            if (restored) broughtBack.add(id);
            if (restored) torrentEvent(alert, EngineEvent.Level.INFO, "engine.event.restored");
            else torrentEvent(alert, EngineEvent.Level.INFO, EngineEvent.Kind.ADDED, "engine.event.added");
            probeArchives(alert.handle(), restored);
        }

        /** The params' name, else the metadata's; a magnet with neither is asked for later. */
        private void rememberName(String id, AddTorrentParams params) {
            String name = params.getName();
            if (name == null || name.isEmpty()) {
                TorrentInfo info = params.getTorrentInfo();
                name = info != null && info.isValid() ? info.name() : "";
                Reference.reachabilityFence(info);
            }
            if (!name.isEmpty()) ownNames.put(id, name);
        }

        /**
         * libtorrent's queue was full and alerts were thrown away: a resume-data answer among them
         * leaves a stop waiting its full bound, a finished or metadata one is never heard. Said once
         * per burst, to the Debug Log, with the kinds that went.
         */
        private void onDropped(org.libtorrent4j.alerts.AlertsDroppedAlert alert) {
            long now = System.currentTimeMillis();
            if (!dropWarningDue(lastDropWarningMs, now)) return;
            lastDropWarningMs = now;
            org.libtorrent4j.swig.bitset_128 dropped = alert.swig().getDropped_alerts();
            List<String> kinds = new ArrayList<>();
            for (int i = 0; i < dropped.size(); i++) {
                if (dropped.test(i)) kinds.add(String.valueOf(AlertType.fromSwig(i)));
            }
            Reference.reachabilityFence(alert);
            LOG.warning("libtorrent's alert queue (" + ALERT_QUEUE_SIZE + ") overflowed; dropped: " + kinds);
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

        /**
         * A peer that drops a probe because both ends only upload is a seed (or, rarely, a partial
         * seed): the probe, once its file list is in, is upload-only, and nothing else is.
         */
        private void onPeerDisconnected(PeerDisconnectedAlert alert) {
            if (fetches.isEmpty() || !UPLOAD_TO_UPLOAD.equals(alert.error().getMessage())) return;
            TorrentHandle handle = alert.handle();
            Fetch fetch = handle.isValid() ? fetchOf(handle.swig()) : null;
            if (fetch != null) fetch.seedMet(alert.endpoint().address().toString());
        }

        /**
         * libtorrent's vote on this machine's address as peers, trackers and the DHT report it: kept
         * for the status bar, and said in the log when it moves, since an address that is suddenly
         * the home connection's rather than the VPN's is the leak a person wants to hear of.
         */
        private void onExternalAddress(String address) {
            String was = connectivity.getAndUpdate(c -> c.seenAs(address)).externalAddress();
            if (!address.equals(was))
                emit(EngineEvent.session(EngineEvent.Level.INFO, tr("engine.event.seenAs", address)));
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
            write(alert.params(), alert.handle().swig());
            savedFor(alert.params().getInfoHashes().getBest().toHex());
        }

        /** A detach waiting on this torrent's save hears it is done. */
        private void savedFor(String id) {
            java.util.concurrent.CountDownLatch waiting = saveWaits.get(id);
            if (waiting != null) waiting.countDown();
        }

        private void onSaveFailed(SaveResumeDataFailedAlert alert) {
            pendingSaves.decrementAndGet();
            if (alert.handle().isValid()) savedFor(id(alert.handle()));
            // "Resume data not modified" is the normal answer to an only-if-modified request.
            if (alert.error().isError()) {
                LOG.fine("Resume data not written for " + alert.handle().infoHash() + ": " + alert.message());
            }
        }

        private void write(AddTorrentParams params, torrent_handle about) {
            String id = params.getInfoHashes().getBest().toHex();
            // Check and write under one lock: see remove(). Without it CI saw the remove test's
            // blob come back, written by a save that had passed the check before the delete.
            synchronized (store) {
                if (removedOne(id, about)) return;
                try {
                    store.save(id, AddTorrentParams.writeResumeDataBuf(params));
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not write resume data for " + id, e);
                }
            }
        }
    }
}
