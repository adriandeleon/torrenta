package com.torrenta.rss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static final Instant NOW = RuleMatcherTest.NOW;

    private static Rule named(String name, String mustContain, boolean smart) {
        return new Rule(
                name,
                true,
                mustContain,
                "",
                false,
                "",
                smart,
                List.of(),
                0,
                Rule.AddPaused.DEFAULT,
                "",
                "",
                null,
                List.of());
    }

    private static List<String> titles(List<RuleEngine.Download> downloads) {
        return downloads.stream()
                .map(d -> d.rule().name() + ":" + d.article().title())
                .toList();
    }

    @Test
    void theFirstRuleThatWantsAnArticleWinsItAndNothingIsTakenTwice() {
        List<Rule> rules = List.of(named("Debian", "debian", false), named("Linux", "linux|debian|fedora", false));
        List<Article> articles = List.of(
                RuleMatcherTest.article("Debian 12.7.0 netinst"),
                RuleMatcherTest.article("Fedora 41 Workstation"),
                RuleMatcherTest.article("Arch Linux 2026.10"),
                RuleMatcherTest.article("Windows 11").withDownloaded(true),
                RuleMatcherTest.article("Fedora 40 (already)").withDownloaded(true),
                new Article("f1", "g", "Linux news", "https://x/news", "", NOW, "", false));
        assertEquals(
                List.of("Debian:Debian 12.7.0 netinst", "Linux:Fedora 41 Workstation", "Linux:Arch Linux 2026.10"),
                titles(RuleEngine.apply(rules, articles, NOW)),
                "first rule wins; downloaded and torrent-less articles are skipped");
        assertEquals(List.of(), RuleEngine.apply(List.of(), articles, NOW), "no rules, nothing");
        assertEquals(
                List.of(),
                RuleEngine.apply(List.of(named("Off", "", false).withEnabled(false)), articles, NOW),
                "a disabled rule wants nothing");
        Rule broken = new Rule("Broken", true, "(", "", true, "", false, List.of(), 0, null, "", "", null, List.of());
        assertEquals(
                List.of("Linux:Debian 12.7.0 netinst"),
                titles(RuleEngine.apply(List.of(broken, named("Linux", "debian", false)), articles.subList(0, 1), NOW)),
                "a rule with a problem is skipped, not fatal");
    }

    @Test
    void theSmartFilterTakesOneCopyOfAnEpisodePerBatchAndRemembersIt() {
        Rule show = named("Show", "show", true);
        Rule any = named("Any", "", false);
        List<Article> batch = List.of(
                RuleMatcherTest.article("Show S01E02 720p"),
                RuleMatcherTest.article("Show S01E02 1080p"),
                RuleMatcherTest.article("Show 1x03 720p"));
        List<RuleEngine.Download> got = RuleEngine.apply(List.of(show, any), batch, NOW);
        assertEquals(
                List.of("Show:Show S01E02 720p", "Any:Show S01E02 1080p", "Show:Show 1x03 720p"),
                titles(got),
                "the second copy falls through to the next rule");
        List<Rule> after = RuleEngine.record(List.of(show, any), got, NOW);
        assertEquals(List.of("1x2", "1x3"), after.get(0).previouslyMatched());
        assertEquals(NOW, after.get(0).lastMatch());
        assertEquals(List.of("1x2"), after.get(1).previouslyMatched(), "remembered whether or not the filter is on");
        assertEquals(NOW, after.get(1).lastMatch());
        List<Article> taken = batch.stream().map(a -> a.withDownloaded(true)).toList();
        assertEquals(List.of(), RuleEngine.apply(after, taken, NOW.plusSeconds(60)), "downloaded: nothing left");
        List<Article> next =
                List.of(RuleMatcherTest.article("Show S01E02 2160p"), RuleMatcherTest.article("Show 1x04"));
        assertEquals(
                List.of("Any:Show S01E02 2160p", "Show:Show 1x04"),
                titles(RuleEngine.apply(after, next, NOW.plusSeconds(60))),
                "next time Show remembers 1x2 and leaves it to Any; 1x4 is new to it");
        List<Rule> untouched = RuleEngine.record(List.of(show), List.of(), NOW);
        assertEquals(List.of(show), untouched, "nothing done, nothing changed");
        assertNull(untouched.get(0).lastMatch());
    }

    @Test
    void theMemoryIsCappedAtAThousandOldestFirstOut() {
        Rule r = named("Show", "", true);
        for (int i = 0; i < Rule.MAX_PREVIOUSLY_MATCHED + 5; i++) r = r.recordMatch("1x" + i, NOW);
        assertEquals(Rule.MAX_PREVIOUSLY_MATCHED, r.previouslyMatched().size());
        assertEquals("1x5", r.previouslyMatched().get(0), "the five oldest went");
        assertEquals("1x1004", r.previouslyMatched().get(Rule.MAX_PREVIOUSLY_MATCHED - 1));
        assertEquals(r, r.recordMatch("1x1004", NOW), "a key already there is not added again");
        assertEquals(r.previouslyMatched(), r.recordMatch("", NOW).previouslyMatched(), "a blank key is not kept");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1200; i++) many.add("k" + i);
        Rule loaded = new Rule("r", true, "", "", false, "", true, List.of(), 0, null, "", "", null, many);
        assertEquals(1000, loaded.previouslyMatched().size(), "capped on construction too");
        assertEquals("k200", loaded.previouslyMatched().get(0));
    }

    @Test
    void duplicateForgetsTheMemoryAndTheWordsAreTheLogLine() {
        Rule r = named("Show", "show", true).recordMatch("1x1", NOW);
        Rule copy = r.duplicate("Show (copy)");
        assertEquals("Show (copy)", copy.name());
        assertEquals("show", copy.mustContain());
        assertTrue(copy.smartEpisodeFilter());
        assertNull(copy.lastMatch());
        assertTrue(copy.previouslyMatched().isEmpty());
        assertFalse(r.previouslyMatched().isEmpty());
        Article a = RuleMatcherTest.article("Show S01E01");
        assertEquals("Rule 'Show' added Show S01E01 from feed Tracker", RuleEngine.describe(r, a, "Tracker"));
        assertEquals("Rule 'Show' added Show S01E01", RuleEngine.describe(r, a, ""));
        assertEquals(
                "Rule 'Show' could not add Show S01E01 from feed Tracker: the server answered 404",
                RuleEngine.describeFailure(r, a, "Tracker", "the server answered 404"));
        assertEquals(Rule.AddPaused.YES, Rule.AddPaused.parse("yes"));
        assertEquals(Rule.AddPaused.NO, Rule.AddPaused.parse("No"));
        assertEquals(Rule.AddPaused.DEFAULT, Rule.AddPaused.parse("whatever"));
        assertEquals(
                Rule.MAX_IGNORE_DAYS, named("r", "", false).recordMatch("", NOW).ignoreDays() + Rule.MAX_IGNORE_DAYS);
        assertTrue(named("r", "", false).appliesTo("any"));
    }
}
