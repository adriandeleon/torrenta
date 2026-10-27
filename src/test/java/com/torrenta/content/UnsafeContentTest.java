package com.torrenta.content;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsafeContentTest {

    private static final long MB = 1024 * 1024;

    /** A film with a disguised program at 1 and a bait note at 2; the film itself at 0. */
    private static final TorrentPreview FILM = new TorrentPreview(
            "id",
            "Film.2026.1080p",
            4002 * MB,
            List.of(
                    new TorrentPreview.FileEntry(0, "Film/Film.mkv", 4000 * MB),
                    new TorrentPreview.FileEntry(1, "Film/Film.mkv.exe", 2 * MB),
                    new TorrentPreview.FileEntry(2, "Film/PASSWORD.txt", 1)),
            false,
            List.of(),
            "",
            "",
            0,
            true,
            false);

    private static AddRequest request(Map<Integer, Priority> priorities) {
        return new AddRequest(
                new AddRequest.Magnet("magnet:?xt=urn:btih:" + "ab".repeat(20)),
                Path.of("/downloads"),
                false,
                false,
                false,
                AddRequest.StopCondition.NONE,
                priorities,
                TorrentLabels.NONE);
    }

    @Test
    void theSettingDecidesAndSkippingEverythingIsARefusal() {
        ContentCheck found = UnsafeContent.check(FILM, request(Map.of()));
        assertEquals(2, found.findings().size());
        assertEquals(UnsafeContent.Decision.CLEAN, UnsafeContent.decide(UnsafeContent.REFUSE, ContentCheck.NONE, 3));
        assertEquals(UnsafeContent.Decision.WARN, UnsafeContent.decide(UnsafeContent.ADD, found, 3));
        assertEquals(UnsafeContent.Decision.SKIP, UnsafeContent.decide(UnsafeContent.SKIP, found, 3));
        assertEquals(UnsafeContent.Decision.REFUSE, UnsafeContent.decide(UnsafeContent.REFUSE, found, 3));
        assertEquals(
                UnsafeContent.Decision.NOTHING_LEFT,
                UnsafeContent.decide(UnsafeContent.SKIP, found, 2),
                "the film already skipped: what is left is what was flagged");
        assertTrue(UnsafeContent.Decision.NOTHING_LEFT.refused());
        assertFalse(UnsafeContent.Decision.SKIP.refused());
    }

    @Test
    void skippingKeepsThePrioritiesTheRequestAlreadyHad() {
        AddRequest asked = request(Map.of(0, Priority.HIGH));
        AddRequest skipped = UnsafeContent.withSkipped(asked, Set.of(1, 2));
        assertEquals(Map.of(0, Priority.HIGH, 1, Priority.IGNORE, 2, Priority.IGNORE), skipped.filePriorities());
        assertEquals(asked.source(), skipped.source());
        assertEquals(asked.stopCondition(), skipped.stopCondition());
    }

    @Test
    void aFileTheRequestAlreadySkipsIsNeitherWantedNorChecked() {
        AddRequest noNote = request(Map.of(2, Priority.IGNORE));
        assertEquals(2, UnsafeContent.wanted(FILM, noNote));
        assertEquals(Set.of(1), UnsafeContent.check(FILM, noNote).indices());
    }

    @Test
    void theMarkSurvivesARoundTripAndReadsSkipWhenUnknown() {
        for (UnsafeContent mode : UnsafeContent.values()) {
            for (boolean resume : new boolean[] {false, true}) {
                UnsafeContent.Pending p = new UnsafeContent.Pending(mode, resume);
                assertEquals(p, UnsafeContent.Pending.parse(p.format()));
            }
        }
        assertEquals("REFUSE resume", new UnsafeContent.Pending(UnsafeContent.REFUSE, true).format());
        assertEquals(new UnsafeContent.Pending(UnsafeContent.SKIP, false), UnsafeContent.Pending.parse("nonsense"));
        assertEquals(UnsafeContent.SKIP, UnsafeContent.byName(null), "the default");
        assertEquals(UnsafeContent.REFUSE, UnsafeContent.byName(" refuse "));
    }

    @Test
    void aMagnetsFilesAreCheckedAsItsPreviewWouldBe() {
        Torrent t = new Torrent(
                "id",
                "Film.2026.1080p",
                1,
                0,
                0,
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
                List.of(),
                false);
        List<TorrentDetail.FileStatus> files = List.of(
                new TorrentDetail.FileStatus(0, "Film/Film.mkv", 4000 * MB, 0, Priority.NORMAL),
                new TorrentDetail.FileStatus(1, "Film/Film.mkv.exe", 2 * MB, 0, Priority.NORMAL));
        TorrentPreview preview = UnsafeContent.previewOf(t, files);
        assertEquals("Film.2026.1080p", preview.name());
        assertEquals(4002 * MB, preview.size());
        assertEquals(Set.of(1), ContentCheck.of(preview, i -> true).indices());
    }

    @Test
    void theLogSaysWhatWasDoneAndARefusalNamesItsReason() {
        ContentCheck found = UnsafeContent.check(FILM, request(Map.of()));
        assertEquals("", UnsafeContent.logLine(UnsafeContent.Decision.CLEAN, "Film", found, false));
        assertEquals(
                "Added Film without the 2 files the content check flagged: "
                        + "A program named like a video or a document: Film.mkv.exe; "
                        + "A file that asks for something before anything plays: PASSWORD.txt",
                UnsafeContent.logLine(UnsafeContent.Decision.SKIP, "Film", found, false));
        assertTrue(UnsafeContent.logLine(UnsafeContent.Decision.WARN, "Film", found, false)
                .startsWith("Added Film as it is, though the content check found: "));
        assertTrue(UnsafeContent.logLine(UnsafeContent.Decision.REFUSE, "Film", found, true)
                .startsWith("Removed Film once its file list arrived: the content check refused it: "));

        UnsafeContent.Refused refused = new UnsafeContent.Refused("Film", UnsafeContent.Decision.NOTHING_LEFT, found);
        assertTrue(
                refused.getMessage().startsWith("every file it wants was flagged by the content check: "),
                "the reason alone, for a path whose own line names the torrent");
        assertTrue(refused.line().startsWith("Did not add Film: every file it wants"));
    }
}
