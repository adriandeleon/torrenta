package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void sidebarShowsUnlessTurnedOffAndItsFilterIsAStringTheUiParses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Settings settings = new Settings(file);
        settings.load();
        assertTrue(settings.sidebar(), "shown by default");
        for (String axis : Settings.SIDEBAR_AXES)
            assertEquals("", settings.sidebarChoice(axis), "nothing chosen is All to the UI");
        assertTrue(settings.sidebarCollapsed().isEmpty(), "every section open by default");
        settings.setSidebar(false);
        settings.setSidebarChoice("category", "category:Linux ISOs");
        settings.setSidebarChoice("status", " status:seeding ");
        settings.setSidebarCollapsed(List.of("tracker", "bogus", "tag"));
        settings.save();
        Settings read = new Settings(file);
        read.load();
        assertFalse(read.sidebar());
        assertEquals("category:Linux ISOs", read.sidebarChoice("category"));
        assertEquals("status:seeding", read.sidebarChoice("status"), "trimmed");
        assertEquals("", read.sidebarChoice("tag"));
        assertEquals(List.of("tag", "tracker"), List.copyOf(read.sidebarCollapsed()), "known axes, in their order");
        assertThrows(IllegalArgumentException.class, () -> read.sidebarChoice("colour"), "not an axis");
    }

    @Test
    void sidebarCollapsedDropsWhatIsNotASection(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(file, "ui.sidebar.collapsed=Status, nonsense,,TAG\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(List.of("status", "tag"), List.copyOf(settings.sidebarCollapsed()));
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
    void speedSettingsAreRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(
                file,
                String.join(
                        "\n",
                        Settings.SPEED_DOWNLOAD + "=2048",
                        Settings.SPEED_SCHEDULE_FROM + "=23:15",
                        Settings.SPEED_SCHEDULE_TO + "=25:00", // unreadable: default
                        Settings.SPEED_SCHEDULE_DAYS + "=mon, TUESDAY ,nonsense,sat",
                        ""));
        Settings settings = new Settings(file);
        settings.load();
        var sp = settings.engineSettings().speed();
        assertEquals(2048, sp.download());
        assertEquals(0, sp.upload());
        assertEquals(23 * 60 + 15, sp.scheduleFrom());
        assertEquals(8 * 60, sp.scheduleTo());
        assertEquals(
                java.util.EnumSet.of(
                        java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.SATURDAY),
                sp.scheduleDays());
        assertEquals(
                "Mon,Tue,Sat".toUpperCase().replace("MON", "MON"),
                Settings.formatDays(sp.scheduleDays()).toUpperCase());
        assertEquals("07:05", Settings.formatMinutes(7 * 60 + 5));
        assertTrue(Settings.parseMinutes("24:00").isEmpty());
    }

    @Test
    void queueAndShareSettingsAreReadAndClamped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Files.writeString(
                file,
                String.join(
                        "\n",
                        Settings.QUEUE_ACTIVE_DOWNLOADS + "=0",
                        Settings.QUEUE_ACTIVE_SEEDS + "=-3", // outside the range: default
                        Settings.QUEUE_IGNORE_SLOW + "=false",
                        Settings.SHARE_RATIO + "=1.5",
                        Settings.SHARE_SEED_MINUTES + "=90",
                        Settings.SHARE_ACTION + "=remove",
                        ""));
        Settings settings = new Settings(file);
        settings.load();
        var q = settings.engineSettings().queue();
        assertEquals(0, q.activeDownloads(), "0 is no limit");
        assertEquals(5, q.activeSeeds());
        assertEquals(15, q.activeLimit());
        assertFalse(q.ignoreSlowTorrents());
        assertEquals(
                new com.torrenta.engine.ShareLimit(1.5, 90, com.torrenta.engine.ShareLimit.Action.REMOVE), q.share());

        Files.writeString(file, Settings.SHARE_RATIO + "=lots\n" + Settings.SHARE_ACTION + "=explode\n");
        settings.load();
        assertEquals(com.torrenta.engine.ShareLimit.NONE, settings.shareLimit(), "unreadable: none, pause");
    }

    @Test
    void aTorrentsOwnShareLimitRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Settings settings = new Settings(file);
        settings.load();
        assertTrue(settings.shareOverride("abc").isEmpty());
        var own = new com.torrenta.engine.ShareLimit(3, 0, com.torrenta.engine.ShareLimit.Action.PAUSE);
        settings.setShareOverride("abc", own);
        settings.setShareOverride("def", com.torrenta.engine.ShareLimit.NONE);
        settings.save();
        Settings read = new Settings(file);
        read.load();
        assertEquals(java.util.Optional.of(own), read.shareOverride("abc"));
        assertEquals(java.util.Map.of("abc", own, "def", com.torrenta.engine.ShareLimit.NONE), read.shareOverrides());
        assertTrue(read.syncedEntries().keySet().stream().noneMatch(k -> k.startsWith("torrent.")), "never synced");
        read.setShareOverride("abc", null);
        assertTrue(read.shareOverride("abc").isEmpty(), "forgotten");
        assertEquals(1, read.shareOverrides().size());
    }

    @Test
    void addWithoutAskingIsOptInAndFilesToPickAreMachineLocal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE_NAME);
        Settings settings = new Settings(file);
        settings.load();
        assertFalse(settings.addWithoutAsking(), "the dialog opens unless asked not to");
        assertFalse(settings.filesToPick("abc"));
        settings.setAddWithoutAsking(true);
        settings.setFilesToPick("abc", true);
        settings.save();
        Settings read = new Settings(file);
        read.load();
        assertTrue(read.addWithoutAsking());
        assertTrue(read.filesToPick("abc"));
        assertTrue(read.syncedEntries().containsKey(Settings.ADD_WITHOUT_ASKING), "a preference, so it syncs");
        assertTrue(read.syncedEntries().keySet().stream().noneMatch(k -> k.startsWith("torrent.")), "never synced");
        read.setFilesToPick("abc", false);
        assertFalse(read.filesToPick("abc"), "forgotten once chosen");
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
