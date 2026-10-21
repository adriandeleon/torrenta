package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.torrenta.engine.CreatedTorrent;
import com.torrenta.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTextTest {

    @Test
    void aBlankLineStartsTheNextTier() {
        assertEquals(
                List.of(List.of("udp://a/announce", "udp://b/announce"), List.of("https://c/announce")),
                CreateText.tiers("udp://a/announce\n udp://b/announce \n\nhttps://c/announce\n"));
        assertEquals(List.of(), CreateText.tiers(""));
        assertEquals(List.of(), CreateText.tiers(null));
        assertEquals(List.of(), CreateText.tiers("\n\n  \n"));
        assertEquals(
                List.of(List.of("udp://a"), List.of("udp://b")),
                CreateText.tiers("\n\nudp://a\n\n\n\nudp://b\n\n"),
                "runs of blank lines make no empty tier");
        assertEquals(
                List.of(List.of("udp://a"), List.of("udp://b")),
                CreateText.tiers("udp://a\r\n\r\nudp://b\r\nudp://a"),
                "Windows line ends, and a URL seen before stays out");
    }

    @Test
    void tiersGoBackToTextTheSameWay() {
        List<List<String>> tiers = List.of(List.of("udp://a", "udp://b"), List.of("https://c"));
        String text = CreateText.tiersText(tiers);
        assertEquals("udp://a\nudp://b\n\nhttps://c", text);
        assertEquals(tiers, CreateText.tiers(text));
        assertEquals("", CreateText.tiersText(List.of()));
        assertEquals("udp://a", CreateText.tiersText(List.of(List.of(), List.of("udp://a"))));
    }

    @Test
    void linesAreTrimmedAndUnique() {
        assertEquals(List.of("https://a/", "https://b/"), CreateText.lines(" https://a/ \n\nhttps://b/\nhttps://a/"));
        assertEquals(List.of(), CreateText.lines(null));
    }

    @Test
    void theFileNameIsTheSourcesWithTheExtension(@TempDir Path dir) throws Exception {
        assertEquals("Big Buck Bunny.torrent", CreateText.fileName(dir.resolve("Big Buck Bunny")));
        assertEquals("payload.bin.torrent", CreateText.fileName(dir.resolve("payload.bin")));
        Path remembered = dir.resolve("torrents");
        Files.createDirectories(remembered);
        assertEquals(
                remembered,
                CreateText.saveDir(remembered.toString(), dir.resolve("src").resolve("x")));
        assertEquals(
                dir.resolve("src"),
                CreateText.saveDir(
                        dir.resolve("gone").toString(), dir.resolve("src").resolve("x")),
                "a remembered folder that is gone gives way to the source's");
        assertEquals(
                dir.resolve("src"), CreateText.saveDir("", dir.resolve("src").resolve("x")));
    }

    @Test
    void theSummarySaysFilesBytesAndPieces() {
        assertEquals(Messages.tr("dialog.create.sizing"), CreateText.summary(-1, 0, 16 * 1024, 0));
        assertEquals(Messages.tr("dialog.create.empty"), CreateText.summary(0, 0, 16 * 1024, 0));
        assertEquals("1 file · 300.0 KiB · 19 pieces of 16 KiB", CreateText.summary(1, 300 * 1024, 16 * 1024, 19));
        assertEquals(
                "2 files · 1.5 GiB · 1,536 pieces of 1 MiB",
                CreateText.summary(2, 1536L * 1024 * 1024, 1024 * 1024, 1536));
        assertEquals("1 file · 1.0 KiB · 1 piece of 16 KiB", CreateText.summary(1, 1024, 16 * 1024, 1));
        assertEquals("Automatic (16 KiB)", CreateText.pieceSizeLabel(0, 1000));
        assertEquals("Automatic (1 MiB)", CreateText.pieceSizeLabel(0, 1024L * 1024 * 1024));
        assertEquals("4 MiB", CreateText.pieceSizeLabel(4 * 1024 * 1024, 0));
        assertEquals("Hashing · 120 of 1,500 pieces", CreateText.hashing(120, 1500));
        assertEquals(Messages.tr("dialog.create.hashing"), CreateText.hashing(0, 0));
    }

    @Test
    void theOutcomeSaysWhereItWentAndWhetherItSeeds(@TempDir Path dir) {
        Path saved = dir.resolve("a.torrent");
        CreatedTorrent plain =
                new CreatedTorrent(new byte[0], "id", "a", "v1", "", 1, 16, 1, "magnet:?", Optional.empty(), "");
        assertEquals("Saved to " + saved, CreateText.outcome(saved, plain, false));
        assertEquals("Saved to " + saved + " · not added: unknown", CreateText.outcome(saved, plain, true));
        CreatedTorrent refused = new CreatedTorrent(
                new byte[0], "id", "a", "v1", "", 1, 16, 1, "magnet:?", Optional.empty(), "already there");
        assertEquals("Saved to " + saved + " · not added: already there", CreateText.outcome(saved, refused, true));
        CreatedTorrent seeding = new CreatedTorrent(
                new byte[0],
                "id",
                "a",
                "v1",
                "",
                1,
                16,
                1,
                "magnet:?",
                Optional.of(T.downloading().build()),
                "");
        assertEquals("Saved to " + saved + " · added and seeding", CreateText.outcome(saved, seeding, true));
    }

    @Test
    void createNeedsASourceThatExists(@TempDir Path dir) throws Exception {
        assertEquals(Messages.tr("dialog.create.problem.noSource"), CreateText.problem(""));
        assertEquals(Messages.tr("dialog.create.problem.noSource"), CreateText.problem(null));
        Path gone = dir.resolve("gone");
        assertEquals("Not found: " + gone, CreateText.problem(gone.toString()));
        Path file = dir.resolve("file.bin");
        Files.write(file, new byte[1]);
        assertEquals("", CreateText.problem(file.toString()));
        assertEquals("", CreateText.problem(dir.toString()));
        assertTrue(CreateText.problem(dir.getRoot().toString()).startsWith("Choose something below"));
    }
}
