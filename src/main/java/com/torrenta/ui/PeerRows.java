package com.torrenta.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.torrenta.engine.TorrentDetail;

/**
 * The Peers tab's rows: one stable object per peer address, its snapshot updated in place each
 * tick, so the selection, the scroll and the Ban Peer menu's target stay on the peer they were
 * on. A peer that left goes, a new one is added at the end, the others keep their places (and a
 * sort the person chose keeps its order until they sort again).
 */
final class PeerRows {

    /** One peer, by address; {@link #peerProperty} is its latest snapshot. */
    static final class Row {
        private final String address;
        private final ObjectProperty<TorrentDetail.Peer> peer;

        Row(TorrentDetail.Peer peer) {
            this.address = peer.address();
            this.peer = new SimpleObjectProperty<>(this, "peer", peer);
        }

        String address() {
            return address;
        }

        TorrentDetail.Peer peer() {
            return peer.get();
        }

        ObjectProperty<TorrentDetail.Peer> peerProperty() {
            return peer;
        }
    }

    private final ObservableList<Row> items = FXCollections.observableArrayList();
    private final Map<String, Row> byAddress = new HashMap<>();

    ObservableList<Row> items() {
        return items;
    }

    /** {@code peers} into the rows: the same address is the same row. FX thread. */
    void apply(List<TorrentDetail.Peer> peers) {
        Set<String> present = new HashSet<>();
        for (TorrentDetail.Peer p : peers) present.add(p.address());
        if (byAddress.keySet().retainAll(present)) items.removeIf(r -> !present.contains(r.address()));
        List<Row> added = new java.util.ArrayList<>();
        for (TorrentDetail.Peer p : peers) {
            Row row = byAddress.get(p.address());
            if (row == null) {
                row = new Row(p);
                byAddress.put(p.address(), row);
                added.add(row);
            } else if (!row.peer().equals(p)) {
                row.peer.set(p);
            }
        }
        if (!added.isEmpty()) items.addAll(added);
    }

    void clear() {
        byAddress.clear();
        items.clear();
    }
}
