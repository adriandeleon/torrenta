package com.torrenta.webapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * {@code sync/maindata} and {@code sync/torrentPeers}: what changed since the answer a client last
 * had, as qBittorrent sends it. A client polls with the {@code rid} of its last answer; when that
 * is the one remembered for its session, it gets only the torrents (and categories, tags, server
 * fields) that changed, and within a torrent only the fields that did, with the removed ones
 * listed by key; any other {@code rid} (0 at first, a forgotten one, another session's) gets the
 * whole picture with {@code full_update}. The diff is pure; {@link Memory} holds one snapshot per
 * session, as qBittorrent does, not a history, so a thousand torrents cost one copy per client.
 */
public final class MainData {

    private MainData() {}

    /**
     * One answer's worth: rows by key (a torrent by its hash, a peer by {@code ip:port}), the
     * categories by name, the tags, the server's fields. A peers snapshot has peers only.
     */
    public record Snapshot(
            Map<String, Map<String, Object>> rows,
            Map<String, Map<String, Object>> categories,
            List<String> tags,
            Map<String, Object> server) {

        public Snapshot {
            rows = Map.copyOf(rows);
            categories = Map.copyOf(categories);
            tags = List.copyOf(tags);
            server = Map.copyOf(server);
        }
    }

    /**
     * {@code sync/maindata}'s answer: {@code current} whole when {@code previous} is null, else
     * what changed since it. Keys in qBittorrent's names; an empty change is left out.
     */
    public static Map<String, Object> maindata(@Nullable Snapshot previous, Snapshot current, long rid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rid", rid);
        if (previous == null) {
            out.put("full_update", true);
            out.put("torrents", current.rows());
            out.put("categories", current.categories());
            out.put("tags", current.tags());
            out.put("server_state", current.server());
            return out;
        }
        putIfAny(out, "torrents", changedRows(previous.rows(), current.rows()));
        putIfAny(out, "torrents_removed", removed(previous.rows(), current.rows()));
        putIfAny(out, "categories", changedWhole(previous.categories(), current.categories()));
        putIfAny(out, "categories_removed", removed(previous.categories(), current.categories()));
        putIfAny(out, "tags", added(previous.tags(), current.tags()));
        putIfAny(out, "tags_removed", added(current.tags(), previous.tags()));
        putIfAny(out, "server_state", changedFields(previous.server(), current.server()));
        return out;
    }

    /** {@code sync/torrentPeers}'s answer, peers by {@code ip:port}. */
    public static Map<String, Object> peers(@Nullable Snapshot previous, Snapshot current, long rid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rid", rid);
        if (previous == null) {
            out.put("full_update", true);
            out.put("peers", current.rows());
        } else {
            putIfAny(out, "peers", changedRows(previous.rows(), current.rows()));
            putIfAny(out, "peers_removed", removed(previous.rows(), current.rows()));
        }
        out.put("show_flags", true);
        return out;
    }

    private static void putIfAny(Map<String, Object> out, String key, Object value) {
        boolean empty = value instanceof Map<?, ?> m ? m.isEmpty() : value instanceof List<?> l && l.isEmpty();
        if (!empty) out.put(key, value);
    }

    /** New rows whole; a row that was there, only its fields that changed; an unchanged one not at all. */
    static Map<String, Map<String, Object>> changedRows(
            Map<String, Map<String, Object>> before, Map<String, Map<String, Object>> now) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : now.entrySet()) {
            Map<String, Object> was = before.get(e.getKey());
            Map<String, Object> changed = was == null ? e.getValue() : changedFields(was, e.getValue());
            if (!changed.isEmpty()) out.put(e.getKey(), changed);
        }
        return out;
    }

    /** Categories are sent whole when anything in one changed, as qBittorrent sends them. */
    private static Map<String, Map<String, Object>> changedWhole(
            Map<String, Map<String, Object>> before, Map<String, Map<String, Object>> now) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        now.forEach((k, v) -> {
            if (!v.equals(before.get(k))) out.put(k, v);
        });
        return out;
    }

    static Map<String, Object> changedFields(Map<String, Object> before, Map<String, Object> now) {
        Map<String, Object> out = new LinkedHashMap<>();
        now.forEach((k, v) -> {
            if (!before.containsKey(k) || !Objects.equals(before.get(k), v)) out.put(k, v);
        });
        return out;
    }

    private static List<String> removed(Map<String, ?> before, Map<String, ?> now) {
        List<String> out = new ArrayList<>();
        for (String k : before.keySet()) if (!now.containsKey(k)) out.add(k);
        return out;
    }

    private static List<String> added(List<String> before, List<String> now) {
        List<String> out = new ArrayList<>(now);
        out.removeAll(before);
        return out;
    }

    /**
     * The snapshot each client was last answered with, by key (its session, or its session and a
     * torrent), at most {@link #KEEP} of them, the least recently answered let go first.
     */
    static final class Memory {

        static final int KEEP = 64;

        private record Kept(long rid, Snapshot snapshot) {}

        private final Map<String, Kept> kept = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
                return size() > KEEP;
            }
        };

        private long next = 1;

        /** What {@code rid} answered for {@code key}, if that is what it was last answered with. */
        synchronized Optional<Snapshot> since(String key, long rid) {
            Kept k = kept.get(key);
            return k != null && k.rid() == rid ? Optional.of(k.snapshot()) : Optional.empty();
        }

        /** Remembers {@code snapshot} as {@code key}'s latest; the rid to answer with. */
        synchronized long remember(String key, Snapshot snapshot) {
            long rid = next++;
            kept.put(key, new Kept(rid, snapshot));
            return rid;
        }
    }
}
