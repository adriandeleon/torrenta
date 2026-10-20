package com.torrenta.engine.libtorrent;

import java.io.File;
import java.io.IOException;
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
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.TorrentStore;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.AnnounceEndpoint;
import org.libtorrent4j.AnnounceEntry;
import org.libtorrent4j.AnnounceInfohash;
import org.libtorrent4j.BDecodeNode;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.PeerInfo;
import org.libtorrent4j.PieceIndexBitfield;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.AddTorrentAlert;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.FileErrorAlert;
import org.libtorrent4j.alerts.FileRenameFailedAlert;
import org.libtorrent4j.alerts.FileRenamedAlert;
import org.libtorrent4j.alerts.ListenFailedAlert;
import org.libtorrent4j.alerts.ListenSucceededAlert;
import org.libtorrent4j.alerts.PortmapAlert;
import org.libtorrent4j.alerts.PortmapErrorAlert;
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert;
import org.libtorrent4j.alerts.TorrentAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TrackerErrorAlert;
import org.libtorrent4j.alerts.TrackerWarningAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.load_torrent_limits;
import org.libtorrent4j.swig.resume_data_flags_t;
import org.libtorrent4j.swig.status_flags_t;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_handle_vector;

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
 */
public final class LibtorrentEngine implements Engine {

    private static final Logger LOG = Logger.getLogger(LibtorrentEngine.class.getName());

    private static final long PERIODIC_FLUSH_SECONDS = 120;
    private static final long STOP_FLUSH_TIMEOUT_MS = 5000;

    /** Read at every use: the Settings window changes it while the session runs. */
    private final java.util.function.Supplier<Path> downloadDir;

    private final TorrentStore store;
    private SessionManager session;
    private ScheduledExecutorService flusher;

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

    public LibtorrentEngine(Path downloadDir, TorrentStore store) {
        this(() -> downloadDir, store);
    }

    public LibtorrentEngine(java.util.function.Supplier<Path> downloadDir, TorrentStore store) {
        this.downloadDir = downloadDir;
        this.store = store;
    }

