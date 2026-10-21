package com.torrenta.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import com.torrenta.rss.Article;

/**
 * One row of the article table: the article as a property, so a read flag that flips or a copy
 * fetched again refreshes the row in place and the selection stays on it. Keyed by feed and
 * {@link Article#identity()}: two feeds may share a guid.
 */
public final class ArticleRow {

    private final String key;
    private final ObjectProperty<Article> article = new SimpleObjectProperty<>(this, "article");

    ArticleRow(Article first) {
        this.key = keyOf(first);
        this.article.set(first);
    }

    static String keyOf(Article a) {
        return a.feedId() + "\u0000" + a.identity();
    }

    public String key() {
        return key;
    }

    public Article article() {
        return article.get();
    }

    public ReadOnlyObjectProperty<Article> articleProperty() {
        return article;
    }

    void update(Article latest) {
        if (!latest.equals(article.get())) article.set(latest);
    }
}
