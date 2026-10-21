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

    /**
     * Makes a {@code .torrent} of a file or folder: hashes every piece, reporting each to
     * {@code progress}, and adds the result for seeding when the request asks. Blocks for as long
     * as the hashing takes (the caller's worker thread; never the FX thread), unlike the other
     * calls, and does not hold the engine's lock while it hashes, so polls go on. Fails when the
     * path cannot be read, or when {@code progress} asked to stop, which it does at the next piece.
     */
    CreatedTorrent create(CreateRequest request, CreateProgress progress) throws EngineException;

    /**
     * Starts fetching a magnet's metadata without adding it, so the Add dialog can show the files
     * while it is open. The magnet joins the swarm writing nothing, is in no snapshot and no
     * store, and leaves once the metadata is in or the fetch is cancelled. Fails if the magnet is
     * already in the session.
     */
    MetadataFetch fetchMetadata(String magnet) throws EngineException;

    /**
     * Adds what a fetch was fetching, taking over the peers it found: the fetch is ended first
     * (the session holds one torrent per info-hash), and the peers it had dialled are handed to
     * the new torrent, which connects to them as it starts rather than looking for the swarm
     * again. A fetch of another torrent is only ended.
     */
    default Torrent add(AddRequest request, MetadataFetch from) throws EngineException {
        from.cancel();
        return add(request);
    }

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

    /** The torrent's own limits, as it has them now. */
    TorrentLimits limits(String id) throws EngineException;

    /**
     * Sets the torrent's own limits; they are kept with its resume data. First-and-last-first is
     * expressed as piece priorities and needs the metadata, so it is skipped silently for a magnet
     * that has none yet.
     */
    void setLimits(String id, TorrentLimits limits) throws EngineException;

    /** The category the torrent is filed under, or empty. */
    String category(String id) throws EngineException;

    /** Files the torrent under a category (empty for none); kept beside its resume data. */
    void setCategory(String id, String category) throws EngineException;

    /** The torrent's tags, sorted. */
    List<String> tags(String id) throws EngineException;

    /** Replaces the torrent's tags; normalised as {@link TorrentLabels} does. */
    void setTags(String id, List<String> tags) throws EngineException;

    /** A magnet link for the torrent, with its trackers, as the engine would build it. */
    String magnetLink(String id) throws EngineException;

    /** Everything the detail pane shows; a few milliseconds, so once a second at most. */
    TorrentDetail detail(String id) throws EngineException;

    void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException;

    /** Adds trackers the torrent does not have yet, on the tier after its last. */
    void addTrackers(String id, List<String> urls) throws EngineException;

    void removeTracker(String id, String url) throws EngineException;

    /** A snapshot of every torrent in the session. */
    List<Torrent> torrents();

    /** Session-wide payload rates. */
    Rates rates();

    /** Writes what is needed to bring the torrents back; also done on {@link #stop()}. */
    void flush();

    /** Applies connection settings now, to the running session; also the ones used at start. */
    void applySettings(EngineSettings settings) throws EngineException;

    /**
     * Re-evaluates the speed limits for this moment: the alternative pair if its schedule says so
     * or it is on by hand, the normal pair otherwise. Called every minute by the window and after
     * any change. Returns whether the alternative pair is in force.
     */
    boolean refreshLimits(java.time.LocalDateTime now) throws EngineException;

    /** Replaces the IP filter: peers in these ranges are refused. Empty clears it. */
    void setIpFilter(List<IpRange> ranges) throws EngineException;

    /** Peers refused by the filter since the engine started. */
    long blockedPeers();

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

    /** Hears the hashing of {@link #create}, on the hashing thread. */
    @FunctionalInterface
    interface CreateProgress {
        /**
         * {@code done} of {@code total} pieces hashed; the last call is {@code total} of
         * {@code total}. Returns whether to go on: false stops the hashing at this piece and
         * the creation fails as cancelled.
         */
        boolean hashed(int done, int total);

        CreateProgress NONE = (done, total) -> true;
    }

    /** A magnet's metadata on its way, from {@link #fetchMetadata}. */
    interface MetadataFetch {

        /** Peers connected to fetch it now; 0 once the fetch is over. Not on the FX thread. */
        int peers();

        /**
         * The whole {@code .torrent}, the magnet's trackers included, once the metadata is in:
         * what {@link #inspect} reads and {@link AddRequest.TorrentFile} adds. Completes on an
         * engine thread; fails if the fetch is cancelled or the engine stops.
         */
        java.util.concurrent.CompletableFuture<byte[]> torrent();

        /** Leaves the swarm. Idempotent; must come before the same magnet is added for real. */
        void cancel();
    }

    /** Bytes per second, session-wide. */
    record Rates(long download, long upload) {
        public static final Rates ZERO = new Rates(0, 0);
    }
}
