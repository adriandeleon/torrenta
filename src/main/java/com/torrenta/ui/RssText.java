package com.torrenta.ui;

import java.util.List;
import java.util.Locale;

import com.torrenta.rss.Article;
import com.torrenta.rss.RssService;

import static com.torrenta.i18n.Messages.tr;

/** The RSS tab's words and its filter rule, pure so they can be tested without a scene. */
final class RssText {

    private RssText() {}

    /** The feed list's line for a capture: "Tracker (3)", "!Tracker (0)" after a failure, "~" while refreshing. */
    static String feedLine(String name, int unread, boolean failed, boolean refreshing) {
        return (failed ? "!" : "") + (refreshing ? "~" : "") + name + " (" + unread + ")";
    }

    /** Whether the article shows under the filter box and the Unread-only toggle. */
    static boolean matches(Article article, String query, boolean unreadOnly) {
        if (unreadOnly && article.read()) return false;
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return article.title().toLowerCase(Locale.ROOT).contains(q)
                || article.description().toLowerCase(Locale.ROOT).contains(q);
    }

    /** Every word of the status line: feeds, unread, what is refreshing, what failed. */
    static String statusLine(int feeds, int unread, int refreshing, List<String> failed) {
        if (feeds == 0) return tr("rss.status.noFeeds");
        StringBuilder sb = new StringBuilder();
        sb.append(tr("rss.status.feeds", feeds));
        sb.append(" · ").append(tr("rss.status.unread", unread));
        if (refreshing > 0) sb.append(" · ").append(tr("rss.status.refreshing", refreshing));
        if (!failed.isEmpty()) {
            sb.append(" · ");
            if (failed.size() == 1) sb.append(failed.get(0));
            else sb.append(tr("rss.status.feedsFailed", failed.size()));
        }
        return sb.toString();
    }

    /** One feed's line: "refreshing…", "last error: …", "refreshed 5 min ago", "never refreshed". */
    static String feedStatus(RssService.Status status, long nowMs) {
        if (status.refreshing()) return tr("rss.feed.refreshing");
        if (status.failed()) return tr("rss.feed.lastError", status.error());
        if (status.refreshedEpochMs() <= 0) return tr("rss.feed.neverRefreshed");
        return tr("rss.feed.refreshed", ago(status.refreshedEpochMs(), nowMs));
    }

    /** "just now", "5 min ago", "2 h ago", "3 days ago". */
    static String ago(long thenMs, long nowMs) {
        long s = Math.max(0, (nowMs - thenMs) / 1000);
        if (s < 60) return tr("rss.ago.justNow");
        long m = s / 60;
        if (m < 60) return tr("rss.ago.minutes", m);
        long h = m / 60;
        if (h < 24) return tr("rss.ago.hours", h);
        long d = h / 24;
        return tr("rss.ago.days", d);
    }

    /** The article's second line in the detail: feed, date, what it carries. */
    static String meta(String feedName, String date, Article article) {
        StringBuilder sb = new StringBuilder(feedName);
        if (!date.isEmpty() && !date.equals("—")) sb.append(" · ").append(date);
        sb.append(" · ")
                .append(
                        !article.hasTorrent()
                                ? tr("rss.meta.noTorrent")
                                : article.isMagnet() ? tr("rss.meta.magnet") : tr("rss.meta.torrent"));
        return sb.toString();
    }
}
