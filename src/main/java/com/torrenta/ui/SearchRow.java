package com.torrenta.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import com.torrenta.search.SearchResult;

/**
 * One row of the results table: the result as a property, so a copy that arrives later from
 * another provider (more seeds, a date the first one lacked) refreshes the row in place and the
 * selection stays on it. Keyed by {@link SearchResult#identity()}.
 */
public final class SearchRow {

    private final String id;
    private final ObjectProperty<SearchResult> result = new SimpleObjectProperty<>(this, "result");

    SearchRow(SearchResult first) {
        this.id = first.identity();
        this.result.set(first);
    }

    public String id() {
        return id;
    }

    public SearchResult result() {
        return result.get();
    }

    public ReadOnlyObjectProperty<SearchResult> resultProperty() {
        return result;
    }

    void update(SearchResult latest) {
        if (!latest.equals(result.get())) result.set(latest);
    }
}
