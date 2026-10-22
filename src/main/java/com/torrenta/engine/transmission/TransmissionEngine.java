package com.torrenta.engine.transmission;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.CreateRequest;
import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.EngineSettings;
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
import com.torrenta.engine.TrackerHost;

/**
 * A Transmission daemon as the engine: the same window, driving {@code transmission-daemon} on a
 * seedbox or a NAS over its RPC.
 *
 * <p>Everything the window asks is one call to the daemon ({@link RpcClient}), on the caller's
 * worker thread; the poll is one {@code torrent-get} with the fields the list needs, and {@link
 * #detail} alone asks for files, peers, trackers and the pieces bitfield of its one torrent. The
 * RPC has no push, so {@link EngineEvent}s are made by comparing one poll with the last: a torrent
 * that appeared (added from another client), one that is gone, one that finished, one that fell
 * into an error. What the daemon cannot do is a missing {@link Capability}, which the window hides;
 * the methods behind one refuse with a plain reason, in case they are reached anyway.
 *
 * <p>Persistence is the daemon's: it keeps its own torrents, resume data and labels, so this
 * engine owns no store. The daemon keeps its own settings too: {@link #applySettings} sends only
 * what differs from the last settings it was given (the ones it was built with, at first), so a
 * change on a Settings page sets that one key on the daemon and nothing else of this machine's
 * file is pushed over the daemon's configuration.
 */
public final class TransmissionEngine implements Engine {

    private static final Logger LOG = Logger.getLogger(TransmissionEngine.class.getName());

    /** The RPC version that knows {@code sequential_download}. */
    static final int RPC_SEQUENTIAL = 18;
    /** The RPC version that knows {@code trackerList}; older ones take {@code trackerAdd}/{@code trackerRemove}. */
    static final int RPC_TRACKER_LIST = 17;
    /** Transmission's speed unit: kilobytes of 1000, as its {@code units} say. */
    static final int KBPS = 1000;
    /** How many polls between two {@code session-stats} readings. */
    private static final int STATS_EVERY = 5;

    /** The fields of every poll, lean on purpose: the list's columns and the diff's needs. */
    static final List<String> LIST_FIELDS = List.of(
            "hashString",
            "name",
            "totalSize",
            "sizeWhenDone",
            "leftUntilDone",
            "percentDone",
            "status",
            "rateDownload",
            "rateUpload",
            "peersConnected",
            "peersSendingToUs",
            "uploadedEver",
            "downloadedEver",
            "addedDate",
            "doneDate",
            "downloadDir",
            "error",
            "errorString",
            "labels",
            "isFinished",
            "queuePosition",
            "secondsSeeding",
            "metadataPercentComplete",
            "trackers");

    /** What {@link #detail} asks, for the one torrent shown. */
    static final List<String> DETAIL_FIELDS = List.of(
            "hashString",
            "downloadDir",
            "isPrivate",
            "pieceCount",
            "pieceSize",
            "pieces",
            "availability",
            "haveValid",
            "haveUnchecked",
            "downloadedEver",
            "uploadedEver",
            "corruptEver",
            "secondsDownloading",
            "secondsSeeding",
            "peersConnected",
            "files",
            "fileStats",
            "peers",
            "trackerStats");

    private final EngineProfile profile;
    private final RpcClient rpc;
    private final Supplier<Path> localDownloadDir;
    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Guards the poll bookkeeping: the last snapshot, the first-poll flag, the counters. */
    private final Object pollLock = new Object();

    private Map<String, Torrent> last = Map.of();
    private boolean polledOnce;
    private int polls;
    /** Ids whose add we announced ourselves, so the next poll does not say "added" again. */
    private final Set<String> announced = new HashSet<>();
    /** Ids we removed ourselves, so the next poll does not say "removed" again. */
    private final Set<String> removedByUs = new HashSet<>();

    private volatile boolean started;
    private volatile boolean stopped;
    private volatile String version = "";
    private volatile int rpcVersion;
    private volatile String remoteDownloadDir = "";
    private volatile Capabilities capabilities = Capabilities.ALL;
    private volatile EngineSettings lastApplied;
    private volatile SessionStats stats = SessionStats.NONE;

    public TransmissionEngine(EngineProfile profile, Supplier<Path> localDownloadDir, EngineSettings settings) {
        this(profile, localDownloadDir, settings, new RpcClient(profile));
    }

    /** For tests: the client against a stub server. */
    TransmissionEngine(
            EngineProfile profile, Supplier<Path> localDownloadDir, EngineSettings settings, HttpClient http) {
        this(profile, localDownloadDir, settings, new RpcClient(profile, http));
    }

    private TransmissionEngine(
            EngineProfile profile, Supplier<Path> localDownloadDir, EngineSettings settings, RpcClient rpc) {
        this.profile = profile;
        this.localDownloadDir = localDownloadDir;
        this.lastApplied = settings == null ? EngineSettings.defaults() : settings;
        this.rpc = rpc;
    }

    /** The daemon's answer to {@code session-get}, for the Test button: version and RPC version. */
    public static Daemon probe(EngineProfile profile) throws EngineException {
        RpcClient.Daemon d = new RpcClient(profile).probe();
        return new Daemon(d.version(), d.rpcVersion(), Json.str(d.session(), "download-dir", ""));
    }

    /** What a daemon says about itself. */
    public record Daemon(String version, int rpcVersion, String downloadDir) {}

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void start() throws EngineException {
        if (started) return;
        RpcClient.Daemon daemon = rpc.probe();
        version = daemon.version();
        rpcVersion = daemon.rpcVersion();
        remoteDownloadDir = Json.str(daemon.session(), "download-dir", "");
        capabilities = capabilitiesFor(rpcVersion, isLoopback(rpc.endpoint()));
        started = true;
        stopped = false;
        emit(EngineEvent.session(
                EngineEvent.Level.INFO,
                "Engine started: " + description() + ", downloading to " + remoteDownloadDir
                        + "; the daemon keeps its own settings"));
        readStats();
    }

