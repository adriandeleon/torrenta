package com.torrenta.webapi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.ShareLimit;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** app/setPreferences onto Settings, in qBittorrent's units, and the two refusals. */
class PreferencesTest {

    private final Settings settings = new FakeBackend().store;
    private final Path downloads = FakeBackend.abs("/srv/downloads");

    private Preferences.Applied apply(String json) throws IOException {
        return Preferences.apply(Preferences.parse(json), settings, List.of(downloads), downloads);
    }

    @Test
    void limitsAreBytesHereAndKibThereRoundedSoTheyNeverLoosen() throws IOException {
        apply("{\"dl_limit\":1500,\"up_limit\":0,\"alt_dl_limit\":1024,\"alt_up_limit\":-1}");
        EngineSettings.Speed s = settings.engineSettings().speed();
        assertEquals(2, s.download(), "1500 bytes is 1.46 KiB: 2, not 1");
        assertEquals(0, s.upload(), "0: none");
        assertEquals(1, s.alternativeDownload());
        assertEquals(0, s.alternativeUpload(), "-1: none");
        Map<String, Object> read = Preferences.read(settings, downloads);
        assertEquals(2048L, read.get("dl_limit"), "read back in bytes");
    }

    @Test
    void theScheduleInQbittorrentsPieces() throws IOException {
        apply("{\"scheduler_enabled\":true,\"schedule_from_hour\":22,\"schedule_from_min\":30,"
                + "\"schedule_to_hour\":6,\"schedule_to_min\":5,\"scheduler_days\":1}");
        EngineSettings.Speed s = settings.engineSettings().speed();
        assertTrue(s.scheduleOn());
        assertEquals(22 * 60 + 30, s.scheduleFrom());
        assertEquals(6 * 60 + 5, s.scheduleTo());
        assertEquals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), s.scheduleDays());
        assertTrue(apply("{\"schedule_from_hour\":25}").applied().isEmpty(), "no 25 o'clock");
        assertEquals(22 * 60 + 30, settings.engineSettings().speed().scheduleFrom(), "and nothing moved");
        assertEquals(0, Preferences.schedulerDays(EnumSet.allOf(DayOfWeek.class)));
        assertEquals(2, Preferences.schedulerDays(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)));
        assertEquals(3, Preferences.schedulerDays(EnumSet.of(DayOfWeek.MONDAY)));
        assertEquals(EnumSet.of(DayOfWeek.SUNDAY), Preferences.schedulerDays(9));
    }

    @Test
    void queueShareConnectionAndProtocol() throws IOException {
        apply("{\"max_active_downloads\":4,\"max_active_uploads\":-1,\"dont_count_slow_torrents\":false,"
                + "\"max_ratio\":1.5,\"max_seeding_time\":120,\"max_ratio_act\":3,"
                + "\"listen_port\":51413,\"upnp\":false,\"max_connec\":300,\"dht\":false,\"encryption\":1,"
                + "\"bittorrent_protocol\":1,\"anonymous_mode\":true,\"add_stopped_enabled\":true,"
                + "\"torrent_content_layout\":\"NoSubfolder\"}");
        EngineSettings s = settings.engineSettings();
        assertEquals(4, s.queue().activeDownloads());
        assertEquals(0, s.queue().activeSeeds(), "-1: no limit, Torrenta's 0");
        assertFalse(s.queue().ignoreSlowTorrents());
        assertEquals(
                new ShareLimit(1.5, 120, ShareLimit.Action.REMOVE_DATA),
                s.queue().share());
        assertEquals(51413, s.listenPort());
        assertFalse(s.upnp());
        assertEquals(300, s.maxConnections());
        assertFalse(s.protocol().dht());
        assertEquals(EngineSettings.Protocol.Encryption.REQUIRE, s.protocol().encryption());
        assertFalse(s.protocol().utp(), "TCP alone");
        assertTrue(s.protocol().anonymous());
        assertTrue(settings.addStartPaused());
        assertEquals(FileRenames.Layout.NO_SUBFOLDER, settings.addContentLayout());

        apply("{\"queueing_enabled\":false,\"max_ratio_enabled\":false}");
        assertEquals(0, settings.engineSettings().queue().activeDownloads(), "queueing off: no counts");
        assertEquals(0, settings.engineSettings().queue().share().ratio(), 0);
        assertTrue(apply("{\"listen_port\":80}").applied().isEmpty(), "below 1024, as the page refuses");

        apply("{\"banned_IPs\":\"203.0.113.9\\n198.51.100.0/24\\n203.0.113.9\"}");
        assertEquals(
                List.of("203.0.113.9", "198.51.100.0/24"), settings.ipFilterBans(), "the IP filter's bans, once each");
        assertEquals(
                "203.0.113.9\n198.51.100.0/24",
                Preferences.read(settings, downloads).get("banned_IPs"));
    }

    /** The door's own keys are changed on this machine's Settings page, never through the door. */
    @Test
    void remoteAccessItselfIsReadOnlyAndUnknownKeysAreIgnored() throws IOException {
        settings.setWebApiPasswordHash("pbkdf2-sha256$1000$AA==$AA==");
        Preferences.Applied a = apply("{\"web_ui_password\":\"owned\",\"web_ui_port\":1,\"web_ui_address\":\"0.0.0.0\","
                + "\"bypass_local_auth\":true,\"some_future_key\":7,\"scan_dirs\":{\"/x\":1},\"dht\":true}");
        assertEquals(List.of("dht"), a.applied());
        assertEquals("pbkdf2-sha256$1000$AA==$AA==", settings.webApi().passwordHash());
        assertEquals(WebApiConfig.DEFAULT_PORT, settings.webApi().port());
        assertEquals(WebApiConfig.DEFAULT_ADDRESS, settings.webApi().address());
        Map<String, Object> read = Preferences.read(settings, downloads);
        assertEquals(false, read.get("bypass_local_auth"));
        assertFalse(read.containsKey("web_ui_password"), "never read either");
    }

    /** The download folder is a root: moving it out would widen where clients may write. */
    @Test
    void theSavePathMustStayInsideTheAllowedFoldersOrNothingIsWritten() throws IOException {
        Preferences.Applied refused = apply("{\"save_path\":\"/etc\",\"dht\":false}");
        assertTrue(refused.refused(), refused.toString());
        assertTrue(settings.engineSettings().protocol().dht(), "the rest of the request was not written either");
        Preferences.Applied ok =
                apply("{\"save_path\":\"" + downloads.resolve("new").toString().replace("\\", "\\\\") + "\"}");
        assertEquals(List.of("save_path"), ok.applied());
        assertEquals(downloads.resolve("new"), settings.downloadDir());
    }

    @Test
    void theJsonIsAFlatObject() throws IOException {
        Map<String, Object> m =
                Preferences.parse("{\"a\":1,\"b\":true,\"c\":\"x\",\"d\":[1,2],\"e\":{\"f\":1},\"g\":1.5}");
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("a", 1L);
        want.put("b", true);
        want.put("c", "x");
        want.put("g", 1.5);
        assertEquals(want, m);
        assertThrows(IOException.class, () -> Preferences.parse("[1]"));
        assertThrows(IOException.class, () -> Preferences.parse("{\"a\":"));
    }
}
