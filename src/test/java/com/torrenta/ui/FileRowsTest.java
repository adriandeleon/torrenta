package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentDetail.FileStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** javafx.base only. */
class FileRowsTest {

    private static FileStatus f(int index, String path, long size, long done) {
        return new FileStatus(index, path, size, done, Priority.NORMAL);
    }

    @Test
    void buildsFoldersAndSumsThem() {
        FileRows rows = new FileRows();
        assertTrue(
                rows.apply(List.of(f(0, "pair/a.bin", 100, 50), f(2, "pair/sub/b.bin", 40, 40))), "first apply builds");
        var root = rows.rootItem();
        assertEquals(1, root.getChildren().size(), "the torrent's root folder");
        var pair = root.getChildren().get(0).getValue();
        assertEquals("pair", pair.name());
        assertEquals(140, pair.sizeProperty().get());
        assertEquals(90, pair.doneProperty().get());
        assertEquals(
                List.of("a.bin", "sub"),
                root.getChildren().get(0).getChildren().stream()
                        .map(i -> i.getValue().name())
                        .toList());
        assertEquals("pair/sub/b.bin", rows.get(2).path());
    }

    @Test
    void sameFilesUpdateInPlace() {
        FileRows rows = new FileRows();
        rows.apply(List.of(f(0, "a.bin", 100, 0)));
        FileRows.Row a = rows.get(0);
        assertFalse(rows.apply(List.of(new FileStatus(0, "a.bin", 100, 70, Priority.HIGH))), "same set: in place");
        assertSame(a, rows.get(0));
        assertEquals(70, a.doneProperty().get());
        assertEquals(Priority.HIGH, a.priorityProperty().get());
        assertEquals(0.7, a.progress(), 1e-9);
    }

    /** Only the files whose status moved are touched, and their folders move by the difference. */
    @Test
    void anUpdateTouchesOnlyWhatChangedAndKeepsTheFolderSums() {
        FileRows rows = new FileRows();
        rows.apply(List.of(
                f(0, "top/a/one.bin", 100, 0),
                f(1, "top/a/two.bin", 50, 0),
                f(2, "top/b/three.bin", 30, 0),
                f(3, "four.bin", 10, 0)));
        assertFalse(rows.apply(List.of(
                f(0, "top/a/one.bin", 100, 0),
                f(1, "top/a/two.bin", 50, 20),
                f(2, "top/b/three.bin", 30, 0),
                f(3, "four.bin", 10, 0))));
        assertEquals(1, rows.changedLastTime(), "one file moved");
        var top = rows.rootItem().getChildren().get(0);
        var a = top.getChildren().get(0).getValue();
        var b = top.getChildren().get(1).getValue();
        assertEquals(20, a.doneProperty().get());
        assertEquals(150, a.sizeProperty().get());
        assertEquals(0, b.doneProperty().get());
        assertEquals(20, top.getValue().doneProperty().get());
        assertEquals(180, top.getValue().sizeProperty().get());

        // A priority alone changes the row, not the sums; the same status again changes nothing.
        rows.apply(List.of(
                f(0, "top/a/one.bin", 100, 0),
                new FileStatus(1, "top/a/two.bin", 50, 20, Priority.HIGH),
                f(2, "top/b/three.bin", 30, 30),
                f(3, "four.bin", 10, 0)));
        assertEquals(2, rows.changedLastTime());
        assertEquals(Priority.HIGH, rows.get(1).priorityProperty().get());
        assertEquals(50, top.getValue().doneProperty().get());
        assertEquals(30, b.doneProperty().get());
        rows.apply(List.of(
                f(0, "top/a/one.bin", 100, 0),
                new FileStatus(1, "top/a/two.bin", 50, 20, Priority.HIGH),
                f(2, "top/b/three.bin", 30, 30),
                f(3, "four.bin", 10, 0)));
        assertEquals(0, rows.changedLastTime());
        assertEquals(50, top.getValue().doneProperty().get());
    }

    @Test
    void aDifferentSetRebuilds() {
        FileRows rows = new FileRows();
        rows.apply(List.of());
        assertTrue(rows.apply(List.of(f(0, "x", 1, 1))), "metadata arrived");
        assertTrue(rows.apply(List.of(f(0, "x", 1, 1), f(1, "y", 1, 0))), "another torrent");
        assertEquals(2, rows.rootItem().getChildren().size());
    }

    @Test
    void aRenamedFileRebuildsTheTree() {
        FileRows rows = new FileRows();
        rows.apply(List.of(f(0, "pair/a.bin", 1, 0), f(1, "pair/b.bin", 1, 0)));
        assertFalse(rows.apply(List.of(f(0, "pair/a.bin", 1, 1), f(1, "pair/b.bin", 1, 0))), "progress only");
        assertTrue(rows.apply(List.of(f(0, "pair/x.bin", 1, 1), f(1, "pair/b.bin", 1, 0))), "a new name");
        assertEquals("pair/x.bin", rows.get(0).path());
    }

    /** Open Folder: a folder is itself, a file its parent, a single-file torrent's the save path. */
    @Test
    void theFolderToOpenIsTheRowsOwnOrItsParent() {
        FileRows rows = new FileRows();
        rows.apply(List.of(f(0, "pair/a.bin", 100, 50), f(1, "pair/sub/b.bin", 40, 40)));
        Path save = Path.of("/dl");
        var pair = rows.rootItem().getChildren().get(0);
        assertEquals(Path.of("/dl/pair"), FileRows.folderOf(save, pair.getValue()));
        assertEquals(Path.of("/dl/pair"), FileRows.folderOf(save, rows.get(0)));
        assertEquals(Path.of("/dl/pair/sub"), FileRows.folderOf(save, rows.get(1)));

        FileRows single = new FileRows();
        single.apply(List.of(f(0, "lone.iso", 10, 10)));
        assertEquals(save, FileRows.folderOf(save, single.get(0)));
    }

    @Test
    void theNearestFolderThatExistsOpensNeverAboveTheSavePath() {
        Path save = Path.of("/data/dl");
        Path wanted = Path.of("/data/dl/pair/sub");
        assertEquals(wanted, FileRows.nearestFolder(save, wanted, p -> true));
        assertEquals(
                Path.of("/data/dl/pair"),
                FileRows.nearestFolder(save, wanted, p -> p.equals(Path.of("/data/dl/pair"))));
        assertEquals(save, FileRows.nearestFolder(save, wanted, p -> false), "nothing there yet");
        assertEquals(save, FileRows.nearestFolder(save, save, p -> false));
        assertEquals(save, FileRows.nearestFolder(save, Path.of("/data/dl/../../etc"), p -> true), "never climbs out");
    }
}
