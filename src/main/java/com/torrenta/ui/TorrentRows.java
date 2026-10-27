package com.torrenta.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.collections.ModifiableObservableListBase;
import javafx.collections.ObservableList;

import com.torrenta.engine.Torrent;
import org.jspecify.annotations.Nullable;

/**
 * The transfer list's rows, reconciled in place from each snapshot.
 *
 * <p>Existing rows are updated, new torrents are appended, vanished ones are removed; the list is
 * never replaced. A tick is at most three list changes, whatever the number of rows: the removals,
 * then <em>one</em> update change over every row that got a new snapshot, then the additions. The
 * update is what makes a {@code FilteredList} on top re-test a row whose state moved and a {@code
 * SortedList} re-sort a row whose rate did, in one pass each. An extractor on the row's property did
 * the same with one change per row, and the sorted list re-sorted everything for each: N² compares
 * a tick, half a second at 5,000 rows. The cells do not need the change: they observe the row's
 * torrent property themselves.
 *
 * <p>Not thread-safe: the FX thread owns it, as it owns the table.
 */
public final class TorrentRows {

    private final RowList rows = new RowList();
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
        List<TorrentRow> added = new ArrayList<>();
        for (Torrent t : snapshot) {
            seen.add(t.id());
            TorrentRow row = byId.get(t.id());
            if (row == null) {
                row = new TorrentRow(t, now);
                byId.put(t.id(), row);
                added.add(row);
            } else {
                row.update(t, now);
            }
        }
        if (seen.size() != byId.size()) {
            rows.retain(seen);
            byId.keySet().retainAll(seen);
        }
        // Every row left from before is in the snapshot, so every one of them was just updated.
        rows.updatedAll();
        rows.addAll(added);
    }

    /** An observable list that can say "all of these changed" as one change, which no stock one can. */
    private static final class RowList extends ModifiableObservableListBase<TorrentRow> {
        private final List<TorrentRow> backing = new ArrayList<>();

        @Override
        public TorrentRow get(int index) {
            return backing.get(index);
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        protected void doAdd(int index, TorrentRow element) {
            backing.add(index, element);
        }

        @Override
        protected TorrentRow doSet(int index, TorrentRow element) {
            return backing.set(index, element);
        }

        @Override
        protected TorrentRow doRemove(int index) {
            return backing.remove(index);
        }

        /** The rows whose id is not in {@code ids} leave, as one change. */
        void retain(Set<String> ids) {
            beginChange();
            try {
                removeIf(row -> !ids.contains(row.id()));
            } finally {
                endChange();
            }
        }

        /** One update change over the whole list. */
        void updatedAll() {
            if (backing.isEmpty()) return;
            beginChange();
            try {
                for (int i = 0; i < backing.size(); i++) nextUpdate(i);
            } finally {
                endChange();
            }
        }
    }
}
