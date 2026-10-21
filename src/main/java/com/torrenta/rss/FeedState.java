package com.torrenta.rss;

import java.util.List;

/**
 * What the reader keeps for one feed besides the feed itself: the validators of the last answer,
 * when it was last asked (epoch milliseconds; 0 for never), what went wrong then (empty when
 * nothing), and the articles, newest first, read flags included.
 */
public record FeedState(String etag, String lastModified, long refreshedEpochMs, String error, List<Article> articles) {

    public static final FeedState EMPTY = new FeedState("", "", 0, "", List.of());

    public FeedState {
        etag = etag == null ? "" : etag;
        lastModified = lastModified == null ? "" : lastModified;
        error = error == null ? "" : error.trim();
        articles = List.copyOf(articles);
    }

    public int unread() {
        int n = 0;
        for (Article a : articles) if (!a.read()) n++;
        return n;
    }

    public FeedState withArticles(List<Article> list) {
        return new FeedState(etag, lastModified, refreshedEpochMs, error, list);
    }

    /** After a fetch that answered: the new validators, the time, no error. */
    public FeedState answered(String newEtag, String newLastModified, long now, List<Article> list) {
        return new FeedState(newEtag, newLastModified, now, "", list);
    }

    /** After a fetch that failed: the time and the reason; the articles and validators stay. */
    public FeedState failed(long now, String reason) {
        return new FeedState(etag, lastModified, now, reason, articles);
    }

    /** The first {@code max} articles: the list is newest first, so the oldest go. */
    public FeedState capped(int max) {
        return articles.size() <= max ? this : withArticles(articles.subList(0, max));
    }
}