    @Override
    public synchronized void start() throws EngineException {
        if (session != null) return;
        NativeLibrary.ensureAvailable();
        Path dir = downloadDir.get();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new EngineException("Could not create the download folder " + dir, e);
        }
        SessionManager s;
        try {
            s = new SessionManager();
            s.start();
        } catch (LinkageError e) {
            // The JNI static initialiser wraps a failed System.load in a LinkageError whose own
            // message is a pointer to the project page; the cause says what actually went wrong.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EngineException("The BitTorrent engine failed to load: " + cause.getMessage(), e);
        }
        s.addListener(new Listener());
        session = s;
        emit(EngineEvent.session(EngineEvent.Level.INFO, "Engine started: " + description()));
        restore(s);

        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, PERIODIC_FLUSH_SECONDS, PERIODIC_FLUSH_SECONDS, TimeUnit.SECONDS);
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
            error_code ec = new error_code();
            add_torrent_params params = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(entry.getValue()), ec);
            if (ec.value() != 0) {
                LOG.warning("Resume data for " + entry.getKey() + " is unreadable (" + ec.message() + "); skipped");
                continue;
            }
            error_code added = new error_code();
            s.swig().add_torrent(params, added);
            if (added.value() != 0) {
                LOG.warning("Could not bring back " + entry.getKey() + ": " + added.message());
            }
        }
    }

    @Override
    public String description() {
        return "libtorrent " + LibTorrent.version() + " (libtorrent4j " + LibTorrent.libtorrent4jVersion() + ")";
    }

    @Override
    public Torrent add(String input) throws EngineException {
        AddRequest.Source source;
        if (CommandLine.isMagnet(input)) {
            source = new AddRequest.Magnet(input);
        } else {
            File file = new File(input);
            if (!file.isFile()) throw new EngineException("No such file: " + input);
            try {
                source = new AddRequest.TorrentFile(Files.readAllBytes(file.toPath()), input);
            } catch (IOException e) {
                throw new EngineException("Could not read " + input + ": " + e.getMessage(), e);
            }
        }
        return add(AddRequest.defaults(source, downloadDir.get()));
    }

    @Override
    public Torrent add(AddRequest request) throws EngineException {
        SessionManager s = running();
        AddTorrentParams params;
        TorrentInfo info = null;
        switch (request.source()) {
            case AddRequest.Magnet magnet -> {
                try {
                    params = AddTorrentParams.parseMagnetUri(magnet.uri());
                } catch (RuntimeException e) {
                    throw new EngineException("Not a valid magnet link", e);
                }
            }
            case AddRequest.TorrentFile file -> {
                params = load(file.bytes(), file.origin());
                info = params.getTorrentInfo();
            }
        }
        params.setSavePath(request.savePath().toString());

        // Renaming files for the layout needs the handle, so such a torrent is added paused and
        // resumed below once the names are right; a plain "start paused" stays paused.
        // A .torrent already has its metadata, so "stop after metadata" means "start paused".
        boolean pauseNow = request.startPaused()
                || (info != null && request.stopCondition() == AddRequest.StopCondition.METADATA_RECEIVED);

        torrent_flags_t flags = params.getFlags();
        if (pauseNow) flags = flags.or_(Flags.PAUSED).and_(Flags.AUTO_MANAGED.inv());
        if (request.sequential()) flags = flags.or_(Flags.SEQUENTIAL_DOWNLOAD);
        if (request.skipCheck()) flags = flags.or_(Flags.SEED_MODE);
        if (request.stopCondition() == AddRequest.StopCondition.FILES_CHECKED) flags = flags.or_(Flags.STOP_WHEN_READY);
        params.setFlags(flags);

        if (info != null && !request.filePriorities().isEmpty()) {
            org.libtorrent4j.Priority[] priorities = new org.libtorrent4j.Priority[info.numFiles()];
            for (int i = 0; i < priorities.length; i++) {
                priorities[i] = toLibtorrent(request.filePriorities().getOrDefault(i, Priority.NORMAL));
            }
            params.filePriorities(priorities);
        }

        String id = params.getInfoHashes().getBest().toHex();
        if (info == null && request.stopCondition() == AddRequest.StopCondition.METADATA_RECEIVED)
            stopAfterMetadata.add(id);

        // The synchronous add, so the caller gets a handle (and an error) straight away; the
        // add_torrent_alert that follows is what writes the first resume data.
        error_code ec = new error_code();
        torrent_handle raw = s.swig().add_torrent(params.swig(), ec);
        if (ec.value() != 0) {
            stopAfterMetadata.remove(id);
            throw new EngineException(ec.message());
        }
        TorrentHandle handle = new TorrentHandle(raw);

        return snapshot(handle);
    }

    @Override
    public TorrentPreview inspect(byte[] torrent) throws EngineException {
        NativeLibrary.ensureAvailable();
        AddTorrentParams params = load(torrent, "the torrent");
        TorrentInfo info = params.getTorrentInfo();
        FileStorage files = info.files();
        List<TorrentPreview.FileEntry> entries = new ArrayList<>(files.numFiles());
        for (int i = 0; i < files.numFiles(); i++) {
            if (files.padFileAt(i)) continue;
            entries.add(new TorrentPreview.FileEntry(i, files.filePath(i).replace('\\', '/'), files.fileSize(i)));
        }
        // The real files' bytes, not totalSize(): a hybrid torrent's padding files are not content.
        long size = entries.stream().mapToLong(TorrentPreview.FileEntry::size).sum();
        return new TorrentPreview(
                params.getInfoHashes().getBest().toHex(),
                params.getName().isEmpty() ? info.name() : params.getName(),
                size,
                List.copyOf(entries),
                info.isPrivate(),
                List.copyOf(params.getTrackers()),
                params.getComment() == null ? "" : params.getComment(),
                params.getCreatedBy() == null ? "" : params.getCreatedBy(),
                params.getCreationDate());
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
            if (ec.value() != 0)
                throw new EngineException("Not a valid .torrent: " + origin + " (" + ec.message() + ")");
            AddTorrentParams params = new AddTorrentParams(raw);
            TorrentInfo info = params.getTorrentInfo();
            if (info == null || !info.isValid()) throw new EngineException("Not a valid .torrent: " + origin);
            return params;
        } catch (RuntimeException e) {
            throw new EngineException("Not a valid .torrent: " + origin, e);
        }
    }

    private static TorrentInfo decode(byte[] bytes, String origin) throws EngineException {
        try {
            TorrentInfo info = new TorrentInfo(bytes);
            if (!info.isValid()) throw new EngineException("Not a valid .torrent: " + origin);
            return info;
        } catch (RuntimeException e) {
            throw new EngineException("Not a valid .torrent: " + origin, e);
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
    public void remove(String id, boolean deleteFiles) throws EngineException {
        SessionManager s = running();
        TorrentHandle handle = find(s, id);
        if (handle == null) throw new EngineException("No such torrent: " + id);
        // Named here, before the handle dies: the removed alert carries only the hash.
        String name = nameOf(handle);
        if (deleteFiles) s.remove(handle, SessionHandle.DELETE_FILES);
        else s.remove(handle);
        emit(EngineEvent.info(id, name, deleteFiles ? "Removed " + name + " and its files" : "Removed " + name));
        // Forgotten before libtorrent confirms: a crash in between loses nothing worse than a
        // torrent that comes back once, which beats one that cannot be removed. Marked and deleted
        // under the store's lock, the same lock the alert thread's write takes around its check,
        // so a write that already passed the check cannot land after this delete.
        synchronized (store) {
            removed.add(id);
            try {
                store.delete(id);
            } catch (IOException e) {
                throw new EngineException("Removed from the session, but its resume data stayed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void pause(String id) throws EngineException {
        TorrentHandle h = handle(id);
        // Out of the queue's hands first: an auto-managed torrent that is paused gets resumed by
        // the queue at its next pass, which is not what "pause" means to a person.
        h.unsetFlags(Flags.AUTO_MANAGED);
        h.pause();
    }

    @Override
    public void resume(String id) throws EngineException {
        TorrentHandle h = handle(id);
        h.setFlags(Flags.AUTO_MANAGED);
        h.resume();
    }

    @Override
    public void forceResume(String id) throws EngineException {
        TorrentHandle h = handle(id);
        h.unsetFlags(Flags.AUTO_MANAGED);
        h.resume();
    }

    @Override
    public void recheck(String id) throws EngineException {
        handle(id).forceRecheck();
    }

    @Override
    public void reannounce(String id) throws EngineException {
        handle(id).forceReannounce();
    }

    @Override
    public void setSequential(String id, boolean sequential) throws EngineException {
        TorrentHandle h = handle(id);
        if (sequential) h.setFlags(Flags.SEQUENTIAL_DOWNLOAD);
        else h.unsetFlags(Flags.SEQUENTIAL_DOWNLOAD);
    }

    @Override
    public String magnetLink(String id) throws EngineException {
        return handle(id).makeMagnetUri();
    }

    @Override
    public TorrentDetail detail(String id) throws EngineException {
        TorrentHandle h = handle(id);
        TorrentStatus status = h.status(StatusQuery.ALL);

        // Files: progress and priority by index; padding files hidden, indices kept.
        List<TorrentDetail.FileStatus> files = new ArrayList<>();
        TorrentInfo info = status.hasMetadata() ? h.torrentFile() : null;
        if (info != null && info.isValid()) {
            FileStorage storage = info.files();
            long[] progress = h.fileProgress();
            org.libtorrent4j.Priority[] priorities = h.filePriorities();
            for (int i = 0; i < storage.numFiles(); i++) {
                if (storage.padFileAt(i)) continue;
                long done = i < progress.length ? progress[i] : 0;
                Priority priority = i < priorities.length ? fromLibtorrent(priorities[i]) : Priority.NORMAL;
                files.add(new TorrentDetail.FileStatus(
                        i, storage.filePath(i).replace('\\', '/'), storage.fileSize(i), done, priority));
            }
        }

        List<TorrentDetail.Peer> peers = new ArrayList<>();
        for (PeerInfo p : h.peerInfo()) {
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
        for (AnnounceEntry entry : h.trackers()) trackers.add(tracker(entry));

        PieceIndexBitfield bits = status.pieces();
        boolean[] pieces = new boolean[Math.max(0, bits.size())];
        for (int i = 0; i < pieces.length; i++) pieces[i] = bits.getBit(i);

        TorrentDetail.General general = new TorrentDetail.General(
                id,
                status.swig().getSave_path(),
                info != null && info.isPrivate(),
                status.numPieces() > 0 ? status.numPieces() : (info == null ? 0 : info.numPieces()),
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
                status.currentTracker() == null ? "" : status.currentTracker());
        return new TorrentDetail(general, files, peers, trackers, pieces);
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
                if (hash.isWorking()) anyWorking = true;
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
    public void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException {
        handle(id).filePriority(fileIndex, toLibtorrent(priority));
    }

    @Override
    public void addTrackers(String id, List<String> urls) throws EngineException {
        TorrentHandle h = handle(id);
        List<AnnounceEntry> existing = h.trackers();
        int tier = existing.stream().mapToInt(AnnounceEntry::tier).max().orElse(-1) + 1;
        for (String url : urls) {
            String u = url.trim();
            if (u.isEmpty() || existing.stream().anyMatch(e -> e.url().equals(u))) continue;
            AnnounceEntry entry = new AnnounceEntry(u);
            entry.tier((short) tier);
            h.addTracker(entry);
        }
    }

    @Override
    public void removeTracker(String id, String url) throws EngineException {
        TorrentHandle h = handle(id);
        List<AnnounceEntry> kept = new ArrayList<>();
        for (AnnounceEntry e : h.trackers()) {
            if (!e.url().equals(url)) kept.add(e);
        }
        h.replaceTrackers(kept);
    }

    private static Priority fromLibtorrent(org.libtorrent4j.Priority priority) {
        if (priority == org.libtorrent4j.Priority.IGNORE) return Priority.IGNORE;
        if (priority == org.libtorrent4j.Priority.LOW) return Priority.LOW;
        if (priority == org.libtorrent4j.Priority.TOP_PRIORITY) return Priority.HIGH;
        return Priority.NORMAL;
    }

    private TorrentHandle handle(String id) throws EngineException {
        TorrentHandle handle = find(running(), id);
        if (handle == null) throw new EngineException("No such torrent: " + id);
        return handle;
    }

    @Override
    public List<Torrent> torrents() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return List.of();
        List<Torrent> out = new ArrayList<>();
        for (TorrentHandle handle : handles(s)) out.add(snapshot(handle));
        return out;
    }

    @Override
    public Rates rates() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return Rates.ZERO;
        return new Rates(s.downloadRate(), s.uploadRate());
    }

    /** Asks every torrent for fresh resume data, if it changed. Answers land through the alert. */
    @Override
    public void flush() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return;
        for (TorrentHandle handle : handles(s)) requestSave(handle, true);
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
        emit(EngineEvent.session(EngineEvent.Level.INFO, "Engine stopped"));
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

    private static List<TorrentHandle> handles(SessionManager s) {
        torrent_handle_vector vector = s.swig().get_torrents();
        List<TorrentHandle> out = new ArrayList<>(vector.size());
        for (int i = 0; i < vector.size(); i++) {
            TorrentHandle handle = new TorrentHandle(vector.get(i));
            if (handle.isValid()) out.add(handle);
        }
        return out;
    }

    private static TorrentHandle find(SessionManager s, String id) {
        for (TorrentHandle handle : handles(s)) {
            if (id(handle).equals(id)) return handle;
        }
        return null;
    }

    private SessionManager running() throws EngineException {
        SessionManager s = session;
        if (s == null || !s.isRunning()) throw new EngineException("The BitTorrent engine is not running");
        return s;
    }

    /** The best info-hash (v2 if the torrent has one, else v1) in hex: the id everywhere. */
    private static String id(TorrentHandle handle) {
        return handle.status().getInfoHashes().getBest().toHex();
    }

    /** The name, or the hash while there is none; empty for a handle that is already gone. */
    private static String nameOf(TorrentHandle handle) {
        if (!handle.isValid()) return "";
        TorrentStatus status = handle.status(StatusQuery.FLAGS);
        String name = status.name();
        return name == null || name.isEmpty() ? status.getInfoHashes().getBest().toHex() : name;
    }

    /**
     * Initialised on first use, never when this class loads: {@code TorrentHandle}'s constants are
     * SWIG objects, and touching one before {@link NativeLibrary#ensureAvailable()} has run makes
     * the JNI class fail to initialise — and then every later use of it, for the life of the JVM.
     */
    private static final class StatusQuery {
        /** The default status query leaves the strings out (they cost a copy per call); ask. */
        static final status_flags_t FLAGS = TorrentHandle.QUERY_NAME.or_(TorrentHandle.QUERY_SAVE_PATH);

        /** For the detail pane: the pieces bitfield too, which costs a copy per piece. */
        static final status_flags_t ALL =
                FLAGS.or_(TorrentHandle.QUERY_PIECES).or_(TorrentHandle.QUERY_DISTRIBUTED_COPIES);
    }

    /** Same rule as {@link StatusQuery}: {@code TorrentFlags}' constants are SWIG objects. */
    private static final class Flags {
        static final torrent_flags_t PAUSED = TorrentFlags.PAUSED;
        static final torrent_flags_t AUTO_MANAGED = TorrentFlags.AUTO_MANAGED;
        static final torrent_flags_t SEQUENTIAL_DOWNLOAD = TorrentFlags.SEQUENTIAL_DOWNLOAD;
        static final torrent_flags_t SEED_MODE = TorrentFlags.SEED_MODE;
        static final torrent_flags_t STOP_WHEN_READY = TorrentFlags.STOP_WHEN_READY;
    }

    private static boolean has(torrent_flags_t flags, torrent_flags_t flag) {
        return flags.and_(flag).non_zero();
    }

    private static Torrent snapshot(TorrentHandle handle) {
        TorrentStatus status = handle.status(StatusQuery.FLAGS);
        torrent_flags_t flags = status.flags();
        String id = status.getInfoHashes().getBest().toHex();
        String name = status.name();
        if (name == null || name.isEmpty()) name = id;
        String tracker = status.currentTracker();
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
                status.addedTime(),
                status.completedTime(),
                tracker == null ? "" : tracker,
                status.swig().getSave_path(),
                // Paused by a person, not parked by the queue: the queue pauses auto-managed ones.
                has(flags, Flags.PAUSED) && !has(flags, Flags.AUTO_MANAGED),
                has(flags, Flags.SEQUENTIAL_DOWNLOAD));
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
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_CHECKED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.FILE_RENAMED.swig(),
            AlertType.FILE_RENAME_FAILED.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.TRACKER_WARNING.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.PORTMAP.swig(),
            AlertType.PORTMAP_ERROR.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
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
                    case METADATA_RECEIVED -> {
                        TorrentAlert<?> t = (TorrentAlert<?>) alert;
                        requestSave(t.handle(), false);
                        if (t.handle().isValid() && stopAfterMetadata.remove(id(t.handle()))) {
                            t.handle().unsetFlags(Flags.AUTO_MANAGED);
                            t.handle().pause();
                            torrentEvent(t, EngineEvent.Level.INFO, "Metadata received for %s; stopped as asked");
                        } else {
                            torrentEvent(t, EngineEvent.Level.INFO, "Metadata received for %s");
                        }
                    }
                    case TORRENT_FINISHED -> {
                        TorrentAlert<?> t = (TorrentAlert<?>) alert;
                        requestSave(t.handle(), false);
                        torrentEvent(t, EngineEvent.Level.INFO, "%s finished");
                    }
                    case TORRENT_CHECKED -> torrentEvent((TorrentAlert<?>) alert, EngineEvent.Level.INFO, "%s checked");
                    case TORRENT_ERROR -> {
                        TorrentErrorAlert t = (TorrentErrorAlert) alert;
                        torrentEvent(
                                t,
                                EngineEvent.Level.ERROR,
                                "%s: " + t.error().getMessage()
                                        + (t.filename().isEmpty() ? "" : " (" + t.filename() + ")"));
                    }
                    case FILE_RENAMED -> {
                        FileRenamedAlert t = (FileRenamedAlert) alert;
                        torrentEvent(t, EngineEvent.Level.INFO, "%s: file " + t.index() + " renamed to " + t.newName());
                    }
                    case FILE_RENAME_FAILED -> {
                        FileRenameFailedAlert t = (FileRenameFailedAlert) alert;
                        torrentEvent(
                                t,
                                EngineEvent.Level.ERROR,
                                "%s: file " + t.getIndex() + " could not be renamed: "
                                        + t.getError().getMessage());
                    }
                    case FILE_ERROR -> {
                        FileErrorAlert t = (FileErrorAlert) alert;
                        torrentEvent(
                                t, EngineEvent.Level.ERROR, "%s: " + t.error().getMessage() + " — " + t.filename());
                    }
                    case TRACKER_ERROR -> {
                        TrackerErrorAlert t = (TrackerErrorAlert) alert;
                        String reason = t.errorMessage().isEmpty() ? t.error().getMessage() : t.errorMessage();
                        torrentEvent(t, EngineEvent.Level.WARNING, "%s: tracker " + t.trackerUrl() + " — " + reason);
                    }
                    case TRACKER_WARNING -> {
                        TrackerWarningAlert t = (TrackerWarningAlert) alert;
                        torrentEvent(
                                t, EngineEvent.Level.WARNING, "%s: tracker " + t.trackerUrl() + " — " + t.message());
                    }
                    case LISTEN_SUCCEEDED -> {
                        ListenSucceededAlert a = (ListenSucceededAlert) alert;
                        emit(EngineEvent.session(
                                EngineEvent.Level.INFO,
                                "Listening on " + a.address() + ":" + a.port() + " (" + a.socketType() + ")"));
                    }
                    case LISTEN_FAILED -> {
                        ListenFailedAlert a = (ListenFailedAlert) alert;
                        emit(EngineEvent.session(
                                EngineEvent.Level.ERROR,
                                "Could not listen on " + a.listenInterface() + ":" + a.port() + " (" + a.socketType()
                                        + "): " + a.error().getMessage()));
                    }
                    case PORTMAP -> {
                        PortmapAlert a = (PortmapAlert) alert;
                        emit(EngineEvent.session(
                                EngineEvent.Level.INFO,
                                "Port " + a.externalPort() + " mapped with " + a.mapTransport() + " (" + a.mapProtocol()
                                        + ")"));
                    }
                    case PORTMAP_ERROR -> {
                        PortmapErrorAlert a = (PortmapErrorAlert) alert;
                        emit(EngineEvent.session(
                                EngineEvent.Level.WARNING,
                                "Port mapping with " + a.mapTransport() + " failed: "
                                        + a.error().getMessage()));
                    }
                    case SAVE_RESUME_DATA -> onSaved((SaveResumeDataAlert) alert);
                    case SAVE_RESUME_DATA_FAILED -> onSaveFailed((SaveResumeDataFailedAlert) alert);
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
        private void onAdded(AddTorrentAlert alert) {
            if (alert.error().isError()) {
                String name = alert.params().getName();
                emit(EngineEvent.error(
                        null,
                        name,
                        "Could not add " + name + ": " + alert.error().getMessage()));
                return;
            }
            write(alert.params());
            // The add params carry the trackers only for a magnet; for a .torrent they live inside
            // the info and the params' own list is empty, so a blob written from the params alone
            // would bring the torrent back without its trackers. libtorrent's own resume data has
            // them: ask for it now, and it replaces the blob shortly after.
            requestSave(alert.handle(), false);
            String id = alert.params().getInfoHashes().getBest().toHex();
            torrentEvent(alert, EngineEvent.Level.INFO, restoring.remove(id) ? "Restored %s" : "Added %s");
        }

        /** {@code template} has one {@code %s}, the torrent's name. */
        private void torrentEvent(TorrentAlert<?> alert, EngineEvent.Level level, String template) {
            TorrentHandle handle = alert.handle();
            if (!handle.isValid()) return;
            String name = nameOf(handle);
            emit(new EngineEvent(java.time.Instant.now(), level, id(handle), name, template.formatted(name)));
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
