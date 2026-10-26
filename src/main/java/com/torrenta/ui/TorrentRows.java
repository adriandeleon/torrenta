package com.torrenta.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.torrenta.engine.Torrent;
import org.jspecify.annotations.Nullable;

/**
 * The transfer list's rows, reconciled in place from each snapshot.
 *
 * <p>Existing rows are updated, new torrents are appended, vanished ones are removed; the list is
 * never replaced. The list is built with an extractor on the row's torrent property, so an update
 * fires a list <em>update</em> change — which is what makes a {@code SortedList} on top of it
 * re-sort a row whose rate just changed without anybody calling {@code sort()}.
 *
 * <p>Not thread-safe: the FX thread owns it, as it owns the table.
 */
public final class TorrentRows {

    private final ObservableList<TorrentRow> rows =
            FXCollections.observableArrayList(row -> new Observable[] {row.torrentProperty()});
    private final Map<String, TorrentRow> byId = new HashMap<>();

    public ObservableList<TorrentRow> list() {
        return rows;
    }

    public @Nullable TorrentRow get(String id) {
        return byId.get(id);
    }

    public void apply(List<Torrent> snapshot) {
        apply(snapshot, System.currentTimeMillis() / 1000);
    }

    /** @param now epoch seconds, for the rows' stall clocks */
    public void apply(List<Torrent> snapshot, long now) {
        Set<String> seen = new HashSet<>();
        for (Torrent t : snapshot) {
            seen.add(t.id());
            TorrentRow row = byId.get(t.id());
            if (row == null) {
                row = new TorrentRow(t, now);
                byId.put(t.id(), row);
                rows.add(row);
            } else {
                row.update(t, now);
            }
        }
        if (seen.size() != byId.size()) {
            rows.removeIf(row -> !seen.contains(row.id()));
            byId.keySet().retainAll(seen);
        }
    }
}
