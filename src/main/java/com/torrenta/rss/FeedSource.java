package com.torrenta.rss;

import java.util.List;

/** Where a feed's items come from: the network, or a stub in a test. */
public interface FeedSource {

    /**
     * What one fetch produced. {@code changed} is false for a 304, when the title and the articles
     * are empty and the validators are the ones sent; otherwise the feed's title (empty when it
     * gave none), its items, and the {@code ETag} and {@code Last-Modified} to send next time
     * (empty when the server gave none).
     */
    record Result(boolean changed, String title, List<Article> articles, String etag, String lastModified) {

        public Result {
            title = title == null ? "" : title.trim();
            articles = List.copyOf(articles);
            etag = etag == null ? "" : etag.trim();
            lastModified = lastModified == null ? "" : lastModified.trim();
        }

        public static Result unchanged(String etag, String lastModified) {
            return new Result(false, "", List.of(), etag, lastModified);
        }
    }

    /**
     * Fetches the feed, conditionally when {@code etag} or {@code lastModified} is given (what the
     * last answer carried), on the caller's thread.
     */
    Result fetch(Feed feed, String etag, String lastModified) throws FeedException;
}
