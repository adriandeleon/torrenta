package com.torrenta.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchFolderTest {

    private static final Path DEFAULT = Path.of("/dl").toAbsolutePath();
    private static final Path WATCH = Path.of("/watch").toAbsolutePath();

    @Test
    void normalisedOnConstruction() {
        WatchFolder w = new WatchFolder(" /watch ", null, null, " ", " Movies ", false);
        assertEquals("/watch", w.folder());
        assertEquals(WatchFolder.AfterAdd.RENAME, w.afterAdd(), "rename is the safe default");
        assertEquals(WatchFolder.SaveTo.DEFAULT, w.saveTo());
        assertEquals("", w.savePath());
        assertEquals("Movies", w.category());
        assertThrows(IllegalArgumentException.class, () -> new WatchFolder(" ", null, null, "", "", false));
        WatchFolder noPath = new WatchFolder("/w", null, WatchFolder.SaveTo.FOLDER, "", "", false);
        assertEquals(WatchFolder.SaveTo.DEFAULT, noPath.saveTo(), "another folder with none named is the default");
        WatchFolder stray = new WatchFolder("/w", null, WatchFolder.SaveTo.WATCH_FOLDER, "/x", "", false);
        assertEquals("", stray.savePath(), "a path only means something for FOLDER");
    }

    @Test
    void destinationFollowsTheChoiceThenTheCategory() {
        Category movies = new Category("Movies", "/movies");
        assertEquals(DEFAULT, entry(WatchFolder.SaveTo.DEFAULT, "").destination(DEFAULT, Optional.empty()));
        assertEquals(
                Path.of("/movies").toAbsolutePath(),
                entry(WatchFolder.SaveTo.DEFAULT, "").destination(DEFAULT, Optional.of(movies)),
                "the category's folder, as the Add dialog fills it");
        assertEquals(WATCH, entry(WatchFolder.SaveTo.WATCH_FOLDER, "").destination(DEFAULT, Optional.of(movies)));
        assertEquals(
                Path.of("/else").toAbsolutePath(),
                entry(WatchFolder.SaveTo.FOLDER, "/else").destination(DEFAULT, Optional.of(movies)),
                "the entry's own folder wins over the category's");
    }

    @Test
    void theRequestCarriesTheEntrysChoicesAndTheDefaultsForTheRest() {
        WatchFolder w = new WatchFolder("/watch", WatchFolder.AfterAdd.DELETE, null, "", "Movies", true);
        AddRequest.Storage storage = new AddRequest.Storage(Path.of("/inc"), true, false);
        AddRequest r = w.request(new AddRequest.Magnet("magnet:?xt=urn:btih:a"), Path.of("/dest"), storage);
        assertEquals(Path.of("/dest"), r.savePath());
        assertTrue(r.startPaused());
        assertFalse(r.sequential());
        assertFalse(r.skipCheck());
        assertEquals(AddRequest.StopCondition.NONE, r.stopCondition());
        assertEquals("Movies", r.labels().category());
        assertEquals(List.of(), r.labels().tags());
        assertEquals(storage, r.storage());
    }

    @Test
    void namesAfterAdding() {
        Path file = Path.of("/w/x.torrent");
        assertEquals(Path.of("/w/x.torrent.added"), WatchFolder.handled(file));
        assertEquals(Path.of("/w/x.torrent.invalid"), WatchFolder.invalid(file));
        assertTrue(WatchFolder.watched(file));
        assertTrue(WatchFolder.watched(Path.of("/w/X.TORRENT")));
        assertTrue(WatchFolder.watched(Path.of("/w/links.magnet")));
        assertFalse(WatchFolder.watched(WatchFolder.handled(file)), "a handled file is not looked at again");
        assertFalse(WatchFolder.watched(WatchFolder.invalid(file)));
        assertFalse(WatchFolder.watched(Path.of("/w/notes.txt")));
        assertTrue(WatchFolder.isMagnetFile(Path.of("/w/links.magnet")));
        assertFalse(WatchFolder.isMagnetFile(file));
    }

    @Test
    void magnetLinesOnePerLine() {
        String text = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=a\n"
                + "\n"
                + "# a comment\n"
                + "  MAGNET:?xt=urn:btih:0123456789abcdef0123456789abcdef01234568  \r\n"
                + "magnet:?\n"
                + "https://example.org/x.torrent\n";
        assertEquals(
                List.of(
                        "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=a",
                        "MAGNET:?xt=urn:btih:0123456789abcdef0123456789abcdef01234568"),
                WatchFolder.magnetLines(text),
                "blank lines, comments, an empty magnet and a URL are skipped");
        assertEquals(List.of(), WatchFolder.magnetLines(""));
        assertEquals(List.of(), WatchFolder.magnetLines(null));
    }

    @Test
    void magnetIdIsTheEnginesId() {
        assertEquals(
                Optional.of("0123456789abcdef0123456789abcdef01234567"),
                WatchFolder.magnetId("magnet:?xt=urn:btih:0123456789ABCDEF0123456789abcdef01234567&dn=x"),
                "hex, lower-cased");
        assertEquals(
                Optional.of("0123456789abcdef0123456789abcdef01234567"),
                WatchFolder.magnetId("magnet:?dn=x&xt=urn:btih:AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"),
                "base32 becomes hex");
        assertEquals(
                Optional.of("1".repeat(64)),
                WatchFolder.magnetId("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&xt=urn:btmh:1220"
                        + "1".repeat(64)),
                "a hybrid magnet is id'd by its v2 hash, as the engine does");
        assertEquals(Optional.empty(), WatchFolder.magnetId("magnet:?dn=x"));
        assertEquals(Optional.empty(), WatchFolder.magnetId("magnet:?xt=urn:btih:zz"));
        assertEquals(Optional.empty(), WatchFolder.magnetId(null));
    }

    @Test
    void aFileIsSettledOnceItsSizeHeldForTwoLooks() {
        WatchFolder.Growth growth = new WatchFolder.Growth();
        Path a = Path.of("/w/a.torrent");
        assertFalse(growth.settled(a, 10), "the first look only notes the size");
        assertFalse(growth.settled(a, 20), "still growing");
        assertTrue(growth.settled(a, 20), "the same size twice: safe to read");
        growth.forget(a);
        assertFalse(growth.settled(a, 20), "forgotten, so a new file of that name starts over");
        Path b = Path.of("/w/b.torrent");
        growth.settled(b, 5);
        growth.retain(List.of(a));
        assertFalse(growth.settled(b, 5), "b was gone at the last look, so it starts over");
        assertTrue(growth.settled(a, 20));
    }

    @Test
    void statusLine() {
        assertEquals("No folders are watched.", WatchFolders.statusText(0, List.of(), true));
        assertEquals("Watching starts once the engine is up.", WatchFolders.statusText(2, List.of(), false));
        assertEquals(
                "Watching 1 folder, every 60 seconds and as files appear.",
                WatchFolders.statusText(1, List.of(), true));
        assertEquals(
                "Watching 2 folders, every 60 seconds and as files appear. Not found: /a, /b.",
                WatchFolders.statusText(2, List.of(Path.of("/a"), Path.of("/b")), true));
    }

    private static WatchFolder entry(WatchFolder.SaveTo saveTo, String savePath) {
        return new WatchFolder(WATCH.toString(), WatchFolder.AfterAdd.RENAME, saveTo, savePath, "", false);
    }
}
