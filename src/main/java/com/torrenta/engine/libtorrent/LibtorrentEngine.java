package com.torrenta.engine.libtorrent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.TorrentStore;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.LibTorrent;
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
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert;
import org.libtorrent4j.alerts.TorrentAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.libtorrent;
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

    private final Path downloadDir;
    private final TorrentStore store;
    private SessionManager session;
    private ScheduledExecutorService flusher;

    /** Resume-data requests whose alert has not arrived yet; {@link #stop()} waits on this. */
    private final AtomicInteger pendingSaves = new AtomicInteger();

    public LibtorrentEngine(Path downloadDir, TorrentStore store) {
        this.downloadDir = downloadDir;
        this.store = store;
    }

    @Override
    public synchronized void start() throws EngineException {
        if (session != null) return;
        NativeLibrary.ensureAvailable();
        try {
            Files.createDirectories(downloadDir);
        } catch (IOException e) {
            throw new EngineException("Could not create the download folder " + downloadDir, e);
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
        SessionManager s = running();
        AddTorrentParams params;
        if (CommandLine.isMagnet(input)) {
            try {
                params = AddTorrentParams.parseMagnetUri(input);
            } catch (RuntimeException e) {
                throw new EngineException("Not a valid magnet link", e);
            }
        } else {
            File file = new File(input);
            if (!file.isFile()) throw new EngineException("No such file: " + input);
            TorrentInfo info;
            try {
                info = new TorrentInfo(file);
            } catch (RuntimeException e) {
                throw new EngineException("Not a valid .torrent file: " + input, e);
            }
            params = new AddTorrentParams();
            params.setTorrentInfo(info);
        }
        params.setSavePath(downloadDir.toString());

        // The synchronous add, so the caller gets a handle (and an error) straight away; the
        // add_torrent_alert that follows is what writes the first resume data.
        error_code ec = new error_code();
        torrent_handle handle = s.swig().add_torrent(params.swig(), ec);
        if (ec.value() != 0) throw new EngineException(ec.message());
        return snapshot(new TorrentHandle(handle));
    }

    @Override
    public void remove(String id, boolean deleteFiles) throws EngineException {
        SessionManager s = running();
        TorrentHandle handle = find(s, id);
        if (handle == null) throw new EngineException("No such torrent: " + id);
        if (deleteFiles) s.remove(handle, SessionHandle.DELETE_FILES);
        else s.remove(handle);
        // Forgotten before libtorrent confirms: a crash in between loses nothing worse than a
        // torrent that comes back once, which beats one that cannot be removed.
        try {
            store.delete(id);
        } catch (IOException e) {
            throw new EngineException("Removed from the session, but its resume data stayed: " + e.getMessage(), e);
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

    /**
     * Initialised on first use, never when this class loads: {@code TorrentHandle}'s constants are
     * SWIG objects, and touching one before {@link NativeLibrary#ensureAvailable()} has run makes
     * the JNI class fail to initialise — and then every later use of it, for the life of the JVM.
     */
    private static final class StatusQuery {
        /** The default status query leaves the strings out (they cost a copy per call); ask. */
        static final status_flags_t FLAGS = TorrentHandle.QUERY_NAME.or_(TorrentHandle.QUERY_SAVE_PATH);
    }

    /** Same rule as {@link StatusQuery}: {@code TorrentFlags}' constants are SWIG objects. */
    private static final class Flags {
        static final torrent_flags_t PAUSED = TorrentFlags.PAUSED;
        static final torrent_flags_t AUTO_MANAGED = TorrentFlags.AUTO_MANAGED;
        static final torrent_flags_t SEQUENTIAL_DOWNLOAD = TorrentFlags.SEQUENTIAL_DOWNLOAD;
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
                    case METADATA_RECEIVED, TORRENT_FINISHED -> requestSave(((TorrentAlert<?>) alert).handle(), false);
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
            if (alert.error().isError()) return;
            write(alert.params());
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
            try {
                store.save(id, AddTorrentParams.writeResumeDataBuf(params));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not write resume data for " + id, e);
            }
        }
    }
}
