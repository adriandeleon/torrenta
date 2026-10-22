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

    @Test
    void aDifferentSetRebuilds() {
        FileRows rows = new FileRows();
        rows.apply(List.of());
        assertTrue(rows.apply(List.of(f(0, "x", 1, 1))), "metadata arrived");
        assertTrue(rows.apply(List.of(f(0, "x", 1, 1), f(1, "y", 1, 0))), "another torrent");
        assertEquals(2, rows.rootItem().getChildren().size());
    }

    @Test
    void aFolderOpensItselfAndAFileItsParent() {
        FileRows rows = new FileRows();
        rows.apply(List.of(f(0, "pair/sub/b.bin", 40, 0), f(1, "top.bin", 10, 0)));
        assertEquals("pair/sub", FileRows.folderOf(rows.get(0)));
        assertEquals("", FileRows.folderOf(rows.get(1)), "a file at the top: the save path itself");
        var pair = rows.rootItem().getChildren().get(0).getValue();
        assertEquals("pair", FileRows.folderOf(pair));
    }

    @Test
    void theNearestFolderThatExistsOpensNeverAboveTheSavePath() {
        Path save = Path.of("/data/dl");
        assertEquals(Path.of("/data/dl/pair/sub"), FileRows.nearestFolder(save, "pair/sub", p -> true));
        assertEquals(
                Path.of("/data/dl/pair"),
                FileRows.nearestFolder(save, "pair/sub", p -> p.equals(Path.of("/data/dl/pair"))));
        assertEquals(save, FileRows.nearestFolder(save, "pair/sub", p -> false), "nothing there yet");
        assertEquals(save, FileRows.nearestFolder(save, "", p -> false));
        assertEquals(save, FileRows.nearestFolder(save, "../../etc", p -> true), "never climbs out");
    }
}
