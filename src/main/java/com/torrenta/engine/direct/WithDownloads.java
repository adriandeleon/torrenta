package com.torrenta.engine.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Connectivity;
import com.torrenta.engine.CreateRequest;
import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.Inputs;
import com.torrenta.engine.IpRange;
import com.torrenta.engine.LinkRequest;
import com.torrenta.engine.Priority;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentPreview;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * A torrent engine with downloads from links beside it: one list, one set of commands. Every call
 * about one id goes to whichever side has it ({@link DirectDownload#isId}); a link that is not a
 * {@code .torrent} ({@link Inputs#isDirectLink}) is added as a download, everything else goes to
 * the torrent engine, which is also the one asked about the session, its settings and its
 * capabilities. What only a torrent can do is refused for a download, with the reason.
 *
 * <p>A download that turns out to be a {@code .torrent} (the server says so, by its type or the
 * name it gives) is handed to the torrent engine when it finishes, and leaves the list.
 */
public final class WithDownloads implements Engine {

    private static final Logger LOG = Logger.getLogger(WithDownloads.class.getName());

    private final Engine torrents;
    private final DirectDownloads links;
    /** The settings last applied: the speed page's limits are the links' too. */
    private volatile EngineSettings settings;

    public WithDownloads(Engine torrents, DirectDownloads links, EngineSettings settings) {
        this.torrents = torrents;
        this.links = links;
        this.settings = settings;
        links.setProxy(proxyOf(settings));
    }

    /** The torrent engine inside. */
    public Engine torrentEngine() {
        return torrents;
    }

    /**
     * The engine's proxy as curl names it: SOCKS5 resolves names through the proxy ({@code
     * socks5h}), as the engine's {@code proxy_hostnames} does. Pure.
     */
    static Curl.Proxy proxyOf(EngineSettings s) {
        if (s == null) return Curl.Proxy.NONE;
        String scheme =
                switch (s.proxy()) {
                    case SOCKS5 -> "socks5h";
                    case HTTP -> "http";
                    case NONE -> "";
                };
        if (scheme.isEmpty() || s.proxyHost().isBlank()) return Curl.Proxy.NONE;
        return new Curl.Proxy(scheme, s.proxyHost().strip(), s.proxyPort(), s.proxyUsername(), s.proxyPassword());
    }

    @Override
    public void start() throws EngineException {
        torrents.start();
        links.onTorrentFile(this::handOver);
        shareLimit();
        links.start();
    }

    /** A finished download that is a {@code .torrent}: added as one, and the download forgotten. */
    private void handOver(DirectDownload d) {
        String file = java.nio.file.Path.of(d.savePath(), d.file()).toString();
        try {
            torrents.add(file);
            links.remove(d.id(), true);
        } catch (EngineException | RuntimeException e) {
            // Kept as a finished download: the .torrent is there to open by hand.
            LOG.log(Level.INFO, "Could not add the downloaded " + file + " as a torrent", e);
        }
    }

    @Override
    public String description() {
        return torrents.description();
    }

    @Override
    public Capabilities capabilities() {
        return torrents.capabilities();
    }

    @Override
    public String downloadDir() {
        return torrents.downloadDir();
    }

    @Override
    public Torrent add(String input) throws EngineException {
        if (Inputs.isDirectLink(input)) return links.add(input, null, false);
        return torrents.add(input);
    }

    @Override
    public Torrent addLink(LinkRequest request) throws EngineException {
        String folder = request.savePath() == null ? null : request.savePath().toString();
        return links.add(request.url(), folder, request.fileName(), request.startPaused());
    }

    @Override
    public Torrent add(AddRequest request) throws EngineException {
        return torrents.add(request);
    }

    @Override
    public Torrent add(AddRequest request, SwarmProbe from) throws EngineException {
        return torrents.add(request, from);
    }

    @Override
    public TorrentPreview inspect(byte[] torrent) throws EngineException {
        return torrents.inspect(torrent);
    }

    @Override
    public CreatedTorrent create(CreateRequest request, CreateProgress progress) throws EngineException {
        return torrents.create(request, progress);
    }

    @Override
    public SwarmProbe probe(AddRequest.Source source, boolean privateToo) throws EngineException {
        return torrents.probe(source, privateToo);
    }

    @Override
    public Detached detach(String id) throws EngineException {
        torrentOnly(id);
        return torrents.detach(id);
    }

    @Override
    public void reattach(Detached torrent) throws EngineException {
        torrentOnly(torrent.id());
        torrents.reattach(torrent);
    }

    @Override
    public String adopt(Adoption adoption) throws EngineException {
        return torrents.adopt(adoption);
    }

    @Override
    public void remove(String id, boolean deleteFiles) throws EngineException {
        if (DirectDownload.isId(id)) links.remove(id, deleteFiles);
        else torrents.remove(id, deleteFiles);
    }

    @Override
    public java.util.Optional<OnDisk> onDisk(String id) throws EngineException {
        return DirectDownload.isId(id) ? links.onDisk(id) : torrents.onDisk(id);
    }

    @Override
    public void pause(String id) throws EngineException {
        if (DirectDownload.isId(id)) links.pause(id);
        else torrents.pause(id);
    }

    @Override
    public void resume(String id) throws EngineException {
        if (DirectDownload.isId(id)) links.resume(id);
        else torrents.resume(id);
    }

    @Override
    public void forceResume(String id) throws EngineException {
        if (DirectDownload.isId(id)) links.forceResume(id);
        else torrents.forceResume(id);
    }

    @Override
    public void recheck(String id) throws EngineException {
        torrentOnly(id);
        torrents.recheck(id);
    }

    @Override
    public void reannounce(String id) throws EngineException {
        torrentOnly(id);
        torrents.reannounce(id);
    }

    @Override
    public void setSequential(String id, boolean sequential) throws EngineException {
        torrentOnly(id);
        torrents.setSequential(id, sequential);
    }

    @Override
    public void queueMove(String id, QueueMove move) throws EngineException {
        torrentOnly(id);
        torrents.queueMove(id, move);
    }

    @Override
    public void moveStorage(String id, String destination, boolean moveFiles) throws EngineException {
        if (DirectDownload.isId(id)) links.moveStorage(id, destination, moveFiles);
        else torrents.moveStorage(id, destination, moveFiles);
    }

    @Override
    public void setSuperSeeding(String id, boolean on) throws EngineException {
        torrentOnly(id);
        torrents.setSuperSeeding(id, on);
    }

    /** A download's only limit is its rate; the rest read as the engine's own. */
    @Override
    public TorrentLimits limits(String id) throws EngineException {
        if (!DirectDownload.isId(id)) return torrents.limits(id);
        return new TorrentLimits((int) Math.min(Integer.MAX_VALUE, links.rateLimit(id)), 0, 0, 0, false);
    }

    @Override
    public void setLimits(String id, TorrentLimits limits) throws EngineException {
        if (DirectDownload.isId(id)) links.setRateLimit(id, limits.downloadLimit());
        else torrents.setLimits(id, limits);
    }

    @Override
    public String category(String id) throws EngineException {
        if (!DirectDownload.isId(id)) return torrents.category(id);
        return links.get(id).map(DirectDownload::category).orElse("");
    }

    @Override
    public void setCategory(String id, String category) throws EngineException {
        if (DirectDownload.isId(id)) links.setCategory(id, category);
        else torrents.setCategory(id, category);
    }

    @Override
    public List<String> tags(String id) throws EngineException {
        if (!DirectDownload.isId(id)) return torrents.tags(id);
        return links.get(id).map(DirectDownload::tags).orElse(List.of());
    }

    @Override
    public void setTags(String id, List<String> tags) throws EngineException {
        if (DirectDownload.isId(id)) links.setTags(id, tags);
        else torrents.setTags(id, tags);
    }

    @Override
    public byte[] exportTorrent(String id) throws EngineException {
        torrentOnly(id);
        return torrents.exportTorrent(id);
    }

    @Override
    public void rename(String id, String name) throws EngineException {
        if (DirectDownload.isId(id)) links.rename(id, name);
        else torrents.rename(id, name);
    }

    @Override
    public String magnetLink(String id) throws EngineException {
        torrentOnly(id);
        return torrents.magnetLink(id);
    }

    @Override
    public TorrentDetail detail(String id) throws EngineException {
        return DirectDownload.isId(id) ? links.detail(id) : torrents.detail(id);
    }

    @Override
    public void setFilePriority(String id, int fileIndex, Priority priority) throws EngineException {
        torrentOnly(id);
        torrents.setFilePriority(id, fileIndex, priority);
    }

    @Override
    public void renamePath(String id, String path, String name) throws EngineException {
        torrentOnly(id);
        torrents.renamePath(id, path, name);
    }

    @Override
    public void addTrackers(String id, List<String> urls) throws EngineException {
        torrentOnly(id);
        torrents.addTrackers(id, urls);
    }

    @Override
    public void removeTracker(String id, String url) throws EngineException {
        torrentOnly(id);
        torrents.removeTracker(id, url);
    }

    @Override
    public List<String> webSeeds(String id) throws EngineException {
        return DirectDownload.isId(id) ? List.of() : torrents.webSeeds(id);
    }

    @Override
    public void addWebSeeds(String id, List<String> urls) throws EngineException {
        torrentOnly(id);
        torrents.addWebSeeds(id, urls);
    }

    @Override
    public void removeWebSeed(String id, String url) throws EngineException {
        torrentOnly(id);
        torrents.removeWebSeed(id, url);
    }

    @Override
    public List<Torrent> torrents() {
        List<Torrent> all = new ArrayList<>(torrents.torrents());
        all.addAll(links.torrents());
        return all;
    }

    @Override
    public Rates rates() {
        Rates r = torrents.rates();
        return new Rates(r.download() + links.rate(), r.upload());
    }

    @Override
    public SessionStats stats() {
        return torrents.stats();
    }

    @Override
    public Connectivity connectivity() {
        return torrents.connectivity();
    }

    /** A torrent's file; a download from a link is curl's, read when it is done. */
    @Override
    public com.torrenta.engine.StreamFile stream(String id, int fileIndex) throws EngineException {
        if (DirectDownload.isId(id)) throw new EngineException(tr("engine.error.noStreaming"));
        return torrents.stream(id, fileIndex);
    }

    @Override
    public java.util.Optional<MutableItem> lookupMutable(
            com.torrenta.engine.MutableSource source, java.time.Duration timeout)
            throws EngineException, InterruptedException {
        return torrents.lookupMutable(source, timeout);
    }

    @Override
    public com.torrenta.engine.MutableKey newMutableKey() throws EngineException {
        return torrents.newMutableKey();
    }

    @Override
    public MutablePut putMutable(
            com.torrenta.engine.MutableKey key, byte[] salt, byte[] value, java.time.Duration timeout)
            throws EngineException, InterruptedException {
        return torrents.putMutable(key, salt, value, timeout);
    }

    @Override
    public java.util.Map<String, com.torrenta.engine.Following> followings() {
        return torrents.followings();
    }

    @Override
    public void setFollowing(String id, com.torrenta.engine.@Nullable Following following) throws EngineException {
        if (DirectDownload.isId(id)) throw new EngineException(tr("engine.error.noMutable"));
        torrents.setFollowing(id, following);
    }

    /** The torrents' files: a download from a link has no v2 root to be found by. */
    @Override
    public com.torrenta.engine.ContentIndex contentIndex() {
        return torrents.contentIndex();
    }

    @Override
    public void flush() {
        torrents.flush();
    }

    @Override
    public void rebind() throws EngineException {
        torrents.rebind();
    }

    @Override
    public void applySettings(EngineSettings settings) throws EngineException {
        this.settings = settings;
        links.setProxy(proxyOf(settings));
        shareLimit();
        torrents.applySettings(settings);
    }

    /**
     * The torrents' limits as the engine has them, and the same download limit for the links,
     * shared among them: the normal pair's, or the alternative one's while it is in force.
     */
    @Override
    public boolean refreshLimits(boolean alternative) throws EngineException {
        this.alternative = alternative;
        shareLimit();
        return torrents.refreshLimits(alternative);
    }

    /** The pair the session last said is in force, for the links' share of it. */
    private volatile boolean alternative;

    private void shareLimit() {
        EngineSettings s = settings;
        links.setGlobalLimit(s == null ? 0 : s.speed().downloadBytes(alternative));
    }

    @Override
    public void setIpFilter(List<IpRange> ranges) throws EngineException {
        torrents.setIpFilter(ranges);
    }

    @Override
    public long blockedPeers() {
        return torrents.blockedPeers();
    }

    @Override
    public long setBlocklist(String url, boolean enabled) throws EngineException {
        return torrents.setBlocklist(url, enabled);
    }

    @Override
    public void addListener(Consumer<com.torrenta.engine.EngineEvent> listener) {
        torrents.addListener(listener);
        links.addListener(listener);
    }

    @Override
    public void removeListener(Consumer<com.torrenta.engine.EngineEvent> listener) {
        torrents.removeListener(listener);
        links.removeListener(listener);
    }

    @Override
    public void stop() {
        links.stop();
        torrents.stop();
    }

    private void torrentOnly(String id) throws EngineException {
        if (!DirectDownload.isId(id)) return;
        String name = links.get(id).map(DirectDownload::name).orElse(id);
        throw new EngineException(tr("direct.error.notTorrent", name));
    }
}
