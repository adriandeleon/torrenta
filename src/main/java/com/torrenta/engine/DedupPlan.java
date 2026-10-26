package com.torrenta.engine;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of a new torrent's files are already on disk, complete, in a torrent the list has, and
 * how each would be put where the new torrent will look for it: a hard link when the two places
 * share a volume, else a copy (a reflink where the file system has one). Found by v2 Merkle root,
 * or by v1 key for a file whose pieces are its own ({@link ContentKeys}), so names and torrents
 * do not matter, only bytes; a v1 file that shares pieces with its neighbours has no key.
 *
 * <p>The target is exactly where libtorrent will look at the add: the incomplete folder when there
 * is one (the move to the destination comes after, with every other file), the path the content
 * layout gives, and no {@code .part}, which goes only on files the add's check found incomplete.
 * The add's own check then verifies what was put there, v2 pieces being per file. Nothing already
 * at a target is ever touched, and a torrent is never its own source.
 *
 * <p>Pure but for {@link Disk}, which the tests replace.
 *
 * @param matches one per file that can be had from disk, in the torrent's file order
 */
public record DedupPlan(List<Match> matches) {

    /** No file to be had. */
    public static final DedupPlan NONE = new DedupPlan(List.of());

    public DedupPlan {
        matches = List.copyOf(matches);
    }

    /** How a file gets to its target. */
    public enum Method {
        /** A hard link: the same volume, no bytes written, the two torrents share the file. */
        LINK,
        /** A copy, or a reflink where the file system can: another volume. */
        COPY
    }

    /**
     * One file of the new torrent that is on disk already.
     *
     * @param index its index in the new torrent
     * @param path its path in the new torrent, as the preview has it
     * @param size its size
     * @param source the complete file it is to be had from
     * @param target where the new torrent will look for it, absolute
     * @param method how it gets there
     */
    public record Match(int index, String path, long size, ContentIndex.Entry source, Path target, Method method) {}

    /** What the plan asks of the disk. */
    public interface Disk {
        /** A regular file's size, or -1 when there is none at {@code path}. */
        long size(Path path);

        /** Whether a hard link from {@code source} can be made at {@code target}: one volume. */
        boolean sameVolume(Path source, Path target);

        /** This machine's disks. */
        Disk SYSTEM = new Disk() {
            @Override
            public long size(Path path) {
                try {
                    return Files.isRegularFile(path) ? Files.size(path) : -1;
                } catch (IOException e) {
                    return -1;
                }
            }

            @Override
            public boolean sameVolume(Path source, Path target) {
                try {
                    FileStore from = Files.getFileStore(source);
                    Path existing = target;
                    while (existing != null && !Files.exists(existing)) existing = existing.getParent();
                    return existing != null && from.equals(Files.getFileStore(existing));
                } catch (IOException e) {
                    return false;
                }
            }
        };
    }

    /**
     * The plan for {@code preview}'s wanted files against {@code index}.
     *
     * @param skipped the files the person left out, by index; they are not fetched, from disk or not
     * @param start the folder the torrent is added into: the incomplete folder, else the save path
     * @param layout the content layout chosen, which renames files as the add will
     */
    public static DedupPlan of(
            TorrentPreview preview,
            ContentIndex index,
            Set<Integer> skipped,
            Path start,
            FileRenames.Layout layout,
            Disk disk) {
        Map<Integer, String> paths = new HashMap<>();
        for (TorrentPreview.FileEntry f : preview.files()) paths.put(f.index(), f.path());
        Map<Integer, String> laidOut = FileRenames.layout(paths, preview.name(), layout);
        Path folder = start.toAbsolutePath().normalize();
        List<Match> out = new ArrayList<>();
        for (TorrentPreview.FileEntry f : preview.files()) {
            if (f.size() <= 0 || skipped.contains(f.index())) continue;
            List<ContentIndex.Entry> candidates = new ArrayList<>();
            if (ContentIndex.isRoot(f.root())) candidates.addAll(index.find(f.root()));
            if (ContentKeys.isV1(f.v1Key())) candidates.addAll(index.find(f.v1Key()));
            if (candidates.isEmpty()) continue;
            Path target =
                    folder.resolve(laidOut.getOrDefault(f.index(), f.path())).normalize();
            // A path that leaves the folder is not one libtorrent would write to.
            if (!target.startsWith(folder) || target.equals(folder)) continue;
            // Something is there already: the add's check looks at it as it is.
            if (disk.size(target) >= 0) continue;
            ContentIndex.Entry best = null;
            boolean bestLinks = false;
            for (ContentIndex.Entry e : candidates) {
                if (e.size() != f.size() || e.torrentId().equals(preview.id())) continue;
                if (e.path().equals(target) || disk.size(e.path()) != f.size()) continue;
                boolean links = disk.sameVolume(e.path(), target);
                if (best == null || (links && !bestLinks)) {
                    best = e;
                    bestLinks = links;
                }
                if (bestLinks) break;
            }
            if (best != null)
                out.add(new Match(f.index(), f.path(), f.size(), best, target, bestLinks ? Method.LINK : Method.COPY));
        }
        return out.isEmpty() ? NONE : new DedupPlan(out);
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    /** The bytes that need not be downloaded. */
    public long bytes() {
        return matches.stream().mapToLong(Match::size).sum();
    }

    /** The bytes that would be written by copies: what a copy costs in disk space and time. */
    public long bytesToCopy() {
        return matches.stream()
                .filter(m -> m.method() == Method.COPY)
                .mapToLong(Match::size)
                .sum();
    }
}
