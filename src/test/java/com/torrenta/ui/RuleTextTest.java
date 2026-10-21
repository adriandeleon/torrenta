package com.torrenta.ui;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.torrenta.i18n.Messages;
import com.torrenta.rss.Article;
import com.torrenta.rss.Rule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleTextTest {

    @Test
    void createRuleFromTitleTakesTheWordsBeforeTheEpisode() {
        assertEquals("Some Show", RuleText.mustContainFromTitle("Some.Show.S01E02.720p.WEB-DL"));
        assertEquals("Some Show", RuleText.mustContainFromTitle("Some Show - 1x02 - The Title [eztv]"));
        assertEquals("Some Show", RuleText.mustContainFromTitle("[group] Some_Show Season 1 Episode 2"));
        assertEquals("Some Show", RuleText.mustContainFromTitle("Some Show: S01E02"));
        assertEquals("Debian 12.7.0 amd64 netinst", RuleText.mustContainFromTitle("Debian 12.7.0 amd64 netinst"));
        assertEquals("Some Show 2024", RuleText.mustContainFromTitle("Some.Show.2024.S01E01"));
        assertEquals("S01E02 Pilot", RuleText.mustContainFromTitle("S01E02.Pilot"), "nothing before: the whole title");
        assertEquals("", RuleText.mustContainFromTitle(""));
        assertEquals("", RuleText.mustContainFromTitle(null));
    }

    @Test
    void namesAreMadeUniqueAndTheColumnsReadWell() {
        assertEquals("Some Show", RuleText.freshName("Some Show", List.of()));
        assertEquals("Some Show (2)", RuleText.freshName("Some Show", List.of("Some Show")));
        assertEquals("Some Show (3)", RuleText.freshName(" Some Show ", List.of("Some Show", "Some Show (2)")));
        assertEquals(Messages.tr("dialog.rule.newName"), RuleText.freshName("", List.of()));
        assertEquals("Show (copy)", RuleText.copyName("Show", List.of("Show")));
        assertEquals("Show (copy 2)", RuleText.copyName("Show", List.of("Show", "Show (copy)")));

        Rule all = Rule.blank("r");
        Rule some =
                new Rule("r", true, "", "", false, "", false, List.of("f1", "f9"), 0, null, "", "", null, List.of());
        Map<String, String> names = Map.of("f1", "Tracker", "f2", "Other");
        assertEquals(Messages.tr("dialog.rule.allFeeds"), RuleText.feedsText(all, names));
        assertEquals("Tracker, f9", RuleText.feedsText(some, names), "an unknown id is shown as it is");
        assertEquals(Messages.tr("dialog.rule.never"), RuleText.lastMatchText(all, 0));
        Rule matched = all.recordMatch("", Instant.ofEpochMilli(1_000_000));
        assertEquals("5 min ago", RuleText.lastMatchText(matched, 1_000_000 + 5 * 60_000));

        Article a = new Article("f1", "g", "Debian 12.7.0", "https://x/p", "", null, "", false);
        assertEquals("Debian 12.7.0 · Tracker", RuleText.matchLine(a, "Tracker"));
        assertEquals("Debian 12.7.0", RuleText.matchLine(a, ""));
        assertEquals(Messages.tr("dialog.rule.matches.empty"), RuleText.matchesHeading(0, 0, false));
        assertEquals("Matches — none of 1 article", RuleText.matchesHeading(0, 1, false));
        assertEquals("Matches — 3 of 120 articles…", RuleText.matchesHeading(3, 120, true));

        assertEquals("", RuleText.rulesStatus(List.of(), true));
        assertEquals("1 rule, run as the feeds refresh", RuleText.rulesStatus(List.of(all), true));
        assertEquals(
                "2 rules, 1 enabled; the switch above is off, so they run only on Run rules now",
                RuleText.rulesStatus(List.of(all, all.withEnabled(false)), false));
    }
}
