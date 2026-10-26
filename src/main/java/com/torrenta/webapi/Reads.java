package com.torrenta.webapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import com.torrenta.engine.Category;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.http.Form;
import com.torrenta.http.Request;
import com.torrenta.http.Response;

/**
 * The reading endpoints of Tier 1: {@code torrents/info}, {@code properties}, {@code files},
 * {@code categories}, {@code tags} and {@code transfer/info}. Downloads from links are not
 * torrents and are left out: a client of qBittorrent's API knows nothing of them. A torrent is
 * found by the hash a client knows ({@link QbitTorrent#hash}: its v1 hash where it has one) or
 * by Torrenta's own id.
 */
final class Reads {

    private final WebApi.Backend backend;
    private final LongSupplier clock;
    private final FactsCache facts;
    private final MainData.Memory mainMemory = new MainData.Memory();
    private final MainData.Memory peerMemory = new MainData.Memory();

    Reads(WebApi.Backend backend, LongSupplier clock, FactsCache facts) {
        this.backend = backend;
        this.clock = clock;
        this.facts = facts;
    }

    /**
     * The torrents a client names in {@code hashes} ({@code |} between them, by the hash it knows or
     * Torrenta's id, any case), or all of them for {@code all}; one it does not have is skipped, as
     * qBittorrent skips it.
     */
    List<Torrent> targets(String hashes, long now) {
        if (hashes == null || hashes.isBlank()) return List.of();
        List<Torrent> all = torrents();
        if (hashes.trim().equalsIgnoreCase("all")) return all;
        Set<String> wanted = TorrentQuery.hashes(hashes);
        List<Torrent> out = new ArrayList<>();
        for (Torrent t : all) {
            String id = t.id().toLowerCase(java.util.Locale.ROOT);
            if (wanted.contains(id)
                    || wanted.contains(
                            QbitTorrent.hash(t, facts.get(t, now).facts()).toLowerCase(java.util.Locale.ROOT)))
                out.add(t);
        }
        return out;
    }

    /** A GET's query and a POST's form, as qBittorrent reads either. */
    static Form params(Request request) {
        Map<String, List<String>> all = new LinkedHashMap<>(request.query().values());
        request.form()
                .values()
                .forEach((k, v) -> all.merge(k, v, (a, b) -> {
                    List<String> both = new ArrayList<>(a);
                    both.addAll(b);
                    return both;
                }));
        return new Form(all);
    }

    private List<Torrent> torrents() {
        List<Torrent> all = backend.torrents().stream().filter(t -> !t.direct()).toList();
        facts.retain(all);
        return all;
    }

    private TorrentQuery.Row row(Torrent t, long now) {
        FactsCache.Known known = facts.get(t, now);
        QbitTorrent.Extras extras = new QbitTorrent.Extras(
                known.limits(), backend.ownShare(t.id()), backend.share(t), backend.lastActivity(t));
        return new TorrentQuery.Row(t, QbitTorrent.fields(t, known.facts(), extras));
    }

    Response info(Request request) {
        long now = clock.getAsLong();
        List<TorrentQuery.Row> rows = new ArrayList<>();
        for (Torrent t : torrents()) rows.add(row(t, now));
        return Response.json(JsonOut.write(TorrentQuery.apply(rows, params(request))));
    }

    /** The torrent a client means by {@code hash}; empty for none. */
    private Optional<Torrent> find(String hash, long now) {
        String h = hash.trim();
        for (Torrent t : torrents()) {
            if (t.id().equalsIgnoreCase(h)) return Optional.of(t);
            if (QbitTorrent.hash(t, facts.get(t, now).facts()).equalsIgnoreCase(h)) return Optional.of(t);
        }
        return Optional.empty();
    }

    /** The torrent named by the request's {@code hash}, or the answer qBittorrent gives instead. */
    private record Found(Torrent torrent, Response refusal) {}

    private Found found(Request request, long now) {
        Optional<String> hash = params(request).first("hash");
        if (hash.isEmpty() || hash.get().isBlank()) return new Found(null, Response.text(400, "Bad Request"));
        return find(hash.get(), now)
                .map(t -> new Found(t, null))
                .orElseGet(() -> new Found(null, Response.text(404, "Not Found")));
    }

