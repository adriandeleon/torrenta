package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Overview's and the status bar's pure parts: the pieces map, the sparklines, the words. */
class OverviewPartsTest {

    private static final PieceStrip.Cell H = PieceStrip.Cell.HAVE;
    private static final PieceStrip.Cell M = PieceStrip.Cell.MISSING;
    private static final PieceStrip.Cell R = PieceStrip.Cell.RARE;
    private static final PieceStrip.Cell S = PieceStrip.Cell.SKIPPED;

    private static boolean[] bits(String s) {
        boolean[] out = new boolean[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = s.charAt(i) == '1';
        return out;
    }

    @Test
    void fewerPiecesThanCellsIsOneCellEach() {
        assertArrayEquals(new PieceStrip.Cell[] {H, M, H}, PieceStrip.cells(bits("101"), new int[0], 100));
        assertArrayEquals(new PieceStrip.Cell[0], PieceStrip.cells(new boolean[0], new int[0], 100));
    }

    @Test
    void aCellIsHaveOnlyWhenItsWholeRunIs() {
        // Eight pieces over four cells: two each.
        assertArrayEquals(new PieceStrip.Cell[] {H, M, M, H}, PieceStrip.cells(bits("11011011"), new int[0], 4));
    }

    @Test
    void aMissingPieceNobodyHasMakesTheCellRare() {
        int[] availability = {3, 3, 3, 0, 3, 3, 0, 3};
        assertArrayEquals(
                new PieceStrip.Cell[] {H, M, H, H},
                PieceStrip.cells(bits("11011111"), new int[] {3, 3, 3, 3, 3, 3, 3, 3}, 4));
        assertArrayEquals(new PieceStrip.Cell[] {H, R, M, H}, PieceStrip.cells(bits("11101011"), availability, 4));
        // A had piece with no copies elsewhere is no trouble of ours.
        assertArrayEquals(new PieceStrip.Cell[] {H, H, H, H}, PieceStrip.cells(bits("11111111"), availability, 4));
    }

    @Test
    void whatASkippedFileLacksIsNeitherMissingNorRare() {
        boolean[] skipped = {false, false, false, false, false, true, true, true};
        int[] none = {3, 3, 3, 3, 3, 0, 0, 0};
        // Pieces 5–7 are the skipped file's, missing and on nobody: still no cause for alarm.
        assertArrayEquals(new PieceStrip.Cell[] {H, H, S, S}, PieceStrip.cells(bits("11111000"), none, skipped, 4));
        // A wanted piece missing beside a skipped one makes the run missing, or rare.
        assertArrayEquals(new PieceStrip.Cell[] {H, M, S, S}, PieceStrip.cells(bits("11011000"), none, skipped, 4));
        assertArrayEquals(
                new PieceStrip.Cell[] {H, H, R, S},
                PieceStrip.cells(bits("11110000"), new int[] {3, 3, 3, 3, 0, 0, 0, 0}, skipped, 4));
    }

    @Test
    void piecesOfSkippedFilesOnlyAreTheSkippedOnes() {
        var general = new com.torrenta.engine.TorrentDetail.General(
                "id", "/d", false, 8, 1024, 0, 1, 0, 0, 0, 0, 0, 0, 0, "", "", "");
        java.util.function.Function<com.torrenta.engine.Priority, com.torrenta.engine.TorrentDetail> detail =
                p -> new com.torrenta.engine.TorrentDetail(
                        general,
                        List.of(
                                new com.torrenta.engine.TorrentDetail.FileStatus(
                                        0, "a", 4500, 0, com.torrenta.engine.Priority.NORMAL, 0, 4),
                                new com.torrenta.engine.TorrentDetail.FileStatus(1, "b", 3000, 0, p, 4, 7)),
                        List.of(),
                        List.of(),
                        new boolean[8]);
        // Piece 4 is shared with the wanted file, so it is wanted.
        assertArrayEquals(
                new boolean[] {false, false, false, false, false, true, true, true},
                PieceStrip.skipped(detail.apply(com.torrenta.engine.Priority.IGNORE)));
        assertArrayEquals(new boolean[8], PieceStrip.skipped(detail.apply(com.torrenta.engine.Priority.NORMAL)));
        // Unknown ranges (an engine that does not say): nothing is waved away.
        var unknown = new com.torrenta.engine.TorrentDetail(
                general,
                List.of(new com.torrenta.engine.TorrentDetail.FileStatus(
                        0, "a", 4500, 0, com.torrenta.engine.Priority.IGNORE)),
                List.of(),
                List.of(),
                new boolean[8]);
        assertArrayEquals(new boolean[8], PieceStrip.skipped(unknown));
    }

    @Test
    void everyPieceIsInExactlyOneCell() {
        // Ten pieces over three cells: 3, 3, 4. The last piece alone is missing.
        assertArrayEquals(new PieceStrip.Cell[] {H, H, M}, PieceStrip.cells(bits("1111111110"), new int[0], 3));
        assertArrayEquals(new PieceStrip.Cell[] {M, H, H}, PieceStrip.cells(bits("0111111111"), new int[0], 3));
    }

    @Test
    void columnsFillTheWidth() {
        assertEquals(1, PieceStrip.columns(0));
        assertEquals(1, PieceStrip.columns(10));
        assertEquals(2, PieceStrip.columns(23));
        assertEquals(40, PieceStrip.columns(40 * 13 - 3));
    }

    private static SpeedHistory.Sample s(long at, long down, long up) {
        return new SpeedHistory.Sample(at, down, up);
    }

    @Test
    void aSparklineRunsFromTheWindowsLeftEdgeToNow() {
        List<SpeedHistory.Sample> samples = List.of(s(40, 0, 0), s(70, 1000, 0), s(100, 2000, 0));
        double[] line = Sparkline.points(samples, 60, 100, 60, 12, 2000, true);
        // The sample at 40 is the left edge, 70 the middle, 100 the right; 0 is a pixel above the
        // bottom, the ceiling a pixel below the top.
        assertArrayEquals(new double[] {0, 11, 30, 6, 60, 1}, line, 1e-9);
    }

    @Test
    void aSparklineWithNoOrOneSampleIsFlat() {
        assertArrayEquals(new double[] {0, 11, 60, 11}, Sparkline.points(List.of(), 60, 100, 60, 12, 2000, true));
        assertArrayEquals(
                new double[] {0, 6, 60, 6},
                Sparkline.points(List.of(s(100, 0, 1000)), 60, 100, 60, 12, 2000, false),
                1e-9);
    }

    @Test
    void theScaleIsOneForBothLinesAndNeverATrickle() {
        List<SpeedHistory.Sample> samples = List.of(s(1, 800, 100), s(2, 100, 1600));
        assertEquals(1800, Sparkline.ceiling(samples, Sparkline.Series.BOTH));
        assertEquals(1024 + 0, Sparkline.ceiling(samples, Sparkline.Series.DOWN));
        assertEquals(1800, Sparkline.ceiling(samples, Sparkline.Series.UP));
        assertEquals(Sparkline.FLOOR, Sparkline.ceiling(List.of(), Sparkline.Series.BOTH));
    }

    @Test
    void theSessionLineStartsAtZeroFromTheLeftEdge() {
        assertArrayEquals(
                new double[] {0, 11, 50, 11, 50, 4, 60, 2}, Sparkline.zeroBefore(new double[] {50, 4, 60, 2}, 12));
        double[] fromTheEdge = {0, 4, 60, 2};
        assertArrayEquals(fromTheEdge, Sparkline.zeroBefore(fromTheEdge, 12));
    }

    @Test
    void theAreaClosesDownToTheBaseline() {
        assertArrayEquals(new double[] {0, 4, 60, 2, 60, 12, 0, 12}, Sparkline.area(new double[] {0, 4, 60, 2}, 12));
        assertArrayEquals(new double[0], Sparkline.area(new double[] {0, 4}, 12));
    }

    @Test
    void theWindowShrinksToTheHistoryInWholeMinutes() {
        assertEquals(60, Sparkline.window(List.of(), 1000, 1800));
        assertEquals(60, Sparkline.window(List.of(s(990, 0, 0)), 1000, 1800));
        assertEquals(120, Sparkline.window(List.of(s(900, 0, 0)), 1000, 1800));
        assertEquals(1800, Sparkline.window(List.of(s(-5000, 0, 0)), 1000, 1800));
    }

    @Test
    void aBlendedHistoryIsMinutesThenSeconds() {
        SpeedHistory h = new SpeedHistory();
        // Twenty minutes of one byte a second, then the last ten at two.
        long now = 6000;
        for (long t = now - 1199; t <= now; t++) h.add(t, t > now - 600 ? 2 : 1, 0);
        List<SpeedHistory.Sample> blended = h.blended(1800, now);
        List<SpeedHistory.Sample> fine = h.samples(600, now);
        assertEquals(fine, blended.subList(blended.size() - 600, blended.size()));
        // The minutes before the seconds, none reaching into them.
        List<SpeedHistory.Sample> minutes = blended.subList(0, blended.size() - 600);
        assertEquals(10, minutes.size());
        for (SpeedHistory.Sample m : minutes) assertEquals(1, m.download());
        assertEquals(h.samples(60, now), h.blended(60, now));
    }

    private static Torrent torrent(long size, long done, long uploaded, long downloaded, String category, long added) {
        return new Torrent(
                "id",
                "Sintel",
                size,
                done,
                size == 0 ? 0 : (double) done / size,
                com.torrenta.engine.TorrentState.DOWNLOADING,
                0,
                0,
                23,
                0,
                uploaded,
                downloaded,
                added,
                0,
                "",
                "/data",
                false,
                false,
                0,
                category,
                List.of(),
                "",
                List.of(),
                false);
    }

    @Test
    void theHeaderLineLeavesOutWhatItDoesNotKnow() {
        long gb = 1L << 30;
        long now = 1_000_000;
        assertEquals(
                "6.2 GiB · 4 files · Films · added 2 d ago",
                OverviewText.subtitle(torrent((long) (6.2 * gb), 0, 0, 0, "Films", now - 2 * 86400 - 5), 4, now));
        assertEquals("1 file", OverviewText.subtitle(torrent(0, 0, 0, 0, "", 0), 1, now));
        assertEquals("", OverviewText.subtitle(torrent(0, 0, 0, 0, "", 0), 0, now));
    }

    @Test
    void theFactsSayItTheShortWay() {
        long gb = 1L << 30;
        Torrent t = torrent(10 * gb, 6 * gb, gb, 6 * gb, "", 0);
        assertEquals("23 connected · no seeds", OverviewText.peers(t));
        assertEquals("6.0 GiB of 10.0 GiB", OverviewText.downloaded(t));
        assertEquals("1.0 GiB · ratio 0.17", OverviewText.uploaded(t));
        assertEquals("10.0 GiB", OverviewText.downloaded(torrent(10 * gb, 10 * gb, 0, 0, "", 0)));
        assertEquals("0 B", OverviewText.uploaded(torrent(10 * gb, 0, 0, 0, "", 0)));
        assertEquals("1,153 of 1,184 · 97.4 %", OverviewText.pieces(1153, 1184));
    }

    @Test
    void aHashIsShortenedToItsEnds() {
        assertEquals("7a3f 02c1 … 9e4d c912", OverviewText.shortHash("7a3f02c1aaaaaaaaaaaaaaaaaaaaaaaa9e4dc912"));
        assertEquals("abcd ef", OverviewText.shortHash("abcdef"));
        assertEquals("—", OverviewText.shortHash(""));
    }
}
