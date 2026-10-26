package com.torrenta.engine;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.torrenta.i18n.Messages.tr;

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

    /**
     * What this engine can do, of the things the window offers. Valid after {@link #start()}: a
     * daemon's answer depends on its version. The window hides the commands, pages, rows and
     * dialog choices behind a missing capability; nothing is greyed out or imitated.
     */
    Capabilities capabilities();

    /**
     * Where a new torrent's files go unless the request says otherwise, as the engine sees it:
     * this machine's download folder for libtorrent, the daemon's for a remote engine (a path on
     * the daemon's machine, so a {@code String}). Valid after {@link #start()}.
     */
    String downloadDir();

    /** Adds a {@code .torrent} file (a path) or a {@code magnet:} link, with the defaults. */
    Torrent add(String input) throws EngineException;

    /** Adds with the dialog's choices. */
    Torrent add(AddRequest request) throws EngineException;

    /**
     * Downloads a link that is not a torrent, as a file, on this machine. Only an engine with
     * downloads from links beside it does ({@code direct.WithDownloads}, which the app always
     * builds); the default refuses.
     */
    default Torrent addLink(LinkRequest request) throws EngineException {
        throw new EngineException(tr("engine.error.noLinks"));
    }

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
     * Joins the swarm of a torrent not added yet, writing nothing, so the Add dialog can show what
     * is there while it is open: a magnet's metadata first ({@link SwarmProbe#torrent}), then for
     * either kind the swarm itself ({@link SwarmProbe#swarm}), until it is added or cancelled. The
     * probe is in no snapshot and no store. A private torrent is looked at only with {@code
     * privateToo}, since a private tracker counts every announce and the person has not added it
     * yet: a magnet's probe leaves once its metadata shows it private, and a private {@code
     * .torrent}'s never joins. Fails if the torrent is already in the session, or (with {@link
     * Capability#METADATA_FETCH} or {@link Capability#SWARM_PREVIEW} missing) where the engine
     * cannot.
     */
    SwarmProbe probe(AddRequest.Source source, boolean privateToo) throws EngineException;

    /**
     * Adds what a probe was looking at, taking over the peers it found: the probe is ended first
     * (the session holds one torrent per info-hash), and the peers it had dialled are handed to
     * the new torrent, which connects to them as it starts rather than looking for the swarm
     * again. A probe of another torrent is only ended.
     */
    default Torrent add(AddRequest request, SwarmProbe from) throws EngineException {
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

    /**
     * Moves the torrent in the queue: to the top, one up, one down, to the bottom. The others
     * shift to make room, as {@link Torrent#queuePosition()} then shows; a seed, which libtorrent
     * keeps out of the download queue, is left where it is.
     */
    void queueMove(String id, QueueMove move) throws EngineException;

    /**
     * Changes where the torrent's files live. With {@code moveFiles} the files are moved there,
     * never over a file already at the destination (one that is there is kept and used); without
     * it the torrent is only pointed at the folder, where the files are expected to be already.
     * The move itself is asynchronous: an event says when it landed or why it did not, and the
     * snapshot's {@link Torrent#moving()} is on in between where the engine can tell. {@code
     * destination} is a path on the engine's machine, so a {@code String}, as {@link
     * #downloadDir()} is.
     */
    void moveStorage(String id, String destination, boolean moveFiles) throws EngineException;

    /**
     * Removes the torrent (not its files) and hands back what puts it back as it was: its resume
     * data written fresh for the occasion, its labels, its storage record, its place in the
     * queue. Only with {@link Capability#UNDO_REMOVE}.
     */
    /**
     * Another client's torrent, from its resume data, added as it is and without a check, as a
     * restored one (nothing that runs on an add runs on it): its metainfo put back into the resume
     * data, its files where {@code savePath} says, its labels filed; with {@link Adoption#check} its
     * pieces are checked on the add instead, and a torrent whose files do not match comes up short
     * rather than wrong. The id it is known by. Only with {@link Capability#IMPORT_RESUME}.
     */
    default String adopt(Adoption adoption) throws EngineException {
        throw new EngineException(tr("engine.error.noImport"));
    }

    /**
     * What {@link #adopt} takes.
     *
     * @param resume the other client's resume data, libtorrent's format
     * @param metainfo the {@code .torrent}; null for a magnet whose metadata never arrived
     * @param name for messages
     * @param check the files on disk do not match what the resume data says (the import plan
     *     looked): its pieces are not taken on trust, and the engine checks the files on the add.
     *     libtorrent 2 keeps no file sizes in resume data, so it would believe a truncated file
     *     whole and seed it
     */
    record Adoption(
            byte[] resume,
            byte[] metainfo,
            String savePath,
            TorrentLabels labels,
            String name,
            boolean check,
            Map<Integer, String> renamed) {

        public Adoption {
            renamed = renamed == null ? Map.of() : Map.copyOf(renamed);
        }

        /** No file renamed. */
        public Adoption(
                byte[] resume, byte[] metainfo, String savePath, TorrentLabels labels, String name, boolean check) {
            this(resume, metainfo, savePath, labels, name, check, Map.of());
        }
    }

    default Detached detach(String id) throws EngineException {
        throw new EngineException(tr("engine.error.noUndo"));
    }

    /**
     * Puts a detached torrent back, without a recheck, as a restored one: nothing that runs on an
     * add runs on it. Refused when a torrent of that id is in the list again.
     */
    default void reattach(Detached torrent) throws EngineException {
        throw new EngineException(tr("engine.error.noUndo"));
    }

    /**
     * A torrent taken out by {@link #detach}. The bytes are the engine's own: its resume data and
     * its sidecars, opaque here as they are in the store.
     *
     * @param id the torrent's id
     * @param name its name, for what the undo says
     * @param resume its resume data, fresh
     * @param labels its labels sidecar, or empty
     * @param storage its storage record, or empty
     * @param queuePosition its place in the queue, or -1
     */
    record Detached(String id, String name, byte[] resume, byte[] labels, byte[] storage, int queuePosition) {
        public Detached {
            labels = labels == null ? new byte[0] : labels;
            storage = storage == null ? new byte[0] : storage;
        }
    }

    /**
     * Super seeding: a seed offers each peer only pieces nobody else has yet, so a first seed's
     * upload goes as far as it can. Meaningful for a complete torrent; kept in its resume data.
     * Needs {@link Capability#SUPER_SEEDING}.
     */
    void setSuperSeeding(String id, boolean on) throws EngineException;

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

    /**
     * The torrent as a {@code .torrent} file: its own info dictionary, byte for byte (so the hash
     * is the same), with the trackers in their tiers and the web seeds it has now. Fails for a
     * magnet whose file list has not arrived. Needs {@link Capability#EXPORT_TORRENT}.
     */
    byte[] exportTorrent(String id) throws EngineException;

    /**
     * Shows the torrent under another name, everywhere the engine names it (the list, events,
     * run-on-event). The files keep theirs; empty goes back to the torrent's own name. Needs
     * {@link Capability#RENAME_TORRENT}.
     */
    void rename(String id, String name) throws EngineException;

    /** A magnet link for the torrent, with its trackers, as the engine would build it. */
    String magnetLink(String id) throws EngineException;

    /** Everything the detail pane shows; a few milliseconds, so once a second at most. */
    TorrentDetail detail(String id) throws EngineException;

    void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException;

    /**
     * Renames a file, or a folder (every file under it), inside the torrent and on disk: {@code
     * path} as the detail's file list has it, {@code name} the new last segment. Kept across a
     * restart; fails with the reason in the interface language when the name will not do.
     */
    void renamePath(String id, String path, String name) throws EngineException;

    /** Adds trackers the torrent does not have yet, on the tier after its last. */
    void addTrackers(String id, List<String> urls) throws EngineException;

    void removeTracker(String id, String url) throws EngineException;

    /** The torrent's web seeds (BEP 19 HTTP sources), in the order the engine keeps them. */
    List<String> webSeeds(String id) throws EngineException;

    /**
     * Adds the web seeds the torrent does not have yet ({@link WebSeeds#fresh}); kept with its
     * resume data. Refuses the whole batch, adding none, when one is not a web seed
     * ({@link WebSeeds#invalid}). Needs {@link Capability#WEB_SEEDS}.
     */
    void addWebSeeds(String id, List<String> urls) throws EngineException;

    /** Removes one web seed, as written; nothing happens when the torrent has no such seed. */
    void removeWebSeed(String id, String url) throws EngineException;

    /** A snapshot of every torrent in the session. */
    List<Torrent> torrents();

    /** Session-wide payload rates. */
    Rates rates();

    /**
     * The session's counters and the all-time totals, as of the engine's last reading: cheap, no
     * engine round trip, so the Statistics window may ask every second.
     */
    SessionStats stats();

    /**
     * The listen port, its mapping, whether a peer from outside has reached it, and the DHT's
     * state, as of the last alerts: cheap, no engine round trip, so the status bar asks every
     * second. {@link Connectivity#NONE} before {@link #start()}, and from an engine that cannot
     * say (a daemon): the status bar then leaves the port and the DHT out.
     */
    default Connectivity connectivity() {
        return Connectivity.NONE;
    }

    /**
     * The complete files the torrents have on disk, by v2 root, kept by the engine as they check,
     * finish, are renamed, moved and removed: what a new torrent's files are looked for in before
     * they are downloaded again. {@link ContentIndex#EMPTY} from an engine that keeps none (a
     * daemon, whose disk is not this machine's).
     */
    default ContentIndex contentIndex() {
        return ContentIndex.EMPTY;
    }

    /** Writes what is needed to bring the torrents back; also done on {@link #stop()}. */
    void flush();

    /** Applies connection settings now, to the running session; also the ones used at start. */
    void applySettings(EngineSettings settings) throws EngineException;

    /**
     * The interface the settings bind to came up, went down or changed its addresses: the engine
     * binds again to what is there now. Nothing for an engine whose network is not this machine's.
     */
    default void rebind() throws EngineException {}

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
     * For an engine that fetches its own blocklist by URL ({@link Capability#BLOCKLIST_URL}): sets
     * the URL, switches the list on or off, and fetches it now. Returns the number of ranges the
     * engine then holds, or -1 when it cannot say. The default refuses, for an engine whose filter
     * is handed to it range by range through {@link #setIpFilter}.
     */
    default long setBlocklist(String url, boolean enabled) throws EngineException {
        throw new EngineException(tr("engine.error.noBlocklistUrl"));
    }

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

    /** A torrent's swarm being looked at before it is added, from {@link #probe}. */
    interface SwarmProbe {

        /** Peers connected to it now; 0 once the probe is over. Not on the FX thread. */
        int peers();

        /**
         * The whole {@code .torrent}, a magnet's trackers included, once the metadata is in:
         * what {@link #inspect} reads and {@link AddRequest.TorrentFile} adds; a {@code .torrent}'s
         * own bytes at once. Completes on an engine thread; fails if a magnet's probe is cancelled
         * or the engine stops first.
         */
        java.util.concurrent.CompletableFuture<byte[]> torrent();

        /**
         * What the probe has seen of the swarm so far, with the pieces counted over the files at
         * {@code wanted} (indices): once the file list is in, it stays in the swarm downloading
         * nothing, to look, until it is added or cancelled. Empty where the engine cannot look,
         * once the probe is over, and for a private torrent not let in ({@link #probe}). Not on
         * the FX thread.
         */
        default java.util.Optional<SwarmPreview> swarm(java.util.Set<Integer> wanted) {
            return java.util.Optional.empty();
        }

        /** Leaves the swarm. Idempotent; must come before the same torrent is added for real. */
        void cancel();
    }

    /** Where {@link #queueMove} puts the torrent. */
    enum QueueMove {
        TOP,
        UP,
        DOWN,
        BOTTOM
    }

    /** Bytes per second, session-wide. */
    record Rates(long download, long upload) {
        public static final Rates ZERO = new Rates(0, 0);
    }

    /**
     * One thing an engine may or may not do. Each is a feature the window offers somewhere: a
     * command, a Settings row, a dialog choice. libtorrent has them all; a Transmission daemon
     * lacks the ones its RPC has no word for.
     */
    enum Capability {
        /** Making a {@code .torrent} of local files and seeding it (Ctrl+N). */
        CREATE_TORRENT,
        /** A magnet's file list fetched while the Add dialog is open, before the torrent is added. */
        METADATA_FETCH,
        /** An IP filter handed over range by range: a blocklist file, manual bans, Ban Peer. */
        IP_FILTER,
        /** A blocklist the engine fetches itself from a URL. */
        BLOCKLIST_URL,
        /** First and last piece of every file first, in the Limits dialog and the Add dialog. */
        FIRST_LAST_PIECES,
        /** The files are on this machine: Open folder, Open file, Browse, free-space readouts. */
        LOCAL_FILES,
        /** A SOCKS5 or HTTP proxy for peers and trackers. */
        PROXY,
        /** Binding to one interface or address, and the IPv6 switch. */
        LISTEN_DETAILS,
        /** How many peers are unchoked at once, session-wide and per torrent. */
        UPLOAD_SLOTS,
        /** Anonymous mode: no client name or version on the wire. */
        ANONYMOUS_MODE,
        /** Rate limits that count protocol overhead. */
        OVERHEAD_IN_LIMITS,
        /** Per-torrent storage choices: an incomplete folder, the {@code .part} suffix, pre-allocation. */
        STORAGE_OPTIONS,
        /** A limit on torrents active in all, over the download and seed counts. */
        ACTIVE_TOTAL_LIMIT,
        /** Trusting the files on disk as complete when adding (seed mode, "skip check"). */
        SEED_MODE,
        /** Stopping by itself once the metadata or the file check is in. */
        STOP_CONDITION,
        /** Pieces in order rather than rarest first. */
        SEQUENTIAL_DOWNLOAD,
        /** Moving a torrent in the queue: to the top, up, down, to the bottom. */
        QUEUE_ORDER,
        /** Changing where a torrent's files live (Set Location). */
        MOVE_STORAGE,
        /** Super seeding a complete torrent, from the menu or as a share limit's action. */
        SUPER_SEEDING,
        /** The torrent as a {@code .torrent} file, its info dictionary as it was loaded. */
        EXPORT_TORRENT,
        /** A name of the person's own for a torrent, the files keeping theirs (Rename…, F2). */
        RENAME_TORRENT,
        /** The content layout on add: in a folder of its own, or without the torrent's root folder. */
        CONTENT_LAYOUT,
        /** Adding and removing a torrent's web seeds; any engine lists them. */
        WEB_SEEDS,
        /**
         * A removal that can be taken back: {@link #detach} keeps what brings the torrent back as
         * it was, and {@link #reattach} brings it back without a recheck.
         */
        UNDO_REMOVE,
        /**
         * A look at a torrent's swarm before it is added ({@link SwarmProbe#swarm}): the Add
         * dialog's Swarm line, for a {@code .torrent} as for a magnet. A daemon has no hidden
         * torrents to look with.
         */
        SWARM_PREVIEW,
        /** Another client's torrents brought over from their resume data, without a check. */
        IMPORT_RESUME;

        /** The capability's name in the interface language, read at call time. */
        public String label() {
            return com.torrenta.i18n.Messages.tr("capability." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** The set an engine has. Immutable; {@link #ALL} is libtorrent's. */
    record Capabilities(Set<Capability> set) {

        public static final Capabilities ALL = new Capabilities(EnumSet.allOf(Capability.class));

        public Capabilities {
            set = set == null || set.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(set));
        }

        public static Capabilities of(Capability... capabilities) {
            return new Capabilities(Set.of(capabilities));
        }

        public boolean has(Capability capability) {
            return set.contains(capability);
        }

        public boolean lacks(Capability capability) {
            return !set.contains(capability);
        }

        /** Everything missing, in declaration order; empty for {@link #ALL}. */
        public List<Capability> missing() {
            List<Capability> out = new java.util.ArrayList<>();
            for (Capability c : Capability.values()) if (lacks(c)) out.add(c);
            return List.copyOf(out);
        }

        /** {@code ALL} without the given ones. */
        public Capabilities without(Capability... capabilities) {
            EnumSet<Capability> left = EnumSet.copyOf(set.isEmpty() ? EnumSet.noneOf(Capability.class) : set);
            for (Capability c : capabilities) left.remove(c);
            return new Capabilities(left);
        }
    }
}
