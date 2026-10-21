package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Engine;
import com.torrenta.engine.TorrentState;
import com.torrenta.tray.TrayDisplay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayStatusTest {

    private static final Engine.Rates RATES = new Engine.Rates(1 << 20, 0);

    @Test
    void anEmptyListIsIdleAndSaysSo() {
        TrayStatus s = TrayStatus.of(List.of(), Engine.Rates.ZERO);
        assertEquals(TrayDisplay.idle(), s.display());
        assertEquals("↓ 0 B/s  ↑ 0 B/s · No torrents", s.line());
    }

    @Test
    void theLineCountsWhatTheTorrentsAreDoing() {
        TrayStatus s = TrayStatus.of(
                List.of(
                        T.downloading().build(),
                        T.downloading().build(),
                        T.seeding().build(),
                        T.downloading().queued(1).build(),
                        T.seeding().paused().build()),
                RATES);
        assertEquals("↓ 1.0 MiB/s  ↑ 0 B/s · 2 downloading, 1 seeding, 1 queued, 1 paused", s.line());
        assertTrue(s.display().downloading());
        assertFalse(s.display().attention());
    }

    /** A queued or paused torrent is not downloading, whatever its state says. */
    @Test
    void onlyARunningDownloadColoursTheArrow() {
        assertFalse(TrayStatus.of(List.of(T.downloading().paused().build()), RATES)
                .display()
                .downloading());
        assertFalse(TrayStatus.of(List.of(T.downloading().queued(0).build()), RATES)
                .display()
                .downloading());
        assertTrue(
                TrayStatus.of(List.of(T.downloading().build()), RATES).display().downloading());
    }

    @Test
    void anErrorAsksForAttentionAndIsCountedOnce() {
        TrayStatus one =
                TrayStatus.of(List.of(T.downloading().error("disk full").build()), RATES);
        assertTrue(one.display().attention());
        assertFalse(one.display().downloading(), "an errored torrent is not downloading");
        assertEquals("↓ 1.0 MiB/s  ↑ 0 B/s · 1 error", one.line());
        TrayStatus two = TrayStatus.of(
                List.of(
                        T.downloading().error("disk full").build(),
                        T.seeding().error("gone").paused().build(),
                        T.seeding().build()),
                RATES);
        assertEquals("↓ 1.0 MiB/s  ↑ 0 B/s · 1 seeding, 2 errors", two.line());
    }

    /** Checking and fetching are neither; the line still says there is something. */
    @Test
    void torrentsThatFitNoCountAreStillCounted() {
        TrayStatus s = TrayStatus.of(
                List.of(T.downloading().state(TorrentState.CHECKING).build()), Engine.Rates.ZERO);
        assertEquals("↓ 0 B/s  ↑ 0 B/s · 1 torrent", s.line());
        assertEquals(TrayDisplay.idle(), s.display());
    }

    /** Equal snapshots are what lets the window skip the round trip to the panel. */
    @Test
    void equalSnapshotsAreEqual() {
        assertEquals(
                TrayStatus.of(List.of(T.downloading().build()), RATES),
                TrayStatus.of(List.of(T.downloading().build()), RATES));
        assertEquals("Starting the engine…", TrayStatus.starting().line());
    }
}
