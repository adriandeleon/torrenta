package com.torrenta.ui;

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
}
