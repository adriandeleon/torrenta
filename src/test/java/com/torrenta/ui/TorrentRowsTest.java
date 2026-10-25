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
                List.of(),
                "",
                java.util.List.of(),
                false);
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

    /** The Queue column reads the position off the row's torrent, so a move re-sorts it in place. */
    @Test
    void aQueuePositionChangeUpdatesTheRowInPlace() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(
                T.downloading().id("a").queued(0).build(),
                T.downloading().id("b").queued(1).build()));
        TorrentRow a = rows.get("a");
        int[] updates = {0};
        rows.list().addListener((javafx.collections.ListChangeListener<TorrentRow>) c -> {
            while (c.next()) if (c.wasUpdated()) updates[0]++;
        });

        rows.apply(List.of(
                T.downloading().id("a").queued(1).build(),
                T.downloading().id("b").queued(0).build()));
        assertSame(a, rows.get("a"));
        assertEquals(1, a.torrent().queuePosition());
        assertEquals(0, rows.get("b").torrent().queuePosition());
        assertEquals(2, updates[0], "the extractor fires an update per changed row, which re-sorts a SortedList");
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
                List.of(),
                "",
                java.util.List.of(),
                false));
        assertEquals(true, MainWindow.rowFilter("", null).test(row));
        assertEquals(true, MainWindow.rowFilter(null, null).test(row));
        assertEquals(true, MainWindow.rowFilter("  desktop ", null).test(row));
        assertEquals(true, MainWindow.rowFilter("UBUNTU", null).test(row));
        assertEquals(false, MainWindow.rowFilter("fedora", null).test(row));
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
                List.of("iso"),
                "",
                java.util.List.of(),
                false));
        SidebarSelection linux = SidebarSelection.ALL.with(SidebarFilter.category("Linux"));
        assertEquals(true, MainWindow.rowFilter("ubuntu", linux).test(row));
        assertEquals(
                false,
                MainWindow.rowFilter("ubuntu", SidebarSelection.ALL.with(SidebarFilter.category("Movies")))
                        .test(row));
        assertEquals(false, MainWindow.rowFilter("fedora", linux).test(row));
        assertEquals(
                true,
                MainWindow.rowFilter("", linux.with(SidebarFilter.tag("iso"))).test(row));
        assertEquals(
                true,
                MainWindow.rowFilter("", linux.with(SidebarFilter.status(SidebarStatus.SEEDING)))
                        .test(row),
                "status, category and name compose");
        assertEquals(
                false,
                MainWindow.rowFilter("", linux.with(SidebarFilter.status(SidebarStatus.DOWNLOADING)))
                        .test(row));
        assertEquals(
                false,
                MainWindow.rowFilter("", linux.with(SidebarFilter.tracker("t.example.org")))
                        .test(row),
                "a trackerless torrent is under no host");
        assertEquals(
                true,
                MainWindow.rowFilter("", linux.with(SidebarFilter.TRACKERLESS)).test(row));
        assertEquals(true, MainWindow.rowFilter("", null).test(row), "no selection is All");

        // A saved view is its query, ANDed with the box and the other sections.
        List<com.torrenta.store.SavedView> views = List.of(
                new com.torrenta.store.SavedView("isos", "ISOs", "tag:iso state:seeding"),
                new com.torrenta.store.SavedView("films", "Films", "cat:Movies"));
        SidebarSelection isos = linux.with(SidebarFilter.view("isos"));
        assertEquals(true, MainWindow.rowFilter("ubuntu", isos, views, 0).test(row));
        assertEquals(false, MainWindow.rowFilter("fedora", isos, views, 0).test(row), "the box still counts");
        assertEquals(
                false,
                MainWindow.rowFilter("", SidebarSelection.ALL.with(SidebarFilter.view("films")), views, 0)
                        .test(row));
        assertEquals(
                true,
                MainWindow.rowFilter("", SidebarSelection.ALL.with(SidebarFilter.view("gone")), views, 0)
                        .test(row),
                "a view deleted since it was chosen filters nothing");
        assertEquals(
                false,
                MainWindow.rowFilter("", SidebarSelection.ALL.with(SidebarFilter.UNTAGGED))
                        .test(row));
        assertEquals(true, MainWindow.rowFilter(null, null).test(row), "no sidebar choice is All");
    }

    @Test
    void theStallClockCountsFromTheFirstQuietTickAndResetsOnAByte() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(t("a", 0)), 1000);
        TorrentRow a = rows.get("a");
        assertEquals(0, a.quietSeconds(), "quiet since now, which is no time yet");
        rows.apply(List.of(t("a", 0)), 1090);
        assertEquals(90, a.quietSeconds());
        rows.apply(List.of(t("a", 5)), 1100);
        assertEquals(0, a.quietSeconds(), "a byte arrived");
        rows.apply(List.of(t("a", 0)), 1200);
        rows.apply(List.of(t("a", 0)), 1230);
        assertEquals(30, a.quietSeconds(), "counted again from the new silence");
    }
}
