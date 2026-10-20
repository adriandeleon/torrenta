package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The reconcile rule. javafx.base only: no toolkit is needed for properties and lists. */
class TorrentRowsTest {

    private static Torrent t(String id, long rate) {
        return new Torrent(
                id,
                "name " + id,
                100,
                0,
                0,
                TorrentState.DOWNLOADING,
                rate,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                false,
                false,
                0,
                "",
                List.of());
    }

    @Test
    void rowsKeepTheirIdentityAcrossTicks() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(t("a", 1), t("b", 1)));
        TorrentRow a = rows.get("a");
        TorrentRow b = rows.get("b");

        rows.apply(List.of(t("a", 2), t("b", 3)));
        assertSame(a, rows.get("a"), "the same object, so selection and scroll survive");
        assertSame(b, rows.get("b"));
        assertEquals(2, a.torrent().downloadRate(), "but with the new snapshot");
        assertEquals(3, b.torrent().downloadRate());
        assertEquals(List.of(a, b), rows.list());
    }

    @Test
    void newTorrentsAppendAndVanishedOnesLeave() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(t("a", 1), t("b", 1), t("c", 1)));
        TorrentRow b = rows.get("b");

        rows.apply(List.of(t("c", 1), t("b", 1), t("d", 1)));
        assertNull(rows.get("a"));
        assertSame(b, rows.get("b"));
        assertEquals(
                List.of("b", "c", "d"),
                rows.list().stream().map(TorrentRow::id).toList(),
                "existing order kept, new at the end");
    }

    @Test
    void emptySnapshotClearsEverything() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(t("a", 1)));
        rows.apply(List.of());
        assertEquals(0, rows.list().size());
        assertNull(rows.get("a"));
    }

    @Test
    void aRowRefusesAnotherTorrentsSnapshot() {
        TorrentRow row = new TorrentRow(t("a", 1));
        assertThrows(IllegalArgumentException.class, () -> row.update(t("b", 1)));
    }

    @Test
    void nameFilterIsCaseInsensitiveSubstringAndBlankMatchesAll() {
        TorrentRow row = new TorrentRow(new Torrent(
                "a",
                "Ubuntu 26.04 Desktop",
                1,
                0,
                0,
                TorrentState.SEEDING,
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
                false,
                false,
                0,
                "",
                List.of()));
        assertEquals(true, MainWindow.nameFilter("").test(row));
        assertEquals(true, MainWindow.nameFilter(null).test(row));
        assertEquals(true, MainWindow.nameFilter("  desktop ").test(row));
        assertEquals(true, MainWindow.nameFilter("UBUNTU").test(row));
        assertEquals(false, MainWindow.nameFilter("fedora").test(row));
    }

    @Test
    void rowFilterIsTheNameBoxAndTheSidebarTogether() {
        TorrentRow row = new TorrentRow(new Torrent(
                "a",
                "Ubuntu 26.04 Desktop",
                1,
                0,
                0,
                TorrentState.SEEDING,
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
                false,
                false,
                0,
                "Linux",
                List.of("iso")));
        assertEquals(
                true,
                MainWindow.rowFilter("ubuntu", SidebarFilter.category("Linux")).test(row));
        assertEquals(
                false,
                MainWindow.rowFilter("ubuntu", SidebarFilter.category("Movies")).test(row));
        assertEquals(
                false,
                MainWindow.rowFilter("fedora", SidebarFilter.category("Linux")).test(row));
        assertEquals(true, MainWindow.rowFilter("", SidebarFilter.tag("iso")).test(row));
        assertEquals(false, MainWindow.rowFilter("", SidebarFilter.UNTAGGED).test(row));
        assertEquals(true, MainWindow.rowFilter(null, null).test(row), "no sidebar choice is All");
    }
}
