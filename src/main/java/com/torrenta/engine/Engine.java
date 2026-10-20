package com.torrenta.engine;

import java.util.List;
import java.util.function.Consumer;

/**
 * The BitTorrent engine, as the interface sees it.
 *
 * <p>Deliberately small: start, add, remove, look, stop. The first implementation is libtorrent
 * through libtorrent4j; a second one could be a Transmission daemon over RPC, which is why nothing
 * here leaks a libtorrent type. Every method may block for a few milliseconds and none is safe on
 * the FX thread.
 *
 * <p>Persistence is the engine's job: {@link #start()} brings back what was there, and the engine
 * writes what it needs to do that as torrents change and on {@link #stop()}.
 */
public interface Engine extends AutoCloseable {

    /** Loads whatever the engine needs, opens the session, brings back the stored torrents. Slow. */
    void start() throws EngineException;

    /** Engine name and version, for the status bar and bug reports. Valid after {@link #start()}. */
    String description();

    /** Adds a {@code .torrent} file (a path) or a {@code magnet:} link, with the defaults. */
    Torrent add(String input) throws EngineException;

    /** Adds with the dialog's choices. */
    Torrent add(AddRequest request) throws EngineException;

    /** Reads a {@code .torrent} without adding it, for the dialog. */
    TorrentPreview inspect(byte[] torrent) throws EngineException;

    /** Removes the torrent and forgets it; with {@code deleteFiles}, its data on disk too. */
    void remove(String id, boolean deleteFiles) throws EngineException;

    /** Stops transferring and leaves the queue; the torrent stays until resumed. */
    void pause(String id) throws EngineException;

    /** Hands the torrent back to the queue, which starts it when a slot is free. */
    void resume(String id) throws EngineException;

    /** Starts it now, regardless of queue limits. */
    void forceResume(String id) throws EngineException;

    /** Re-hashes what is on disk against the pieces; progress drops and climbs back. */
    void recheck(String id) throws EngineException;

    /** Announces to the trackers now rather than at the next scheduled time. */
    void reannounce(String id) throws EngineException;

    /** Pieces in order rather than rarest first, for previewing while downloading. */
    void setSequential(String id, boolean sequential) throws EngineException;

    /** A magnet link for the torrent, with its trackers, as the engine would build it. */
    String magnetLink(String id) throws EngineException;

    /** A snapshot of every torrent in the session. */
    List<Torrent> torrents();

    /** Session-wide payload rates. */
    Rates rates();

    /** Writes what is needed to bring the torrents back; also done on {@link #stop()}. */
    void flush();

    /**
     * Hears every {@link EngineEvent}, on an engine thread: the listener hands it to the FX thread
     * itself. Added before or after {@link #start()}.
     */
    void addListener(Consumer<EngineEvent> listener);

    void removeListener(Consumer<EngineEvent> listener);

    void stop();

    @Override
    default void close() {
        stop();
    }

    /** Bytes per second, session-wide. */
    record Rates(long download, long upload) {
        public static final Rates ZERO = new Rates(0, 0);
    }
}
