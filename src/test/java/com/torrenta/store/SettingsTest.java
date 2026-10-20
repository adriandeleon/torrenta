package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @Test
    void missingFileMeansDefaults(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("none").resolve(Settings.FILE_NAME));
        settings.load();
        assertEquals(Settings.defaultDownloadDir(), settings.downloadDir());
    }

    @Test
    void roundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cfg").resolve(Settings.FILE_NAME);
        Settings written = new Settings(file);
        written.downloadDir(dir.resolve("dl"));
        written.save();
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.list(file.getParent()).anyMatch(p -> p.toString().endsWith(".part")), "no temp file left");

        Settings read = new Settings(file);
        read.load();
        assertEquals(dir.resolve("dl").toAbsolutePath(), read.downloadDir());
    }

    @Test
    void aBlankValueFallsBackToTheDefault(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(file, Settings.DOWNLOAD_DIR + "=\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(Settings.defaultDownloadDir(), settings.downloadDir());
    }
}
