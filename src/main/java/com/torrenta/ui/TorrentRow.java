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
    /** Epoch seconds since when it has been quiet ({@link RowState#quiet}); 0 while it is not. */
    private long quietSince;
    /** As of the last update, so every cell of one tick reads the same number. */
    private long quietSeconds;

    TorrentRow(Torrent first) {
        this(first, 0);
    }

    TorrentRow(Torrent first, long now) {
        this.id = first.id();
        clock(first, now);
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

    /**
     * Seconds it has been downloading without receiving anything, as of the last update. Counted
     * from when this row first saw it quiet: a torrent that was already stalled at startup shows
     * the time since startup, which is the most anyone can know.
     */
    public long quietSeconds() {
        return quietSeconds;
    }

    void update(Torrent latest) {
        update(latest, 0);
    }

    void update(Torrent latest, long now) {
        if (!latest.id().equals(id)) throw new IllegalArgumentException("row " + id + " given " + latest.id());
        // Before the property fires: its listeners (the cells) read quietSeconds.
        clock(latest, now);
        torrent.set(latest);
    }

    private void clock(Torrent t, long now) {
        if (!RowState.quiet(t)) {
            quietSince = 0;
        } else if (quietSince == 0) {
            quietSince = now;
        }
        quietSeconds = quietSince == 0 ? 0 : Math.max(0, now - quietSince);
    }
}