    @Override
    public String description() {
        return "Transmission " + version + " (RPC " + rpcVersion + ") at " + profile.hostLine();
    }

    /** The daemon's version alone, for the window title and the profile page. */
    public String version() {
        return version;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    /**
     * What a daemon of this RPC version can do, of the things the window offers. Sequential
     * download arrived with RPC 18 (Transmission 4.1); queue moves and set-location are older than
     * any daemon still running; the files count as local when the daemon is on this machine, so
     * Open folder works against it. Pure.
     */
    static Capabilities capabilitiesFor(int rpcVersion, boolean loopback) {
        EnumSet<Capability> has = EnumSet.of(Capability.BLOCKLIST_URL, Capability.QUEUE_ORDER, Capability.MOVE_STORAGE);
        if (rpcVersion >= RPC_SEQUENTIAL) has.add(Capability.SEQUENTIAL_DOWNLOAD);
        if (loopback) has.add(Capability.LOCAL_FILES);
        return new Capabilities(has);
    }

    static boolean isLoopback(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]");
    }

    @Override
    public String downloadDir() {
        return remoteDownloadDir;
    }

    @Override
    public void stop() {
        if (!started || stopped) return;
        stopped = true;
        emit(EngineEvent.session(EngineEvent.Level.INFO, "Engine stopped"));
    }

    private void checkRunning() throws EngineException {
        if (!started) throw new EngineException("The engine has not started");
        if (stopped) throw new EngineException("The engine stopped");
    }

    // ---- adding ---------------------------------------------------------------------------------

    @Override
    public Torrent add(String input) throws EngineException {
        AddRequest.Source source;
        if (CommandLine.isMagnet(input)) {
            source = new AddRequest.Magnet(input);
        } else {
            Path file = Path.of(input);
            if (!Files.isRegularFile(file)) throw new EngineException("No such file: " + input);
            try {
                source = new AddRequest.TorrentFile(Files.readAllBytes(file), input);
            } catch (IOException e) {
                throw new EngineException("Could not read " + input + ": " + e.getMessage(), e);
            }
        }
        return add(AddRequest.defaults(source, localDownloadDir.get()));
    }

    @Override
    public Torrent add(AddRequest request) throws EngineException {
        checkRunning();
        Map<String, Object> args = addArguments(request, localDownloadDir.get(), capabilities);
        Map<String, Object> answer = rpc.call("torrent-add", args);
        if (Json.has(answer, "torrent-duplicate")) {
            Map<String, Object> dup = Json.obj(answer, "torrent-duplicate");
            throw new EngineException(
                    Json.str(dup, "name", Json.str(dup, "hashString", "That torrent")) + " is already in the list");
        }
        Map<String, Object> added = Json.obj(answer, "torrent-added");
        String id = Json.str(added, "hashString", "").toLowerCase(Locale.ROOT);
        if (id.isEmpty()) throw new EngineException("The daemon added nothing");
        String name = Json.str(added, "name", id);
        synchronized (pollLock) {
            announced.add(id);
        }
        emit(EngineEvent.of(EngineEvent.Kind.ADDED, EngineEvent.Level.INFO, id, name, "Added " + name));
        Torrent fresh = one(id);
        if (fresh != null) return fresh;
        return placeholder(id, name, request);
    }

