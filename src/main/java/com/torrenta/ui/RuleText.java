package com.torrenta.ui;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.torrenta.rss.Article;
import com.torrenta.rss.EpisodeFilter;
import com.torrenta.rss.Rule;

/** The rule dialog's and the Rules table's words, pure so they can be tested without a scene. */
final class RuleText {

    private RuleText() {}

    /**
     * What "Create rule from title" puts in must-contain: the title's words before its episode
     * token ({@code Some.Show.S01E02.720p} → {@code Some Show}), a dot or underscore beside a
     * letter read as a space (a version's {@code 12.7.0} stays), a leading {@code [group]} tag and
     * trailing dashes dropped; the whole title, cleaned the same way, when it names no episode.
     */
    static String mustContainFromTitle(String title) {
        String t = title == null ? "" : title;
        int at = EpisodeFilter.tokenStart(t);
        String head = at > 0 ? t.substring(0, at) : at == 0 ? "" : t;
        String cleaned = clean(head);
        return cleaned.isEmpty() ? clean(t) : cleaned;
    }

    private static String clean(String text) {
        return text.replaceAll("^\\s*\\[[^\\]]*\\]", "")
                .replaceAll("(?<=\\p{L})[._]+|[._]+(?=\\p{L})", " ")
                .replaceAll("[\\s\\-–—:._]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** A name for a new rule that no existing rule has: the phrase, then "phrase (2)", "phrase (3)". */
    static String freshName(String wanted, Collection<String> taken) {
        String base = wanted == null || wanted.isBlank() ? "New rule" : wanted.trim();
        if (!taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")";
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /** Duplicate's name: "Name (copy)", then "Name (copy 2)". */
    static String copyName(String name, Collection<String> taken) {
        String first = name + " (copy)";
        if (!taken.contains(first)) return first;
        for (int i = 2; ; i++) {
            String candidate = name + " (copy " + i + ")";
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /** The Feeds column: "All feeds", or the names, an unknown id shown as its id. */
    static String feedsText(Rule rule, Map<String, String> namesById) {
        if (rule.feeds().isEmpty()) return "All feeds";
        return String.join(
                ", ",
                rule.feeds().stream().map(id -> namesById.getOrDefault(id, id)).toList());
    }

    /** The Last match column: "never", else "5 min ago". */
    static String lastMatchText(Rule rule, long nowMs) {
        return rule.lastMatch() == null ? "never" : RssText.ago(rule.lastMatch().toEpochMilli(), nowMs);
    }

    /** One line of the dialog's Matches list: the title, then the feed in the muted ink after a dot. */
    static String matchLine(Article article, String feedName) {
        return feedName.isEmpty() ? article.displayTitle() : article.displayTitle() + " · " + feedName;
    }

    /** The heading over the Matches list. */
    static String matchesHeading(int matched, int total, boolean stale) {
        if (total == 0) return "Matches — no articles yet; the list fills as the feeds refresh";
        String n = matched == 0 ? "none" : Integer.toString(matched);
        return "Matches — " + n + " of " + total + (total == 1 ? " article" : " articles") + (stale ? "…" : "");
    }

    /** The Rules section's line under the table. */
    static String rulesStatus(List<Rule> rules, boolean autoDownload) {
        if (rules.isEmpty()) return "";
        long on = rules.stream().filter(Rule::enabled).count();
        String count = rules.size() == 1 ? "1 rule" : rules.size() + " rules";
        String enabled = on == rules.size() ? "" : ", " + on + " enabled";
        return count
                + enabled
                + (autoDownload
                        ? ", run as the feeds refresh"
                        : "; the switch above is off, so they run only on Run rules now");
    }
}
