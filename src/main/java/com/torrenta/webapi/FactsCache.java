package com.torrenta.webapi;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;

/**
 * Each torrent's {@link QbitTorrent.Facts} and limits, read through the detail once and kept: a
 * detail is a session call with the pieces (an RPC on a daemon), and the *arr apps ask for the
 * whole list every minute. Read again when the name or save path changed (a rename, a move), while
 * the metadata is still missing, and after {@link #MAX_AGE_MILLIS}, for the time active and limits
 * a person changed. Thread-safe.
 */
final class FactsCache {

    static final long MAX_AGE_MILLIS = 60_000;

    record Known(QbitTorrent.Facts facts, TorrentLimits limits) {}

    private record Entry(Known known, String savePath, String name, long readAt) {}

    private final WebApi.Backend backend;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    FactsCache(WebApi.Backend backend) {
        this.backend = backend;
    }

    Known get(Torrent t, long now) {
        Entry e = entries.get(t.id());
        if (e != null
                && e.known().facts().metadata()
                && e.savePath().equals(t.savePath())
                && e.name().equals(t.name())
                && now - e.readAt() < MAX_AGE_MILLIS) return e.known();
        Optional<TorrentDetail> detail = backend.detail(t.id());
        QbitTorrent.Facts facts = detail.map(d -> QbitTorrent.Facts.of(t, d, backend.magnet(t.id())))
                .orElseGet(() -> QbitTorrent.Facts.unknown(t));
        Known known = new Known(facts, backend.limits(t.id()));
        entries.put(t.id(), new Entry(known, t.savePath(), t.name(), now));
        return known;
    }

    /** Forgets the torrents no longer in the list. */
    void retain(Collection<Torrent> torrents) {
        Set<String> ids = new HashSet<>();
        for (Torrent t : torrents) ids.add(t.id());
        entries.keySet().retainAll(ids);
    }
}
