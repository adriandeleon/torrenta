package com.torrenta.ui;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SidebarStatusTest {

    private static Torrent t(TorrentState state, boolean paused, long down, long up, String error) {
        return new Torrent(
                "id", "n", 100, 10, 0.1, state, down, up, 0, 0, 0, 0, 0, 0, "", "", paused, false, 0, "", List.of(),
                error, List.of(), false);
    }

    private static Set<SidebarStatus> of(Torrent t) {
        Set<SidebarStatus> out = EnumSet.noneOf(SidebarStatus.class);
        for (SidebarStatus s : SidebarStatus.values()) if (s.matches(t)) out.add(s);
        return out;
    }

    @Test
    void eachTorrentIsInTheStatusesItIsIn() {
        assertEquals(
                EnumSet.of(SidebarStatus.DOWNLOADING, SidebarStatus.ACTIVE),
                of(t(TorrentState.DOWNLOADING, false, 1000, 0, "")),
                "downloading at speed");
        assertEquals(
                EnumSet.of(SidebarStatus.DOWNLOADING, SidebarStatus.INACTIVE, SidebarStatus.STALLED),
                of(t(TorrentState.DOWNLOADING, false, 0, 0, "")),
                "downloading with no peer is stalled");
        assertEquals(
                EnumSet.of(SidebarStatus.DOWNLOADING, SidebarStatus.INACTIVE, SidebarStatus.STALLED),
                of(t(TorrentState.FETCHING_METADATA, false, 0, 0, "")),
                "a magnet waiting for metadata is downloading");
        assertEquals(
                EnumSet.of(SidebarStatus.SEEDING, SidebarStatus.COMPLETED, SidebarStatus.ACTIVE),
                of(t(TorrentState.SEEDING, false, 0, 500, "")),
                "uploading");
        assertEquals(
                EnumSet.of(
                        SidebarStatus.SEEDING, SidebarStatus.COMPLETED, SidebarStatus.INACTIVE, SidebarStatus.STALLED),
                of(t(TorrentState.SEEDING, false, 0, 0, "")),
                "a seed nobody wants is stalled");
        assertEquals(
                EnumSet.of(
                        SidebarStatus.SEEDING, SidebarStatus.COMPLETED, SidebarStatus.INACTIVE, SidebarStatus.STALLED),
                of(t(TorrentState.FINISHED, false, 0, 0, "")),
                "finished with files left out counts as complete");
        assertEquals(
                EnumSet.of(SidebarStatus.PAUSED, SidebarStatus.INACTIVE),
                of(t(TorrentState.DOWNLOADING, true, 0, 0, "")),
                "paused is neither downloading nor stalled");
        assertEquals(
                EnumSet.of(SidebarStatus.PAUSED, SidebarStatus.COMPLETED, SidebarStatus.INACTIVE),
                of(t(TorrentState.SEEDING, true, 0, 0, "")),
                "a paused seed is still complete");
        assertEquals(
                EnumSet.of(SidebarStatus.CHECKING, SidebarStatus.INACTIVE),
                of(t(TorrentState.CHECKING, false, 0, 0, "")));
        assertEquals(
                EnumSet.of(SidebarStatus.DOWNLOADING, SidebarStatus.INACTIVE, SidebarStatus.ERRORED),
                of(t(TorrentState.DOWNLOADING, false, 0, 0, "disk full")),
                "an errored torrent is not stalled, it is errored");
        assertEquals(
                EnumSet.of(SidebarStatus.ACTIVE),
                of(t(TorrentState.UNKNOWN, false, 1, 0, "")),
                "an unknown state is only what its rates say");
    }

    @Test
    void keysRoundTripAndTitlesRead() {
        for (SidebarStatus s : SidebarStatus.values()) {
            assertEquals(Optional.of(s), SidebarStatus.parse(s.key()), s.key());
            assertEquals(Optional.of(s), SidebarStatus.parse(" " + s.title().toUpperCase() + " "));
        }
        assertEquals("downloading", SidebarStatus.DOWNLOADING.key());
        assertEquals("Tracker error".length() > 0, true);
        assertEquals(Optional.empty(), SidebarStatus.parse("flying"));
        assertEquals(Optional.empty(), SidebarStatus.parse(null));
        assertEquals(
                List.of(
                        "Downloading",
                        "Seeding",
                        "Completed",
                        "Paused",
                        "Active",
                        "Inactive",
                        "Stalled",
                        "Checking",
                        "Errored"),
                java.util.Arrays.stream(SidebarStatus.values())
                        .map(SidebarStatus::title)
                        .toList(),
                "qBittorrent's order");
    }
}
