package com.torrenta.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        SortedList<TorrentRow> sorted = new SortedList<>(
                rows.list(), Comparator.comparingInt(r -> r.torrent().queuePosition()));
        assertEquals(List.of("a", "b"), ids(sorted));

        rows.apply(List.of(
                T.downloading().id("a").queued(1).build(),
                T.downloading().id("b").queued(0).build()));
        assertSame(a, rows.get("a"));
        assertEquals(1, a.torrent().queuePosition());
        assertEquals(0, rows.get("b").torrent().queuePosition());
        assertEquals(List.of("b", "a"), ids(sorted), "re-sorted without a sort() call");
    }

    @Test
    void aTickIsOneUpdateChangeOverEveryRowItKept() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(t("a", 1), t("b", 1), t("c", 1)));
        List<String> changes = new ArrayList<>();
        rows.list().addListener((ListChangeListener<TorrentRow>) c -> {
            StringBuilder change = new StringBuilder();
            while (c.next()) {
                if (c.wasUpdated())
                    change.append("update ").append(c.getFrom()).append('-').append(c.getTo());
                else if (c.wasAdded()) change.append("add ").append(c.getAddedSize());
                else if (c.wasRemoved()) change.append("remove ").append(c.getRemovedSize());
                change.append(';');
            }
            changes.add(change.toString());
        });

        rows.apply(List.of(t("a", 2), t("b", 2), t("c", 2)));
        assertEquals(List.of("update 0-3;"), changes, "one change, one range: one sort and one filter pass");

        changes.clear();
        rows.apply(List.of(t("c", 3), t("a", 3), t("d", 3), t("e", 3)));
        assertEquals(List.of("remove 1;", "update 0-2;", "add 2;"), changes);

        changes.clear();
        rows.apply(List.of());
        assertEquals(List.of("remove 4;"), changes, "nothing left to update");
    }

    /** What the extractor did, without the cost: a filter re-tests and a sort re-places a moved row. */
    @Test
    void aFilteredSortedViewFollowsTheTick() {
        TorrentRows rows = new TorrentRows();
        rows.apply(List.of(
                T.downloading().id("a").down(10).build(),
                T.downloading().id("b").down(20).build(),
                T.downloading().id("c").down(30).build()));
        FilteredList<TorrentRow> downloading =
                new FilteredList<>(rows.list(), r -> r.torrent().state() == TorrentState.DOWNLOADING);
        SortedList<TorrentRow> fastest = new SortedList<>(
                downloading, Comparator.comparingLong(r -> -r.torrent().downloadRate()));
        assertEquals(List.of("c", "b", "a"), ids(fastest));

        rows.apply(List.of(
                T.downloading().id("a").down(50).build(),
                T.seeding().id("b").build(),
                T.downloading().id("c").down(30).build()));
        assertEquals(List.of("a", "c"), ids(fastest), "b finished and left the filter; a overtook c");

        rows.apply(List.of(
                T.downloading().id("a").down(50).build(),
                T.downloading().id("b").down(40).build(),
                T.downloading().id("c").down(30).build()));
        assertEquals(List.of("a", "b", "c"), ids(fastest), "and came back where its rate puts it");
    }

    /** The measured cost: an extractor's one change per row re-sorted the whole list per row. */
    @Test
    void aTickSortsOnceNotOncePerRow() {
        int n = 2_000;
        TorrentRows rows = new TorrentRows();
        rows.apply(snapshot(n, 0));
        long[] compares = {0};
        SortedList<TorrentRow> sorted = new SortedList<>(new FilteredList<>(rows.list(), r -> true), (x, y) -> {
            compares[0]++;
            return Long.compare(x.torrent().downloadRate(), y.torrent().downloadRate());
        });
        assertEquals(n, sorted.size());

        compares[0] = 0;
        rows.apply(snapshot(n, 1));
        assertTrue(compares[0] < 40L * n, compares[0] + " compares for " + n + " rows");
        assertEquals("r1999", sorted.get(0).id(), "the rates reversed, and so did the order");
    }

    private static List<Torrent> snapshot(int n, int tick) {
        List<Torrent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(t("r" + i, tick == 0 ? i : n - i));
        return out;
    }

    private static List<String> ids(List<TorrentRow> list) {
        return list.stream().map(TorrentRow::id).toList();
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
