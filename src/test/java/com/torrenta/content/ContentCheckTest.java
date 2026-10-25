package com.torrenta.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.TorrentPreview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentCheckTest {

    private static final long MB = 1024 * 1024;
    private static final Set<String> BAIT = ContentCheck.parseWords("password, codec, unlock, how to play");

    /** A torrent of these files, paths and sizes alternating, indexed in order. */
    private static TorrentPreview torrent(String name, Object... pathsAndSizes) {
        List<TorrentPreview.FileEntry> files = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < pathsAndSizes.length; i += 2) {
            long size = (Long) pathsAndSizes[i + 1];
            files.add(new TorrentPreview.FileEntry(i / 2, (String) pathsAndSizes[i], size));
            total += size;
        }
        return new TorrentPreview("id", name, total, files, false, List.of(), "", "", 0, true, false);
    }

    private static ContentCheck check(TorrentPreview t) {
        return ContentCheck.of(t, i -> true, BAIT);
    }

    private static List<ContentCheck.Kind> kinds(TorrentPreview t) {
        return check(t).findings().stream().map(ContentCheck.Finding::kind).toList();
    }

    @Test
    void aCleanFilmHasNothing() {
        TorrentPreview film = torrent(
                "Film.2026.1080p.BluRay.x264",
                "Film/Film.mkv",
                4000 * MB,
                "Film/Film.srt",
                1L,
                "Film/Film.nfo",
                1L,
                "Film/Sample/sample.mkv",
                20 * MB);
        assertTrue(check(film).isEmpty());
    }

    @Test
    void aProgramDressedAsAVideoIsDisguisedWhateverTheTorrent() {
        assertEquals(
                List.of(ContentCheck.Kind.DISGUISED),
                kinds(torrent("Tools", "Film.mkv.exe", 2 * MB, "readme.txt", 1L)));
        assertEquals(
                List.of(ContentCheck.Kind.DISGUISED),
                kinds(torrent("x", "Film.mkv        .exe", 2 * MB)),
                "spaces before the last dot push the real extension out of sight");
        assertEquals(List.of(ContentCheck.Kind.DISGUISED), kinds(torrent("x", "Report.pdf.scr", 1L)));
        assertTrue(kinds(torrent("Tool 1.2", "Tool 1.2.exe", 1L)).isEmpty(), "a version number is no disguise");
        assertTrue(kinds(torrent("App", "setup.exe", 1L)).isEmpty());
    }

    @Test
    void bidiControlsHideTheExtensionAndAreShownForWhatTheyAre() {
        String name = "Film‮vkm.exe";
        ContentCheck c = check(torrent("x", name, 1L));
        assertEquals(List.of(ContentCheck.Kind.HIDDEN_EXTENSION), kinds(torrent("x", name, 1L)));
        assertEquals(ContentCheck.Severity.DANGER, c.severity());
        assertEquals("Film⟨U+202E⟩vkm.exe", ContentCheck.visible(name));
        assertEquals(
                "A name that hides its real extension: Film⟨U+202E⟩vkm.exe",
                c.lines().get(0).text());
        assertEquals(
                List.of(ContentCheck.Kind.HIDDEN_EXTENSION),
                kinds(torrent("x", "Film‮4pm.mkv.exe", 1L)),
                "one finding per file, the most serious");
    }

    @Test
    void aWindowsShortcutIsFlaggedEvenInASoftwareTorrent() {
        assertEquals(
                List.of(ContentCheck.Kind.SHORTCUT),
                kinds(torrent("App 3.0", "App/setup.exe", 50 * MB, "App/Start here.lnk", 1L)));
    }

    @Test
    void aProgramInAMediaTorrentIsDangerAndInASoftwareTorrentNothing() {
        TorrentPreview film = torrent(
                "Film", "Film.mkv", 4000 * MB, "Codec installer.exe", 2 * MB, "Player.app/Contents/MacOS/x", 1L);
        assertEquals(List.of(ContentCheck.Kind.PROGRAM, ContentCheck.Kind.PROGRAM), kinds(film));
        assertEquals(ContentCheck.Severity.DANGER, check(film).severity());
        assertTrue(
                kinds(torrent("Debian 13.1", "debian-13.1-amd64.iso", 700 * MB, "install.sh", 1L))
                        .isEmpty(),
                "a program where programs belong");
    }

    @Test
    void theNameAloneCanMakeATorrentMedia() {
        assertEquals(
                List.of(ContentCheck.Kind.PROGRAM),
                kinds(torrent("Show.S01E02.720p.WEB-DL.H.264", "Show.S01E02.exe", 900 * MB)),
                "an episode that is all program");
        assertTrue(ContentCheck.promisesMedia("Album (2026) [FLAC]"));
        assertTrue(ContentCheck.promisesMedia("Film 2026 2160p UHD Blu-ray Remux"));
        assertFalse(ContentCheck.promisesMedia("LibreOffice 26.2 x64"));
    }

    @Test
    void aLinkIsACautionInMediaAndNothingElsewhere() {
        TorrentPreview film = torrent("Film", "Film.mkv", 4000 * MB, "Get the codec.url", 1L);
        assertEquals(List.of(ContentCheck.Kind.LINK), kinds(film));
        assertEquals(ContentCheck.Severity.CAUTION, check(film).severity());
        assertTrue(kinds(torrent("App", "app.zip", 9 * MB, "Homepage.url", 1L)).isEmpty());
    }

    @Test
    void anArchiveIsACautionOnlyWhereTheNamePromisesMediaAndNoMediaIsThere() {
        TorrentPreview archived =
                torrent("Film.2026.1080p.WEB-DL", "Film.rar", 4000 * MB, "Film.r00", 100 * MB, "info.nfo", 1L);
        assertEquals(List.of(ContentCheck.Kind.ARCHIVE, ContentCheck.Kind.ARCHIVE), kinds(archived));
        assertTrue(
                kinds(torrent("Film.2026.1080p", "Film.mkv", 4000 * MB, "Subs.zip", 1 * MB))
                        .isEmpty(),
                "subtitles zipped beside the film");
        assertTrue(kinds(torrent("Photos 2026", "photos.zip", 900 * MB)).isEmpty(), "no promise, no finding");
    }

    @Test
    void aBaitNameInAMediaTorrentIsACaution() {
        TorrentPreview film = torrent(
                "Film",
                "Film.mkv",
                4000 * MB,
                "PASSWORD.txt",
                1L,
                "How_to_play.txt",
                1L,
                "Passwords and Keys.mkv",
                900 * MB,
                "passwordless.txt",
                1L);
        assertEquals(List.of(ContentCheck.Kind.BAIT, ContentCheck.Kind.BAIT), kinds(film));
        assertTrue(
                kinds(torrent("Album [FLAC]", "01 Password.flac", 30 * MB, "02 Unlock.flac", 30 * MB))
                        .isEmpty(),
                "a song named for a word is the song");
        assertTrue(kinds(torrent("Notes", "password.txt", 1L)).isEmpty(), "nothing to lure anyone to");
    }

    @Test
    void onlyWantedFilesAreReportedButAllOfThemMakeTheContext() {
        TorrentPreview film = torrent("Film", "Film.mkv", 4000 * MB, "codec.exe", 1L, "unlock.lnk", 1L);
        ContentCheck c = ContentCheck.of(film, i -> i != 0 && i != 2, BAIT);
        assertEquals(
                List.of(ContentCheck.Kind.PROGRAM),
                c.findings().stream().map(ContentCheck.Finding::kind).toList(),
                "the film skipped still makes it a media torrent; the shortcut skipped is no longer reported");
        assertTrue(ContentCheck.of(film, i -> i == 0, BAIT).isEmpty());
    }

    @Test
    void linesGroupByKindMostSeriousFirstAndCountTheRest() {
        TorrentPreview film = torrent(
                "Film",
                "Film.mkv",
                4000 * MB,
                "Get the codec.url",
                1L,
                "setup.exe",
                1L,
                "patch.exe",
                1L,
                "crack.exe",
                1L);
        ContentCheck c = check(film);
        List<ContentCheck.Line> lines = c.lines();
        assertEquals(2, lines.size());
        assertEquals(ContentCheck.Kind.PROGRAM, lines.get(0).kind());
        assertEquals(
                "A program in a media torrent: setup.exe and 2 more",
                lines.get(0).text());
        assertEquals(
                "A link to a web page in a media torrent: Get the codec.url",
                lines.get(1).text());
        assertEquals(Set.of(1, 2, 3, 4), c.indices());
        assertEquals("This torrent may not be what it says", c.title());
    }

    @Test
    void theCatalogueWordsParseToWholeLowerCasePhrases() {
        assertEquals(
                Set.of("password", "how to play", "codec"), ContentCheck.parseWords(" Password ,How-to_Play,, codec "));
        assertFalse(ContentCheck.baitWords().isEmpty(), "the English catalogue has words");
    }
}
