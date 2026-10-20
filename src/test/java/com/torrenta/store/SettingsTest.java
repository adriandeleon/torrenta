package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void toolbarShowsUnlessTurnedOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Settings settings = new Settings(file);
        settings.load();
        assertTrue(settings.toolbar(), "shown by default");
        settings.setToolbar(false);
        settings.save();
        Settings read = new Settings(file);
        read.load();
        assertFalse(read.toolbar());
    }

    @Test
    void themeIsAStringTheUiInterprets(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Settings settings = new Settings(file);
        settings.load();
        assertEquals("", settings.theme(), "unset: the UI picks its default");
        settings.setTheme("nord-light");
        settings.save();
        Settings read = new Settings(file);
        read.load();
        assertEquals("nord-light", read.theme());
        Files.writeString(file, Settings.THEME + "=  cupertino-dark \n");
        read.load();
        assertEquals("cupertino-dark", read.theme(), "trimmed");
    }

    @Test
    void booleansAreLenientAndResettable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(
                file,
                Settings.UPDATE_CHECK + "=False\n" + Settings.ADD_START_PAUSED + "=yes\n" + Settings.ADD_SEQUENTIAL
                        + "=\n");
        Settings settings = new Settings(file);
        settings.load();
        assertFalse(settings.updateCheck(), "any spelling of false is false");
        assertTrue(settings.addStartPaused(), "anything else is true");
        assertFalse(settings.addSequential(), "blank is the default");
        settings.reset(Settings.UPDATE_CHECK);
        assertTrue(settings.updateCheck(), "reset means the default again");
    }

    @Test
    void connectionSettingsAreClamped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(
                file,
                String.join(
                        "\n",
                        Settings.CONN_PORT + "=80", // below 1024: default
                        Settings.CONN_MAX_CONNECTIONS + "=abc", // unreadable: default
                        Settings.CONN_UPLOAD_SLOTS + "=50",
                        Settings.PROXY_TYPE + "=socks5", // case does not matter
                        Settings.PROXY_PORT + "=70000", // above 65535: default
                        Settings.CONN_INTERFACE + "=  tun0 ",
                        ""));
        Settings settings = new Settings(file);
        settings.load();
        var e = settings.engineSettings();
        assertEquals(6881, e.listenPort());
        assertEquals(500, e.maxConnections());
        assertEquals(50, e.uploadSlots());
        assertEquals(com.torrenta.engine.EngineSettings.ProxyType.SOCKS5, e.proxy());
        assertEquals(1080, e.proxyPort());
        assertEquals("tun0", e.listenInterface());
        assertTrue(e.upnp(), "untouched keys are the defaults");
        assertTrue(e.protocol().dht(), "protocol defaults too");
        assertEquals(
                com.torrenta.engine.EngineSettings.Protocol.Encryption.PREFER,
                e.protocol().encryption());
    }

    @Test
    void protocolSettingsAreRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(
                file,
                String.join(
                        "\n",
                        Settings.PROTO_DHT + "=false",
                        Settings.PROTO_ENCRYPTION + "=require",
                        Settings.PROTO_DEFAULT_TRACKERS
                                + "=  udp://a.example.org:1/announce   udp://b.example.org:2/announce udp://a.example.org:1/announce ",
                        ""));
        Settings settings = new Settings(file);
        settings.load();
        var p = settings.engineSettings().protocol();
        assertFalse(p.dht());
        assertTrue(p.pex(), "untouched: default");
        assertEquals(com.torrenta.engine.EngineSettings.Protocol.Encryption.REQUIRE, p.encryption());
        assertEquals(
                List.of("udp://a.example.org:1/announce", "udp://b.example.org:2/announce"),
                p.defaultTrackers(),
                "split, trimmed, deduplicated");
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
