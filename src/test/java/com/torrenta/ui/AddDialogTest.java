package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentPreview.FileEntry;
import com.torrenta.i18n.Messages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AddDialogTest {

    private static TorrentPreview preview(boolean v1, boolean v2, List<String> trackers, String comment) {
        return new TorrentPreview(
                "id",
                "Big Buck Bunny",
                6_335_000_000L,
                List.of(
                        new FileEntry(0, "Big Buck Bunny/a.mkv", 6_000_000_000L),
                        new FileEntry(2, "Big Buck Bunny/b.srt", 1)),
                false,
                trackers,
                comment,
                "",
                0,
                v1,
                v2);
    }

    @Test
    void theKickerSaysWhereItCameFrom() {
        assertEquals("ADD TORRENT · FROM FILE", AddDialog.kicker(AddDialog.Via.FILE, false));
        assertEquals("ADD TORRENT · FROM URL", AddDialog.kicker(AddDialog.Via.URL, false));
        assertEquals("ADD TORRENT · FROM MAGNET LINK", AddDialog.kicker(AddDialog.Via.MAGNET, false));
        assertEquals("ADD TORRENT · FROM SEARCH", AddDialog.kicker(AddDialog.Via.SEARCH, false));
        assertEquals("ADD TORRENT · FROM CLIPBOARD", AddDialog.kicker(AddDialog.Via.CLIPBOARD, false));
        assertEquals(Messages.tr("dialog.add.kicker.chooseFiles"), AddDialog.kicker(AddDialog.Via.CLIPBOARD, true));
    }

    @Test
    void theSummaryLineIsSizeFilesVersionTrackersComment() {
        assertEquals(
                "5.9 GiB · 2 files · hybrid v1+v2 · 4 trackers · “Blender open movie”",
                AddDialog.summary(preview(true, true, List.of("a", "b", "c", "d"), "  Blender\n open movie ")));
        assertEquals("5.9 GiB · 2 files · v2 · 1 tracker", AddDialog.summary(preview(false, true, List.of("a"), "")));
        assertEquals("5.9 GiB · 2 files · v1 · no trackers", AddDialog.summary(preview(true, false, List.of(), "")));
    }

    @Test
    void aMagnetBeforeItsMetadataSaysWhatItCan() {
        assertEquals(
                "Size unknown until the file list arrives · 2 trackers",
                AddDialog.magnetSummary(
                        new AddRequest.Magnet("magnet:?xt=urn:btih:abc&tr=udp%3A%2F%2Fa&tr=udp%3A%2F%2Fb")));
        assertEquals(
                "Size unknown until the file list arrives · no trackers, DHT only",
                AddDialog.magnetSummary(new AddRequest.Magnet("magnet:?xt=urn:btih:abc")));
        assertEquals("", AddDialog.magnetSummary(new AddRequest.TorrentFile(new byte[0], "x")));
    }

    @Test
    void theFetchingLineCountsPeersAndTime() {
        assertEquals("Fetching file list · 0 peers · 0s", AddDialog.fetchingLine(0, 0));
        assertEquals("Fetching file list · 1 peer · 12s", AddDialog.fetchingLine(1, 12));
        assertEquals("Fetching file list · 7 peers · 1m 05s", AddDialog.fetchingLine(7, 65));
        assertEquals("Fetching file list · 0 peers · 0s", AddDialog.fetchingLine(-1, -3), "never negative");
    }

    @Test
    void addingWithoutAskingUsesTheDefaults() {
        AddRequest.Source source = new AddRequest.Magnet("magnet:?xt=urn:btih:abc");
        Path data = Path.of("/data").toAbsolutePath();
        Path incomplete = Path.of("/data/incomplete").toAbsolutePath();
        AddDialog.Defaults direct = new AddDialog.Defaults(data, true, false, false, incomplete, false, false);
        assertEquals(
                new AddRequest(
                        source,
                        data,
                        true,
                        false,
                        false,
                        AddRequest.StopCondition.NONE,
                        Map.of(),
                        TorrentLabels.NONE,
                        AddRequest.Storage.DEFAULT),
                AddDialog.withoutAsking(source, direct));
        AddDialog.Defaults via = new AddDialog.Defaults(data, false, true, true, incomplete, true, true);
        assertEquals(
                new AddRequest.Storage(incomplete, true, true),
                AddDialog.withoutAsking(source, via).storage(),
                "the storage plan applies without the dialog too");
    }

    @Test
    void theFolderIsRememberedOnlyWhenItIsThePersonsOwnChoice() {
        Path downloads = Path.of("/home/me/Downloads");
        assertEquals(
                Optional.of(Path.of("/data/films")),
                AddDialog.folderToRemember(Path.of("/data/films"), downloads, Optional.empty()));
        assertEquals(
                Optional.empty(),
                AddDialog.folderToRemember(Path.of("/home/me/Downloads/"), downloads, Optional.empty()),
                "the default already");
        assertEquals(
                Optional.empty(),
                AddDialog.folderToRemember(Path.of("/data/films"), downloads, Optional.of(Path.of("/data/films"))),
                "the category's folder belongs to the category");
    }

    @Test
    void chordsSpellEnterOut() {
        assertEquals("Shift+Enter", AddDialog.keyLabel("Shift+↵"));
        assertEquals("⌘Enter", AddDialog.keyLabel("⌘⏎"));
        assertEquals("Esc", AddDialog.keyLabel("Esc"));
    }
}
