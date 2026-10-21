package com.torrenta.rss;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMatcherTest {

    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    static final String MAGNET = "magnet:?xt=urn:btih:" + "0".repeat(40);

    static Article article(String feed, String title, Instant when) {
        return new Article(feed, "g:" + title, title, "https://x/" + title.hashCode(), MAGNET, when, "", false);
    }

    static Article article(String title) {
        return article("f1", title, NOW.minusSeconds(3600));
    }

    static Rule rule(String mustContain, String mustNotContain, boolean regex) {
        return new Rule(
                "r",
                true,
                mustContain,
                mustNotContain,
                regex,
                "",
                false,
                List.of(),
                0,
                Rule.AddPaused.DEFAULT,
                "",
                "",
                null,
                List.of());
    }

    private static boolean matches(Rule r, Article a) {
        return RuleMatcher.matches(r, a, NOW) != null;
    }

    @Test
    void phrasesAreWordsThatMustAllAppearWithAlternativesOnABar() {
        assertTrue(matches(rule("", "", false), article("Anything at all")), "blank wants everything");
        assertTrue(matches(rule("debian netinst", "", false), article("Debian 12.7.0 amd64 NETINST")), "case-free");
        assertFalse(matches(rule("debian netinst", "", false), article("Debian 12.7.0 amd64 DVD")), "every word");
        assertTrue(matches(rule("debian|ubuntu", "", false), article("Ubuntu 24.04.1 desktop")), "either");
        assertTrue(matches(rule("debian amd64 | ubuntu arm64", "", false), article("ubuntu arm64 server")));
        assertFalse(matches(rule("debian amd64 | ubuntu arm64", "", false), article("ubuntu amd64 server")));
        assertTrue(matches(rule("deb", "", false), article("Debian")), "a word is a substring, as in qBittorrent");
        assertFalse(matches(rule("debian", "dvd", false), article("Debian 12.7.0 DVD")), "must not");
        assertTrue(matches(rule("debian", "dvd", false), article("Debian 12.7.0 netinst")));
        assertFalse(matches(rule("", "dvd|cd", false), article("Fedora Live CD")), "must not, either");
        assertFalse(matches(rule("", "live cd", false), article("Fedora Live CD")), "must not, all words");
        assertTrue(matches(rule("", "live dvd", false), article("Fedora Live CD")));
        assertFalse(matches(rule(" | debian", "", false), article("Fedora")), "an empty alternative is dropped");
        assertTrue(matches(rule(" | debian", "", false), article("Debian")));
    }

    @Test
    void regexModeTakesBothExpressionsAsPatterns() {
        assertTrue(matches(rule("^debian.*netinst$", "", true), article("Debian 12.7.0 amd64 netinst")), "case-free");
        assertFalse(matches(rule("^debian.*netinst$", "", true), article("Debian 12.7.0 amd64 netinst x")));
        assertTrue(matches(rule("debian|ubuntu", "", true), article("Ubuntu 24.04")), "a regex bar is alternation too");
        assertFalse(matches(rule("debian", "\\b(dvd|cd)\\b", true), article("Debian DVD")));
        assertTrue(matches(rule("debian", "\\b(dvd|cd)\\b", true), article("Debian DVDs")));
        assertTrue(matches(rule("", "", true), article("Anything")), "blank patterns want everything");
        assertEquals(
                Optional.of("must contain is not a regular expression: Unclosed group"),
                RuleMatcher.problem(rule("(debian", "", true)));
        assertTrue(RuleMatcher.problem(rule("debian", "[", true)).orElse("").startsWith("must not contain is not"));
        assertEquals(Optional.empty(), RuleMatcher.problem(rule("(debian", "", false)), "no problem as a phrase");
        assertNull(
                RuleMatcher.matches(rule("(debian", "", true), article("(debian"), NOW), "a broken rule wants nothing");
        assertThrows(IllegalArgumentException.class, () -> RuleMatcher.compile(rule("(", "", true)));
        assertEquals(
                Optional.of("a name is needed"),
                RuleMatcher.problem(rule("", "", false).withName(" ")));
        assertEquals(
                Optional.of("episode filter: \"1\" is not season x episode: write 1x2, 1x2-5 or 1x2- (from episode 2"
                        + " on), several separated by ;"),
                RuleMatcher.problem(
                        new Rule("r", true, "", "", false, "1", false, List.of(), 0, null, "", "", null, List.of())));
    }

    @Test
    void feedsAgeAndTorrentGateTheMatch() {
        Rule all = rule("", "", false);
        Rule only = new Rule(
                "r", true, "", "", false, "", false, List.of("f2"), 0, Rule.AddPaused.DEFAULT, "", "", null, List.of());
        assertTrue(matches(all, article("f1", "x", NOW)));
        assertFalse(matches(only, article("f1", "x", NOW)), "another feed");
        assertTrue(matches(only, article("f2", "x", NOW)));
        assertNull(
                RuleMatcher.matches(all, new Article("f1", "g", "No torrent", "https://x/p", "", NOW, "", false), NOW),
                "nothing to download");

        Rule week = new Rule(
                "r", true, "", "", false, "", false, List.of(), 7, Rule.AddPaused.DEFAULT, "", "", null, List.of());
        assertTrue(matches(week, article("f1", "fresh", NOW.minusSeconds(6 * 86_400))));
        assertFalse(matches(week, article("f1", "stale", NOW.minusSeconds(8 * 86_400))));
        assertTrue(matches(week, article("f1", "undated", null)), "no date: not known to be old");
        assertTrue(matches(all, article("f1", "ancient", NOW.minusSeconds(400L * 86_400))), "0 is no limit");
    }

    @Test
    void theEpisodeFilterAndTheSmartFilterReadTheTitle() {
        Rule from = new Rule(
                "r",
                true,
                "show",
                "",
                false,
                "1x2-",
                false,
                List.of(),
                0,
                Rule.AddPaused.DEFAULT,
                "",
                "",
                null,
                List.of());
        assertTrue(matches(from, article("Show S01E03 720p")));
        assertFalse(matches(from, article("Show S01E01 720p")));
        assertFalse(matches(from, article("Show complete season pack")), "a filter needs an episode");

        Rule smart = new Rule(
                "r",
                true,
                "show",
                "",
                false,
                "",
                true,
                List.of(),
                0,
                Rule.AddPaused.DEFAULT,
                "",
                "",
                null,
                List.of("1x2", "show special"));
        assertFalse(matches(smart, article("Show S01E02 1080p")), "already taken, in another spelling");
        assertFalse(matches(smart, article("Show 1x02 720p")));
        assertTrue(matches(smart, article("Show S01E03 1080p")));
        assertFalse(matches(smart, article("Show.Special")), "no episode: the title itself is the key");
        assertTrue(matches(smart, article("Show Other Special")));
        assertTrue(matches(smart.withEnabled(false), article("Show S01E03")), "enabled is the engine's business");
        RuleMatcher.Match m = RuleMatcher.matches(smart, article("Show S01E03 1080p"), NOW);
        assertNotNull(m);
        assertEquals("1x3", m.episodeKey());
        assertEquals("show special", RuleMatcher.episodeKey("Show.Special"));
        assertEquals("show special", RuleMatcher.episodeKey("  SHOW   Special "));
        assertEquals("12x5", RuleMatcher.episodeKey("Show S12E05 [group]"));
    }
}
