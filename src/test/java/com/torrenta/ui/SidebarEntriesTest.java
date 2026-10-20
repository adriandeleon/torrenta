package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.ui.SidebarEntries.Entry;
import com.torrenta.ui.SidebarEntries.Section;
import com.torrenta.ui.SidebarFilter.Axis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarEntriesTest {

    private static Torrent t(
            String id,
            TorrentState state,
            long rate,
            String category,
            List<String> tags,
            List<String> hosts,
            boolean trackerError) {
        return new Torrent(
                id,
                "name " + id,
                100,
                10,
                0.1,
                state,
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
                category,
                tags,
                "",
                hosts,
                trackerError);
    }

    private static List<String> titles(Section s) {
        return s.entries().stream().map(e -> e.title).toList();
    }

    private static int count(SidebarEntries entries, SidebarFilter f, Axis axis) {
        return entries.find(f, axis).orElseThrow().count.get();
    }

    @Test
    void sectionsListWhatExistsAndWhatIsInUse() {
        SidebarEntries entries = new SidebarEntries();
        assertEquals(
                List.of(
                        "All",
                        "Downloading",
                        "Seeding",
                        "Completed",
                        "Paused",
                        "Active",
                        "Inactive",
                        "Stalled",
                        "Checking",
                        "Errored"),
                titles(entries.status),
                "fixed from the start");
        List<Section> rebuilt = entries.update(
                List.of("Books"),
                List.of(),
                List.of(
                        t("a", TorrentState.DOWNLOADING, 5, "Movies", List.of("hd"), List.of("a.example.org"), false),
                        t("b", TorrentState.SEEDING, 0, "", List.of(), List.of(), false)));
        assertEquals(
                List.of(entries.categories, entries.tags, entries.trackers),
                rebuilt,
                "every section with names is new");
        assertEquals(List.of("All", "Books", "Movies", "Uncategorised"), titles(entries.categories));
        assertEquals(List.of("All", "hd", "Untagged"), titles(entries.tags));
        assertEquals(
                List.of("All", "a.example.org", "Trackerless"),
                titles(entries.trackers),
                "no error, no Tracker error entry");
        assertEquals(2, entries.all.count.get());
        assertEquals(2, count(entries, SidebarFilter.ALL, Axis.STATUS), "a section's All counts everything");
        assertEquals(1, count(entries, SidebarFilter.status(SidebarStatus.DOWNLOADING), Axis.STATUS));
        assertEquals(1, count(entries, SidebarFilter.status(SidebarStatus.STALLED), Axis.STATUS));
        assertEquals(0, count(entries, SidebarFilter.category("Books"), Axis.CATEGORY));
        assertEquals(1, count(entries, SidebarFilter.category("Movies"), Axis.CATEGORY));
        assertEquals(1, count(entries, SidebarFilter.UNTAGGED, Axis.TAG));
        assertEquals(1, count(entries, SidebarFilter.TRACKERLESS, Axis.TRACKER));
    }

    @Test
    void countsMoveInPlaceAndASectionIsRebuiltOnlyWhenItsNamesChange() {
        SidebarEntries entries = new SidebarEntries();
        entries.update(
                List.of(),
                List.of(),
                List.of(t("a", TorrentState.DOWNLOADING, 5, "Movies", List.of("hd"), List.of("a.example.org"), false)));
        Entry movies =
                entries.find(SidebarFilter.category("Movies"), Axis.CATEGORY).orElseThrow();
        Entry host = entries.find(SidebarFilter.tracker("a.example.org"), Axis.TRACKER)
                .orElseThrow();
        Entry active = entries.find(SidebarFilter.status(SidebarStatus.ACTIVE), Axis.STATUS)
                .orElseThrow();
        assertEquals(1, active.count.get());

        List<Section> rebuilt = entries.update(
                List.of(),
                List.of(),
                List.of(
                        t("a", TorrentState.DOWNLOADING, 0, "Movies", List.of("hd"), List.of("a.example.org"), false),
                        t("b", TorrentState.DOWNLOADING, 0, "Movies", List.of("hd"), List.of("a.example.org"), false)));
        assertTrue(rebuilt.isEmpty(), "same names: nothing rebuilt");
        assertSame(
                movies,
                entries.find(SidebarFilter.category("Movies"), Axis.CATEGORY).orElseThrow());
        assertSame(
                host,
                entries.find(SidebarFilter.tracker("a.example.org"), Axis.TRACKER)
                        .orElseThrow());
        assertEquals(2, movies.count.get(), "the count moved on the same entry");
        assertEquals(2, host.count.get());
        assertEquals(0, active.count.get());
        assertEquals(2, entries.all.count.get());

        rebuilt = entries.update(
                List.of(),
                List.of(),
                List.of(t("a", TorrentState.DOWNLOADING, 0, "Movies", List.of("hd"), List.of("a.example.org"), true)));
        assertEquals(List.of(entries.trackers), rebuilt, "a tracker error is a new entry in Trackers only");
        assertEquals(List.of("All", "a.example.org", "Trackerless", "Tracker error"), titles(entries.trackers));
        assertEquals(1, count(entries, SidebarFilter.TRACKER_ERROR, Axis.TRACKER));
        assertSame(
                movies,
                entries.find(SidebarFilter.category("Movies"), Axis.CATEGORY).orElseThrow());

        rebuilt = entries.update(List.of(), List.of(), List.of());
        assertEquals(List.of(entries.categories, entries.tags, entries.trackers), rebuilt);
        assertEquals(List.of("All", "Uncategorised"), titles(entries.categories));
        assertEquals(List.of("All", "Trackerless"), titles(entries.trackers));
        assertEquals(0, entries.all.count.get());
    }

    @Test
    void aChoiceIsListedOrNot() {
        SidebarEntries entries = new SidebarEntries();
        entries.update(
                List.of(), List.of(), List.of(t("a", TorrentState.SEEDING, 0, "Movies", List.of(), List.of(), false)));
        SidebarSelection s = SidebarSelection.ALL
                .with(SidebarFilter.category("Movies"))
                .with(SidebarFilter.tracker("gone.example.org"))
                .with(SidebarFilter.status(SidebarStatus.SEEDING));
        assertTrue(entries.lists(s, Axis.CATEGORY));
        assertTrue(entries.lists(s, Axis.STATUS));
        assertTrue(entries.lists(s, Axis.TAG), "All is always listed");
        assertFalse(entries.lists(s, Axis.TRACKER), "no torrent has that host");
        assertTrue(entries.lists(s.cleared(Axis.TRACKER), Axis.TRACKER));
        List<Torrent> some = List.of(t("a", TorrentState.SEEDING, 0, "Movies", List.of(), List.of(), false));
        assertEquals(List.of(Axis.TRACKER), entries.stale(s, some), "the one axis to clear");
        assertEquals(List.of(), entries.stale(s.cleared(Axis.TRACKER), some));
        entries.update(List.of(), List.of(), List.of());
        assertEquals(List.of(), entries.stale(s, List.of()), "no torrents at all: nothing is concluded");
        assertEquals(
                List.of(Axis.CATEGORY, Axis.TRACKER),
                entries.stale(s, some),
                "but with torrents the category and the host are both gone from the entries");
    }

    @Test
    void chosenEntriesAreTheSelectionsOrTheAllOfAnEmptyAxis() {
        SidebarEntries entries = new SidebarEntries();
        entries.update(
                List.of(), List.of(), List.of(t("a", TorrentState.SEEDING, 0, "Movies", List.of(), List.of(), false)));
        SidebarSelection s = SidebarSelection.ALL.with(SidebarFilter.category("Movies"));
        assertTrue(entries.find(SidebarFilter.category("Movies"), Axis.CATEGORY)
                .orElseThrow()
                .chosenIn(s));
        assertFalse(entries.find(SidebarFilter.ALL, Axis.CATEGORY).orElseThrow().chosenIn(s));
        assertTrue(entries.find(SidebarFilter.ALL, Axis.STATUS).orElseThrow().chosenIn(s), "nothing chosen there");
        assertFalse(entries.all.chosenIn(s), "the top All only when nothing is chosen anywhere");
        assertTrue(entries.all.chosenIn(SidebarSelection.ALL));
        assertFalse(entries.status.heading.chosenIn(SidebarSelection.ALL));
        assertTrue(entries.status.heading.isHeading());
        assertEquals("Status", entries.status.heading.title);
    }
}
