package com.torrenta.engine.libtorrent;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.torrent_handle;

/**
 * A torrent's {@code torrent_info} read while libtorrent cannot free it. libtorrent4j 2.1.0-39 has
 * no owning way to it from a handle: {@code torrent_file_ptr()} is {@code torrent_file().get()},
 * the {@code shared_ptr} dropped on return, so the info and every {@code FileStorage} read through
 * it live exactly as long as the torrent does, and no fence of ours holds them. A {@code
 * remove_torrent} is only posted; libtorrent's network thread then destroys the torrent whenever it
 * gets to it, under whatever read another thread is making (the alert thread's {@code
 * indexContent}, in {@code file_storage::file_path}, on macOS CI). The metadata's arrival is no
 * hazard: {@code get_torrent_file()} hands out nothing until the info is valid, and the valid one
 * is never replaced.
 *
 * <p>So every read holds the read side, and every remove takes the write side, marks the handle and
 * posts the remove under it: a read in flight finishes first, and none starts on that torrent
 * after. Nothing inside a read may remove a torrent (the write side would wait on the read itself),
 * and a read does nothing but read: events, the engine's lock and removals come after it.
 */
final class InfoReads {

    /** What a read does with the info; the info and its file storage are valid only inside it. */
    @FunctionalInterface
    interface Read<T, E extends Exception> {
        T apply(TorrentInfo info) throws E;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Handles removed whose torrent may still be alive, as the session's own copies. Written under
     * the write side, read under the read side; one goes once its handle has expired, which is the
     * torrent destroyed, after which the handle hands out no info at all.
     */
    private final List<torrent_handle> removing = new ArrayList<>();

    /**
     * {@code read} applied to the torrent's info, or {@code none} when it has none: no metadata yet,
     * a handle that has gone, or a torrent whose removal is posted.
     */
    <T, E extends Exception> T read(TorrentHandle h, T none, Read<T, E> read) throws E {
        lock.readLock().lock();
        try {
            if (removed(h.swig())) return none;
            TorrentInfo info = h.torrentFile();
            if (info == null || !info.isValid()) return none;
            try {
                return read.apply(info);
            } finally {
                Reference.reachabilityFence(info);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Posts the removal of {@code h} once no read is in flight, and keeps every later read off it.
     * {@code h} must be the session's own copy (from {@code find_torrent}), never an alert's, which
     * points into the alert.
     */
    void remove(TorrentHandle h, Runnable post) {
        if (lock.getReadHoldCount() > 0) throw new IllegalStateException("a torrent removed inside a read of its info");
        lock.writeLock().lock();
        try {
            removing.removeIf(gone -> !gone.is_valid());
            removing.add(h.swig());
            post.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean removed(torrent_handle about) {
        for (torrent_handle gone : removing) if (gone.eq(about)) return true;
        return false;
    }
}
