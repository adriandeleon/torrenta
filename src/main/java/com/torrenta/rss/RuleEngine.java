package com.torrenta.rss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.torrenta.i18n.Messages.tr;

/**
 * The rules over a batch of articles: which to download, under which rule, and how the rules
 * remember it. Pure; the service runs it on the fetch thread and does the downloading.
 */
public final class RuleEngine {

    private RuleEngine() {}

    /** One torrent to add: the article, and the rule whose settings it is added with. */
    public record Download(Rule rule, Article article, String episodeKey) {}

    /**
     * The first enabled rule that wants an article wins it; an article already downloaded, or
     * without a torrent, is skipped; a rule with a problem (a bad regex) is skipped; under the
     * smart episode filter a rule takes one copy of an episode per batch, the first listed, and
     * leaves the others to the rules after it.
     */
    public static List<Download> apply(List<Rule> rules, List<Article> articles, Instant now) {
        List<RuleMatcher> matchers = new ArrayList<>();
        for (Rule r : rules) {
            if (r.enabled() && RuleMatcher.problem(r).isEmpty()) matchers.add(RuleMatcher.compile(r));
        }
        if (matchers.isEmpty()) return List.of();
        Map<String, Set<String>> taken = new HashMap<>();
        List<Download> out = new ArrayList<>();
        for (Article a : articles) {
            if (a.downloaded() || !a.hasTorrent()) continue;
            for (RuleMatcher m : matchers) {
                RuleMatcher.Match match = m.match(a, now);
                if (match == null) continue;
                Rule rule = m.rule();
                if (rule.smartEpisodeFilter()
                        && !taken.computeIfAbsent(rule.name(), k -> new HashSet<>())
                                .add(match.episodeKey())) continue;
                out.add(new Download(rule, a, match.episodeKey()));
                break;
            }
        }
        return out;
    }

    /**
     * The rules after the downloads that went through: each rule named gets the time and the
     * episode remembered; the others are returned as they were, in the same order.
     */
    public static List<Rule> record(List<Rule> rules, List<Download> done, Instant now) {
        Map<String, Rule> byName = new LinkedHashMap<>();
        for (Rule r : rules) byName.put(r.name(), r);
        for (Download d : done) {
            Rule r = byName.get(d.rule().name());
            if (r != null) byName.put(r.name(), r.recordMatch(d.episodeKey(), now));
        }
        return List.copyOf(byName.values());
    }

    /** The event-log line for an add: "Rule 'Linux ISOs' added Debian 12.7.0 from feed Tracker". */
    public static String describe(Rule rule, Article article, String feedName) {
        return feedName.isEmpty()
                ? tr("rss.rule.added", rule.name(), article.displayTitle())
                : tr("rss.rule.addedFrom", rule.name(), article.displayTitle(), feedName);
    }

    /** The line for an add that failed. */
    public static String describeFailure(Rule rule, Article article, String feedName, String reason) {
        return feedName.isEmpty()
                ? tr("rss.rule.addFailed", rule.name(), article.displayTitle(), reason)
                : tr("rss.rule.addFailedFrom", rule.name(), article.displayTitle(), feedName, reason);
    }
}
