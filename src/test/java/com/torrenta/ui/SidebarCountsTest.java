package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.direct.DirectDownload;
import com.torrenta.ui.SidebarEntries.Entry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarCountsTest {

    private static Torrent t(
            String id,
            TorrentState state,
            boolean paused,
            long down,
            long up,
            String error,
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
                down,
                up,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                paused,
                false,
                0,
                category,
                tags,
                error,
                hosts,
                trackerError);
    }

    /** Every state, paused or not, moving or not, errored, links, and every shape of label. */
    private static List<Torrent> varied() {
        List<Torrent> out = new ArrayList<>();
        String[] categories = {"", "Movies", "movies", "TV", " TV"};
        List<List<String>> tags = List.of(
                List.of(), List.of("hd"), List.of("hd", "old"), List.of("old", "old"), List.of("HD"), List.of(""));
        List<List<String>> hosts = List.of(
                List.of(),
                List.of("tracker.example.org"),
                List.of("tracker.example.org", "udp.opentrackr.org"),
                List.of("udp.opentrackr.org", "udp.opentrackr.org"));
        int n = 0;
        for (TorrentState state : TorrentState.values())
            for (int flags = 0; flags < 16; flags++) {
                boolean paused = (flags & 1) != 0;
                long rate = (flags & 2) != 0 ? 1000 : 0;
                String error = (flags & 4) != 0 ? "disk full" : "";
                // An id with the downloads' prefix is a download from a link.
                String id = (flags & 8) != 0 ? DirectDownload.idOf("https://example.org/" + n) : "t" + n;
                out.add(t(
                        id,
                        state,
                        paused,
                        n % 2 == 0 ? rate : 0,
                        n % 2 == 0 ? 0 : rate,
                        error,
                        categories[n % categories.length],
                        tags.get(n % tags.size()),
                        hosts.get(n % hosts.size()),
                        n % 7 == 0));
                n++;
            }
        return out;
    }

    @Test
    void everyEntryCountsWhatItsFilterCounts() {
        List<Torrent> torrents = varied();
        SidebarEntries entries = new SidebarEntries();
        entries.update(List.of("Movies", "Empty"), List.of("hd", "unused"), torrents);
        SidebarCounts counts = SidebarCounts.of(torrents);
        List<Entry> all = entries.entries();
        assertTrue(all.size() > 25, "the snapshot lists every section: " + all.size());
        for (Entry e : all) {
            assertEquals(e.filter.count(torrents), counts.of(e.filter), e.filter.format());
            assertEquals(e.filter.count(torrents), e.count.get(), "the entry's own count: " + e.filter.format());
        }
        assertTrue(all.stream().anyMatch(e -> e.filter.equals(SidebarFilter.TRACKER_ERROR)));
    }

    @Test
    void filtersNoEntryListsCountAsTheirOwnCountDoes() {
        List<Torrent> torrents = varied();
        SidebarCounts counts = SidebarCounts.of(torrents);
        for (SidebarFilter f : List.of(
                SidebarFilter.category("movies"),
                SidebarFilter.category(" TV"),
                SidebarFilter.category("nobody"),
                new SidebarFilter(SidebarFilter.Kind.CATEGORY, "  "),
                SidebarFilter.tag("HD"),
                SidebarFilter.tag("nobody"),
                new SidebarFilter(SidebarFilter.Kind.TAG, ""),
                SidebarFilter.tracker("UDP.opentrackr.org"),
                SidebarFilter.tracker("nobody.example"),
                SidebarFilter.view("mine"))) assertEquals(f.count(torrents), counts.of(f), f.format());
    }

    @Test
    void aNameTwiceOnOneTorrentIsOneTorrent() {
        List<Torrent> torrents = List.of(t(
                "a",
                TorrentState.DOWNLOADING,
                false,
                0,
                0,
                "",
                "",
                List.of("old", "old"),
                List.of("x.org", "x.org"),
                false));
        SidebarCounts counts = SidebarCounts.of(torrents);
        assertEquals(1, counts.of(SidebarFilter.tag("old")));
        assertEquals(1, counts.of(SidebarFilter.tracker("x.org")));
    }

    @Test
    void noTorrentsCountNothingButAll() {
        SidebarCounts counts = SidebarCounts.of(List.of());
        assertEquals(0, counts.of(SidebarFilter.ALL));
        assertEquals(0, counts.of(SidebarFilter.status(SidebarStatus.PAUSED)));
        assertEquals(0, counts.of(SidebarFilter.UNCATEGORISED));
        assertFalse(counts.anyTrackerError());
    }

    @Test
    void theStatusIsReadOnceWhenTheFilterIsMade() {
        SidebarFilter f = SidebarFilter.parse(" Status: PAUSED ");
        assertEquals(SidebarStatus.PAUSED, f.parsed());
        assertEquals(SidebarStatus.PAUSED, f.status());
        assertEquals(SidebarFilter.status(SidebarStatus.PAUSED), f, "equal to the one made from the enum");
        assertEquals("status:paused", f.format(), "the settings file's form is unchanged");
        assertEquals(null, SidebarFilter.category("x").parsed());
        assertEquals(
                SidebarFilter.status(SidebarStatus.SEEDING),
                new SidebarFilter(SidebarFilter.Kind.STATUS, "seeding", SidebarStatus.PAUSED),
                "the status is derived from the key, whatever is passed");
    }
}
