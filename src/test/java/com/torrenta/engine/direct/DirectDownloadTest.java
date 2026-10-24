package com.torrenta.engine.direct;

import java.io.IOException;
import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectDownloadTest {

    @Test
    void theIdIsTheLinksAndNeverAnInfoHash() {
        String id = DirectDownload.idOf("https://x.org/a.iso");
        assertEquals(id, DirectDownload.idOf("  https://x.org/a.iso "));
        assertTrue(DirectDownload.isId(id));
        assertTrue(id.startsWith("url-") && id.length() == 44, id);
        assertFalse(DirectDownload.isId("0123456789abcdef0123456789abcdef01234567"));
        assertFalse(DirectDownload.isId(null));
    }

    @Test
    void theFileRoundTrips() throws IOException {
        DirectDownload a = DirectDownload.of("https://x.org/a.iso", "a.iso", "/dl", 100)
                .withCategory("Linux")
                .withTags(List.of("big", "iso"))
                .withRateLimit(1024)
                .withOwnName("Debian");
        DirectDownload b = DirectDownload.of("ftp://y.org/b.bin", "b.bin", "/dl", 200)
                .withCompleted(300, 42)
                .withError("");
        DirectDownload c = DirectDownload.of("https://z.org/c", "c", "/dl", 300)
                .withPaused(true)
                .withError("HTTP 404");
        DirectDownload d = DirectDownload.of("https://z.org/d", "x", "/dl", 400).withChosenName("chosen.bin");
        assertTrue(d.named());
        String text = DirectDownload.format(List.of(a, b, c, d));
        assertEquals(List.of(a, b, c, d), DirectDownload.parse(text));
        assertEquals("[]\n", DirectDownload.format(List.of()));
    }

    @Test
    void anEntryWithoutALinkIsSkippedAndABrokenFileThrows() throws IOException {
        assertEquals(List.of(), DirectDownload.parse("[{\"file\":\"x\"}, 3, {\"url\":\"https://x.org/a\"}]"));
        assertThrows(IOException.class, () -> DirectDownload.parse("{}"));
        assertThrows(IOException.class, () -> DirectDownload.parse("[{\"url\":\"https://x.org/a\""));
    }

    @Test
    void theNameOnDiskCarriesPartUntilFinished() {
        DirectDownload d = DirectDownload.of("https://x.org/a.iso", "a.iso", "/dl", 1);
        assertEquals("a.iso.part", d.onDisk());
        assertEquals("a.iso", d.withCompleted(2, 10).onDisk());
    }

    @Test
    void aSnapshotIsARowGroupedByItsHost() {
        DirectDownload d =
                DirectDownload.of("https://CDN.x.org/a.iso", "a.iso", "/dl", 1).withSize(1000);
        Torrent running = DirectDownloads.snapshot(d, "a.iso", 250, 100, true);
        assertTrue(running.direct());
        assertEquals(0.25, running.progress());
        assertEquals(TorrentState.DOWNLOADING, running.state());
        assertEquals(List.of("cdn.x.org"), running.trackers());
        assertEquals(100, running.downloadRate());
        assertFalse(running.queued());

        Torrent waiting = DirectDownloads.snapshot(d, "a.iso", 250, 100, false);
        assertTrue(waiting.queued(), "not running, not paused, no error: waiting for a slot");
        assertEquals(0, waiting.downloadRate());
        assertFalse(DirectDownloads.snapshot(d.withPaused(true), "a.iso", 250, 0, false)
                .queued());

        Torrent unknown = DirectDownloads.snapshot(d.withSize(-1), "a.iso", 250, 0, true);
        assertEquals(0, unknown.size());
        assertEquals(0, unknown.progress());

        Torrent done = DirectDownloads.snapshot(d.withCompleted(5, 1000), "a.iso", 1000, 0, false);
        assertEquals(TorrentState.FINISHED, done.state());
        assertEquals(1.0, done.progress());
        assertFalse(done.queued());
    }
}
