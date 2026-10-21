package com.torrenta.ui;

import java.time.ZoneOffset;
import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest {

    private static Torrent torrent(TorrentState state, long size, long done, long rate) {
        return new Torrent(
                "id",
                "n",
                size,
                done,
                size == 0 ? 0 : (double) done / size,
                state,
                rate,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                false,
                false,
                0,
                "",
                List.of(),
                "",
                java.util.List.of(),
                false);
    }

    @Test
    void stateLabelShowsAUserPauseFirst() {
        Torrent running = torrent(TorrentState.DOWNLOADING, 100, 10, 1);
        assertEquals("Downloading", Format.stateLabel(running));
        Torrent paused = new Torrent(
                "id",
                "n",
                100,
                10,
                0.1,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                true,
                false,
                0,
                "",
                List.of(),
                "",
                java.util.List.of(),
                false);
        assertEquals("Paused", Format.stateLabel(paused));
        Torrent errored = new Torrent(
                "id",
                "n",
                100,
                10,
                0.1,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                true,
                false,
                0,
                "",
                List.of(),
                "No such file",
                List.of(),
                false);
        assertEquals("Error", Format.stateLabel(errored), "an error outranks even the pause");
    }

    @Test
    void queueColumnCountsFromOneAndDashesTheUnqueued() {
        assertEquals("1", Format.queuePosition(0), "the status line says Queued #1 for position 0");
        assertEquals("12", Format.queuePosition(11));
        assertEquals("—", Format.queuePosition(-1), "a seed is outside libtorrent's queue");
    }

    @Test
    void tagsColumnIsCommaSeparated() {
        assertEquals("", Format.tags(List.of()));
        assertEquals("4k, hd", Format.tags(List.of("4k", "hd")));
    }

    @Test
    void percent() {
        assertEquals("0.0 %", Format.percent(0));
        assertEquals("42.5 %", Format.percent(0.425));
        assertEquals("100.0 %", Format.percent(1));
    }

    @Test
    void etaOnlyWhileDownloading() {
        assertEquals(Format.NO_ETA, Format.etaSeconds(torrent(TorrentState.SEEDING, 100, 100, 0)));
        assertEquals(Format.NO_ETA, Format.etaSeconds(torrent(TorrentState.FETCHING_METADATA, 0, 0, 0)));
        assertEquals(Format.NO_ETA, Format.etaSeconds(torrent(TorrentState.DOWNLOADING, 100, 100, 50)), "nothing left");
        assertEquals(Format.INFINITE_ETA, Format.etaSeconds(torrent(TorrentState.DOWNLOADING, 100, 10, 0)), "no speed");
        assertEquals(30, Format.etaSeconds(torrent(TorrentState.DOWNLOADING, 1000, 100, 30)));
        assertEquals(
                Format.INFINITE_ETA, Format.etaSeconds(torrent(TorrentState.DOWNLOADING, 1L << 50, 0, 1)), "centuries");
    }

    @Test
    void etaAndDurationText() {
        assertEquals("—", Format.eta(Format.NO_ETA));
        assertEquals("∞", Format.eta(Format.INFINITE_ETA));
        assertEquals("45s", Format.eta(45));
        assertEquals("3m 4s", Format.duration(184));
        assertEquals("2h 10m", Format.duration(2 * 3600 + 10 * 60 + 59));
        assertEquals("3d 2h", Format.duration(3 * 86400 + 2 * 3600));
    }

    @Test
    void ratio() {
        assertEquals("—", Format.ratio(Format.ratioValue(0, 0)));
        assertEquals("∞", Format.ratio(Format.ratioValue(10, 0)));
        assertEquals("0.50", Format.ratio(Format.ratioValue(50, 100)));
        assertEquals("2.00", Format.ratio(Format.ratioValue(200, 100)));
    }

    @Test
    void trackerHost() {
        assertEquals("—", Format.host(""));
        assertEquals("—", Format.host(null));
        assertEquals("tracker.example.org", Format.host("udp://tracker.example.org:6969/announce"));
        assertEquals("t.example.com", Format.host("https://t.example.com/announce?x=1"));
        assertEquals("not a url", Format.host("not a url"), "shown as is rather than hidden");
    }

    @Test
    void dateTime() {
        assertEquals("—", Format.dateTime(0, ZoneOffset.UTC));
        assertEquals("2026-10-20 03:40", Format.dateTime(1792467600L, ZoneOffset.UTC));
    }

    @Test
    void wholePercentFloorsSoOnlyFinishedSaysAHundred() {
        assertEquals("0%", Format.wholePercent(0));
        assertEquals("62%", Format.wholePercent(0.62));
        assertEquals("99%", Format.wholePercent(0.9997));
        assertEquals("100%", Format.wholePercent(1));
        assertEquals("0%", Format.wholePercent(-0.1));
    }

    @Test
    void coarseDurationsDoNotTick() {
        assertEquals("under a minute", Format.coarse(59));
        assertEquals("1 min", Format.coarse(60));
        assertEquals("18 min", Format.coarse(18 * 60 + 59));
        assertEquals("2 h", Format.coarse(7200));
        assertEquals("2 h 5 min", Format.coarse(7200 + 300 + 30));
        assertEquals("3 d", Format.coarse(3 * 86400 + 7200));
    }

    @Test
    void aStillRateIsADash() {
        assertEquals("—", Format.rate(0));
        assertEquals(Bytes.rate(2048), Format.rate(2048));
    }
}
