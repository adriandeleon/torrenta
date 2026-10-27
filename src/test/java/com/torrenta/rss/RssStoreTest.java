package com.torrenta.rss;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssStoreTest {

    private static Article article(String guid, int daysAgo, boolean read) {
        return new Article(
                "f1",
                guid,
                "Title " + guid,
                "https://x/" + guid,
                guid.hashCode() % 2 == 0 ? "magnet:?xt=urn:btih:" + "0".repeat(40) : "",
                Instant.parse("2026-10-18T00:00:00Z").minusSeconds(daysAgo * 86_400L),
                "about " + guid + "\nsecond line",
                read);
    }

    @Test
    void feedsAndArticlesRoundTripThroughTheFiles(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir.resolve("cfg"));
        store.load();
        assertTrue(store.feeds().isEmpty(), "no files yet is simply empty");
        assertEquals(FeedState.EMPTY, store.state("f1"));

        Feed f1 = new Feed("f1", "https://tracker.example/rss", "Tracker", 0);
        Feed f2 = new Feed("f2", "https://atom.example/feed", "", 15);
        store.setFeeds(List.of(f1, f2));
        FeedState state = new FeedState(
                "\"v1\"",
                "Sun, 18 Oct 2026 09:12:00 GMT",
                1_760_000_000_000L,
                "",
                List.of(
                        article("a", 0, true),
                        article("b", 1, false),
                        new Article("f1", "", "No date", "", "", null, "", false)));
        store.setState("f1", state);

        Path root = dir.resolve("cfg").resolve(RssStore.DIR);
        assertEquals(
                "[\n"
                        + "  {\"id\":\"f1\",\"url\":\"https://tracker.example/rss\",\"name\":\"Tracker\",\"refreshMinutes\":0},\n"
                        + "  {\"id\":\"f2\",\"url\":\"https://atom.example/feed\",\"name\":\"\",\"refreshMinutes\":15}\n"
                        + "]\n",
                Files.readString(root.resolve(RssStore.FEEDS_FILE)));
        String articles = Files.readString(root.resolve(RssStore.ARTICLES_DIR).resolve("f1.json"));
        assertTrue(
                articles.startsWith("{\"etag\":\"\\\"v1\\\"\",\"lastModified\":\"Sun, 18 Oct 2026 09:12:00 GMT\","
                        + "\"refreshedEpochMs\":1760000000000,\"error\":\"\",\"articles\":[\n"),
                articles);
        assertTrue(articles.contains("\"published\":\"2026-10-18T00:00:00Z\""), articles);
        assertTrue(
                articles.contains(
                        "{\"guid\":\"\",\"title\":\"No date\",\"link\":\"\",\"torrent\":\"\",\"description\":\"\",\"read\":false}"),
                "no published field when unknown: " + articles);
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".part")), "written atomically");
        }

        RssStore again = new RssStore(dir.resolve("cfg"));
        again.load();
        assertEquals(List.of(f1, f2), again.feeds());
        assertEquals(f2, again.feed("f2"));
        assertNull(again.feed("nope"));
        assertEquals(state, again.state("f1"), "every field back, read flags included");
        assertEquals(2, again.state("f1").unread());
        assertEquals(FeedState.EMPTY, again.state("f2"), "never fetched");

        again.setFeeds(List.of(f2));
        assertFalse(
                Files.exists(root.resolve(RssStore.ARTICLES_DIR).resolve("f1.json")), "a removed feed's articles go");
        assertEquals(FeedState.EMPTY, again.state("f1"));
        assertEquals("[]\n", RssStore.formatFeeds(List.of()));
    }

    @Test
    void rulesRoundTripThroughTheirFile(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        store.load();
        assertTrue(store.rules().isEmpty(), "no file yet is simply empty");
        Rule show = new Rule(
                "Show",
                true,
                "show 720p|show 1080p",
                "sample",
                false,
                "1x2-;2x1-4",
                true,
                List.of("f1", "f2"),
                7,
                Rule.AddPaused.YES,
                "TV",
                "/data/tv",
                Instant.parse("2026-10-18T09:12:00Z"),
                List.of("1x2", "1x3"));
        Rule everything = Rule.blank("Everything").withEnabled(false);
        store.setRules(List.of(show, everything));
        String text = Files.readString(dir.resolve(RssStore.DIR).resolve(RssStore.RULES_FILE));
        assertEquals(
                "[\n"
                        + "  {\"name\":\"Show\",\"enabled\":true,\"mustContain\":\"show 720p|show 1080p\","
                        + "\"mustNotContain\":\"sample\",\"useRegex\":false,\"episodeFilter\":\"1x2-;2x1-4\","
                        + "\"smartEpisodeFilter\":true,\"feeds\":[\"f1\",\"f2\"],\"ignoreDays\":7,\"addPaused\":\"yes\","
                        + "\"category\":\"TV\",\"savePath\":\"/data/tv\",\"lastMatch\":\"2026-10-18T09:12:00Z\","
                        + "\"previouslyMatched\":[\"1x2\",\"1x3\"]},\n"
                        + "  {\"name\":\"Everything\",\"enabled\":false,\"mustContain\":\"\",\"mustNotContain\":\"\","
                        + "\"useRegex\":false,\"episodeFilter\":\"\",\"smartEpisodeFilter\":false,\"feeds\":[],"
                        + "\"ignoreDays\":0,\"addPaused\":\"default\",\"category\":\"\",\"savePath\":\"\","
                        + "\"previouslyMatched\":[]}\n"
                        + "]\n",
                text,
                "no lastMatch field while the rule never matched");
        RssStore again = new RssStore(dir);
        again.load();
        assertEquals(List.of(show, everything), again.rules(), "every field back");
        assertEquals("[]\n", RssStore.formatRules(List.of()));
        assertThrows(IOException.class, () -> RssStore.parseRules("{}"));
        assertEquals(
                List.of("A"),
                RssStore.parseRules("[{\"name\":\"A\"},{\"name\":\"\"},{\"name\":\"A\",\"enabled\":false},3]").stream()
                        .map(Rule::name)
                        .toList(),
                "no name: skipped; a second of the same name: skipped");
        assertTrue(RssStore.parseRules("[{\"name\":\"A\"}]").get(0).enabled(), "the defaults of a short entry");
        Files.writeString(dir.resolve(RssStore.DIR).resolve(RssStore.RULES_FILE), "nope");
        again.load();
        assertTrue(again.rules().isEmpty(), "unreadable is empty, not fatal");
    }

    @Test
    void theDownloadedFlagIsWrittenOnlyWhenSetAndSurvivesAMerge(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        store.setFeeds(List.of(new Feed("f1", "https://x/rss", "", 0)));
        Article taken = article("a", 0, true).withDownloaded(true);
        store.setState("f1", FeedState.EMPTY.withArticles(List.of(taken, article("b", 1, false))));
        String text = Files.readString(
                dir.resolve(RssStore.DIR).resolve(RssStore.ARTICLES_DIR).resolve("f1.json"));
        assertTrue(text.contains("\"read\":true,\"downloaded\":true}"), text);
        assertEquals(1, text.split("downloaded", -1).length - 1, "absent while false");
        RssStore again = new RssStore(dir);
        again.load();
        assertTrue(again.state("f1").articles().get(0).downloaded());
        assertFalse(again.state("f1").articles().get(1).downloaded());
        List<Article> merged = RssService.merge(List.of(taken), List.of(article("a", 0, false)));
        assertTrue(merged.get(0).downloaded(), "a copy fetched again keeps the flag");
        assertTrue(merged.get(0).read());
    }

    @Test
    void aFeedKeepsAtMostFiveHundredArticlesTheOldestDropped(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        store.setFeeds(List.of(new Feed("f1", "https://x/rss", "", 0)));
        List<Article> many = new ArrayList<>();
        for (int i = 0; i < 620; i++) many.add(article("g" + i, i, false));
        FeedState kept = store.setState("f1", FeedState.EMPTY.withArticles(many));
        assertEquals(RssStore.MAX_ARTICLES, kept.articles().size());
        assertEquals("g0", kept.articles().get(0).guid(), "the newest stays");
        assertEquals("g499", kept.articles().get(499).guid(), "the oldest went");
        RssStore again = new RssStore(dir);
        again.load();
        assertEquals(500, again.state("f1").articles().size());
    }

    @Test
    void readStateSurvivesAMerge() {
        List<Article> existing = List.of(article("a", 0, true), article("b", 1, false), article("old", 9, true));
        List<Article> incoming = List.of(
                new Article(
                        "f1", "new", "Newest", "https://x/new", "", Instant.parse("2026-10-19T00:00:00Z"), "", false),
                new Article(
                        "f1",
                        "a",
                        "Title a (edited)",
                        "https://x/a",
                        "",
                        Instant.parse("2026-10-18T00:00:00Z"),
                        "",
                        false),
                article("b", 1, false));
        List<Article> merged = RssService.merge(existing, incoming);
        assertEquals(
                List.of("new", "a", "b", "old"),
                merged.stream().map(Article::guid).toList());
        assertTrue(merged.get(1).read(), "a's read flag kept");
        assertEquals("Title a (edited)", merged.get(1).title(), "but the fetched copy's fields");
        assertFalse(merged.get(0).read());
        assertTrue(merged.get(3).read(), "an article the feed dropped stays, as it was");
    }

    /** A 304 once wrote a feed's whole article file again to note the time. */
    @Test
    void aRefreshThatChangedNothingWritesItsFactsAloneAndTheyWinOnLoad(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        Feed f1 = new Feed("f1", "https://x/rss", "X", 0);
        store.setFeeds(List.of(f1));
        List<Article> articles = List.of(article("a", 0, true), article("b", 1, false));
        store.setState("f1", new FeedState("\"v1\"", "", 1000, "", articles));
        Path root = dir.resolve(RssStore.DIR);
        Path articlesFile = root.resolve(RssStore.ARTICLES_DIR).resolve("f1.json");
        Path factsFile = root.resolve(RssStore.FETCHED_DIR).resolve("f1.json");
        String written = Files.readString(articlesFile);
        assertFalse(Files.exists(factsFile), "none until a refresh changes nothing else");

        FeedState kept = store.setFacts("f1", new FeedState("\"v1\"", "", 2000, "", List.of()));
        assertEquals(articles, kept.articles(), "the articles held stay, whatever the facts carried");
        assertEquals(written, Files.readString(articlesFile), "the articles file is not written again");
        assertEquals(
                "{\"etag\":\"\\\"v1\\\"\",\"lastModified\":\"\",\"refreshedEpochMs\":2000,\"error\":\"\",\"articles\":[]}\n",
                Files.readString(factsFile));
        store.setFacts("f1", store.state("f1").failed(3000, "the server answered 500"));

        RssStore again = new RssStore(dir);
        again.load();
        assertEquals(new FeedState("\"v1\"", "", 3000, "the server answered 500", articles), again.state("f1"));

        // A change to the articles writes them with the facts, and the facts file goes.
        again.setState("f1", again.state("f1").withArticles(List.of(article("a", 0, true))));
        assertFalse(Files.exists(factsFile));
        RssStore third = new RssStore(dir);
        third.load();
        assertEquals(3000, third.state("f1").refreshedEpochMs());
        assertEquals("the server answered 500", third.state("f1").error());
        assertEquals(1, third.state("f1").articles().size());
    }

    @Test
    void aFeedWithFactsAndNoArticlesFileStillHasItsRefresh(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        store.setFeeds(List.of(new Feed("f1", "https://x/rss", "X", 0)));
        store.setFacts("f1", FeedState.EMPTY.failed(5000, "unreachable"));
        RssStore again = new RssStore(dir);
        again.load();
        assertEquals(new FeedState("", "", 5000, "unreachable", List.of()), again.state("f1"));
        again.setFeeds(List.of());
        assertFalse(
                Files.exists(
                        dir.resolve(RssStore.DIR).resolve(RssStore.FETCHED_DIR).resolve("f1.json")),
                "a removed feed's facts go with it");
    }

    @Test
    void anUnreadableFactsFileLeavesTheArticlesFilesFacts(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        store.setFeeds(List.of(new Feed("f1", "https://x/rss", "X", 0)));
        FeedState state = new FeedState("e", "", 1000, "", List.of(article("a", 0, false)));
        store.setState("f1", state);
        Path facts = dir.resolve(RssStore.DIR).resolve(RssStore.FETCHED_DIR).resolve("f1.json");
        Files.createDirectories(facts.getParent());
        Files.writeString(facts, "{ not json");
        RssStore again = new RssStore(dir);
        again.load();
        assertEquals(state, again.state("f1"));
    }

    @Test
    void anUnreadableFileIsEmptyNotFatal(@TempDir Path dir) throws IOException {
        Path root = dir.resolve(RssStore.DIR);
        Files.createDirectories(root.resolve(RssStore.ARTICLES_DIR));
        Files.writeString(root.resolve(RssStore.FEEDS_FILE), "[{\"id\":\"f1\",\"url\":\"https://x\"}]");
        Files.writeString(root.resolve(RssStore.ARTICLES_DIR).resolve("f1.json"), "{ not json");
        RssStore store = new RssStore(dir);
        store.load();
        assertEquals(1, store.feeds().size(), "a feed without name or interval is still a feed");
        assertEquals(FeedState.EMPTY, store.state("f1"));
        Files.writeString(root.resolve(RssStore.FEEDS_FILE), "nope");
        store.load();
        assertTrue(store.feeds().isEmpty());
        assertThrows(IOException.class, () -> RssStore.parseFeeds("{}"));
        assertThrows(IOException.class, () -> RssStore.parseState("[]", "f"));
        assertEquals(List.of(), RssStore.parseFeeds("[{\"url\":\"https://no-id\"}, 7]"), "no id: skipped");
    }
}
