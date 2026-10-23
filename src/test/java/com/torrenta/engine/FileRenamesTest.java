package com.torrenta.engine;

import java.util.Map;

import com.torrenta.engine.FileRenames.Layout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRenamesTest {

    private static final Map<Integer, String> PAIR = Map.of(0, "pair/a.bin", 2, "pair/sub/b.bin", 3, "pair/sub/c.bin");

    @Test
    void aFileKeepsItsFolderAndAFolderTakesEverythingUnderIt() {
        assertEquals(Map.of(0, "pair/x.bin"), FileRenames.plan(PAIR, "pair/a.bin", " x.bin "));
        assertEquals(Map.of(2, "pair/deep/b.bin", 3, "pair/deep/c.bin"), FileRenames.plan(PAIR, "pair/sub", "deep"));
        assertEquals(
                Map.of(0, "top/a.bin", 2, "top/sub/b.bin", 3, "top/sub/c.bin"),
                FileRenames.plan(PAIR, "pair", "top"),
                "the root folder");
        assertEquals(Map.of(), FileRenames.plan(PAIR, "pair/su", "x"), "a prefix is not a folder");
    }

    @Test
    void aNameThatWillNotDoSaysWhy() {
        assertTrue(FileRenames.problem(PAIR, "pair/a.bin", "x.bin").isEmpty());
        assertTrue(FileRenames.problem(PAIR, "pair/a.bin", "  ").isPresent(), "empty");
        assertTrue(FileRenames.problem(PAIR, "pair/a.bin", "a/b").isPresent(), "a separator");
        assertTrue(FileRenames.problem(PAIR, "pair/a.bin", "..").isPresent());
        assertTrue(FileRenames.problem(PAIR, "pair/a.bin", "sub").isPresent(), "a folder is there");
        assertTrue(FileRenames.problem(PAIR, "pair/sub/b.bin", "c.bin").isPresent(), "a file is there");
        assertTrue(FileRenames.problem(PAIR, "pair/gone.bin", "x").isPresent(), "nothing to rename");
        assertTrue(FileRenames.problem(PAIR, "pair/a.bin", "a.bin").isEmpty(), "its own name is no clash");
    }

    @Test
    void layoutsTakeTheRootAwayOrPutOneThere() {
        assertEquals(
                Map.of(0, "a.bin", 2, "sub/b.bin", 3, "sub/c.bin"),
                FileRenames.layout(PAIR, "pair", Layout.NO_SUBFOLDER));
        assertEquals(Map.of(), FileRenames.layout(PAIR, "pair", Layout.SUBFOLDER), "it has one");
        assertEquals(Map.of(), FileRenames.layout(PAIR, "pair", Layout.ORIGINAL));
        Map<Integer, String> single = Map.of(0, "movie.mkv");
        assertEquals(Map.of(0, "movie/movie.mkv"), FileRenames.layout(single, "movie.mkv", Layout.SUBFOLDER));
        assertEquals(Map.of(), FileRenames.layout(single, "movie.mkv", Layout.NO_SUBFOLDER), "it has none");
        assertEquals(
                Map.of(),
                FileRenames.layout(Map.of(0, "a/x", 1, "b/y"), "t", Layout.NO_SUBFOLDER),
                "two roots are not one");
        assertEquals(".mkv", FileRenames.stem(".mkv"), "a dot first is no extension");
        assertEquals("archive.tar", FileRenames.stem("archive.tar.gz"));
        assertEquals(Layout.NO_SUBFOLDER, Layout.byName("no_subfolder"));
        assertEquals(Layout.ORIGINAL, Layout.byName("nonsense"));
    }
}
