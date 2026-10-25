package com.torrenta.webapi;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentState;
import com.torrenta.session.AddDefaults;

/** A backend the tests fill in by hand, counting the detail reads the cache is meant to save. */
@SuppressWarnings("unchecked")
final class FakeBackend implements WebApi.Backend {

    EngineSettings settings = EngineSettings.defaults();
    Path savePath = Path.of("/srv/downloads");
    final List<Torrent> torrents = new ArrayList<>();
    final Map<String, TorrentDetail> details = new HashMap<>();
    final Map<String, ShareLimit> own = new HashMap<>();
    final List<Category> categories = new ArrayList<>();
    final List<String> tags = new ArrayList<>();
    ShareLimit share = ShareLimit.NONE;
    WebApi.Transfer transfer = new WebApi.Transfer(0, 0, 0, 0, 0, 0, 0, "disconnected");
    final AtomicInteger detailReads = new AtomicInteger();
    /** Every call the engine took, as {@code name arg arg}. */
    final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** What reached the screened add. */
    final List<AddRequest> adds = new java.util.concurrent.CopyOnWriteArrayList<>();

    final List<Path> roots = new ArrayList<>(List.of(Path.of("/srv/downloads")));
    final Map<String, byte[]> served = new HashMap<>();
    ShareLimit global = ShareLimit.NONE;
    boolean engineUp = true;
    final Map<String, List<String>> engineTags = new HashMap<>();

    /** An engine that records what it is asked and answers the little the routes read back. */
    final Engine engine = (Engine) Proxy.newProxyInstance(
            Engine.class.getClassLoader(), new Class<?>[] {Engine.class}, (proxy, method, args) -> {
                StringBuilder call = new StringBuilder(method.getName());
                if (args != null) for (Object a : args) call.append(' ').append(a);
                calls.add(call.toString());
                return switch (method.getName()) {
                    case "torrents" -> List.copyOf(torrents);
                    case "capabilities" -> Engine.Capabilities.ALL;
                    case "tags" -> engineTags.getOrDefault((String) args[0], List.of());
                    case "setTags" -> {
                        engineTags.put((String) args[0], List.copyOf((List<String>) args[1]));
                        yield null;
                    }
                    default -> null;
                };
            });

    /** A torrent with the fields most tests care about; the rest plausible. */
    static Torrent torrent(String id, String name, TorrentState state, double progress, long down, long up) {
        return torrent(id, name, state, progress, down, up, false, false, "");
    }

    static Torrent torrent(
            String id,
            String name,
            TorrentState state,
            double progress,
            long down,
            long up,
            boolean paused,
            boolean queued,
            String error) {
        long size = 1000;
        return new Torrent(
                id,
                name,
                size,
                (long) (size * progress),
                progress,
                state,
                down,
                up,
                3,
                1,
                400,
                800,
                1_700_000_000L,
                progress >= 1 ? 1_700_000_500L : 0,
                "http://tracker.example/announce",
                "/srv/downloads",
                paused,
                false,
                progress >= 1 ? 120 : 0,
                "",
                List.of(),
                error,
                List.of("tracker.example"),
                false,
                queued,
                queued ? 0 : -1,
                false,
                false);
    }

    static TorrentDetail detail(String id, String v1, String v2, List<TorrentDetail.FileStatus> files) {
        TorrentDetail.General g = new TorrentDetail.General(
                id, "/srv/downloads", false, 4, 16384, 2, 1.0, 800, 400, 0, 3600, 120, 30, 3, "", v1, v2);
        return new TorrentDetail(g, files, List.of(), List.of(), new boolean[4]);
    }

    @Override
    public EngineSettings engineSettings() {
        return settings;
    }

    @Override
    public Path defaultSavePath() {
        return savePath;
    }

    @Override
    public List<Torrent> torrents() {
        return List.copyOf(torrents);
    }

    @Override
    public Optional<TorrentDetail> detail(String id) {
        detailReads.incrementAndGet();
        return Optional.ofNullable(details.get(id));
    }

    @Override
    public String magnet(String id) {
        return "magnet:?xt=urn:btih:" + id;
    }

    @Override
    public TorrentLimits limits(String id) {
        return TorrentLimits.NONE;
    }

    @Override
    public Optional<ShareLimit> ownShare(String id) {
        return Optional.ofNullable(own.get(id));
    }

    @Override
    public ShareLimit share(Torrent t) {
        return own.getOrDefault(t.id(), share);
    }

    @Override
    public long lastActivity(Torrent t) {
        return t.added();
    }

    @Override
    public List<Category> categories() {
        return List.copyOf(categories);
    }

    @Override
    public List<String> tags() {
        return List.copyOf(tags);
    }

    @Override
    public WebApi.Transfer transfer() {
        return transfer;
    }

    @Override
    public ShareLimit globalShare() {
        return global;
    }

    @Override
    public Engine engine() {
        return engineUp ? engine : null;
    }

    @Override
    public Torrent add(AddRequest request) throws EngineException {
        adds.add(request);
        return torrent("added" + adds.size(), "added", TorrentState.DOWNLOADING, 0, 0, 0);
    }

    @Override
    public AddDefaults addDefaults() {
        return new AddDefaults(savePath, false, false, false, savePath.resolve("incomplete"), false, false);
    }

    @Override
    public List<Path> allowedRoots() {
        List<Path> all = new ArrayList<>(roots);
        for (Category c : categories) if (!c.savePath().isEmpty()) all.add(Path.of(c.savePath()));
        return all;
    }

    @Override
    public void putCategory(Category category) {
        categories.removeIf(c -> c.name().equalsIgnoreCase(category.name()));
        categories.add(category);
    }

    @Override
    public void addTags(Collection<String> more) {
        for (String t : more) if (!tags.contains(t)) tags.add(t);
    }

    @Override
    public void setShareOverride(String id, ShareLimit limit) {
        if (limit == null) own.remove(id);
        else own.put(id, limit);
    }

    @Override
    public byte[] fetch(URI url) throws IOException {
        byte[] bytes = served.get(url.toString());
        if (bytes == null) throw new IOException("404 for " + url);
        return bytes;
    }
}
