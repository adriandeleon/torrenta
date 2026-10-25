package com.torrenta.ui;

import com.torrenta.rss.Article;

import static com.torrenta.i18n.Messages.tr;

/**
 * An article's words in the RSS tab: the status line while its {@code .torrent} is fetched, and
 * what the copy item says and copies. The article into an add is {@link
 * com.torrenta.session.RuleAdd#sourceOf}.
 */
final class RssAdd {

    private RssAdd() {}

    /** The status line while a {@code .torrent} link is being fetched; empty for a magnet. */
    static String fetchingText(Article article) {
        return article.hasTorrent() && !article.isMagnet() ? tr("search.fetching", article.displayTitle()) : "";
    }

    /** The context menu's copy item: what kind of link the article has. */
    static String copyLabel(Article article) {
        return article.isMagnet()
                ? tr("rss.menu.copyMagnetLink")
                : article.hasTorrent() ? tr("rss.menu.copyTorrentLink") : tr("rss.menu.copyLink");
    }

    /** What Ctrl+C copies: the torrent when there is one, else the page. */
    static String copyText(Article article) {
        return article.hasTorrent() ? article.torrentLink() : article.link();
    }
}
