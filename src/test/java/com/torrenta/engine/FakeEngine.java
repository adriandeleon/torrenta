package com.torrenta.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An engine with no network and no native: torrents are records in a map, every action is
 * written to {@link #calls()} as {@code "pause <id>"}, and the actions a window takes on a torrent
 * change it the way a real engine would at its next snapshot. For tests that drive a window, which
 * polls an engine off the FX thread: every method is synchronized, the listeners are told on the
 * caller's thread. What the fake cannot do (inspect, create, probe) fails the way an engine
 * without the capability would, with an {@link EngineException}.
 */
public class FakeEngine implements Engine {

    private final Map<String, Torrent> torrents = new LinkedHashMap<>();
    private final Map<String, TorrentLimits> limits = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();
    private Capabilities capabilities = Capabilities.ALL;
    private String downloadDir = System.getProperty("java.io.tmpdir");
    private boolean started;
    private boolean stopped;
    private int added;

    /** A torrent half downloaded from four peers, the shape most window tests want. */
    public static Torrent torrent(String id, String name) {
        return torrent(id, name, TorrentState.DOWNLOADING, 0.5);
    }

    public static Torrent torrent(String id, String name, TorrentState state, double progress) {
        long size = 1L << 30;
        long done = (long) (size * progress);
        boolean downloading = state == TorrentState.DOWNLOADING;
        return new Torrent(
                id,
                name,
                size,
                done,
                progress,
                state,
                downloading ? 512 * 1024 : 0,
                downloading ? 64 * 1024 : 0,
                4,
                2,
                done / 4,
                done,
                1_790_000_000L,
                state == TorrentState.SEEDING ? 1_790_003_600L : 0,
                "tracker.example.org",
                System.getProperty("java.io.tmpdir"),
                false,
                false,
                0,
                "",
                List.of(),
                "",
                List.of("https://tracker.example.org/announce"),
                false);
    }

    /** Puts a torrent in the list, or replaces the one with its id; the next poll shows it. */
    public synchronized FakeEngine put(Torrent torrent) {
        torrents.put(torrent.id(), torrent);
        return this;
    }

    public synchronized FakeEngine withCapabilities(Capabilities capabilities) {
        this.capabilities = capabilities;
        return this;
    }

    public synchronized FakeEngine withDownloadDir(String dir) {
        this.downloadDir = dir;
        return this;
    }

    /** Every action in the order it was asked, {@code "<verb> <id> [<argument>…]"}. */
    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    public synchronized boolean started() {
        return started;
    }

    public synchronized boolean stopped() {
        return stopped;
    }

    /** Raises an event as the engine's alert thread would, on the caller's thread. */
    public void emit(EngineEvent event) {
        for (Consumer<EngineEvent> listener : listeners) listener.accept(event);
    }

    private void call(String verb, Object... args) {
        StringBuilder line = new StringBuilder(verb);
        for (Object arg : args) line.append(' ').append(arg);
        calls.add(line.toString());
    }

    private Torrent existing(String id) throws EngineException {
        Torrent torrent = torrents.get(id);
        if (torrent == null) throw new EngineException("no torrent " + id);
        return torrent;
    }

    private static Torrent paused(Torrent t, boolean paused) {
        return new Torrent(
                t.id(),
                t.name(),
                t.size(),
                t.done(),
                t.progress(),
                t.state(),
                paused ? 0 : t.downloadRate(),
                paused ? 0 : t.uploadRate(),
                t.peers(),
                t.seeds(),
                t.uploaded(),
                t.downloaded(),
                t.added(),
                t.completed(),
                t.tracker(),
                t.savePath(),
                paused,
                t.sequential(),
                t.seedingTime(),
                t.category(),
                t.tags(),
                t.error(),
                t.trackers(),
                t.trackerError(),
                t.queued(),
                t.queuePosition(),
                t.moving(),
                t.superSeeding());
    }

    private static Torrent labelled(Torrent t, String category, List<String> tags) {
        return new Torrent(
                t.id(),
                t.name(),
                t.size(),
                t.done(),
                t.progress(),
                t.state(),
                t.downloadRate(),
                t.uploadRate(),
                t.peers(),
                t.seeds(),
                t.uploaded(),
                t.downloaded(),
                t.added(),
                t.completed(),
                t.tracker(),
                t.savePath(),
                t.paused(),
                t.sequential(),
                t.seedingTime(),
                category,
                tags,
                t.error(),
                t.trackers(),
                t.trackerError(),
                t.queued(),
                t.queuePosition(),
                t.moving(),
                t.superSeeding());
    }

    private Torrent newTorrent(String name) {
        added++;
        Torrent torrent = torrent("fake%04d".formatted(added), name, TorrentState.FETCHING_METADATA, 0);
        torrents.put(torrent.id(), torrent);
        return torrent;
    }

    @Override
    public synchronized void start() {
        call("start");
        started = true;
    }

    @Override
    public String description() {
        return "fake engine";
    }

    @Override
    public synchronized Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public synchronized String downloadDir() {
        return downloadDir;
    }

    @Override
    public synchronized Torrent add(String input) {
        call("add", input);
        return newTorrent(input);
    }

    @Override
    public synchronized Torrent add(AddRequest request) {
        call("add", request.source());
        return newTorrent(request.source().toString());
    }

    @Override
    public TorrentPreview inspect(byte[] torrent) throws EngineException {
        throw new EngineException("the fake engine reads no .torrent");
    }

    @Override
    public CreatedTorrent create(CreateRequest request, CreateProgress progress) throws EngineException {
        throw new EngineException("the fake engine creates no torrent");
    }

    @Override
    public SwarmProbe probe(AddRequest.Source source, boolean privateToo) throws EngineException {
        throw new EngineException("the fake engine probes no swarm");
    }

    @Override
    public synchronized void remove(String id, boolean deleteFiles) throws EngineException {
        call("remove", id, deleteFiles);
        existing(id);
        torrents.remove(id);
    }

    @Override
    public synchronized void pause(String id) throws EngineException {
        call("pause", id);
        torrents.put(id, paused(existing(id), true));
    }

    @Override
    public synchronized void resume(String id) throws EngineException {
        call("resume", id);
        torrents.put(id, paused(existing(id), false));
    }

    @Override
    public synchronized void forceResume(String id) throws EngineException {
        call("forceResume", id);
        torrents.put(id, paused(existing(id), false));
    }

    @Override
    public synchronized void recheck(String id) throws EngineException {
        call("recheck", id);
        existing(id);
    }

    @Override
    public synchronized void reannounce(String id) throws EngineException {
        call("reannounce", id);
        existing(id);
    }

    @Override
    public synchronized void setSequential(String id, boolean sequential) throws EngineException {
        call("setSequential", id, sequential);
        existing(id);
    }

    @Override
    public synchronized void queueMove(String id, QueueMove move) throws EngineException {
        call("queueMove", id, move);
        existing(id);
    }

    @Override
    public synchronized void moveStorage(String id, String destination, boolean moveFiles) throws EngineException {
        call("moveStorage", id, destination, moveFiles);
        existing(id);
    }

    @Override
    public synchronized void setSuperSeeding(String id, boolean on) throws EngineException {
        call("setSuperSeeding", id, on);
        existing(id);
    }

    @Override
    public synchronized TorrentLimits limits(String id) throws EngineException {
        existing(id);
        return limits.getOrDefault(id, TorrentLimits.NONE);
    }

    @Override
    public synchronized void setLimits(String id, TorrentLimits limits) throws EngineException {
        call("setLimits", id, limits);
        existing(id);
        this.limits.put(id, limits);
    }

    @Override
    public synchronized String category(String id) throws EngineException {
        return existing(id).category();
    }

    @Override
    public synchronized void setCategory(String id, String category) throws EngineException {
        call("setCategory", id, category);
        Torrent torrent = existing(id);
        torrents.put(id, labelled(torrent, category, torrent.tags()));
    }

    @Override
    public synchronized List<String> tags(String id) throws EngineException {
        return existing(id).tags();
    }

    @Override
    public synchronized void setTags(String id, List<String> tags) throws EngineException {
        call("setTags", id, tags);
        Torrent torrent = existing(id);
        torrents.put(id, labelled(torrent, torrent.category(), tags));
    }

    @Override
    public synchronized byte[] exportTorrent(String id) throws EngineException {
        call("exportTorrent", id);
        existing(id);
        return new byte[0];
    }

    @Override
    public synchronized void rename(String id, String name) throws EngineException {
        call("rename", id, name);
        existing(id);
    }

    @Override
    public synchronized String magnetLink(String id) throws EngineException {
        existing(id);
        return "magnet:?xt=urn:btih:" + id;
    }

    @Override
    public synchronized TorrentDetail detail(String id) throws EngineException {
        Torrent torrent = existing(id);
        TorrentDetail.General general = new TorrentDetail.General(
                id,
                torrent.savePath(),
                false,
                64,
                16 * 1024 * 1024,
                (int) (64 * torrent.progress()),
                2.0,
                torrent.downloaded(),
                torrent.uploaded(),
                0,
                600,
                0,
                120,
                torrent.peers(),
                torrent.tracker(),
                id,
                "");
        boolean[] pieces = new boolean[64];
        for (int i = 0; i < general.piecesHave(); i++) pieces[i] = true;
        return new TorrentDetail(general, List.of(), List.of(), List.of(), pieces);
    }

    @Override
    public synchronized void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException {
        call("setFilePriority", id, fileIndex, priority);
        existing(id);
    }

    @Override
    public synchronized void renamePath(String id, String path, String name) throws EngineException {
        call("renamePath", id, path, name);
        existing(id);
    }

    @Override
    public synchronized void addTrackers(String id, List<String> urls) throws EngineException {
        call("addTrackers", id, urls);
        existing(id);
    }

    @Override
    public synchronized void removeTracker(String id, String url) throws EngineException {
        call("removeTracker", id, url);
        existing(id);
    }

    @Override
    public synchronized List<String> webSeeds(String id) throws EngineException {
        existing(id);
        return List.of();
    }

    @Override
    public synchronized void addWebSeeds(String id, List<String> urls) throws EngineException {
        call("addWebSeeds", id, urls);
        existing(id);
    }

    @Override
    public synchronized void removeWebSeed(String id, String url) throws EngineException {
        call("removeWebSeed", id, url);
        existing(id);
    }

    @Override
    public synchronized List<Torrent> torrents() {
        return List.copyOf(torrents.values());
    }

    @Override
    public synchronized Rates rates() {
        long down = 0;
        long up = 0;
        for (Torrent torrent : torrents.values()) {
            down += torrent.downloadRate();
            up += torrent.uploadRate();
        }
        return new Rates(down, up);
    }

    @Override
    public SessionStats stats() {
        return SessionStats.NONE;
    }

    @Override
    public synchronized void flush() {
        call("flush");
    }

    @Override
    public synchronized void applySettings(EngineSettings settings) {
        call("applySettings");
    }

    @Override
    public synchronized boolean refreshLimits(boolean alternative) {
        return false;
    }

    @Override
    public synchronized void setIpFilter(List<IpRange> ranges) {
        call("setIpFilter", ranges.size());
    }

    @Override
    public long blockedPeers() {
        return 0;
    }

    @Override
    public void addListener(Consumer<EngineEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Consumer<EngineEvent> listener) {
        listeners.remove(listener);
    }

    @Override
    public synchronized void stop() {
        call("stop");
        stopped = true;
    }
}
