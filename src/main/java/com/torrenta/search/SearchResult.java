package com.torrenta.search;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import com.torrenta.engine.WatchFolder;
import org.jspecify.annotations.Nullable;

/**
 * One hit from one provider: what the results table shows and what the Add dialog is given.
 *
 * @param name the torrent's name as the indexer lists it
 * @param size bytes, or {@code -1} when the indexer did not say
 * @param seeds seeders, or {@code -1} when unknown
 * @param leechers leechers, or {@code -1} when unknown
 * @param published when the indexer says it appeared, or null
 * @param link how to get it: a magnet, or a URL to a {@code .torrent}
 * @param infoHash the v1 or v2 info-hash in lower-case hex when known, else empty; read off a
 *     magnet when the indexer gave none
 * @param provider the provider's {@link SearchProvider#name()}
 * @param detailUrl the indexer's page for it, or empty
 * @param category what the indexer files it under ({@link SearchCategory#ofNewznab}), or null
 *     when it did not say or said something none of ours is; never {@link SearchCategory#ALL}
 */
public record SearchResult(
        String name,
        long size,
        int seeds,
        int leechers,
        @Nullable Instant published,
        Link link,
        String infoHash,
        String provider,
        String detailUrl,
        @Nullable SearchCategory category) {

    /** Where a result leads: one of the two things the Add dialog accepts. */
    public sealed interface Link {
        /** The text as it is added: the magnet URI or the URL. */
        String value();

        /** A {@code magnet:?xt=urn:btih:…} link. */
        record Magnet(String uri) implements Link {
            public Magnet {
                uri = uri == null ? "" : uri.trim();
                if (!uri.regionMatches(true, 0, "magnet:", 0, 7))
                    throw new IllegalArgumentException("not a magnet link: " + uri);
            }

            @Override
            public String value() {
                return uri;
            }
        }

        /** A URL that serves a {@code .torrent}. */
        record TorrentUrl(String url) implements Link {
            public TorrentUrl {
                url = url == null ? "" : url.trim();
                if (url.isEmpty()) throw new IllegalArgumentException("a .torrent link needs a URL");
            }

            @Override
            public String value() {
                return url;
            }
        }
    }

    public SearchResult {
        name = name == null ? "" : name.trim();
        if (size < 0) size = -1;
        if (seeds < 0) seeds = -1;
        if (leechers < 0) leechers = -1;
        if (link == null) throw new IllegalArgumentException("a result needs a link");
        infoHash = infoHash == null ? "" : infoHash.trim().toLowerCase(Locale.ROOT);
        if (infoHash.isEmpty() && link instanceof Link.Magnet magnet) {
            infoHash = WatchFolder.magnetId(magnet.uri()).orElse("");
        }
        provider = provider == null ? "" : provider.trim();
        detailUrl = detailUrl == null ? "" : detailUrl.trim();
        if (category == SearchCategory.ALL) category = null;
    }

    /** A result whose category is not known. */
    public SearchResult(
            String name,
            long size,
            int seeds,
            int leechers,
            @Nullable Instant published,
            Link link,
            String infoHash,
            String provider,
            String detailUrl) {
        this(name, size, seeds, leechers, published, link, infoHash, provider, detailUrl, null);
    }

    /**
     * What makes two results the same torrent: the info-hash when one is known, else the link
     * itself (two providers that hand out the same magnet or URL).
     */
    public String identity() {
        return infoHash.isEmpty() ? "link:" + link.value() : "hash:" + infoHash;
    }

    /** The published date, when the indexer gave one. */
    public Optional<Instant> publishedAt() {
        return Optional.ofNullable(published);
    }
}
