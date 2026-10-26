package com.torrenta.webapi;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;

/**
 * One torrent as {@code torrents/info} writes it: qBittorrent 5.0's field names and units (bytes,
 * bytes per second, epoch seconds, -1 for "none"). Pure; the facts that need the torrent's file
 * list are read once and kept ({@link Facts}).
 *
 * <p>Two fields the *arr apps depend on: {@code hash} is the v1 info-hash when the torrent has one,
 * since that is what they computed from the {@code .torrent} or magnet they sent and look it up by
 * (Torrenta's own id is the best hash, a hybrid's v2); and {@code content_path}, which they import
 * from, is the file of a single-file torrent and the root folder of the others.
 */
public final class QbitTorrent {

    private QbitTorrent() {}

    /** qBittorrent's "no estimate", 100 days in seconds. */
    static final long MAX_ETA = 8_640_000;

    /** qBittorrent's ratio for a torrent that uploaded with nothing to measure against. */
    static final double MAX_RATIO = 9999;

    /**
     * What a torrent's file list and metadata say, which a snapshot does not: read with the detail,
     * kept while the torrent keeps its name and save path.
     *
     * @param infoHashV1 hex, empty for a v2-only torrent
     * @param infoHashV2 hex, empty for a v1-only torrent
     * @param contentPath what the *arr apps import from
     * @param magnet the torrent as a magnet link; empty when the engine could not say
     * @param metadata the file list was known when these were read
     * @param activeSeconds time spent running, as of the reading: the one fact here that moves,
     *     which is why they are read again now and then
     * @param rootPath the folder every file sits under; empty for a single file or none
     */
    public record Facts(
            String infoHashV1,
            String infoHashV2,
            String contentPath,
            String magnet,
            boolean isPrivate,
            int pieceSize,
            int pieces,
            boolean metadata,
            long activeSeconds,
            String rootPath) {

        /** Before anything was read: the snapshot's own save path and name. */
        static Facts unknown(Torrent t) {
            return new Facts(
                    "", "", QbitTorrent.contentPath(t.savePath(), t.name(), List.of()), "", false, 0, 0, false, 0, "");
        }

        static Facts of(Torrent t, TorrentDetail detail, String magnet) {
            TorrentDetail.General g = detail.general();
            return new Facts(
                    g.infoHashV1(),
                    g.infoHashV2(),
                    QbitTorrent.contentPath(t.savePath(), t.name(), detail.files()),
                    magnet == null ? "" : magnet,
                    g.isPrivate(),
                    g.pieceLength(),
                    g.pieceCount(),
                    !detail.files().isEmpty(),
                    g.activeSeconds(),
                    QbitTorrent.rootPath(t.savePath(), detail.files()));
        }
    }

    /** What a row needs beyond the snapshot and its facts, from the session. */
    public record Extras(TorrentLimits limits, Optional<ShareLimit> ownShare, ShareLimit share, long lastActivity) {}

    /** The id a qBittorrent client knows the torrent by: its v1 hash, else Torrenta's id. */
    public static String hash(Torrent t, Facts f) {
        return f.infoHashV1().isEmpty() ? t.id() : f.infoHashV1();
    }

    /**
     * The file of a single-file torrent; the folder every file sits under, when they share one;
     * else the save path itself (a torrent laid out without its root folder).
     */
    static String contentPath(String savePath, String name, List<TorrentDetail.FileStatus> files) {
        Path base = Path.of(savePath);
        if (files.isEmpty()) return base.resolve(name).toString();
        if (files.size() == 1) return base.resolve(files.getFirst().path()).toString();
        String root = firstSegment(files.getFirst().path());
        for (TorrentDetail.FileStatus f : files)
            if (!firstSegment(f.path()).equals(root) || f.path().length() == root.length()) return base.toString();
        return base.resolve(root).toString();
    }

    /** qBittorrent's {@code root_path}: the shared root folder of a multi-file torrent, else empty. */
    static String rootPath(String savePath, List<TorrentDetail.FileStatus> files) {
        if (files.size() < 2) return "";
        String content = contentPath(savePath, "", files);
        return content.equals(Path.of(savePath).toString()) ? "" : content;
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        int back = path.indexOf('\\');
        int cut = slash < 0 ? back : back < 0 ? slash : Math.min(slash, back);
        return cut < 0 ? path : path.substring(0, cut);
    }

