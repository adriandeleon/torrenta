package com.torrenta.session;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrashPlanTest {

    private static final Path SAVE = Path.of("/data/downloads");

    private static List<Path> entries(String... files) {
        return TrashPlan.entries(new Engine.OnDisk(
                SAVE, java.util.Arrays.stream(files).map(SAVE::resolve).toList(), List.of()));
    }

    @Test
    void aTorrentsFolderGoesAsOneItem() {
        assertEquals(List.of(SAVE.resolve("Album")), entries("Album/01.flac", "Album/art/cover.jpg", "Album/02.flac"));
    }

    @Test
    void aTorrentWithoutItsFolderGoesFileByFile() {
        assertEquals(List.of(SAVE.resolve("film.mkv"), SAVE.resolve("film.srt")), entries("film.mkv", "film.srt"));
    }

    @Test
    void theSavePathIsNeverAnItemAndAFileElsewhereIsItself() {
        Path elsewhere = Path.of("/other/x.bin");
        List<Path> files = List.of(SAVE, SAVE.resolve("a/b.bin"), elsewhere);
        assertEquals(
                List.of(SAVE.resolve("a"), elsewhere), TrashPlan.entries(new Engine.OnDisk(SAVE, files, List.of())));
        assertEquals(
                List.of(),
                TrashPlan.entries(new Engine.OnDisk(SAVE, List.of(), List.of())),
                "a magnet without metadata");
    }

    @Test
    void anEntryInsideAnotherIsLeftToIt() {
        Path outside = Path.of("/other");
        List<Path> files = List.of(outside.resolve("deep/x.bin"), outside);
        assertEquals(List.of(outside), TrashPlan.entries(new Engine.OnDisk(SAVE, files, List.of())));
    }
}
