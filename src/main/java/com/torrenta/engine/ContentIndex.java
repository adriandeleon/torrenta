package com.torrenta.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The complete files the torrents in the list have on disk, by their BitTorrent v2 Merkle root
 * ({@code pieces root}, SHA-256 over the file's own 16 KiB blocks), and by their {@link
 * ContentKeys#v1 v1 key} where their pieces are their own: two files with the same key have the
 * same bytes, whatever torrent and name they came with. What a new torrent's dedup is looked up
 * in. A hybrid's file is there under both.
 *
 * <p>Only complete files are indexed: the torrent's
 * whole entry is replaced when a file of it completes, when it finishes, is checked (a recheck
 * that finds a file damaged takes it out), renamed or moved, and dropped when it is removed. It
 * is never written down: the roots are in the torrents' own metadata, and libtorrent says each
 * whole file again as it loads a torrent, so it is built again at every start. Thread-safe; the
 * engine keeps it from its alert thread.
 */
public final class ContentIndex {

    /** No torrent's files, ever: what an engine that keeps none hands out. It takes nothing. */
    public static final ContentIndex EMPTY = new ContentIndex(true);

    /**
     * One complete file.
     *
     * @param root its key: a v2 Merkle root, 64 hex digits, or a {@link ContentKeys#v1 v1 key}
     * @param torrentId the torrent it belongs to
     * @param index its index in that torrent
     * @param path where it is on disk now, absolute
     * @param size its size in bytes
     */
    public record Entry(String root, String torrentId, int index, Path path, long size) {
        public Entry {
            root = normal(root);
        }
    }

    private final Map<String, List<Entry>> byRoot = new HashMap<>();
    private final Map<String, List<Entry>> byTorrent = new HashMap<>();
    private final boolean frozen;

    public ContentIndex() {
        this(false);
    }

    private ContentIndex(boolean frozen) {
        this.frozen = frozen;
    }

    /** The torrent's complete files, replacing whatever was known of it; none forgets it. */
    public synchronized void put(String torrentId, List<Entry> files) {
        if (frozen) return;
        forget(torrentId);
        List<Entry> kept = new ArrayList<>();
        for (Entry e : files) {
            if (!isKey(e.root()) || !e.torrentId().equals(torrentId) || e.size() <= 0) continue;
            kept.add(e);
            byRoot.computeIfAbsent(e.root(), k -> new ArrayList<>()).add(e);
        }
        if (!kept.isEmpty()) byTorrent.put(torrentId, kept);
    }

    /** The torrent is gone, or no longer has a file worth sharing. */
    public synchronized void remove(String torrentId) {
        forget(torrentId);
    }

    private void forget(String torrentId) {
        List<Entry> old = byTorrent.remove(torrentId);
        if (old == null) return;
        for (Entry e : old) {
            List<Entry> same = byRoot.get(e.root());
            if (same == null) continue;
            same.remove(e);
            if (same.isEmpty()) byRoot.remove(e.root());
        }
    }

    /** Every complete file with this root, in the order the torrents were indexed. */
    public synchronized List<Entry> find(String root) {
        List<Entry> same = byRoot.get(normal(root));
        return same == null ? List.of() : List.copyOf(same);
    }

    /** What is known of one torrent: one entry per file, a hybrid's by its v2 root. */
    public synchronized List<Entry> of(String torrentId) {
        List<Entry> files = byTorrent.get(torrentId);
        if (files == null) return List.of();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        return files.stream().filter(e -> seen.add(e.index())).toList();
    }

    /** How many files are indexed, over every torrent; a file under two keys counts once. */
    public synchronized int size() {
        int n = 0;
        for (String id : byTorrent.keySet()) n += of(id).size();
        return n;
    }

    /** A v2 root as this index keys it: 64 hex digits, not all zeros (libtorrent's "no root"). */
    public static boolean isRoot(String root) {
        String r = normal(root);
        return r.length() == 64 && r.chars().allMatch(c -> Character.digit(c, 16) >= 0) && !r.matches("0+");
    }

    /** A key this index takes: a v2 root, or a v1 key. */
    public static boolean isKey(String key) {
        return isRoot(key) || ContentKeys.isV1(key);
    }

    private static String normal(String root) {
        return root == null ? "" : root.trim().toLowerCase(Locale.ROOT);
    }
}