    Response properties(Request request) {
        long now = clock.getAsLong();
        Found f = found(request, now);
        if (f.refusal() != null) return f.refusal();
        Torrent t = f.torrent();
        Optional<TorrentDetail> detail = backend.detail(t.id());
        if (detail.isEmpty()) return Response.text(404, "Not Found");
        TorrentDetail.General g = detail.get().general();
        QbitTorrent.Facts known = facts.get(t, now).facts();
        TorrentLimits limits = backend.limits(t.id());
        long active = Math.max(1, g.activeSeconds());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("addition_date", t.added());
        m.put("comment", "");
        m.put("completion_date", t.completed() > 0 ? t.completed() : -1);
        m.put("created_by", "");
        m.put("creation_date", -1);
        m.put("dl_limit", limits.downloadLimit() > 0 ? limits.downloadLimit() : -1);
        m.put("dl_speed", t.downloadRate());
        m.put("dl_speed_avg", g.downloaded() / active);
        m.put("download_path", "");
        m.put("eta", QbitTorrent.eta(t));
        m.put("has_metadata", !detail.get().files().isEmpty());
        m.put("hash", QbitTorrent.hash(t, known));
        m.put("infohash_v1", g.infoHashV1());
        m.put("infohash_v2", g.infoHashV2());
        m.put("isPrivate", g.isPrivate());
        m.put("is_private", g.isPrivate());
        m.put("last_seen", t.completed() > 0 ? t.completed() : -1);
        m.put("name", t.name());
        m.put("nb_connections", g.connections());
        m.put("nb_connections_limit", limits.maxConnections() > 0 ? limits.maxConnections() : -1);
        m.put("peers", Math.max(0, t.peers() - t.seeds()));
        m.put("peers_total", Math.max(0, t.peers() - t.seeds()));
        m.put("piece_size", g.pieceLength());
        m.put("pieces_have", g.piecesHave());
        m.put("pieces_num", g.pieceCount());
        m.put("reannounce", g.nextAnnounceSeconds());
        m.put("save_path", t.savePath());
        m.put("seeding_time", t.seedingTime());
        m.put("seeds", t.seeds());
        m.put("seeds_total", t.seeds());
        m.put("share_ratio", QbitTorrent.ratio(t));
        m.put("time_elapsed", g.activeSeconds());
        m.put("total_downloaded", g.downloaded());
        m.put("total_size", t.size());
        m.put("total_uploaded", g.uploaded());
        m.put("total_wasted", g.wasted());
        m.put("up_limit", limits.uploadLimit() > 0 ? limits.uploadLimit() : -1);
        m.put("up_speed", t.uploadRate());
        m.put("up_speed_avg", g.uploaded() / active);
        return Response.json(JsonOut.write(m));
    }

    Response files(Request request) {
        long now = clock.getAsLong();
        Found f = found(request, now);
        if (f.refusal() != null) return f.refusal();
        Optional<TorrentDetail> detail = backend.detail(f.torrent().id());
        if (detail.isEmpty()) return Response.text(404, "Not Found");
        Set<Integer> indexes = indexes(params(request).first("indexes").orElse(""));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TorrentDetail.FileStatus file : detail.get().files()) {
            if (indexes != null && !indexes.contains(file.index())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("availability", -1);
            m.put("index", file.index());
            m.put("is_seed", file.progress() >= 1);
            m.put("name", file.path().replace('\\', '/'));
            m.put("piece_range", List.of(file.firstPiece(), file.lastPiece()));
            m.put("priority", priority(file.priority()));
            m.put("progress", file.progress());
            m.put("size", file.size());
            out.add(m);
        }
        return Response.json(JsonOut.write(out));
    }

