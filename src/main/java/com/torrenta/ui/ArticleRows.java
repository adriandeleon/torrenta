package com.torrenta.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.torrenta.rss.Article;
import org.jspecify.annotations.Nullable;

/**
 * The article table's rows, reconciled in place from each list the service hands over: an article
 * already shown keeps its row (and the selection on it), new ones are appended in the list's
 * order, and ones that fell out are removed. {@link SearchRows}' shape; the list has an extractor
 * on the row's property so a sorted view re-sorts an updated row.
 *
 * <p>Not thread-safe: the FX thread owns it, as it owns the table.
 */
public final class ArticleRows {

    private final ObservableList<ArticleRow> rows =
            FXCollections.observableArrayList(row -> new Observable[] {row.articleProperty()});
    private final Map<String, ArticleRow> byKey = new HashMap<>();

    public ObservableList<ArticleRow> list() {
        return rows;
    }

    public @Nullable ArticleRow get(String key) {
        return byKey.get(key);
    }

    public void apply(List<Article> articles) {
        Set<String> seen = new HashSet<>();
        for (Article a : articles) {
            String key = ArticleRow.keyOf(a);
            if (!seen.add(key)) continue;
            ArticleRow row = byKey.get(key);
            if (row == null) {
                row = new ArticleRow(a);
                byKey.put(key, row);
                rows.add(row);
            } else {
                row.update(a);
            }
        }
        if (seen.size() != byKey.size()) {
            rows.removeIf(row -> !seen.contains(row.key()));
            byKey.keySet().retainAll(seen);
        }
    }

    public void clear() {
        rows.clear();
        byKey.clear();
    }
}
