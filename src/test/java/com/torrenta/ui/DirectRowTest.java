package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A download from a link reads as one: its server where a torrent has a swarm, no seeding after. */
class DirectRowTest {

    private static String line(T t, long quiet) {
        return StatusLine.of(t.build(), quiet, ShareLimit.NONE).text();
    }

    @Test
    void downloadingNamesTheServer() {
        assertEquals(
                "62% · from cdn.x.org",
                line(T.downloading().progress(0.62).down(10).direct("cdn.x.org"), 0));
    }

    @Test
    void anUnknownSizeShowsTheBytesSoFar() {
        T t = T.downloading().down(10).direct("x.org");
        t.size = 0;
        t.done = 2048;
        assertEquals("2.0 KiB · from x.org", line(t, 0));
    }

    @Test
    void aStallBlamesTheServerNotPeers() {
        assertEquals(
                "Stalled 18 min · 40% · x.org is not sending",
                line(T.downloading().progress(0.4).direct("x.org"), 18 * 60));
    }

    @Test
    void finishedIsDoneNotSeeding() {
        T t = T.downloading().state(TorrentState.FINISHED).progress(1).direct("x.org");
        assertEquals(RowState.DONE, RowState.of(t.build(), 0));
        assertEquals("Done · from x.org", line(t, 0));
    }

    @Test
    void theSidebarHasAFinishedDownloadCompletedOnly() {
        var done = T.downloading()
                .state(TorrentState.FINISHED)
                .progress(1)
                .direct("x.org")
                .build();
        assertTrue(SidebarStatus.COMPLETED.matches(done));
        assertTrue(!SidebarStatus.SEEDING.matches(done) && !SidebarStatus.STALLED.matches(done));
        var seed = T.seeding().build();
        assertTrue(SidebarStatus.SEEDING.matches(seed) && SidebarStatus.STALLED.matches(seed), "a torrent still does");
    }

    @Test
    void theSidebarHasAnEntryForDownloadsFromLinks() {
        assertTrue(SidebarStatus.LINKS.matches(T.downloading().direct("x.org").build()));
        assertTrue(SidebarStatus.LINKS.matches(T.downloading()
                .state(TorrentState.FINISHED)
                .progress(1)
                .direct("x.org")
                .build()));
        assertTrue(!SidebarStatus.LINKS.matches(T.seeding().build()), "a torrent is not from a link");
        assertEquals("links", SidebarStatus.LINKS.key());
        assertEquals("From links", SidebarStatus.LINKS.title());
    }

    @Test
    void torrentOnlyCommandsSkipTheDownloads() {
        var download = T.downloading().direct("x.org").build();
        var torrent = T.seeding().id("b").build();
        assertTrue(!Actions.anyTorrent(List.of(download)), "nothing for recheck to act on");
        assertTrue(Actions.anyTorrent(List.of(download, torrent)));
        assertEquals(List.of(torrent), Actions.torrentsOnly(List.of(download, torrent)));
        assertTrue(!Actions.anyTorrent(List.of()));
    }

    @Test
    void theLinkDialogRefusesANameTheDiskWouldChange() {
        assertTrue(LinkDialog.problem("debian.iso").isEmpty());
        assertEquals("Give the file a name", LinkDialog.problem("  ").orElseThrow());
        assertTrue(LinkDialog.problem("a/b.iso").isPresent());
        assertTrue(LinkDialog.problem("what?.iso").isPresent());
        assertTrue(LinkDialog.problem(".hidden").isPresent());
        assertTrue(LinkDialog.problem("CON").isPresent());
    }

    @Test
    void theLinksOwnNameLeftAsItWasIsNoChoice() {
        var kept = LinkDialog.request("https://x.org/a.iso", " /dl ", "a.iso", "a.iso", true);
        assertEquals(null, kept.fileName(), "the server may still name it");
        assertEquals(java.nio.file.Path.of("/dl"), kept.savePath());
        assertTrue(kept.startPaused());
        assertEquals(
                "mine.iso",
                LinkDialog.request("https://x.org/a.iso", "/dl", " mine.iso ", "a.iso", false)
                        .fileName());
    }

    @Test
    void pausedQueuedAndErrorReadAsATorrentsDo() {
        assertEquals(
                "Paused by you · 30%",
                line(T.downloading().progress(0.3).paused().direct("x.org"), 0));
        assertEquals(
                "Queued · starts when a download slot frees",
                line(T.downloading().queued(-1).direct("x.org"), 0));
        assertEquals(
                "Error · The server answered HTTP 404",
                line(T.downloading().error("The server answered HTTP 404").direct("x.org"), 0));
    }

    @Test
    void theOnlyDiagnosisIsAnError() {
        TorrentDetail none = new TorrentDetail(null, List.of(), List.of(), List.of(), new boolean[0]);
        var stalled = T.downloading().progress(0.4).direct("x.org").build();
        assertTrue(Diagnosis.of(stalled, RowState.STALLED, none, 600).isEmpty(), "no peers to find");
        var failed = T.downloading().error("boom").direct("x.org").build();
        assertTrue(Diagnosis.of(failed, RowState.ERROR, none, 0).isPresent());
    }
}
