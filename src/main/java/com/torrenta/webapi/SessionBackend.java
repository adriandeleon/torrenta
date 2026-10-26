package com.torrenta.webapi;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Connectivity;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.net.TorrentFetch;
import com.torrenta.session.AddDefaults;
import com.torrenta.session.Session;
import com.torrenta.store.LabelStore;
import com.torrenta.store.Settings;

import static com.torrenta.i18n.Messages.tr;

/**
 * The API over the running {@link Session}, this machine's {@link Settings} and its {@link
 * LabelStore}: the window's and headless's. The engine is asked on the request's own thread (a
 * virtual one), never the toolkit's; with no engine up the lists are empty.
 */
public final class SessionBackend implements WebApi.Backend {

    private static final Logger LOG = Logger.getLogger(SessionBackend.class.getName());

    private final Session session;
    private final Settings settings;
    private final LabelStore labels;
    /** When each torrent last moved a byte, epoch seconds, from the session's polls. */
    private final Map<String, Long> lastActive = new ConcurrentHashMap<>();

    public SessionBackend(Session session, Settings settings, LabelStore labels) {
        this.session = session;
        this.settings = settings;
        this.labels = labels;
        session.addListener(poll -> {
            for (Torrent t : poll.torrents())
                if (t.downloadRate() > 0 || t.uploadRate() > 0) lastActive.put(t.id(), poll.nowSeconds());
            if (lastActive.size() > poll.torrents().size() * 2 + 16) {
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (Torrent t : poll.torrents()) ids.add(t.id());
                lastActive.keySet().retainAll(ids);
            }
        });
    }

    @Override
    public EngineSettings engineSettings() {
        return settings.engineSettings();
    }

    @Override
    public Path defaultSavePath() {
        return settings.downloadDir();
    }

    @Override
    public List<Torrent> torrents() {
        Engine e = session.engine();
        return e == null ? List.of() : e.torrents();
    }

    @Override
    public Optional<TorrentDetail> detail(String id) {
        Engine e = session.engine();
        if (e == null) return Optional.empty();
        try {
            return Optional.ofNullable(e.detail(id));
        } catch (EngineException | RuntimeException ex) {
            LOG.log(Level.FINE, "No detail for " + id, ex);
            return Optional.empty();
        }
    }

    @Override
    public String magnet(String id) {
        Engine e = session.engine();
        if (e == null) return "";
        try {
            String link = e.magnetLink(id);
            return link == null ? "" : link;
        } catch (EngineException | RuntimeException ex) {
            return "";
        }
    }

    @Override
    public TorrentLimits limits(String id) {
        Engine e = session.engine();
        if (e == null) return TorrentLimits.NONE;
        try {
            return e.limits(id);
        } catch (EngineException | RuntimeException ex) {
            return TorrentLimits.NONE;
        }
    }

    @Override
    public Optional<ShareLimit> ownShare(String id) {
        return settings.shareOverride(id);
    }

    @Override
    public ShareLimit share(Torrent t) {
        return session.shareLimitFor(t);
    }

    @Override
    public ShareLimit globalShare() {
        return settings.shareLimit();
    }

    @Override
    public long lastActivity(Torrent t) {
        Long seen = lastActive.get(t.id());
        if (seen != null) return seen;
        return t.completed() > 0 ? t.completed() : t.added();
    }

    @Override
    public List<Category> categories() {
        return labels.categories();
    }

    @Override
    public List<String> tags() {
        return labels.tags();
    }

    @Override
    public WebApi.Transfer transfer() {
        Engine e = session.engine();
        EngineSettings.Speed speed = settings.engineSettings().speed();
        LocalDateTime now = LocalDateTime.now();
        long down = speed.effectiveDownloadBytes(now);
        long up = speed.effectiveUploadBytes(now);
        if (e == null) return new WebApi.Transfer(0, 0, 0, 0, down, up, 0, "disconnected");
        Engine.Rates rates = e.rates();
        SessionStats stats = e.stats();
        EngineSettings.Queue queue = settings.engineSettings().queue();
        return new WebApi.Transfer(
                rates.download(),
                rates.upload(),
                Math.max(0, stats.sessionDownload()),
                Math.max(0, stats.sessionUpload()),
                down,
                up,
                Math.max(0, stats.dhtNodes()),
                connection(e.connectivity()),
                Math.max(0, stats.allTimeDownload()),
                Math.max(0, stats.allTimeUpload()),
                Math.max(0, stats.peersConnected()),
                e.capabilities().has(Engine.Capability.LOCAL_FILES) ? freeSpace(settings.downloadDir()) : -1,
                speed.alternativeActive(now),
                queue.activeDownloads() > 0 || queue.activeSeeds() > 0 || queue.activeLimit() > 0);
    }

    /** Usable bytes on the volume of {@code dir}, or of its nearest folder that exists; -1 unknown. */
    static long freeSpace(Path dir) {
        Path at = dir.toAbsolutePath();
        while (at != null && !java.nio.file.Files.exists(at)) at = at.getParent();
        if (at == null) return -1;
        try {
            return java.nio.file.Files.getFileStore(at).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * qBittorrent's word: {@code connected} once a peer from outside reached the port, {@code
     * firewalled} while listening without one, {@code disconnected} when nothing listens. An engine
     * that keeps no such facts (a daemon) reads as connected: it is up, and nothing says otherwise.
     */
    static String connection(Connectivity c) {
        if (c.equals(Connectivity.NONE)) return "connected";
        if (!c.listening()) return "disconnected";
        return c.incoming() ? "connected" : "firewalled";
    }

    @Override
    public Engine engine() {
        return session.engine();
    }

    @Override
    public Torrent add(AddRequest request) throws EngineException {
        Engine e = session.engine();
        if (e == null) throw new EngineException(tr("status.engineNotRunning"));
        return session.add(e, request);
    }

    @Override
    public AddDefaults addDefaults() {
        return session.addDefaults();
    }

    /**
     * The download folder (a daemon's own too, when the files are not on this machine), each
     * category's folder, and the folders added by hand on the Remote access page.
     */
    @Override
    public List<Path> allowedRoots() {
        List<Path> roots = new java.util.ArrayList<>();
        roots.add(settings.downloadDir());
        roots.add(session.addDefaults().savePath());
        for (Category c : labels.categories()) if (!c.savePath().isEmpty()) roots.add(Path.of(c.savePath()));
        roots.addAll(settings.webApiRoots());
        return roots;
    }

    @Override
    public void putCategory(Category category) throws IOException {
        labels.putCategory(category);
    }

    @Override
    public void addTags(Collection<String> tags) throws IOException {
        labels.addTags(tags);
    }

    @Override
    public void setShareOverride(String id, ShareLimit limit) {
        settings.setShareOverride(id, limit);
        settings.saveQuietly();
    }

    @Override
    public Optional<Path> rootFolder() {
        return settings.webApiRootFolder();
    }

    @Override
    public byte[] fetch(URI url) throws IOException {
        return new TorrentFetch().fetch(url);
    }
}
