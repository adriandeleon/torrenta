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
                List.of(tags),
                "",
                java.util.List.of(),
                false);
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
        for (SidebarFilter f : List.of(
                SidebarFilter.status(SidebarStatus.SEEDING),
                SidebarFilter.tracker("tracker.example.org"),
                SidebarFilter.TRACKERLESS,
                SidebarFilter.TRACKER_ERROR)) {
            assertEquals(f, SidebarFilter.parse(f.format()), f.format());
        }
        assertEquals(
                "status:seeding", SidebarFilter.status(SidebarStatus.SEEDING).format());
        assertEquals(SidebarFilter.status(SidebarStatus.SEEDING), SidebarFilter.parse("Status: Seeding"));
        assertEquals(SidebarFilter.ALL, SidebarFilter.parse("status:flying"), "a status that is not one");
        assertEquals(SidebarFilter.ALL, new SidebarFilter(SidebarFilter.Kind.STATUS, "flying"));
        assertEquals(
                SidebarFilter.tracker("t.example.org"),
                SidebarFilter.parse("tracker:T.Example.ORG"),
                "hosts are case-free");
        assertEquals(SidebarFilter.TRACKERLESS, SidebarFilter.parse("tracker:"));
        assertEquals(SidebarFilter.TRACKER_ERROR, SidebarFilter.parse("tracker-error"));
        assertEquals(SidebarFilter.UNCATEGORISED, SidebarFilter.parse("Uncategorized"), "either spelling");
        assertEquals(SidebarFilter.UNCATEGORISED, SidebarFilter.parse("category:"), "no name is none");
        assertEquals(SidebarFilter.tag("x"), SidebarFilter.parse(" TAG: x "), "the kind is case-free, the name kept");
        assertEquals(SidebarFilter.UNTAGGED, SidebarFilter.parse("tag:"));
    }

    @Test
    void parseOnAnAxisKeepsOnlyThatAxis() {
        assertEquals(SidebarFilter.category("x"), SidebarFilter.parse("category:x", SidebarFilter.Axis.CATEGORY));
        assertEquals(
                SidebarFilter.ALL,
                SidebarFilter.parse("category:x", SidebarFilter.Axis.TAG),
                "a category remembered under the tag key is nothing");
        assertEquals(SidebarFilter.UNTAGGED, SidebarFilter.parse("untagged", SidebarFilter.Axis.TAG));
        assertEquals(SidebarFilter.TRACKER_ERROR, SidebarFilter.parse("tracker-error", SidebarFilter.Axis.TRACKER));
        assertEquals(
                SidebarFilter.tag("t"), SidebarFilter.parse("tag:t", SidebarFilter.Axis.ALL), "ALL takes anything");
    }

    @Test
    void axes() {
        assertEquals(SidebarFilter.Axis.ALL, SidebarFilter.ALL.axis());
        assertEquals(
                SidebarFilter.Axis.STATUS,
                SidebarFilter.status(SidebarStatus.PAUSED).axis());
        assertEquals(SidebarFilter.Axis.CATEGORY, SidebarFilter.UNCATEGORISED.axis());
        assertEquals(SidebarFilter.Axis.TAG, SidebarFilter.tag("x").axis());
        assertEquals(SidebarFilter.Axis.TRACKER, SidebarFilter.TRACKERLESS.axis());
        assertEquals(SidebarFilter.Axis.TRACKER, SidebarFilter.TRACKER_ERROR.axis());
        assertEquals("tracker", SidebarFilter.Axis.TRACKER.key());
    }

    @Test
    void trackerKindsMatchHostsAndErrors() {
        Torrent one = tracked("a", List.of("a.example.org", "b.example.org"), false);
        Torrent two = tracked("b", List.of("b.example.org"), true);
        Torrent none = tracked("c", List.of(), false);
        List<Torrent> all = List.of(one, two, none);
        assertEquals(1, SidebarFilter.tracker("a.example.org").count(all));
        assertEquals(2, SidebarFilter.tracker("b.example.org").count(all));
        assertEquals(1, SidebarFilter.TRACKERLESS.count(all));
        assertEquals(1, SidebarFilter.TRACKER_ERROR.count(all));
        assertEquals(List.of("a.example.org", "b.example.org"), SidebarFilter.trackerHosts(all), "each once, sorted");
        assertEquals(List.of(), SidebarFilter.trackerHosts(List.of(none)));
    }

    private static Torrent tracked(String id, List<String> hosts, boolean error) {
        Torrent base = t(id, "");
        return new Torrent(
                base.id(),
                base.name(),
                base.size(),
                base.done(),
                base.progress(),
                base.state(),
                base.downloadRate(),
                base.uploadRate(),
                base.peers(),
                base.seeds(),
                base.uploaded(),
                base.downloaded(),
                base.added(),
                base.completed(),
                base.tracker(),
                base.savePath(),
                base.paused(),
                base.sequential(),
                base.seedingTime(),
                base.category(),
                base.tags(),
                "",
                hosts,
                error);
    }

    @Test
    void titles() {
        assertEquals("All", SidebarFilter.ALL.title());
        assertEquals("Seeding", SidebarFilter.status(SidebarStatus.SEEDING).title());
        assertEquals("t.example.org", SidebarFilter.tracker("t.example.org").title());
        assertEquals("Trackerless", SidebarFilter.TRACKERLESS.title());
        assertEquals("Tracker error", SidebarFilter.TRACKER_ERROR.title());
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
