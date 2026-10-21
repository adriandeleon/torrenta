package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.SessionStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTextTest {

    private static final SessionStats BUSY = new SessionStats(
            1536,
            3 * 1024 * 1024,
            10L * 1024 * 1024 * 1024,
            5L * 1024 * 1024 * 1024,
            3725,
            90_000,
            2000,
            4000,
            512,
            12,
            3,
            345,
            64,
            128,
            4,
            2,
            new SessionStats.Torrents(1, 2, 3, 4, 5, 6));

    private static String value(List<StatsText.Row> rows, String section, String label) {
        return rows.stream()
                .filter(r -> r.section().equals(section) && r.label().equals(label))
                .findFirst()
                .orElseThrow()
                .value();
    }

    @Test
    void rowsFormatBytesRatiosDurationsAndCounts() {
        List<StatsText.Row> rows = StatsText.rows(BUSY);
        assertEquals("1.5 KiB", value(rows, "This session", "Downloaded"));
        assertEquals("3.0 MiB", value(rows, "This session", "Uploaded"));
        assertEquals("2048.00", value(rows, "This session", "Share ratio"));
        assertEquals("1h 2m", value(rows, "This session", "Active"));
        assertEquals("512 B", value(rows, "This session", "Wasted"));
        assertEquals("10.0 GiB", value(rows, "All time", "Downloaded"));
        assertEquals("0.50", value(rows, "All time", "Share ratio"));
        assertEquals("1d 1h", value(rows, "All time", "Active"));
        assertEquals("12", value(rows, "Network", "Connected peers"));
        assertEquals("345", value(rows, "Network", "DHT nodes"));
        assertEquals("1.0 MiB", value(rows, "Disk", "Read"), "64 blocks of 16 KiB");
        assertEquals("2.0 MiB", value(rows, "Disk", "Written"));
        assertEquals("64.0 KiB", value(rows, "Disk", "Buffers in use"));
        assertEquals("2", value(rows, "Disk", "Queued jobs"));
        assertEquals("2", value(rows, "Torrents", "Downloading"));
        assertEquals("3", value(rows, "Torrents", "Seeding"));
        assertEquals("1", value(rows, "Torrents", "Checking"));
        assertEquals("6", value(rows, "Torrents", "Errored"));
    }

    @Test
    void nothingYetIsZerosAndDashes() {
        List<StatsText.Row> rows = StatsText.rows(SessionStats.NONE);
        assertEquals("0 B", value(rows, "This session", "Downloaded"));
        assertEquals("—", value(rows, "This session", "Share ratio"), "nothing has moved either way");
        assertEquals("0s", value(rows, "This session", "Active"));
        assertEquals("0", value(rows, "Network", "Connected peers"));
    }

    @Test
    void aCounterTheLibraryLacksIsADash() {
        SessionStats partial = new SessionStats(
                0,
                0,
                0,
                0,
                0,
                0,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                new SessionStats.Torrents(-1, -1, -1, -1, -1, -1));
        List<StatsText.Row> rows = StatsText.rows(partial);
        assertEquals("—", value(rows, "This session", "Wasted"));
        assertEquals("—", value(rows, "Network", "DHT nodes"));
        assertEquals("—", value(rows, "Disk", "Read"));
        assertEquals("—", value(rows, "Torrents", "Seeding"));
    }

    @Test
    void theOrderAndTheLabelsNeverChange() {
        List<StatsText.Row> a = StatsText.rows(BUSY);
        List<StatsText.Row> b = StatsText.rows(SessionStats.NONE);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).section(), b.get(i).section());
            assertEquals(a.get(i).label(), b.get(i).label());
        }
        assertEquals(
                StatsText.SECTIONS,
                a.stream().map(StatsText.Row::section).distinct().toList(),
                "the sections appear in their order, each once");
        assertEquals("This session", a.get(0).section());
    }

    @Test
    void ratioUsesTheColumnsRule() {
        assertEquals(
                "∞",
                Format.ratio(
                        new SessionStats(0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SessionStats.Torrents.NONE)
                                .sessionRatio()));
        assertEquals(
                0.5,
                new SessionStats(10, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SessionStats.Torrents.NONE)
                        .sessionRatio());
    }

    @Test
    void legendAndSummary() {
        assertEquals("↓ 1.0 KiB/s  (peak 2.0 KiB/s)", StatsText.legend("↓", 1024, 2048));
        String summary = StatsText.summary(BUSY);
        assertTrue(summary.startsWith("Downloaded=1.5 KiB | Uploaded=3.0 MiB | "), summary);
        assertTrue(summary.endsWith("Errored=6"), summary);
    }
}
