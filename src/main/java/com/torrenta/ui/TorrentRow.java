package com.torrenta.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import com.torrenta.engine.Torrent;

/**
 * One row of the transfer list: a stable object for one info-hash, holding the latest snapshot.
 *
 * <p>The table's rows are these, not the snapshots. A snapshot is replaced every tick; the row
 * lives as long as the torrent does, which is what lets the selection, the scroll position and the
 * sort survive the tick. Every column reads through {@link #torrentProperty()}, so a new snapshot
 * updates every cell of the row and nothing else.
 */
public final class TorrentRow {

    private final String id;
    private final ObjectProperty<Torrent> torrent = new SimpleObjectProperty<>(this, "torrent");

    TorrentRow(Torrent first) {
        this.id = first.id();
        this.torrent.set(first);
    }

    public String id() {
        return id;
    }

    public Torrent torrent() {
        return torrent.get();
    }

    public ReadOnlyObjectProperty<Torrent> torrentProperty() {
        return torrent;
    }

    void update(Torrent latest) {
        if (!latest.id().equals(id)) throw new IllegalArgumentException("row " + id + " given " + latest.id());
        torrent.set(latest);
    }
}
