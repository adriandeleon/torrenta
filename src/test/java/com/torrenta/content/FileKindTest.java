package com.torrenta.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileKindTest {

    @ParameterizedTest
    @CsvSource({
        "Movie.2024.1080p.mkv, VIDEO",
        "clip.MP4, VIDEO",
        "01 - Track.flac, AUDIO",
        "cover.JPG, IMAGE",
        "John Byrne's Next Men 06 Lies.cbr, BOOK",
        "novel.epub, BOOK",
        "README.txt, DOCUMENT",
        "release.nfo, DOCUMENT",
        "Movie.en.srt, SUBTITLE",
        "pack.part1.rar, ARCHIVE",
        "pack.r00, ARCHIVE",
        "pack.7z.001, ARCHIVE",
        "backup.tar.gz, ARCHIVE",
        "tails-amd64-7.15.iso, DISC",
        "crise_meta.sqlite, DATA",
        "crise_meta.xml, DATA",
        "setup.exe, PROGRAM",
        "Something.AppImage, PROGRAM",
        "Makefile, OTHER",
        ".hidden, OTHER",
        "trailing., OTHER",
        "notes.weird, OTHER",
    })
    void aFileIsKnownByItsLastExtension(String name, FileKind expected) {
        assertEquals(expected, FileKind.of(name, false));
    }

    @Test
    void aFolderIsAFolderWhateverItsName() {
        assertEquals(FileKind.FOLDER, FileKind.of("Season.1.mkv", true));
    }

    @Test
    void extensionIsLowerCaseAndEmptyWhenThereIsNone() {
        assertEquals("mkv", FileKind.extension("A.B.MKV"));
        assertEquals("", FileKind.extension("Makefile"));
        assertEquals("", FileKind.extension(".bashrc"));
        assertEquals("", FileKind.extension("dot."));
    }
}
