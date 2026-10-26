package com.torrenta.engine;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupPlanTest {

    static final String R1 = "1".repeat(64);
    static final String R2 = "2".repeat(64);
    static final String R3 = "3".repeat(64);

    /** A disk of files by path, and which top folders share a volume. */
    static final class FakeDisk implements DedupPlan.Disk {
        final Map<Path, Long> files = new HashMap<>();
        /** The first segment of each path names its volume. */
        @Override
        public long size(Path path) {
            return files.getOrDefault(path, -1L);
        }

        @Override
        public boolean sameVolume(Path source, Path target) {
            return volume(source).equals(volume(target));
        }

        private static String volume(Path p) {
            return p.toAbsolutePath().getName(0).toString();
        }

        FakeDisk with(Path path, long size) {
            files.put(path, size);
            return this;
        }
    }

    private static Path abs(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    private static TorrentPreview preview(String id, String name, List<TorrentPreview.FileEntry> files) {
        long size = files.stream().mapToLong(TorrentPreview.FileEntry::size).sum();
        return new TorrentPreview(id, name, size, files, false, List.of(), "", "", 0, true, true);
    }

    /** "Album" here: a.bin (R1, 10), b.bin (R2, 20), c.bin (R3, 30), only a and b in the index. */
    private static ContentIndex index() {
        ContentIndex index = new ContentIndex();
        index.put(
                "old",
                List.of(
                        new ContentIndex.Entry(R1, "old", 0, abs("/disk/seeds/Old/first.bin"), 10),
                        new ContentIndex.Entry(R2, "old", 1, abs("/disk/seeds/Old/second.bin"), 20)));
        return index;
    }

    private static TorrentPreview album() {
        return preview(
                "new",
                "Album",
                List.of(
                        new TorrentPreview.FileEntry(0, "Album/a.bin", 10, R1),
                        new TorrentPreview.FileEntry(1, "Album/b.bin", 20, R2),
                        new TorrentPreview.FileEntry(2, "Album/c.bin", 30, R3)));
    }

    private static FakeDisk sources() {
        return new FakeDisk().with(abs("/disk/seeds/Old/first.bin"), 10).with(abs("/disk/seeds/Old/second.bin"), 20);
    }

    @Test
    void theFilesOnDiskAreLinkedWhereTheAddWillLookForThem() {
        DedupPlan plan = DedupPlan.of(
                album(), index(), Set.of(), Path.of("/disk/downloads"), FileRenames.Layout.ORIGINAL, sources());
        assertEquals(2, plan.matches().size(), "c.bin is nowhere: downloaded as ever");
        DedupPlan.Match a = plan.matches().getFirst();
        assertEquals(0, a.index());
        assertEquals(abs("/disk/seeds/Old/first.bin"), a.source().path(), "found by root, whatever its name");
        assertEquals(abs("/disk/downloads/Album/a.bin"), a.target());
        assertEquals(DedupPlan.Method.LINK, a.method(), "one volume: a hard link");
        assertEquals(30, plan.bytes());
        assertEquals(0, plan.bytesToCopy());
    }

    @Test
    void anotherVolumeIsACopyAndItsBytesAreCounted() {
        DedupPlan plan = DedupPlan.of(
                album(), index(), Set.of(), Path.of("/other/downloads"), FileRenames.Layout.ORIGINAL, sources());
        assertTrue(plan.matches().stream().allMatch(m -> m.method() == DedupPlan.Method.COPY));
        assertEquals(30, plan.bytesToCopy());
    }

    @Test
    void aSourceOnTheTargetsVolumeIsPreferred() {
        ContentIndex index = index();
        index.put("near", List.of(new ContentIndex.Entry(R1, "near", 0, abs("/other/keep/a.bin"), 10)));
        FakeDisk disk = sources().with(abs("/other/keep/a.bin"), 10);
        DedupPlan plan = DedupPlan.of(
                album(), index, Set.of(1, 2), Path.of("/other/downloads"), FileRenames.Layout.ORIGINAL, disk);
        DedupPlan.Match a = plan.matches().getFirst();
        assertEquals("near", a.source().torrentId(), "the second copy, because it can be linked");
        assertEquals(DedupPlan.Method.LINK, a.method());
    }

    @Test
    void theTargetFollowsTheLayoutAndTheIncompleteFolder() {
        DedupPlan flat = DedupPlan.of(
                album(), index(), Set.of(), Path.of("/disk/incomplete"), FileRenames.Layout.NO_SUBFOLDER, sources());
        assertEquals(
                abs("/disk/incomplete/a.bin"),
                flat.matches().getFirst().target(),
                "no subfolder, in the incomplete folder: where the add's check looks");
        TorrentPreview single =
                preview("one", "Movie.mkv", List.of(new TorrentPreview.FileEntry(0, "Movie.mkv", 10, R1)));
        DedupPlan wrapped =
                DedupPlan.of(single, index(), Set.of(), Path.of("/disk/dl"), FileRenames.Layout.SUBFOLDER, sources());
        assertEquals(
                abs("/disk/dl/Movie/Movie.mkv"), wrapped.matches().getFirst().target());
    }

    @Test
    void whatCannotBeTrustedOrIsNotWantedIsLeftToTheDownload() {
        // Skipped by the person.
        assertEquals(
                List.of(1),
                DedupPlan.of(album(), index(), Set.of(0), Path.of("/disk/dl"), FileRenames.Layout.ORIGINAL, sources())
                        .matches()
                        .stream()
                        .map(DedupPlan.Match::index)
                        .toList());
        // The source is gone from disk, or changed size since it was indexed.
        FakeDisk changed = new FakeDisk().with(abs("/disk/seeds/Old/second.bin"), 21);
        assertTrue(DedupPlan.of(album(), index(), Set.of(), Path.of("/disk/dl"), FileRenames.Layout.ORIGINAL, changed)
                .isEmpty());
        // Something is at the target already: never written over, the add's check judges it.
        FakeDisk occupied = sources().with(abs("/disk/dl/Album/a.bin"), 3);
        assertEquals(
                List.of(1),
                DedupPlan.of(album(), index(), Set.of(), Path.of("/disk/dl"), FileRenames.Layout.ORIGINAL, occupied)
                        .matches()
                        .stream()
                        .map(DedupPlan.Match::index)
                        .toList());
        // A v1-only torrent has no roots; a size that differs is not the same file.
        TorrentPreview v1 = preview("v1", "Album", List.of(new TorrentPreview.FileEntry(0, "Album/a.bin", 10)));
        assertTrue(DedupPlan.of(v1, index(), Set.of(), Path.of("/disk/dl"), FileRenames.Layout.ORIGINAL, sources())
                .isEmpty());
        TorrentPreview bigger =
                preview("big", "Album", List.of(new TorrentPreview.FileEntry(0, "Album/a.bin", 11, R1)));
        assertTrue(DedupPlan.of(bigger, index(), Set.of(), Path.of("/disk/dl"), FileRenames.Layout.ORIGINAL, sources())
                .isEmpty());
    }

    @Test
    void aTorrentIsNeverItsOwnSourceNorAFileItsOwnTarget() {
        TorrentPreview again = preview("old", "Old", List.of(new TorrentPreview.FileEntry(0, "Old/first.bin", 10, R1)));
        assertTrue(
                DedupPlan.of(again, index(), Set.of(), Path.of("/elsewhere"), FileRenames.Layout.ORIGINAL, sources())
                        .isEmpty(),
                "the same torrent added again");
        TorrentPreview samePlace =
                preview("other", "Old", List.of(new TorrentPreview.FileEntry(0, "Old/first.bin", 10, R1)));
        FakeDisk disk = new FakeDisk(); // the target is the source, but say it is not there
        disk.files.put(abs("/disk/seeds/Old/first.bin"), 10L);
        DedupPlan plan =
                DedupPlan.of(samePlace, index(), Set.of(), Path.of("/disk/seeds"), FileRenames.Layout.ORIGINAL, disk);
        assertTrue(plan.isEmpty(), "the file is there already, as itself");
    }
}
