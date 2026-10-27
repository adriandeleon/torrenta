package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.TorrentDetail.Peer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** javafx.base only. */
class PeerRowsTest {

    private static Peer peer(String address, long down) {
        return new Peer(address, "qB", "D", 0.5, down, 0, 0, 0);
    }

    private static List<String> addresses(PeerRows rows) {
        return rows.items().stream().map(PeerRows.Row::address).toList();
    }

    @Test
    void theSameAddressIsTheSameRowUpdatedInPlace() {
        PeerRows rows = new PeerRows();
        rows.apply(List.of(peer("10.0.0.1:6881", 0), peer("10.0.0.2:6881", 0)));
        PeerRows.Row first = rows.items().get(0);
        rows.apply(List.of(peer("10.0.0.2:6881", 5), peer("10.0.0.1:6881", 7)));
        assertSame(first, rows.items().get(0), "kept its place, not re-ordered by the engine's list");
        assertEquals(7, first.peer().downRate());
        assertEquals(List.of("10.0.0.1:6881", "10.0.0.2:6881"), addresses(rows));
    }

    @Test
    void aPeerThatLeftGoesAndANewOneComesLast() {
        PeerRows rows = new PeerRows();
        rows.apply(List.of(peer("a", 0), peer("b", 0), peer("c", 0)));
        PeerRows.Row c = rows.items().get(2);
        rows.apply(List.of(peer("c", 1), peer("d", 0), peer("a", 0)));
        assertEquals(List.of("a", "c", "d"), addresses(rows));
        assertSame(c, rows.items().get(1));
        rows.apply(List.of());
        assertTrue(rows.items().isEmpty());
    }

    /** An unchanged snapshot fires nothing: the list and the row's property stay quiet. */
    @Test
    void anUnchangedTickChangesNothing() {
        PeerRows rows = new PeerRows();
        rows.apply(List.of(peer("a", 1)));
        int[] changes = {0};
        rows.items().addListener((javafx.collections.ListChangeListener<PeerRows.Row>) ch -> changes[0]++);
        rows.items().get(0).peerProperty().addListener((o, was, now) -> changes[0]++);
        rows.apply(List.of(peer("a", 1)));
        assertEquals(0, changes[0]);
    }
}