    /** {@code 1|3}; null for every file. */
    static Set<Integer> indexes(String text) {
        if (text.isBlank()) return null;
        try {
            return Arrays.stream(text.split("\\|"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * qBittorrent's file priorities: 0 skip, 1 normal, 6 high, 7 maximal. Its "normal" is
     * libtorrent's lowest wanted priority, so low and normal both read as 1.
     */
    static int priority(Priority p) {
        return switch (p) {
            case IGNORE -> 0;
            case LOW, NORMAL -> 1;
            case HIGH -> 6;
        };
    }

    Response categories(Request request) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Category c : backend.categories()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("savePath", c.savePath());
            out.put(c.name(), m);
        }
        return Response.json(JsonOut.write(out));
    }

    Response tags(Request request) {
        return Response.json(JsonOut.write(List.copyOf(backend.tags())));
    }

    Response transfer(Request request) {
        WebApi.Transfer x = backend.transfer();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connection_status", x.connection());
        m.put("dht_nodes", x.dhtNodes());
        m.put("dl_info_data", x.downloadedSession());
        m.put("dl_info_speed", x.downloadRate());
        m.put("dl_rate_limit", x.downloadLimit());
        m.put("up_info_data", x.uploadedSession());
        m.put("up_info_speed", x.uploadRate());
        m.put("up_rate_limit", x.uploadLimit());
        return Response.json(JsonOut.write(m));
    }

    /** {@code transfer/info}'s fields and the rest of qBittorrent's {@code server_state}. */
    static Map<String, Object> serverState(WebApi.Transfer x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alltime_dl", x.allTimeDownload());
        m.put("alltime_ul", x.allTimeUpload());
        m.put("connection_status", x.connection());
        m.put("dht_nodes", x.dhtNodes());
        m.put("dl_info_data", x.downloadedSession());
        m.put("dl_info_speed", x.downloadRate());
        m.put("dl_rate_limit", x.downloadLimit());
        m.put("free_space_on_disk", x.freeSpace());
        m.put(
                "global_ratio",
                x.allTimeDownload() > 0
                        ? String.format(java.util.Locale.ROOT, "%.2f", (double) x.allTimeUpload() / x.allTimeDownload())
                        : "0.00");
        m.put("queueing", x.queueing());
        m.put("refresh_interval", 1500);
        m.put("total_peer_connections", x.peers());
        m.put("up_info_data", x.uploadedSession());
        m.put("up_info_speed", x.uploadRate());
        m.put("up_rate_limit", x.uploadLimit());
        m.put("use_alt_speed_limits", x.alternative());
        m.put("use_subcategories", false);
        return m;
    }

    /** Whose answers {@code rid} counts in: the client's session. */
    private static String client(Request request) {
        return String.valueOf(request.cookies().get(WebApi.COOKIE));
    }

    private static long rid(Form params) {
        try {
            return Long.parseLong(params.first("rid").orElse("0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code sync/maindata}: the list, the categories, the tags and the server's state, whole the
     * first time and as what changed after, against the snapshot this session was last answered
     * with ({@link MainData}).
     */
    Response maindata(Request request) {
        long now = clock.getAsLong();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Torrent t : torrents()) {
            TorrentQuery.Row r = row(t, now);
            rows.put(r.hash(), r.fields());
        }
        Map<String, Map<String, Object>> categories = new LinkedHashMap<>();
        for (Category c : backend.categories()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("savePath", c.savePath());
            categories.put(c.name(), m);
        }
        MainData.Snapshot current =
                new MainData.Snapshot(rows, categories, backend.tags(), serverState(backend.transfer()));
        String key = client(request);
        MainData.Snapshot previous = mainMemory.since(key, rid(params(request))).orElse(null);
        long rid = mainMemory.remember(key, current);
        return Response.json(JsonOut.write(MainData.maindata(previous, current, rid)));
    }

    /** {@code sync/torrentPeers}: one torrent's peers by {@code ip:port}, the same way. */
    Response torrentPeers(Request request) {
        long now = clock.getAsLong();
        Found f = found(request, now);
        if (f.refusal() != null) return f.refusal();
        Optional<TorrentDetail> detail = backend.detail(f.torrent().id());
        if (detail.isEmpty()) return Response.text(404, "Not Found");
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (TorrentDetail.Peer p : detail.get().peers()) {
            String[] ipPort = ipAndPort(p.address());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("client", p.client());
            m.put("connection", p.flags().indexOf('P') >= 0 ? "μTP" : "BT");
            m.put("country", "");
            m.put("country_code", "");
            m.put("dl_speed", p.downRate());
            m.put("downloaded", p.downloaded());
            m.put("files", "");
            m.put(
                    "flags",
                    String.join(
                            " ",
                            p.flags()
                                    .chars()
                                    .mapToObj(c -> String.valueOf((char) c))
                                    .toList()));
            m.put("flags_desc", "");
            m.put("ip", ipPort[0]);
            m.put("port", ipPort[1].isEmpty() ? 0 : Integer.parseInt(ipPort[1]));
            m.put("progress", p.progress());
            m.put("relevance", p.progress());
            m.put("up_speed", p.upRate());
            m.put("uploaded", p.uploaded());
            rows.put(p.address(), m);
        }
        MainData.Snapshot current = new MainData.Snapshot(rows, Map.of(), List.of(), Map.of());
        String key = client(request) + "|" + f.torrent().id();
        MainData.Snapshot previous = peerMemory.since(key, rid(params(request))).orElse(null);
        long rid = peerMemory.remember(key, current);
        return Response.json(JsonOut.write(MainData.peers(previous, current, rid)));
    }

    /** {@code 1.2.3.4:51413} or {@code [::1]:51413} into address and port; with no port, an empty one. */
    static String[] ipAndPort(String address) {
        String a = address.trim();
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close > 0) {
                String port = a.length() > close + 2 && a.charAt(close + 1) == ':' ? a.substring(close + 2) : "";
                return new String[] {a.substring(1, close), port.matches("\\d+") ? port : ""};
            }
        }
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon && a.substring(colon + 1).matches("\\d+"))
            return new String[] {a.substring(0, colon), a.substring(colon + 1)};
        return new String[] {a, ""};
    }
}
