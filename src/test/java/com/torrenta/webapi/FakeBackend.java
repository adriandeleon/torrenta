package com.torrenta.webapi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.torrenta.engine.Category;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentState;

/** A backend the tests fill in by hand, counting the detail reads the cache is meant to save. */
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
}