    /** Seconds to finish at the present rate; {@link #MAX_ETA} when it will not, or is done. */
    static long eta(Torrent t) {
        long left = Math.max(0, t.size() - t.done());
        if (left == 0 || t.downloadRate() <= 0) return MAX_ETA;
        return Math.min(MAX_ETA, (left + t.downloadRate() - 1) / t.downloadRate());
    }

    /**
     * Uploaded over downloaded, as the share limit measures it: against the size when nothing was
     * downloaded (the files were already there).
     */
    static double ratio(Torrent t) {
        long base = t.downloaded() > 0 ? t.downloaded() : t.size();
        if (base <= 0) return t.uploaded() > 0 ? MAX_RATIO : 0;
        return Math.min(MAX_RATIO, (double) t.uploaded() / base);
    }

    /** A limit as qBittorrent writes one: -2 for "the global one", -1 for "none". */
    private static double ratioLimit(Optional<ShareLimit> own) {
        return own.map(s -> s.ratio() > 0 ? s.ratio() : -1).orElse(-2.0);
    }

    private static long seedingTimeLimit(Optional<ShareLimit> own) {
        return own.map(s -> s.seedingMinutes() > 0 ? (long) s.seedingMinutes() : -1L)
                .orElse(-2L);
    }

    /** The row, in qBittorrent's field order where it has one. */
    public static Map<String, Object> fields(Torrent t, Facts f, Extras x) {
        Map<String, Object> m = new LinkedHashMap<>();
        long size = t.size();
        m.put("added_on", t.added());
        m.put("amount_left", Math.max(0, size - t.done()));
        m.put("auto_tmm", false);
        m.put("availability", -1);
        m.put("category", t.category());
        m.put("completed", t.done());
        m.put("completion_on", t.completed() > 0 ? t.completed() : -1);
        m.put("comment", "");
        m.put("content_path", f.metadata() ? f.contentPath() : "");
        m.put("dl_limit", x.limits().downloadLimit());
        m.put("dlspeed", t.downloadRate());
        m.put("download_path", "");
        m.put("downloaded", t.downloaded());
        m.put("downloaded_session", 0);
        m.put("eta", eta(t));
        m.put("f_l_piece_prio", x.limits().firstAndLastFirst());
        m.put("force_start", false);
        m.put("has_metadata", f.metadata());
        m.put("hash", hash(t, f));
        m.put("inactive_seeding_time_limit", -2);
        m.put("infohash_v1", f.infoHashV1());
        m.put("infohash_v2", f.infoHashV2());
        m.put("last_activity", x.lastActivity());
        m.put("magnet_uri", f.magnet());
        m.put("max_inactive_seeding_time", -1);
        m.put("max_ratio", x.share().ratio() > 0 ? x.share().ratio() : -1);
        m.put("max_seeding_time", x.share().seedingMinutes() > 0 ? x.share().seedingMinutes() : -1);
        m.put("name", t.name());
        m.put("num_complete", t.seeds());
        m.put("num_incomplete", Math.max(0, t.peers() - t.seeds()));
        m.put("num_leechs", Math.max(0, t.peers() - t.seeds()));
        m.put("num_seeds", t.seeds());
        m.put("popularity", 0);
        m.put("priority", t.queuePosition() >= 0 ? t.queuePosition() + 1 : 0);
        // Unknown until the metadata says: qBittorrent writes null.
        m.put("private", f.metadata() ? f.isPrivate() : null);
        m.put("progress", t.progress());
        m.put("ratio", ratio(t));
        m.put("ratio_limit", ratioLimit(x.ownShare()));
        m.put("reannounce", 0);
        m.put("root_path", f.rootPath());
        m.put("save_path", t.savePath());
        m.put("seeding_time", t.seedingTime());
        m.put("seeding_time_limit", seedingTimeLimit(x.ownShare()));
        m.put("seen_complete", t.completed() > 0 ? t.completed() : -1);
        m.put("seq_dl", t.sequential());
        m.put("size", size);
        m.put("state", QbitState.of(t).wire());
        m.put("super_seeding", t.superSeeding());
        m.put("tags", String.join(", ", t.tags()));
        m.put("time_active", Math.max(f.activeSeconds(), t.seedingTime()));
        m.put("total_size", size);
        m.put("tracker", t.tracker());
        m.put("trackers_count", t.trackers().size());
        m.put("up_limit", x.limits().uploadLimit());
        m.put("uploaded", t.uploaded());
        m.put("uploaded_session", 0);
        m.put("upspeed", t.uploadRate());
        return m;
    }
}
