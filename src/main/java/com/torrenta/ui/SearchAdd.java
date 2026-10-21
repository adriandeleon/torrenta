package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;

import com.torrenta.engine.AddRequest;
import com.torrenta.search.SearchResult;

/**
 * A result into what the Add dialog takes: a magnet is the magnet source as it is; a {@code
 * .torrent} link is fetched (on the caller's worker) and becomes the file source with the URL as
 * its origin. Pure but for the fetch, which is handed in.
 */
final class SearchAdd {

    /** Downloads a {@code .torrent}; {@link com.torrenta.net.TorrentFetch} in the window. */
    interface Fetcher {
        byte[] fetch(URI url) throws IOException;
    }

    private SearchAdd() {}

    static AddRequest.Source sourceOf(SearchResult result, Fetcher fetcher) throws IOException {
        return switch (result.link()) {
            case SearchResult.Link.Magnet magnet -> new AddRequest.Magnet(magnet.uri());
            case SearchResult.Link.TorrentUrl url -> {
                URI uri;
                try {
                    uri = URI.create(url.url());
                } catch (IllegalArgumentException e) {
                    throw new IOException("The link is not a URL: " + url.url());
                }
                yield new AddRequest.TorrentFile(fetcher.fetch(uri), url.url());
            }
        };
    }

    /** The status line while a {@code .torrent} link is being fetched; empty for a magnet. */
    static String fetchingText(SearchResult result) {
        return result.link() instanceof SearchResult.Link.TorrentUrl ? "Fetching " + result.name() + "…" : "";
    }

    /** The context menu's copy item: what kind of link the result has. */
    static String copyLabel(SearchResult result) {
        return result.link() instanceof SearchResult.Link.Magnet ? "Copy Magnet Link" : "Copy Link";
    }
}
