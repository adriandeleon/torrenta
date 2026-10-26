package com.torrenta.webapi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.Priority;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static com.torrenta.webapi.FakeBackend.torrent;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A torrent as torrents/info writes it; content_path and hash are what the *arr apps lean on. */
class QbitTorrentTest {

    private static TorrentDetail.FileStatus file(int index, String path) {
        return new TorrentDetail.FileStatus(index, path, 100, 100, Priority.NORMAL, 0, 1);
    }

    private static String p(String first, String... more) {
        return Path.of(first, more).toString();
    }

    /** What Sonarr and Radarr import from. */
    @Test
    void theContentPathIsTheFileOrTheRootFolder() {
        assertEquals(
                p("/srv/downloads", "Film.mkv"),
                QbitTorrent.contentPath("/srv/downloads", "Film", List.of(file(0, "Film.mkv"))),
                "a single file: the file itself, under whatever name it has");
        assertEquals(
                p("/srv/downloads", "Show S01"),
                QbitTorrent.contentPath(
                        "/srv/downloads", "Show S01", List.of(file(0, "Show S01/e1.mkv"), file(1, "Show S01/e2.mkv"))),
                "several under one folder: the folder");
        assertEquals(
                p("/srv/downloads"),
                QbitTorrent.contentPath("/srv/downloads", "Show", List.of(file(0, "e1.mkv"), file(1, "e2.mkv"))),
                "laid out without its root folder: the save path");
        assertEquals(
                p("/srv/downloads", "Magnet name"),
                QbitTorrent.contentPath("/srv/downloads", "Magnet name", List.of()),
                "before the metadata: the name");
    }

    /** Sonarr looks a torrent up by the v1 hash it computed; a hybrid's own id is its v2 hash. */
    @Test
    void theHashIsTheV1HashWhereThereIsOne() {
        Torrent t = torrent("b2b2", "x", TorrentState.SEEDING, 1, 0, 0);
        TorrentDetail hybrid = FakeBackend.detail("b2b2", "a1a1", "b2b2b2b2", List.of(file(0, "x")));
        assertEquals("a1a1", QbitTorrent.hash(t, QbitTorrent.Facts.of(t, hybrid, "")));
        TorrentDetail v2only = FakeBackend.detail("b2b2", "", "b2b2b2b2", List.of(file(0, "x")));
        assertEquals("b2b2", QbitTorrent.hash(t, QbitTorrent.Facts.of(t, v2only, "")));
    }

    @Test
    void theEtaAndRatioAreQbittorrents() {
        Torrent down = torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 100, 0);
        assertEquals(5, QbitTorrent.eta(down), "500 bytes left at 100 a second");
        assertEquals(QbitTorrent.MAX_ETA, QbitTorrent.eta(torrent("a", "a", TorrentState.DOWNLOADING, 0.5, 0, 0)));
        assertEquals(QbitTorrent.MAX_ETA, QbitTorrent.eta(torrent("a", "a", TorrentState.SEEDING, 1, 0, 9)));
        assertEquals(0.5, QbitTorrent.ratio(down), 1e-9, "400 up over 800 down");
    }

    @Test
    void theRowCarriesTheStateAndLimitsInQbittorrentsUnits() {
        Torrent t = torrent("a", "Film", TorrentState.SEEDING, 1, 0, 0);
        QbitTorrent.Facts f =
                QbitTorrent.Facts.of(t, FakeBackend.detail("a", "a1", "", List.of(file(0, "Film.mkv"))), "m");
        Map<String, Object> row = QbitTorrent.fields(
                t,
                f,
                new QbitTorrent.Extras(
                        new TorrentLimits(2048, 0, 0, 0, true),
                        Optional.of(new ShareLimit(1.5, 0, ShareLimit.Action.PAUSE)),
                        new ShareLimit(1.5, 0, ShareLimit.Action.PAUSE),
                        1_700_000_900L));
        assertEquals("stalledUP", row.get("state"));
        assertEquals("a1", row.get("hash"));
        assertEquals(1.0, row.get("progress"));
        assertEquals(2048, row.get("dl_limit"));
        assertEquals(-1, row.get("up_limit"), "no limit is -1");
        assertEquals(true, row.get("f_l_piece_prio"));
        assertEquals(1.5, row.get("ratio_limit"));
        assertEquals(-1L, row.get("seeding_time_limit"), "its own limit without a time: none");
        assertEquals(1_700_000_500L, row.get("completion_on"));
        assertEquals(p("/srv/downloads", "Film.mkv"), row.get("content_path"));

        Map<String, Object> global = QbitTorrent.fields(
                t, f, new QbitTorrent.Extras(TorrentLimits.NONE, Optional.empty(), ShareLimit.NONE, 0));
        assertEquals(-2.0, global.get("ratio_limit"), "no limit of its own: the global one");
        assertEquals(-2L, global.get("seeding_time_limit"));
        assertEquals(-1.0, global.get("max_ratio"), "a ratio is a double, -1 for none");
    }
}
