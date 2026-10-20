package com.torrenta.automation;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventCommandTest {

    private static final Path SAVE = Path.of("downloads").toAbsolutePath();

    private static Torrent torrent(String name, String category, List<String> tags) {
        return new Torrent(
                "0123456789abcdef0123456789abcdef01234567",
                name,
                153_600,
                153_600,
                1.0,
                TorrentState.SEEDING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "udp://tracker.example.org:6969/announce",
                SAVE.toString(),
                false,
                false,
                0,
                category,
                tags,
                "",
                List.of("tracker.example.org"),
                false);
    }

    private static TorrentDetail detail(String v1, String v2, String... paths) {
        List<TorrentDetail.FileStatus> files = new java.util.ArrayList<>();
        for (int i = 0; i < paths.length; i++)
            files.add(new TorrentDetail.FileStatus(i, paths[i], 10, 10, Priority.NORMAL));
        TorrentDetail.General general =
                new TorrentDetail.General("id", SAVE.toString(), false, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, "", v1, v2);
        return new TorrentDetail(general, files, List.of(), List.of(), new boolean[0]);
    }

    @Test
    void whitespaceSeparatesAndQuotesKeepAValueTogether() {
        assertEquals(List.of("a", "b", "c"), EventCommand.tokens("a  b\tc"));
        assertEquals(
                List.of("notify", "two words", "three more words"),
                EventCommand.tokens("notify \"two words\" 'three more words'"));
        assertEquals(
                List.of("say \"hi\""), EventCommand.tokens("\"say \\\"hi\\\"\""), "an escaped quote inside quotes");
        assertEquals(List.of("it's"), EventCommand.tokens("\"it's\""), "a single quote inside double quotes is itself");
        assertEquals(List.of("a b"), EventCommand.tokens("a\\ b"), "a backslash keeps a space");
        assertEquals(List.of("C:\\Program Files\\x.exe", "%N"), EventCommand.tokens("\"C:\\Program Files\\x.exe\" %N"));
        assertEquals(
                List.of("C:\\tools\\run.cmd"),
                EventCommand.tokens("C:\\tools\\run.cmd"),
                "other backslashes are literal");
        assertEquals(List.of("ab", "cd"), EventCommand.tokens("a'b' \"c\"d"), "quotes join with what is beside them");
        assertEquals(List.of(""), EventCommand.tokens("\"\""), "an empty quoted value is an empty argument");
        assertEquals(List.of("unterminated quote"), EventCommand.tokens("\"unterminated quote"));
        assertEquals(List.of(), EventCommand.tokens("   "));
        assertEquals(List.of(), EventCommand.tokens(null));
    }

    @Test
    void everyPlaceholderAndTheLiteralPercent() {
        Torrent t = torrent("Some Show S01", "tv", List.of("hd", "new"));
        TorrentDetail d = detail(
                "1111111111111111111111111111111111111111",
                "2".repeat(64),
                "Some Show S01/a.mkv",
                "Some Show S01/sub/b.srt");
        EventCommand.Subject s = EventCommand.subject(t, d);
        assertEquals("Some Show S01", EventCommand.substitute("%N", s));
        assertEquals("tv", EventCommand.substitute("%L", s));
        assertEquals("hd,new", EventCommand.substitute("%G", s));
        assertEquals(SAVE.resolve("Some Show S01").toString(), EventCommand.substitute("%F", s));
        assertEquals(SAVE.resolve("Some Show S01").toString(), EventCommand.substitute("%R", s));
        assertEquals(SAVE.toString(), EventCommand.substitute("%D", s));
        assertEquals("2", EventCommand.substitute("%C", s));
        assertEquals("153600", EventCommand.substitute("%Z", s));
        assertEquals("udp://tracker.example.org:6969/announce", EventCommand.substitute("%T", s));
        assertEquals("1111111111111111111111111111111111111111", EventCommand.substitute("%I", s));
        assertEquals("2".repeat(64), EventCommand.substitute("%J", s));
        assertEquals("0123456789abcdef0123456789abcdef01234567", EventCommand.substitute("%K", s));
        assertEquals("100%", EventCommand.substitute("100%%", s));
        assertEquals("%X stays", EventCommand.substitute("%X stays", s), "an unknown letter is left as written");
        assertEquals("50%", EventCommand.substitute("50%", s), "a trailing percent is itself");
        assertEquals(
                "name=Some Show S01 (2 files, 153600 bytes)",
                EventCommand.substitute("name=%N (%C files, %Z bytes)", s));
    }

    @Test
    void aNameWithSpacesAndQuotesStaysOneArgument() {
        // The name keeps its quotes (a magnet's display name can say anything); the file on disk
        // cannot, on Windows, so the torrent's own file is named without them.
        Torrent t = torrent("It's \"Big\" Movie", "", List.of());
        EventCommand.Subject s = EventCommand.subject(t, detail("", "", "Its Big Movie.mkv"));
        List<String> argv = EventCommand.substitute(EventCommand.tokens("/usr/bin/handle --name %N \"%F\""), s);
        assertEquals(
                List.of(
                        "/usr/bin/handle",
                        "--name",
                        "It's \"Big\" Movie",
                        SAVE.resolve("Its Big Movie.mkv").toString()),
                argv,
                "substitution never re-splits");
        Torrent unnameable = torrent("no\0name", "", List.of());
        assertEquals(
                SAVE,
                EventCommand.subject(unnameable, null).contentPath(),
                "a name no file system takes falls back to the save path rather than failing");
    }

    @Test
    void contentPathIsTheRootFolderOrTheOneFileOrTheSavePath() {
        Torrent t = torrent("solo", "", List.of());
        EventCommand.Subject single = EventCommand.subject(t, detail("", "", "solo.iso"));
        assertEquals(SAVE.resolve("solo.iso"), single.contentPath());
        assertNull(single.rootPath(), "a single file has no root folder");
        assertEquals("", EventCommand.substitute("%R", single));
        assertEquals(1, single.fileCount());

        EventCommand.Subject flat = EventCommand.subject(t, detail("", "", "a.bin", "b.bin"));
        assertEquals(SAVE, flat.contentPath(), "several files side by side: the save path");
        assertNull(flat.rootPath());

        EventCommand.Subject windows = EventCommand.subject(t, detail("", "", "solo\\a.bin", "solo\\sub\\b.bin"));
        assertEquals(SAVE.resolve("solo"), windows.rootPath(), "a backslash separates too");

        EventCommand.Subject noDetail = EventCommand.subject(t, null);
        assertEquals(SAVE.resolve("solo"), noDetail.contentPath(), "without the files, the name stands in");
        assertEquals(0, noDetail.fileCount());
        assertEquals(t.id(), noDetail.infoHashV1(), "a 40-digit id is taken for a v1 hash");
        assertEquals("", noDetail.infoHashV2());
    }

    @Test
    void rootFolderIsTheOneEveryFileIsUnder() {
        assertEquals("pair", EventCommand.rootFolder(List.of("pair/a.bin", "pair/sub/b.bin")));
        assertEquals("", EventCommand.rootFolder(List.of("a.bin")));
        assertEquals("", EventCommand.rootFolder(List.of("pair/a.bin", "other/b.bin")));
        assertEquals("", EventCommand.rootFolder(List.of("pair/a.bin", "b.bin")));
        assertEquals("", EventCommand.rootFolder(List.of()));
    }
}
