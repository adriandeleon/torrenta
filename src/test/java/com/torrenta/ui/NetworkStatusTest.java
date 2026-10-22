package com.torrenta.ui;

import java.util.List;
import java.util.Optional;

import com.torrenta.engine.Connectivity;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.TorrentState;
import com.torrenta.ui.NetworkStatus.Dot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure: no toolkit, English catalogue. */
class NetworkStatusTest {

    private static final Connectivity UP = Connectivity.NONE.dht(true).listened(51413, true);

    @Test
    void nothingIsSaidBeforeThePortIsKnown() {
        assertEquals(Optional.empty(), NetworkStatus.port(Connectivity.NONE, 0));
        assertEquals(Optional.empty(), NetworkStatus.dht(Connectivity.NONE.dht(true), 0));
    }

    @Test
    void thePortIsCheckedUntilAPeerComesOrTimeRunsOut() {
        NetworkStatus.Port checking = NetworkStatus.port(UP, 0).orElseThrow();
        assertEquals("Port 51413 · checking reachability", checking.text(), "no grouping in a port number");
        assertEquals(Dot.CHECKING, checking.dot());
        assertEquals(
                Dot.CHECKING,
                NetworkStatus.port(UP, NetworkStatus.BLOCKED_AFTER_SECONDS - 1)
                        .orElseThrow()
                        .dot());

        NetworkStatus.Port blocked =
                NetworkStatus.port(UP, NetworkStatus.BLOCKED_AFTER_SECONDS).orElseThrow();
        assertEquals("Port 51413 · blocked", blocked.text());
        assertEquals(Dot.BLOCKED, blocked.dot());
        assertTrue(blocked.tooltip().contains("5 minutes"), blocked.tooltip());

        NetworkStatus.Port open =
                NetworkStatus.port(UP.connectedFrom("203.0.113.9"), 10_000).orElseThrow();
        assertEquals("Port 51413 · open", open.text());
        assertEquals(Dot.OPEN, open.dot());
    }

    @Test
    void aPortNothingListensOnIsDown() {
        NetworkStatus.Port down =
                NetworkStatus.port(Connectivity.NONE.listened(6881, false), 0).orElseThrow();
        assertEquals("Port 6881 · not listening", down.text());
        assertEquals(Dot.DOWN, down.dot());
        assertFalse(down.tooltip().contains("router"), "no mapping line for a port that is not open");
    }

    @Test
    void theTooltipSaysWhatTheRouterDid() {
        assertTrue(
                NetworkStatus.port(UP.mapped(true), 0).orElseThrow().tooltip().contains("forwarded the port"));
        assertTrue(
                NetworkStatus.port(UP.mapped(false), 0).orElseThrow().tooltip().contains("did not forward"));
        assertFalse(NetworkStatus.port(UP, 0).orElseThrow().tooltip().contains("router"));
    }

    @Test
    void theDhtCountsItsNodesAndSaysWhileItBootstraps() {
        assertEquals(Optional.of("DHT 38 nodes, bootstrapping"), NetworkStatus.dht(UP, 38));
        assertEquals(Optional.of("DHT 1 node"), NetworkStatus.dht(UP.bootstrapped(), 1));
        assertEquals(Optional.of("DHT no nodes"), NetworkStatus.dht(UP.bootstrapped(), 0));
        assertEquals(Optional.of("DHT 1412 nodes"), NetworkStatus.dht(UP.bootstrapped(), 1412));
        assertEquals(Optional.of("DHT on"), NetworkStatus.dht(UP.bootstrapped(), SessionStats.UNKNOWN));
        assertEquals(Optional.of("DHT off"), NetworkStatus.dht(UP.dht(false), 38));
    }

    @Test
    void freeSpaceWarnsWhenLow() {
        long gib = 1024L * 1024 * 1024;
        NetworkStatus.Free plenty =
                NetworkStatus.free(new FreeSpace.Disk(1000 * gib, 412 * gib, "/home"), "~/Downloads");
        assertTrue(plenty.text().endsWith(" free on /home"), plenty.text());
        assertFalse(plenty.low());
        assertTrue(plenty.tooltip().contains("~/Downloads"));
        assertTrue(NetworkStatus.free(new FreeSpace.Disk(1000 * gib, gib, "/home"), "x")
                .low());
    }

    @Test
    void onlyATorrentTalkingToPeersCountsAsRunning() {
        assertFalse(NetworkStatus.anyRunning(List.of()));
        assertTrue(NetworkStatus.anyRunning(List.of(T.downloading().build())));
        assertTrue(NetworkStatus.anyRunning(List.of(T.seeding().build())));
        assertTrue(NetworkStatus.anyRunning(
                List.of(T.downloading().state(TorrentState.FETCHING_METADATA).build())));
        assertFalse(NetworkStatus.anyRunning(List.of(
                T.downloading().paused().build(),
                T.downloading().queued(2).build(),
                T.downloading().error("disk full").build(),
                T.downloading().state(TorrentState.CHECKING).build())));
    }

    @Test
    void theClockCountsRunningTimeOnAnUnprovenPortAndStartsOverOnANewOne() {
        NetworkStatus.Clock clock = new NetworkStatus.Clock();
        assertEquals(0, clock.tick(UP, true, 100), "the first tick only sets the time");
        assertEquals(1, clock.tick(UP, true, 101));
        assertEquals(1, clock.tick(UP, false, 102), "nothing running: nothing to prove");
        assertEquals(2, clock.tick(UP, true, 103));
        assertEquals(7, clock.tick(UP, true, 3_700), "a sleep counts as a few seconds, not an hour");
        assertEquals(7, clock.tick(UP.connectedFrom("8.8.8.8"), true, 3_701), "proven: it stops");
        assertEquals(0, clock.tick(UP.listened(6881, true), true, 3_702), "a new port starts over");
    }
}
