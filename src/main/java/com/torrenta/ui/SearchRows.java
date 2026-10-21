package com.torrenta.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.torrenta.search.SearchResult;

/**
 * The results table's rows, reconciled in place from each merged list as the providers answer one
 * by one: a result already shown keeps its row (and the selection on it), new ones are appended in
 * the merged order, and ones that fell out of the merge are removed. {@link TorrentRows}' shape;
 * the list has an extractor on the row's result property so a sorted view re-sorts an updated row.
 *
 * <p>Not thread-safe: the FX thread owns it, as it owns the table.
 */
public final class SearchRows {

    private final ObservableList<SearchRow> rows =
            FXCollections.observableArrayList(row -> new Observable[] {row.resultProperty()});
    private final Map<String, SearchRow> byId = new HashMap<>();

    public ObservableList<SearchRow> list() {
        return rows;
    }

    public SearchRow get(String id) {
        return byId.get(id);
    }

    /** The merged list, best first; the rows' order follows it for the ones appended. */
    public void apply(List<SearchResult> merged) {
        Set<String> seen = new HashSet<>();
        for (SearchResult r : merged) {
            if (!seen.add(r.identity())) continue;
            SearchRow row = byId.get(r.identity());
            if (row == null) {
                row = new SearchRow(r);
                byId.put(r.identity(), row);
                rows.add(row);
            } else {
                row.update(r);
            }
        }
        if (seen.size() != byId.size()) {
            rows.removeIf(row -> !seen.contains(row.id()));
            byId.keySet().retainAll(seen);
        }
    }

    public void clear() {
        rows.clear();
        byId.clear();
    }
}
