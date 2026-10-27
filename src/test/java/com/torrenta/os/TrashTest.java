package com.torrenta.os;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrashTest {

    @Test
    void eachPlatformHasItsOwn() {
        Path home = Path.of("/home/u");
        assertInstanceOf(WindowsTrash.class, Trash.forPlatform("Windows 11", home, null));
        assertInstanceOf(MacTrash.class, Trash.forPlatform("Mac OS X", home, null));
        assertInstanceOf(FreedesktopTrash.class, Trash.forPlatform("Linux", home, null));
    }

    /** The spec's Path key: UTF-8, percent-encoded, slashes kept. */
    @Test
    void thePathIsEncodedAsTheSpecSays() {
        assertEquals(
                "/data/My%20Film%20%5B2024%5D/caf%C3%A9.mkv", FreedesktopTrash.encode("/data/My Film [2024]/café.mkv"));
        assertEquals("a-b_c.d~e/f", FreedesktopTrash.encode("a-b_c.d~e/f"));
        assertEquals("%25%23%3F%26", FreedesktopTrash.encode("%#?&"));
    }

    @Test
    void theInfoFileSaysWhereAndWhen() {
        assertEquals(
                "[Trash Info]\nPath=/d/a%20b\nDeletionDate=2026-10-26T09:05:03\n",
                FreedesktopTrash.info("/d/a b", LocalDateTime.of(2026, 10, 26, 9, 5, 3, 999_000_000)));
    }

    @Test
    void aSecondOfTheSameNameIsNumberedBeforeItsExtension() {
        assertEquals("film.mkv", Volumes.numbered("film.mkv", 1));
        assertEquals("film 2.mkv", Volumes.numbered("film.mkv", 2));
        assertEquals("README 3", Volumes.numbered("README", 3));
        assertEquals(".hidden 2", Volumes.numbered(".hidden", 2));
    }

    /** On the home trash's volume: renamed into files/, with an info file, twice under two names. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileAndAFolderGoToTheHomeTrashWithTheirInfo(@TempDir Path dir) throws Exception {
        Path trashDir = dir.resolve("data/Trash");
        Trash trash = new FreedesktopTrash(trashDir);
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Path film = Files.writeString(downloads.resolve("film.mkv"), "one");
        Path album = Files.createDirectories(downloads.resolve("Album"));
        Files.writeString(album.resolve("01.flac"), "music");

        trash.trash(film);
        trash.trash(album);
        assertFalse(Files.exists(film));
        assertFalse(Files.exists(album));
        assertEquals("one", Files.readString(trashDir.resolve("files/film.mkv")));
        assertEquals("music", Files.readString(trashDir.resolve("files/Album/01.flac")));
        String info = Files.readString(trashDir.resolve("info/film.mkv.trashinfo"), StandardCharsets.UTF_8);
        assertTrue(
                info.startsWith("[Trash Info]\nPath=" + FreedesktopTrash.encode(film.toString()) + "\nDeletionDate="),
                info);
        assertTrue(Files.isRegularFile(trashDir.resolve("info/Album.trashinfo")));
        assertEquals(
                "rwx------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(trashDir)),
                "made owner-only");

        Files.writeString(film, "two");
        trash.trash(film);
        assertEquals("two", Files.readString(trashDir.resolve("files/film 2.mkv")));
        assertTrue(Files.isRegularFile(trashDir.resolve("info/film 2.mkv.trashinfo")));
        assertEquals("one", Files.readString(trashDir.resolve("files/film.mkv")), "the first is untouched");

        trash.trash(downloads.resolve("gone.bin")); // nothing there: nothing to do
        assertEquals(
                List.of("film 2.mkv.trashinfo", "film.mkv.trashinfo", "Album.trashinfo").stream()
                        .sorted()
                        .toList(),
                Files.list(trashDir.resolve("info"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void finderNamesASecondCopyAsFinderDoes(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Trash trash = new MacTrash(home);
        Path file = Files.writeString(dir.resolve("a.txt"), "1");
        trash.trash(file);
        Files.writeString(file, "2");
        trash.trash(file);
        Path folder = Files.createDirectories(dir.resolve("Season.1"));
        trash.trash(folder);
        Files.createDirectories(folder);
        trash.trash(folder);
        assertEquals("1", Files.readString(home.resolve(".Trash/a.txt")));
        assertEquals("2", Files.readString(home.resolve(".Trash/a 2.txt")));
        assertTrue(Files.isDirectory(home.resolve(".Trash/Season.1 2")), "a folder is numbered at its end");
    }

    @Test
    void theRecycleBinScriptTakesThePathAsAParameter() {
        assertEquals(
                List.of(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        Path.of("r.ps1").toString(),
                        "-Path",
                        Path.of("a; rm -rf b").toString()),
                WindowsTrash.argv(Path.of("r.ps1"), Path.of("a; rm -rf b")));
    }
}
