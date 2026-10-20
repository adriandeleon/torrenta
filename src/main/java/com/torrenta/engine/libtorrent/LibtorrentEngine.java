package com.torrenta.engine.libtorrent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_handle_vector;

/**
 * The engine on libtorrent, through libtorrent4j.
 *
 * <p>One {@link SessionManager}, one download directory. Torrents are enumerated from the session
 * each time rather than tracked here, so there is exactly one source of truth and nothing to keep
 * in step. No resume data, limits or settings yet.
 */
public final class LibtorrentEngine implements Engine {

    private final Path downloadDir;
    private SessionManager session;

    public LibtorrentEngine(Path downloadDir) {
        this.downloadDir = downloadDir;
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
        session = s;
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
        // asynchronous one reports through an alert that nothing listens to yet.
        error_code ec = new error_code();
        torrent_handle handle = s.swig().add_torrent(params.swig(), ec);
        if (ec.value() != 0) throw new EngineException(ec.message());
        return snapshot(new TorrentHandle(handle));
    }

    @Override
    public List<Torrent> torrents() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return List.of();
        torrent_handle_vector handles = s.swig().get_torrents();
        List<Torrent> out = new ArrayList<>(handles.size());
        for (int i = 0; i < handles.size(); i++) {
            TorrentHandle handle = new TorrentHandle(handles.get(i));
            if (handle.isValid()) out.add(snapshot(handle));
        }
        return out;
    }

    @Override
    public Rates rates() {
        SessionManager s = session;
        if (s == null || !s.isRunning()) return Rates.ZERO;
        return new Rates(s.downloadRate(), s.uploadRate());
    }

    @Override
    public synchronized void stop() {
        if (session == null) return;
        session.stop();
        session = null;
    }

    private SessionManager running() throws EngineException {
        SessionManager s = session;
        if (s == null || !s.isRunning()) throw new EngineException("The BitTorrent engine is not running");
        return s;
    }

    private static Torrent snapshot(TorrentHandle handle) {
        // The default status query leaves the name out (it costs a string copy per call); ask.
        TorrentStatus status = handle.status(TorrentHandle.QUERY_NAME);
        String name = status.name();
        if (name == null || name.isEmpty()) name = handle.infoHash().toHex();
        return new Torrent(
                handle.infoHash().toHex(),
                name,
                status.totalWanted(),
                status.progress(),
                state(status.state()),
                status.downloadPayloadRate(),
                status.uploadPayloadRate(),
                status.numPeers(),
                status.numSeeds());
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
}
