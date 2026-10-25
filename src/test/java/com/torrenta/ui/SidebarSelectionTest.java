package com.torrenta.ui;

import java.util.List;
import java.util.Map;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.ui.SidebarFilter.Axis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarSelectionTest {

    private static Torrent t(
            TorrentState state, boolean paused, long rate, String category, List<String> tags, List<String> hosts) {
        return new Torrent(
                "id", "n", 100, 10, 0.1, state, rate, 0, 0, 0, 0, 0, 0, 0, "", "", paused, false, 0, category, tags, "",
                hosts, false);
    }

    private static final SidebarFilter SEEDING = SidebarFilter.status(SidebarStatus.SEEDING);
    private static final SidebarFilter MOVIES = SidebarFilter.category("Movies");
    private static final SidebarFilter HD = SidebarFilter.tag("hd");
    private static final SidebarFilter HOST = SidebarFilter.tracker("t.example.org");

    @Test
    void everyAxisMustPass() {
        SidebarSelection s =
                SidebarSelection.ALL.with(SEEDING).with(MOVIES).with(HD).with(HOST);
        Torrent yes = t(TorrentState.SEEDING, false, 0, "Movies", List.of("hd"), List.of("t.example.org"));
        assertTrue(s.matches(yes));
        assertFalse(
                s.matches(t(TorrentState.DOWNLOADING, false, 0, "Movies", List.of("hd"), List.of("t.example.org"))));
        assertFalse(s.matches(t(TorrentState.SEEDING, false, 0, "Books", List.of("hd"), List.of("t.example.org"))));
        assertFalse(s.matches(t(TorrentState.SEEDING, false, 0, "Movies", List.of(), List.of("t.example.org"))));
        assertFalse(s.matches(t(TorrentState.SEEDING, false, 0, "Movies", List.of("hd"), List.of())));
        assertTrue(SidebarSelection.ALL.matches(yes));
        assertTrue(SidebarSelection.ALL.isAll());
        assertFalse(s.isAll());
    }

    @Test
    void withReplacesItsAxisAndAllClearsEverything() {
        SidebarSelection s = SidebarSelection.ALL.with(SEEDING).with(MOVIES);
        assertEquals(SEEDING, s.status());
        assertEquals(MOVIES, s.category());
        assertEquals(SidebarFilter.ALL, s.tag());
        SidebarSelection paused = s.with(SidebarFilter.status(SidebarStatus.PAUSED));
        assertEquals(SidebarStatus.PAUSED, paused.status().status(), "one choice per axis");
        assertEquals(MOVIES, paused.category(), "the other axes stay");
        assertEquals(SidebarSelection.ALL, s.with(SidebarFilter.ALL), "the top All clears every axis");
        assertEquals(SidebarSelection.ALL.with(MOVIES), s.cleared(Axis.STATUS));
        assertEquals(
                SidebarSelection.ALL.with(MOVIES),
                s.choose(SidebarFilter.ALL, Axis.STATUS),
                "a section's All clears it alone");
        assertEquals(s.with(HD), s.choose(HD, Axis.TAG));
        assertEquals(SidebarSelection.ALL, s.choose(SidebarFilter.ALL, Axis.ALL));
    }

    @Test
    void onAndHas() {
        SidebarSelection s = SidebarSelection.ALL.with(SEEDING).with(HOST);
        assertEquals(SEEDING, s.on(Axis.STATUS));
        assertEquals(SidebarFilter.ALL, s.on(Axis.CATEGORY));
        assertEquals(SidebarFilter.ALL, s.on(Axis.ALL));
        assertTrue(s.has(SEEDING));
        assertTrue(s.has(HOST));
        assertFalse(s.has(MOVIES));
        assertFalse(s.has(SidebarFilter.ALL), "All is chosen only when nothing is");
        assertTrue(SidebarSelection.ALL.has(SidebarFilter.ALL));
    }

    @Test
    void aFilterOnTheWrongAxisIsIgnoredByTheConstructor() {
        SidebarSelection s = new SidebarSelection(MOVIES, SEEDING, null, SidebarFilter.TRACKERLESS);
        assertEquals(SidebarFilter.ALL, s.status());
        assertEquals(SidebarFilter.ALL, s.category());
        assertEquals(SidebarFilter.ALL, s.tag());
        assertEquals(SidebarFilter.TRACKERLESS, s.tracker());
    }

    @Test
    void formatAndParseRoundTripThroughTheSettingsKeys() {
        SidebarSelection s =
                SidebarSelection.ALL.with(SEEDING).with(MOVIES).with(HD).with(HOST);
        Map<Axis, String> keys = s.format();
        assertEquals("status:seeding", keys.get(Axis.STATUS));
        assertEquals("category:Movies", keys.get(Axis.CATEGORY));
        assertEquals("tag:hd", keys.get(Axis.TAG));
        assertEquals("tracker:t.example.org", keys.get(Axis.TRACKER));
        assertEquals(
                s,
                SidebarSelection.parse(
                        keys.get(Axis.STATUS), keys.get(Axis.CATEGORY), keys.get(Axis.TAG), keys.get(Axis.TRACKER)));
        assertEquals(SidebarSelection.ALL, SidebarSelection.parse(null, "", "all", "nonsense"));
        assertEquals(
                SidebarSelection.ALL.with(HD),
                SidebarSelection.parse("category:Movies", "status:seeding", "tag:hd", ""),
                "a value under the wrong key is dropped, not moved");
        assertEquals("status:seeding category:Movies tag:hd tracker:t.example.org", s.describe());
        assertEquals("all", SidebarSelection.ALL.describe());
        assertEquals("tag:hd", SidebarSelection.ALL.with(HD).describe());
    }

    @Test
    void aViewIsAnAxisOfItsOwnKeptAndReadBackLikeTheOthers() {
        SidebarFilter seeding = SidebarFilter.view("needs-seeding");
        SidebarSelection s = SidebarSelection.ALL.with(HD).with(seeding);
        assertEquals(seeding, s.view());
        assertEquals(HD, s.tag(), "choosing a view leaves the other sections alone");
        assertEquals(SidebarFilter.ALL, s.cleared(Axis.VIEW).view());
        assertEquals("view:needs-seeding tag:hd", s.describe());
        assertEquals("view:needs-seeding", s.format().get(Axis.VIEW));
        assertEquals(s, SidebarSelection.parse("all", "all", "tag:hd", "all", "view:needs-seeding"));
        assertEquals(
                SidebarFilter.ALL,
                SidebarSelection.parse(null, null, null, null, "tag:hd").view(),
                "a remembered view that is not one is nothing");
        assertTrue(s.has(seeding));
        assertEquals(SidebarFilter.ALL, SidebarFilter.parse("view:"), "a view with no slug is no view");
    }
}
