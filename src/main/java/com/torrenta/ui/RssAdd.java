package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;

import com.torrenta.engine.AddRequest;
import com.torrenta.rss.Article;

/**
 * An article into what the Add dialog takes, as {@link SearchAdd} does for a result: a magnet is
 * the magnet source as it is; a {@code .torrent} link is fetched (on the caller's worker) and
 * becomes the file source with the URL as its origin. An article without a torrent is refused.
 */
final class RssAdd {

    private RssAdd() {}

    static AddRequest.Source sourceOf(Article article, SearchAdd.Fetcher fetcher) throws IOException {
        if (!article.hasTorrent()) throw new IOException("No torrent in " + article.displayTitle());
        if (article.isMagnet()) return new AddRequest.Magnet(article.torrentLink());
        URI uri;
        try {
            uri = URI.create(article.torrentLink());
        } catch (IllegalArgumentException e) {
            throw new IOException("The link is not a URL: " + article.torrentLink());
        }
        return new AddRequest.TorrentFile(fetcher.fetch(uri), article.torrentLink());
    }

    /** The status line while a {@code .torrent} link is being fetched; empty for a magnet. */
    static String fetchingText(Article article) {
        return article.hasTorrent() && !article.isMagnet() ? "Fetching " + article.displayTitle() + "…" : "";
    }

    /** The context menu's copy item: what kind of link the article has. */
    static String copyLabel(Article article) {
        return article.isMagnet() ? "Copy Magnet Link" : article.hasTorrent() ? "Copy Torrent Link" : "Copy Link";
    }

    /** What Ctrl+C copies: the torrent when there is one, else the page. */
    static String copyText(Article article) {
        return article.hasTorrent() ? article.torrentLink() : article.link();
    }
}