    /**
     * The {@code torrent-add} arguments for a request. A save path equal to this machine's
     * default download folder means "the daemon's own", since the dialog's default is this
     * machine's folder and a seedbox has no such path; any other path is sent as typed, with
     * forward slashes. Pure.
     */
    static Map<String, Object> addArguments(AddRequest request, Path localDefault, Capabilities caps) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (request.source()) {
            case AddRequest.TorrentFile f ->
                args.put("metainfo", Base64.getEncoder().encodeToString(f.bytes()));
            case AddRequest.Magnet m -> args.put("filename", m.uri());
        }
        Path save = request.savePath();
        if (save != null && !save.toString().isEmpty() && !isDefaultFolder(save, localDefault)) {
            args.put("download-dir", remotePath(save));
        }
        args.put("paused", request.startPaused());
        List<String> labels = TransmissionLabels.toLabels(request.labels());
        if (!labels.isEmpty()) args.put("labels", labels);
        List<Long> unwanted = new ArrayList<>();
        List<Long> high = new ArrayList<>();
        List<Long> low = new ArrayList<>();
        for (Map.Entry<Integer, Priority> e : request.filePriorities().entrySet()) {
            switch (e.getValue()) {
                case IGNORE -> unwanted.add((long) e.getKey());
                case HIGH -> high.add((long) e.getKey());
                case LOW -> low.add((long) e.getKey());
                case NORMAL -> {}
            }
        }
        if (!unwanted.isEmpty()) args.put("files-unwanted", unwanted);
        if (!high.isEmpty()) args.put("priority-high", high);
        if (!low.isEmpty()) args.put("priority-low", low);
        if (request.sequential() && caps.has(Capability.SEQUENTIAL_DOWNLOAD)) args.put("sequential_download", true);
        return args;
    }

    private static boolean isDefaultFolder(Path save, Path localDefault) {
        if (localDefault == null) return false;
        try {
            return save.toAbsolutePath()
                    .normalize()
                    .equals(localDefault.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return save.equals(localDefault);
        }
    }

    /** A path for the daemon's machine: forward slashes, since a seedbox is almost always POSIX. */
    static String remotePath(Path path) {
        String s = path.toString();
        return s.indexOf('\\') >= 0 && !s.startsWith("\\\\") && s.length() > 2 && s.charAt(1) != ':'
                ? s.replace('\\', '/')
                : s;
    }

    private Torrent placeholder(String id, String name, AddRequest request) {
        return new Torrent(
                id,
                name,
                0,
                0,
                0,
                request.source() instanceof AddRequest.Magnet
                        ? TorrentState.FETCHING_METADATA
                        : TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                System.currentTimeMillis() / 1000,
                0,
                "",
                request.savePath() == null
                        ? remoteDownloadDir
                        : request.savePath().toString(),
                request.startPaused(),
                request.sequential(),
                0,
                request.labels().category(),
                request.labels().tags(),
                "",
                List.of(),
                false);
    }

    @Override
    public TorrentPreview inspect(byte[] torrent) throws EngineException {
        try {
            return Bencode.preview(torrent);
        } catch (IOException | RuntimeException e) {
            throw new EngineException("Not a torrent: " + e.getMessage(), e);
        }
    }

    @Override
    public CreatedTorrent create(CreateRequest request, CreateProgress progress) throws EngineException {
        throw new EngineException("Transmission cannot create a torrent; the files would have to be on its machine");
    }

    @Override
    public MetadataFetch fetchMetadata(String magnet) throws EngineException {
        throw new EngineException("Transmission lists a magnet's files once it is added, not before");
    }

    // ---- per-torrent actions --------------------------------------------------------------------

    @Override
    public void remove(String id, boolean deleteFiles) throws EngineException {
        checkRunning();
        String name = nameOf(id);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ids", List.of(id));
        args.put("delete-local-data", deleteFiles);
        rpc.call("torrent-remove", args);
        synchronized (pollLock) {
            removedByUs.add(id);
        }
        emit(EngineEvent.info(id, name, deleteFiles ? "Removed " + name + " and its files" : "Removed " + name));
    }

    @Override
    public void pause(String id) throws EngineException {
        action("torrent-stop", id);
    }

    @Override
    public void resume(String id) throws EngineException {
        action("torrent-start", id);
    }

    @Override
    public void forceResume(String id) throws EngineException {
        action("torrent-start-now", id);
    }

    @Override
    public void recheck(String id) throws EngineException {
        action("torrent-verify", id);
    }

    @Override
    public void reannounce(String id) throws EngineException {
        action("torrent-reannounce", id);
    }

    private void action(String method, String id) throws EngineException {
        checkRunning();
        rpc.call(method, Map.of("ids", List.of(id)));
    }

    @Override
    public void setSequential(String id, boolean sequential) throws EngineException {
        checkRunning();
        if (capabilities.lacks(Capability.SEQUENTIAL_DOWNLOAD)) {
            throw new EngineException("This daemon (RPC " + rpcVersion + ") has no sequential download; 4.1 does");
        }
        set(id, Map.of("sequential_download", sequential));
    }

    @Override
    public void queueMove(String id, QueueMove move) throws EngineException {
        action("queue-move-" + move.name().toLowerCase(Locale.ROOT), id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code torrent-set-location}: the daemon moves the files on its own time and answers at
     * once, and its list has no word for "moving", so the row shows nothing in between; the next
     * poll shows the new folder.
     */
    @Override
    public void moveStorage(String id, String destination, boolean moveFiles) throws EngineException {
        checkRunning();
        if (destination == null || destination.isBlank()) throw new EngineException("No destination folder");
        String name = nameOf(id);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ids", List.of(id));
        args.put("location", destination.trim());
        args.put("move", moveFiles);
        rpc.call("torrent-set-location", args);
        emit(EngineEvent.info(
                id,
                name,
                moveFiles
                        ? "Moving " + name + " to " + destination.trim()
                        : "Pointed " + name + " at " + destination.trim()));
    }

    private void set(String id, Map<String, Object> fields) throws EngineException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ids", List.of(id));
        args.putAll(fields);
        rpc.call("torrent-set", args);
    }

    @Override
    public TorrentLimits limits(String id) throws EngineException {
        checkRunning();
        Map<String, Object> t =
                get(id, List.of("downloadLimit", "downloadLimited", "uploadLimit", "uploadLimited", "peer-limit"));
        return limitsOf(t);
    }

    /** Transmission's per-torrent caps as the dialog's record: KB/s to bytes, off is 0. Pure. */
    static TorrentLimits limitsOf(Map<String, Object> t) {
        int down = Json.bool(t, "downloadLimited", false) ? Json.integer(t, "downloadLimit", 0) * KBPS : 0;
        int up = Json.bool(t, "uploadLimited", false) ? Json.integer(t, "uploadLimit", 0) * KBPS : 0;
        return new TorrentLimits(down, up, Json.integer(t, "peer-limit", 0), 0, false);
    }

    @Override
    public void setLimits(String id, TorrentLimits limits) throws EngineException {
        checkRunning();
        set(id, limitFields(limits));
    }

    /** The {@code torrent-set} fields for the dialog's limits; upload slots and piece order have none. Pure. */
    static Map<String, Object> limitFields(TorrentLimits limits) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("downloadLimited", limits.downloadLimit() > 0);
        if (limits.downloadLimit() > 0) f.put("downloadLimit", kbps(limits.downloadLimit()));
        f.put("uploadLimited", limits.uploadLimit() > 0);
        if (limits.uploadLimit() > 0) f.put("uploadLimit", kbps(limits.uploadLimit()));
        if (limits.maxConnections() > 0) f.put("peer-limit", limits.maxConnections());
        return f;
    }

    /** Bytes per second as Transmission's KB/s, never 0 for a limit that is on. */
    static long kbps(long bytesPerSecond) {
        return Math.max(1, (bytesPerSecond + KBPS - 1) / KBPS);
    }

    @Override
    public String category(String id) throws EngineException {
        return labelsOf(id).category();
    }

    @Override
    public void setCategory(String id, String category) throws EngineException {
        checkRunning();
        TorrentLabels now = labelsOf(id);
        set(id, Map.of("labels", TransmissionLabels.toLabels(now.withCategory(category))));
    }

    @Override
    public List<String> tags(String id) throws EngineException {
        return labelsOf(id).tags();
    }

    @Override
    public void setTags(String id, List<String> tags) throws EngineException {
        checkRunning();
        TorrentLabels now = labelsOf(id);
        set(id, Map.of("labels", TransmissionLabels.toLabels(now.withTags(tags))));
    }

    private TorrentLabels labelsOf(String id) throws EngineException {
        checkRunning();
        return TransmissionLabels.fromLabels(Json.strings(get(id, List.of("labels")), "labels"));
    }

    @Override
    public String magnetLink(String id) throws EngineException {
        checkRunning();
        String link = Json.str(get(id, List.of("magnetLink")), "magnetLink", "");
        return link.isEmpty() ? "magnet:?xt=urn:btih:" + id : link;
    }

    @Override
    public void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException {
        checkRunning();
        Map<String, Object> f = new LinkedHashMap<>();
        List<Long> index = List.of((long) fileIndex);
        switch (priority) {
            case IGNORE -> f.put("files-unwanted", index);
            case LOW -> {
                f.put("files-wanted", index);
                f.put("priority-low", index);
            }
            case NORMAL -> {
                f.put("files-wanted", index);
                f.put("priority-normal", index);
            }
            case HIGH -> {
                f.put("files-wanted", index);
                f.put("priority-high", index);
            }
        }
        set(id, f);
    }

    @Override
    public void addTrackers(String id, List<String> urls) throws EngineException {
        checkRunning();
        if (rpcVersion >= RPC_TRACKER_LIST) {
            List<List<String>> tiers = tiers(Json.str(get(id, List.of("trackerList")), "trackerList", ""));
            List<String> have = tiers.stream().flatMap(List::stream).toList();
            boolean changed = false;
            for (String url : urls) {
                String u = url.trim();
                if (u.isEmpty() || have.contains(u)) continue;
                tiers.add(List.of(u));
                changed = true;
            }
            if (changed) set(id, Map.of("trackerList", trackerList(tiers)));
        } else {
            List<String> fresh =
                    urls.stream().map(String::trim).filter(u -> !u.isEmpty()).toList();
            if (!fresh.isEmpty()) set(id, Map.of("trackerAdd", fresh));
        }
    }

    @Override
    public void removeTracker(String id, String url) throws EngineException {
        checkRunning();
        if (rpcVersion >= RPC_TRACKER_LIST) {
            List<List<String>> tiers = tiers(Json.str(get(id, List.of("trackerList")), "trackerList", ""));
            List<List<String>> kept = new ArrayList<>();
            for (List<String> tier : tiers) {
                List<String> left = tier.stream().filter(u -> !u.equals(url)).toList();
                if (!left.isEmpty()) kept.add(left);
            }
            set(id, Map.of("trackerList", trackerList(kept)));
        } else {
            for (Object o : Json.list(get(id, List.of("trackers")), "trackers")) {
                Map<String, Object> t = Json.obj(o);
                if (Json.str(t, "announce", "").equals(url)) {
                    set(id, Map.of("trackerRemove", List.of(Json.lng(t, "id", 0))));
                    return;
                }
            }
        }
    }

    /** Transmission's {@code trackerList}: one URL per line, a blank line between tiers. Pure. */
    static List<List<String>> tiers(String trackerList) {
        List<List<String>> tiers = new ArrayList<>();
        List<String> tier = new ArrayList<>();
        for (String line : trackerList.split("\\R", -1)) {
            String u = line.trim();
            if (u.isEmpty()) {
                if (!tier.isEmpty()) tiers.add(List.copyOf(tier));
                tier = new ArrayList<>();
            } else {
                tier.add(u);
            }
        }
        if (!tier.isEmpty()) tiers.add(List.copyOf(tier));
        return tiers;
    }

    static String trackerList(List<List<String>> tiers) {
        StringBuilder sb = new StringBuilder();
        for (List<String> tier : tiers) {
            if (sb.length() > 0) sb.append('\n');
            for (String u : tier) sb.append(u).append('\n');
        }
        return sb.toString();
    }

    // ---- reading --------------------------------------------------------------------------------

    /** One torrent's fields, or an exception when the daemon no longer has it. */
    private Map<String, Object> get(String id, List<String> fields) throws EngineException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ids", List.of(id));
        args.put("fields", fields);
        List<Object> torrents = Json.list(rpc.call("torrent-get", args), "torrents");
        if (torrents.isEmpty()) throw new EngineException("No torrent " + id + " on the daemon");
        return Json.obj(torrents.get(0));
    }

    private Torrent one(String id) {
        try {
            return snapshot(get(id, listFields()));
        } catch (EngineException e) {
            return null;
        }
    }

    private String nameOf(String id) {
        synchronized (pollLock) {
            Torrent t = last.get(id);
            if (t != null) return t.name();
        }
        try {
            return Json.str(get(id, List.of("name")), "name", id);
        } catch (EngineException e) {
            return id;
        }
    }

    private List<String> listFields() {
        if (capabilities.has(Capability.SEQUENTIAL_DOWNLOAD)) {
            List<String> f = new ArrayList<>(LIST_FIELDS);
            f.add("sequential_download");
            return f;
        }
        return LIST_FIELDS;
    }

    @Override
    public List<Torrent> torrents() {
        if (!started || stopped) return List.of();
        List<Object> raw;
        try {
            raw = Json.list(rpc.call("torrent-get", Map.of("fields", listFields())), "torrents");
        } catch (EngineException e) {
            LOG.log(Level.FINE, "Poll failed: " + e.getMessage());
            synchronized (pollLock) {
                return new ArrayList<>(last.values());
            }
        }
        List<Torrent> out = new ArrayList<>(raw.size());
        Map<String, Torrent> now = new LinkedHashMap<>();
        for (Object o : raw) {
            Torrent t = snapshot(Json.obj(o));
            out.add(t);
            now.put(t.id(), t);
        }
        List<EngineEvent> events;
        boolean statsDue;
        synchronized (pollLock) {
            events = polledOnce ? diff(last, now, announced, removedByUs) : List.of();
            announced.retainAll(now.keySet());
            removedByUs.removeIf(id -> !last.containsKey(id) || !now.containsKey(id));
            last = now;
            polledOnce = true;
            statsDue = polls++ % STATS_EVERY == 0;
        }
        for (EngineEvent e : events) emit(e);
        if (statsDue) readStats();
        return out;
    }

    /**
     * The events between two polls: appeared (unless we added it), gone (unless we removed it),
     * finished, newly in error, metadata arrived. Pure; the sets name what this engine did itself.
     */
    static List<EngineEvent> diff(
            Map<String, Torrent> before, Map<String, Torrent> after, Set<String> announced, Set<String> removedByUs) {
        List<EngineEvent> out = new ArrayList<>();
        for (Torrent t : after.values()) {
            Torrent was = before.get(t.id());
            if (was == null) {
                if (!announced.contains(t.id())) {
                    out.add(EngineEvent.of(
                            EngineEvent.Kind.ADDED, EngineEvent.Level.INFO, t.id(), t.name(), "Added " + t.name()));
                }
                continue;
            }
            if (was.state() == TorrentState.FETCHING_METADATA && t.state() != TorrentState.FETCHING_METADATA) {
                out.add(EngineEvent.info(t.id(), t.name(), "Metadata received for " + t.name()));
            }
            boolean doneNow = t.size() > 0 && t.done() >= t.size();
            boolean doneBefore = was.size() > 0 && was.done() >= was.size();
            if (doneNow && !doneBefore && was.state() != TorrentState.CHECKING) {
                out.add(EngineEvent.of(
                        EngineEvent.Kind.FINISHED, EngineEvent.Level.INFO, t.id(), t.name(), t.name() + " finished"));
            }
            if (!t.error().isEmpty() && !t.error().equals(was.error())) {
                out.add(EngineEvent.of(
                        EngineEvent.Kind.ERROR,
                        t.trackerError() ? EngineEvent.Level.WARNING : EngineEvent.Level.ERROR,
                        t.id(),
                        t.name(),
                        t.name() + ": " + t.error()));
            }
        }
        for (Torrent was : before.values()) {
            if (!after.containsKey(was.id()) && !removedByUs.contains(was.id())) {
                out.add(EngineEvent.info(was.id(), was.name(), "Removed " + was.name()));
            }
        }
        return out;
    }

    /** One {@code torrent-get} entry as the list's record. Pure. */
    static Torrent snapshot(Map<String, Object> t) {
        String id = Json.str(t, "hashString", "").toLowerCase(Locale.ROOT);
        String name = Json.str(t, "name", id);
        long size = Json.lng(t, "sizeWhenDone", 0);
        long left = Json.lng(t, "leftUntilDone", 0);
        long total = Json.lng(t, "totalSize", size);
        int status = Json.integer(t, "status", 0);
        double metadata = Json.dbl(t, "metadataPercentComplete", 1);
        int error = Json.integer(t, "error", 0);
        List<String> announces = new ArrayList<>();
        for (Object o : Json.list(t, "trackers")) {
            String url = Json.str(Json.obj(o), "announce", "");
            if (!url.isEmpty()) announces.add(url);
        }
        TorrentLabels labels = TransmissionLabels.fromLabels(Json.strings(t, "labels"));
        return new Torrent(
                id,
                name,
                size,
                Math.max(0, size - left),
                Json.dbl(t, "percentDone", 0),
                state(status, metadata, size, left, total),
                Json.lng(t, "rateDownload", 0),
                Json.lng(t, "rateUpload", 0),
                Json.integer(t, "peersConnected", 0),
                Json.integer(t, "peersSendingToUs", 0),
                Json.lng(t, "uploadedEver", 0),
                Json.lng(t, "downloadedEver", 0),
                Json.lng(t, "addedDate", 0),
                Json.lng(t, "doneDate", 0),
                announces.isEmpty() ? "" : announces.get(0),
                Json.str(t, "downloadDir", ""),
                status == STOPPED,
                Json.bool(t, "sequential_download", false),
                Json.lng(t, "secondsSeeding", 0),
                labels.category(),
                labels.tags(),
                error == 0 ? "" : Json.str(t, "errorString", ""),
                TrackerHost.hosts(announces),
                error == ERROR_TRACKER_WARNING || error == ERROR_TRACKER,
                status == DOWNLOAD_WAIT || status == SEED_WAIT,
                Json.integer(t, "queuePosition", -1),
                false);
    }

    static final int STOPPED = 0;
    static final int CHECK_WAIT = 1;
    static final int CHECK = 2;
    static final int DOWNLOAD_WAIT = 3;
    static final int DOWNLOAD = 4;
    static final int SEED_WAIT = 5;
    static final int SEED = 6;
    static final int ERROR_TRACKER_WARNING = 1;
    static final int ERROR_TRACKER = 2;
    static final int ERROR_LOCAL = 3;

    /**
     * Transmission's seven statuses folded into the list's six. A stopped torrent keeps the state
     * its data is in, as libtorrent's does; a seed with files left out is finished, not seeding.
     * Pure.
     */
    static TorrentState state(int status, double metadata, long sizeWhenDone, long left, long totalSize) {
        boolean noMetadata = metadata < 1.0 && sizeWhenDone == 0;
        switch (status) {
            case CHECK_WAIT, CHECK -> {
                return TorrentState.CHECKING;
            }
            case SEED_WAIT, SEED -> {
                return sizeWhenDone < totalSize ? TorrentState.FINISHED : TorrentState.SEEDING;
            }
            case DOWNLOAD_WAIT, DOWNLOAD, STOPPED -> {
                if (noMetadata) return TorrentState.FETCHING_METADATA;
                if (sizeWhenDone > 0 && left == 0) {
                    return sizeWhenDone < totalSize ? TorrentState.FINISHED : TorrentState.SEEDING;
                }
                return TorrentState.DOWNLOADING;
            }
            default -> {
                return TorrentState.UNKNOWN;
            }
        }
    }

    @Override
    public Rates rates() {
        long down = 0;
        long up = 0;
        synchronized (pollLock) {
            for (Torrent t : last.values()) {
                down += t.downloadRate();
                up += t.uploadRate();
            }
        }
        return new Rates(down, up);
    }

    @Override
    public SessionStats stats() {
        return stats;
    }

    private void readStats() {
        try {
            Map<String, Object> s = rpc.call("session-stats", Map.of());
            List<Torrent> snapshot;
            synchronized (pollLock) {
                snapshot = new ArrayList<>(last.values());
            }
            stats = statsOf(s, snapshot);
        } catch (EngineException e) {
            LOG.log(Level.FINE, "session-stats failed: " + e.getMessage());
        }
    }

    /** {@code session-stats} as the window's record; counters the daemon has no word for are unknown. Pure. */
    static SessionStats statsOf(Map<String, Object> s, List<Torrent> snapshot) {
        Map<String, Object> current = Json.obj(s, "current-stats");
        Map<String, Object> all = Json.obj(s, "cumulative-stats");
        long checking = 0;
        long downloading = 0;
        long seeding = 0;
        long queued = 0;
        long stopped = 0;
        long error = 0;
        long peers = 0;
        for (Torrent t : snapshot) {
            peers += t.peers();
            if (!t.error().isEmpty()) error++;
            if (t.paused()) stopped++;
            else if (t.queued()) queued++;
            else {
                switch (t.state()) {
                    case CHECKING -> checking++;
                    case SEEDING, FINISHED -> seeding++;
                    default -> downloading++;
                }
            }
        }
        return new SessionStats(
                Json.lng(current, "downloadedBytes", 0),
                Json.lng(current, "uploadedBytes", 0),
                Json.lng(all, "downloadedBytes", 0),
                Json.lng(all, "uploadedBytes", 0),
                Json.lng(current, "secondsActive", 0),
                Json.lng(all, "secondsActive", 0),
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                peers,
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                SessionStats.UNKNOWN,
                new SessionStats.Torrents(checking, downloading, seeding, queued, stopped, error));
    }

    @Override
    public TorrentDetail detail(String id) throws EngineException {
        checkRunning();
        return detailOf(get(id, DETAIL_FIELDS), System.currentTimeMillis() / 1000);
    }

    /** One {@code torrent-get} entry with the detail fields as the pane's record. Pure. */
    static TorrentDetail detailOf(Map<String, Object> t, long nowSeconds) {
        String id = Json.str(t, "hashString", "").toLowerCase(Locale.ROOT);
        int pieceCount = Json.integer(t, "pieceCount", 0);
        boolean[] pieces = pieces(Json.str(t, "pieces", ""), pieceCount);
        int have = 0;
        for (boolean p : pieces) if (p) have++;

        // Distributed copies, as libtorrent counts them: whole copies among the peers, plus the
        // fraction of pieces one more copy covers; Transmission says how many peers have each
        // missing piece (-1 for ones we have).
        double availability = 0;
        List<Object> perPiece = Json.list(t, "availability");
        if (pieceCount > 0 && !perPiece.isEmpty()) {
            int min = Integer.MAX_VALUE;
            int withCopies = 0;
            for (Object o : perPiece) {
                int n = o instanceof Number num ? num.intValue() : 0;
                if (n < 0) {
                    withCopies++;
                    continue;
                }
                min = Math.min(min, n);
                if (n > 0) withCopies++;
            }
            if (min == Integer.MAX_VALUE) min = 0;
            availability = min + (double) withCopies / pieceCount;
            if (have == pieceCount) availability = Math.max(availability, 1.0);
        }

        List<TorrentDetail.FileStatus> files = new ArrayList<>();
        List<Object> rawFiles = Json.list(t, "files");
        List<Object> rawStats = Json.list(t, "fileStats");
        for (int i = 0; i < rawFiles.size(); i++) {
            Map<String, Object> f = Json.obj(rawFiles.get(i));
            Map<String, Object> s = i < rawStats.size() ? Json.obj(rawStats.get(i)) : Map.of();
            Priority priority =
                    Json.bool(s, "wanted", true) ? priority(Json.integer(s, "priority", 0)) : Priority.IGNORE;
            files.add(new TorrentDetail.FileStatus(
                    i,
                    Json.str(f, "name", "").replace('\\', '/'),
                    Json.lng(f, "length", 0),
                    Json.lng(f, "bytesCompleted", 0),
                    priority));
        }

        List<TorrentDetail.Peer> peers = new ArrayList<>();
        for (Object o : Json.list(t, "peers")) {
            Map<String, Object> p = Json.obj(o);
            peers.add(new TorrentDetail.Peer(
                    peerAddress(Json.str(p, "address", ""), Json.lng(p, "port", 0)),
                    Json.str(p, "clientName", ""),
                    Json.str(p, "flagStr", ""),
                    Json.dbl(p, "progress", 0),
                    Json.lng(p, "rateToClient", 0),
                    Json.lng(p, "rateToPeer", 0),
                    0,
                    0));
        }

        List<TorrentDetail.Tracker> trackers = new ArrayList<>();
        long nextAnnounce = -1;
        String current = "";
        for (Object o : Json.list(t, "trackerStats")) {
            Map<String, Object> s = Json.obj(o);
            TorrentDetail.Tracker tracker = tracker(s);
            trackers.add(tracker);
            long next = Json.lng(s, "nextAnnounceTime", 0);
            if (next > 0) {
                long in = Math.max(0, next - nowSeconds);
                if (nextAnnounce < 0 || in < nextAnnounce) nextAnnounce = in;
            }
            if (current.isEmpty() && Json.bool(s, "lastAnnounceSucceeded", false)) current = tracker.url();
        }
        if (current.isEmpty() && !trackers.isEmpty()) current = trackers.get(0).url();

        TorrentDetail.General general = new TorrentDetail.General(
                id,
                Json.str(t, "downloadDir", ""),
                Json.bool(t, "isPrivate", false),
                pieceCount,
                Json.integer(t, "pieceSize", 0),
                have,
                availability,
                Json.lng(t, "downloadedEver", 0),
                Json.lng(t, "uploadedEver", 0),
                Json.lng(t, "corruptEver", 0),
                Json.lng(t, "secondsDownloading", 0) + Json.lng(t, "secondsSeeding", 0),
                Json.lng(t, "secondsSeeding", 0),
                nextAnnounce < 0 ? 0 : nextAnnounce,
                Json.integer(t, "peersConnected", 0),
                current,
                id,
                "");
        return new TorrentDetail(general, files, peers, trackers, pieces);
    }

    /** Transmission's -1/0/1 as the dialog's ladder. */
    /** {@code 10.0.0.5:51413}, or {@code [2001:db8::5]:51413}: an IPv6 address is bracketed. */
    static String peerAddress(String address, long port) {
        return (address.contains(":") ? "[" + address + "]" : address) + ":" + port;
    }

    static Priority priority(int value) {
        return value < 0 ? Priority.LOW : value > 0 ? Priority.HIGH : Priority.NORMAL;
    }

    /**
     * The pieces bitfield: base64 of a bitfield, the first piece in the highest bit of the first
     * byte, as the BitTorrent wire format has it. Pure.
     */
    static boolean[] pieces(String base64, int pieceCount) {
        boolean[] out = new boolean[Math.max(0, pieceCount)];
        if (base64 == null || base64.isEmpty() || pieceCount <= 0) return out;
        byte[] bits;
        try {
            bits = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return out;
        }
        for (int i = 0; i < pieceCount; i++) {
            int b = i / 8;
            if (b >= bits.length) break;
            out[i] = (bits[b] & (0x80 >>> (i % 8))) != 0;
        }
        return out;
    }

    static final int ANNOUNCE_QUEUED = 2;
    static final int ANNOUNCE_ACTIVE = 3;

    /** One {@code trackerStats} entry as the pane's row, worded as libtorrent's are. Pure. */
    static TorrentDetail.Tracker tracker(Map<String, Object> s) {
        boolean announced = Json.bool(s, "hasAnnounced", false);
        boolean succeeded = Json.bool(s, "lastAnnounceSucceeded", false);
        int state = Json.integer(s, "announceState", 0);
        String result = Json.str(s, "lastAnnounceResult", "");
        String status;
        int fails = 0;
        if (state == ANNOUNCE_ACTIVE || state == ANNOUNCE_QUEUED) status = "updating";
        else if (succeeded) status = "working";
        else if (announced) {
            status = "error";
            fails = 1;
        } else status = "not contacted";
        String message = succeeded ? "" : result;
        return new TorrentDetail.Tracker(
                Json.str(s, "announce", ""), Json.integer(s, "tier", 0), status, message, fails);
    }

    // ---- session ----------------------------------------------------------------------------------

    @Override
    public void flush() {
        // The daemon keeps its own resume data.
    }

    @Override
    public void applySettings(EngineSettings settings) throws EngineException {
        checkRunning();
        Map<String, Object> changed = sessionDiff(lastApplied, settings);
        lastApplied = settings;
        if (changed.isEmpty()) return;
        rpc.call("session-set", changed);
        emit(EngineEvent.session(
                EngineEvent.Level.INFO, "Daemon settings changed: " + String.join(", ", changed.keySet())));
    }

    /**
     * The {@code session-set} arguments for what changed between two settings records: nothing
     * for an equal pair, so a page edit sets one thing on the daemon and leaves the rest as the
     * daemon's administrator had it. Pure.
     */
    static Map<String, Object> sessionDiff(EngineSettings before, EngineSettings after) {
        Map<String, Object> all = sessionArguments(after);
        if (before == null) return all;
        Map<String, Object> was = sessionArguments(before);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (!Objects.equals(was.get(e.getKey()), e.getValue())) out.put(e.getKey(), e.getValue());
        }
        // A limit's value and its switch travel together, or the daemon shows a stale number.
        for (String pair : List.of("speed-limit-down", "speed-limit-up", "download-queue-size", "seed-queue-size")) {
            String on = pair.endsWith("-size") ? pair.replace("-size", "-enabled") : pair + "-enabled";
            if (out.containsKey(pair) || out.containsKey(on)) {
                out.put(pair, all.get(pair));
                out.put(on, all.get(on));
            }
        }
        if (out.containsKey("seedRatioLimit") || out.containsKey("seedRatioLimited")) {
            out.put("seedRatioLimit", all.get("seedRatioLimit"));
            out.put("seedRatioLimited", all.get("seedRatioLimited"));
        }
        if (out.containsKey("idle-seeding-limit") || out.containsKey("idle-seeding-limit-enabled")) {
            out.put("idle-seeding-limit", all.get("idle-seeding-limit"));
            out.put("idle-seeding-limit-enabled", all.get("idle-seeding-limit-enabled"));
        }
        return out;
    }

    /** Every engine setting the daemon has a word for, as {@code session-set} arguments. Pure. */
    static Map<String, Object> sessionArguments(EngineSettings s) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("peer-port", (long) s.listenPort());
        a.put("peer-port-random-on-start", s.randomPort());
        a.put("port-forwarding-enabled", s.upnp());
        a.put("peer-limit-global", (long) s.maxConnections());
        a.put("peer-limit-per-torrent", (long) s.maxConnectionsPerTorrent());
        EngineSettings.Protocol p = s.protocol();
        a.put("dht-enabled", p.dht());
        a.put("pex-enabled", p.pex());
        a.put("lpd-enabled", p.lsd());
        a.put("utp-enabled", p.utp());
        a.put(
                "encryption",
                switch (p.encryption()) {
                    case REQUIRE -> "required";
                    case PREFER -> "preferred";
                    case OFF -> "tolerated";
                });
        a.put("default-trackers", String.join("\n", p.defaultTrackers()));
        EngineSettings.Speed sp = s.speed();
        a.put("speed-limit-down-enabled", sp.download() > 0);
        a.put("speed-limit-down", kibToKb(sp.download()));
        a.put("speed-limit-up-enabled", sp.upload() > 0);
        a.put("speed-limit-up", kibToKb(sp.upload()));
        a.put("alt-speed-down", kibToKb(sp.alternativeDownload()));
        a.put("alt-speed-up", kibToKb(sp.alternativeUpload()));
        a.put("alt-speed-enabled", sp.alternativeOn());
        a.put("alt-speed-time-enabled", sp.scheduleOn());
        a.put("alt-speed-time-begin", (long) sp.scheduleFrom());
        a.put("alt-speed-time-end", (long) sp.scheduleTo());
        a.put("alt-speed-time-day", (long) dayMask(sp.scheduleDays()));
        EngineSettings.Queue q = s.queue();
        a.put("download-queue-enabled", q.activeDownloads() > 0);
        a.put("download-queue-size", (long) Math.max(1, q.activeDownloads()));
        a.put("seed-queue-enabled", q.activeSeeds() > 0);
        a.put("seed-queue-size", (long) Math.max(1, q.activeSeeds()));
        a.put("queue-stalled-enabled", q.ignoreSlowTorrents());
        ShareLimit share = q.share();
        a.put("seedRatioLimited", share.ratio() > 0);
        a.put("seedRatioLimit", share.ratio() > 0 ? share.ratio() : 2.0);
        a.put("idle-seeding-limit-enabled", share.seedingMinutes() > 0);
        a.put("idle-seeding-limit", (long) Math.max(1, share.seedingMinutes()));
        return a;
    }

    /** KiB/s of the settings as Transmission's KB/s; 0 stays 0, anything else at least 1. */
    static long kibToKb(int kib) {
        if (kib <= 0) return 0;
        return Math.max(1, Math.round(kib * 1024.0 / KBPS));
    }

    /** Transmission's day bitmask: Sunday is 1, Monday 2, … Saturday 64. Pure. */
    static int dayMask(Set<DayOfWeek> days) {
        int mask = 0;
        for (DayOfWeek d : days) {
            mask |= switch (d) {
                case SUNDAY -> 1;
                case MONDAY -> 2;
                case TUESDAY -> 4;
                case WEDNESDAY -> 8;
                case THURSDAY -> 16;
                case FRIDAY -> 32;
                case SATURDAY -> 64;
            };
        }
        return mask;
    }

    @Override
    public boolean refreshLimits(java.time.LocalDateTime now) throws EngineException {
        checkRunning();
        // The schedule is the daemon's own once pushed; it flips alt-speed-enabled itself.
        Map<String, Object> session = rpc.call("session-get", Map.of("fields", List.of("alt-speed-enabled")));
        return Json.bool(session, "alt-speed-enabled", lastApplied.speed().alternativeOn());
    }

    @Override
    public void setIpFilter(List<IpRange> ranges) throws EngineException {
        throw new EngineException("Transmission takes a blocklist URL, not a list of ranges");
    }

    @Override
    public long blockedPeers() {
        return 0;
    }

    @Override
    public long setBlocklist(String url, boolean enabled) throws EngineException {
        checkRunning();
        Map<String, Object> args = new LinkedHashMap<>();
        if (url != null && !url.isBlank()) args.put("blocklist-url", url.trim());
        args.put("blocklist-enabled", enabled);
        rpc.call("session-set", args);
        if (!enabled || url == null || url.isBlank()) return 0;
        Map<String, Object> answer = rpc.call("blocklist-update", Map.of());
        long size = Json.lng(answer, "blocklist-size", -1);
        emit(EngineEvent.session(EngineEvent.Level.INFO, "The daemon fetched its blocklist: " + size + " ranges"));
        return size;
    }

    // ---- events ---------------------------------------------------------------------------------

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
}
