package com.torrenta.os;

import java.util.List;
import java.util.Map;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BadgeStateTest {

    @Test
    void countsOnlyWhatIsReallyDownloading() {
        BadgeState state = BadgeState.of(
                List.of(
                        torrent(TorrentState.DOWNLOADING, false, false, ""),
                        torrent(TorrentState.DOWNLOADING, false, false, ""),
                        torrent(TorrentState.DOWNLOADING, true, false, ""),
                        torrent(TorrentState.DOWNLOADING, false, true, ""),
                        torrent(TorrentState.DOWNLOADING, false, false, "disk full"),
                        torrent(TorrentState.SEEDING, false, false, ""),
                        torrent(TorrentState.CHECKING, false, false, "")),
                2 << 20);
        assertEquals(new BadgeState(2, 2 << 20), state);
        assertTrue(state.active());
        assertEquals(BadgeState.idle(), BadgeState.of(List.of(torrent(TorrentState.SEEDING, false, false, "")), 500));
        assertFalse(BadgeState.idle().active());
        assertEquals(BadgeState.idle(), BadgeState.of(List.of(), 0));
    }

    /** What fits in a dock badge: a number and a unit letter. */
    @Test
    void theTextIsShort() {
        assertEquals("", BadgeState.idle().text(), "nothing to say");
        assertEquals("1K", new BadgeState(1, 10).text(), "a trickle still shows");
        assertEquals("1K", new BadgeState(1, 1024).text());
        assertEquals("850K", new BadgeState(1, 850 * 1024).text());
        assertEquals("1.2M", new BadgeState(1, (long) (1.2 * 1024 * 1024)).text());
        assertEquals("12M", new BadgeState(1, 12L * 1024 * 1024).text());
        assertEquals("1.5G", new BadgeState(1, (long) (1.5 * 1024 * 1024 * 1024)).text());
        assertEquals("12G", new BadgeState(1, 12L * 1024 * 1024 * 1024).text());
    }

    @Test
    void theUnitySignalCarriesTheCountAndWhetherToShowIt() {
        assertEquals("application://torrenta-Torrenta.desktop", UnityBadge.appUri("torrenta-Torrenta.desktop"));
        Map<String, Variant<?>> shown = UnityBadge.properties(new BadgeState(3, 1));
        assertEquals(3L, shown.get("count").getValue());
        assertEquals(true, shown.get("count-visible").getValue());
        Map<String, Variant<?>> hidden = UnityBadge.properties(BadgeState.idle());
        assertEquals(0L, hidden.get("count").getValue());
        assertEquals(false, hidden.get("count-visible").getValue());
    }

    /** Windows has no badge (AWT badges only its own windows); the reason is said, never thrown. */
    @Test
    void windowsHasNoBadgeAndSaysWhy() {
        Badges.Attempt attempt = Badges.create("Windows 11", "x.desktop");
        assertTrue(attempt.badge().isEmpty());
        assertFalse(attempt.reason().isBlank());
    }

    private static Torrent torrent(TorrentState state, boolean paused, boolean queued, String error) {
        return new Torrent(
                "id", "name", 1000, 0, 0, state, 0, 0, 0, 0, 0, 0, 0, 0, "", "/data", paused, false, 0, "", List.of(),
                error, List.of(), false, queued, -1, false);
    }
}
