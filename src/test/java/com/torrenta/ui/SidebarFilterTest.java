package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarFilterTest {

    private static Torrent t(String id, String category, String... tags) {
        return new Torrent(
                id,
                "name " + id,
                1,
                0,
                0,
                TorrentState.DOWNLOADING,
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
                category,
                List.of(tags));
    }

    private static final List<Torrent> TORRENTS =
            List.of(t("a", "Movies", "hd"), t("b", "Movies"), t("c", "", "hd", "old"), t("d", ""));

    @Test
    void eachKindMatchesWhatItSays() {
        assertEquals(4, SidebarFilter.ALL.count(TORRENTS));
        assertEquals(2, SidebarFilter.category("Movies").count(TORRENTS));
        assertEquals(0, SidebarFilter.category("movies").count(TORRENTS), "names are exact");
        assertEquals(2, SidebarFilter.UNCATEGORISED.count(TORRENTS));
        assertEquals(2, SidebarFilter.tag("hd").count(TORRENTS));
        assertEquals(1, SidebarFilter.tag("old").count(TORRENTS));
        assertEquals(2, SidebarFilter.UNTAGGED.count(TORRENTS));
        assertTrue(SidebarFilter.tag("hd").matches(t("x", "", "hd")));
        assertFalse(SidebarFilter.tag("hd").matches(t("x", "", "h")));
    }

    @Test
    void formatAndParseRoundTrip() {
        for (SidebarFilter f : List.of(
                SidebarFilter.ALL,
                SidebarFilter.category("Linux ISOs"),
                SidebarFilter.UNCATEGORISED,
                SidebarFilter.tag("hd"),
                SidebarFilter.UNTAGGED)) {
            assertEquals(f, SidebarFilter.parse(f.format()), f.format());
        }
        assertEquals("category:Linux ISOs", SidebarFilter.category("Linux ISOs").format());
        assertEquals(SidebarFilter.ALL, SidebarFilter.parse(null));
        assertEquals(SidebarFilter.ALL, SidebarFilter.parse("  "));
        assertEquals(SidebarFilter.ALL, SidebarFilter.parse("status:seeding"), "a kind not yet known");
        assertEquals(SidebarFilter.UNCATEGORISED, SidebarFilter.parse("Uncategorized"), "either spelling");
        assertEquals(SidebarFilter.UNCATEGORISED, SidebarFilter.parse("category:"), "no name is none");
        assertEquals(SidebarFilter.tag("x"), SidebarFilter.parse(" TAG: x "), "the kind is case-free, the name kept");
        assertEquals(SidebarFilter.UNTAGGED, SidebarFilter.parse("tag:"));
    }

    @Test
    void titles() {
        assertEquals("All", SidebarFilter.ALL.title());
        assertEquals("Movies", SidebarFilter.category("Movies").title());
        assertEquals("Uncategorised", SidebarFilter.UNCATEGORISED.title());
        assertEquals("hd", SidebarFilter.tag("hd").title());
        assertEquals("Untagged", SidebarFilter.UNTAGGED.title());
        assertEquals(
                SidebarFilter.ALL,
                new SidebarFilter(SidebarFilter.Kind.ALL, "ignored"),
                "the value belongs to two kinds");
    }

    @Test
    void namesAreTheStoresPlusTheOnesInUse() {
        assertEquals(
                List.of("Books", "Movies"),
                SidebarFilter.names(List.of("Books", " "), TORRENTS, false),
                "in use though not stored; blanks dropped");
        assertEquals(
                List.of("hd", "old", "Zed"),
                SidebarFilter.names(List.of("Zed"), TORRENTS, true),
                "sorted ignoring case");
        assertEquals(List.of(), SidebarFilter.names(List.of(), List.of(), true));
    }
}
