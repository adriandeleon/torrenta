package com.torrenta.rss;

import java.time.Instant;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * One item of a feed, as the reader keeps it: which feed, the item's id (its {@code guid} or Atom
 * {@code id}; a feed that gives none is keyed by title and link), the title, the page it links
 * to, the torrent it carries (a magnet or a {@code .torrent} URL, empty when it carries none),
 * when it was published (null when the feed did not say), its description as plain text, whether
 * it has been read, and whether its torrent has been added (by a rule, or by hand through
 * Download).
 */
public record Article(
        String feedId,
        String guid,
        String title,
        String link,
        String torrentLink,
        @Nullable Instant published,
        String description,
        boolean read,
        boolean downloaded) {

    /** Not yet downloaded: what a parser or an older file hands over. */
    public Article(
            String feedId,
            String guid,
            String title,
            String link,
            String torrentLink,
            @Nullable Instant published,
            String description,
            boolean read) {
        this(feedId, guid, title, link, torrentLink, published, description, read, false);
    }

    public Article {
        feedId = feedId == null ? "" : feedId;
        guid = guid == null ? "" : guid.trim();
        title = title == null ? "" : title.trim();
        link = link == null ? "" : link.trim();
        torrentLink = torrentLink == null ? "" : torrentLink.trim();
        description = description == null ? "" : description;
    }

    /** What makes two copies of an item the same: the guid, else the title and the link. */
    public String identity() {
        return guid.isEmpty() ? "t:" + title + "\n" + link : "g:" + guid;
    }

    public boolean hasTorrent() {
        return !torrentLink.isEmpty();
    }

    /** Whether the torrent link is a magnet rather than a {@code .torrent} to fetch. */
    public boolean isMagnet() {
        return torrentLink.toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    /** The title, else the first line of the description, else the link: something to show. */
    public String displayTitle() {
        if (!title.isEmpty()) return title;
        String first = description.strip();
        int nl = first.indexOf('\n');
        if (nl > 0) first = first.substring(0, nl);
        if (first.length() > 80) first = first.substring(0, 79) + "…";
        return first.isEmpty() ? link : first;
    }

    public Article withRead(boolean isRead) {
        return read == isRead
                ? this
                : new Article(feedId, guid, title, link, torrentLink, published, description, isRead, downloaded);
    }

    /** The same item filed under a feed: what the service stamps on a source's items. */
    public Article withFeedId(String id) {
        return feedId.equals(id)
                ? this
                : new Article(id, guid, title, link, torrentLink, published, description, read, downloaded);
    }

    public Article withDownloaded(boolean isDownloaded) {
        return downloaded == isDownloaded
                ? this
                : new Article(feedId, guid, title, link, torrentLink, published, description, read, isDownloaded);
    }

    /** The same item with another copy's flags: what a merge does with a copy fetched again. */
    public Article withFlagsOf(Article other) {
        return withRead(other.read).withDownloaded(other.downloaded);
    }
}
