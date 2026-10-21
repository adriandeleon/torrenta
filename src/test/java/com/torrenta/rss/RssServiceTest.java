package com.torrenta.rss;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The service with a stub source and inline executors, so every step is seen in order. */
class RssServiceTest {

    /** Answers by URL: a result, or a failure; counts the asks and keeps the validators sent. */
    private static final class Stub implements FeedSource {
        final Map<String, Result> answers = new ConcurrentHashMap<>();
        final Map<String, String> failures = new ConcurrentHashMap<>();
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<String> validators = new CopyOnWriteArrayList<>();

        @Override
        public Result fetch(Feed feed, String etag, String lastModified) throws FeedException {
            asked.add(feed.url());
            validators.add(etag + "|" + lastModified);
            String failure = failures.get(feed.url());
            if (failure != null) throw new FeedException(failure);
            Result r = answers.get(feed.url());
            if (r == null) throw new FeedException("the server answered 404");
            return r;
        }
    }

    private static final class Clocks {
        Instant now = Instant.parse("2026-10-18T12:00:00Z");

        Clock clock() {
            return new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };
        }
    }

    private static Article item(String guid, String when) {
        return new Article(
                "",
                guid,
                "Item " + guid,
                "https://x/" + guid,
                "",
                when == null ? null : Instant.parse(when),
                "",
                false);
    }

    private static FeedSource.Result answer(String title, String etag, Article... items) {
        return new FeedSource.Result(true, title, List.of(items), etag, "");
    }

    private final Stub stub = new Stub();
    private final Clocks clocks = new Clocks();
    private final List<String> told = new ArrayList<>();

    private RssService service(Path dir, int globalMinutes) {
        RssStore store = new RssStore(dir);
        store.load();
        RssService s = new RssService(store, stub, () -> globalMinutes, Runnable::run, Runnable::run, clocks.clock());
        s.addListener(id -> told.add(id == null ? "list" : id));
        return s;
    }

    @Test
    void addingAFeedFetchesItNamesItAndMergesByGuidOnTheNextRefresh(@TempDir Path dir) throws IOException {
        RssService s = service(dir, 30);
        stub.answers.put(
                "https://x/rss", answer("Tracker", "\"v1\"", item("a", "2026-10-18T10:00:00Z"), item("b", null)));
        Feed f = Feed.of("https://x/rss", "", 0);
        s.addFeed(f);
        assertEquals(List.of("list", f.id(), "list", f.id()), told, "the list, refreshing, renamed, refreshed");
        assertEquals("Tracker", s.feed(f.id()).name(), "a blank name takes the feed's title");
        assertEquals(
                List.of("b", "a"),
                s.articles(f.id()).stream().map(Article::guid).toList(),
                "undated first");
        assertEquals(2, s.unread(f.id()));
        assertEquals(2, s.unreadTotal());
        RssService.Status status = s.status(f.id());
        assertEquals(clocks.now.toEpochMilli(), status.refreshedEpochMs());
        assertFalse(status.failed());
        assertFalse(status.refreshing());

        s.markRead(f.id(), "g:a", true);
        assertEquals(1, s.unread(f.id()));
        told.clear();
        stub.answers.put(
                "https://x/rss",
                answer("Tracker", "\"v2\"", item("c", "2026-10-18T11:00:00Z"), item("a", "2026-10-18T10:00:00Z")));
        clocks.now = clocks.now.plusSeconds(3600);
        s.refresh(f.id());
        assertEquals(
                "\"v1\"|", stub.validators.get(stub.validators.size() - 1), "the last answer's validators are sent");
        List<Article> after = s.articles(f.id());
        assertEquals(List.of("b", "c", "a"), after.stream().map(Article::guid).toList());
        assertTrue(after.get(2).read(), "a stays read");
        assertFalse(after.get(1).read());
        assertEquals(2, s.unread(f.id()));
        assertEquals(List.of(f.id(), f.id()), told, "refreshing, then refreshed; the name was set already");

        s.markFeedRead(f.id());
        assertEquals(0, s.unread(f.id()));

        RssService reopened = service(dir, 30);
        assertEquals(
                List.of("b", "c", "a"),
                reopened.articles(f.id()).stream().map(Article::guid).toList());
        assertEquals(0, reopened.unread(f.id()), "read flags on disk");
    }

    @Test
    void anUnchangedAnswerKeepsTheArticlesAndAFailureIsTheStatus(@TempDir Path dir) throws IOException {
        RssService s = service(dir, 30);
        stub.answers.put("https://x/rss", answer("T", "\"v1\"", item("a", null)));
        Feed f = Feed.of("https://x/rss", "Mine", 0);
        s.addFeed(f);
        assertEquals("Mine", s.feed(f.id()).name(), "a given name is kept");
        stub.answers.put("https://x/rss", FeedSource.Result.unchanged("\"v1\"", ""));
        s.refresh(f.id());
        assertEquals(1, s.articles(f.id()).size());
        assertEquals("\"v1\"", s.state(f.id()).etag());

        stub.failures.put("https://x/rss", "the server answered 404");
        clocks.now = clocks.now.plusSeconds(60);
        s.refresh(f.id());
        RssService.Status status = s.status(f.id());
        assertTrue(status.failed());
        assertEquals("the server answered 404", status.error());
        assertEquals(clocks.now.toEpochMilli(), status.refreshedEpochMs(), "a failure counts as a try");
        assertEquals(1, s.articles(f.id()).size(), "the articles stay");

        stub.failures.remove("https://x/rss");
        stub.answers.put("https://x/rss", answer("T", "\"v2\"", item("a", null), item("b", null)));
        s.refresh(f.id());
        assertFalse(s.status(f.id()).failed(), "the next good answer clears it");
        assertEquals(2, s.articles(f.id()).size());
    }

    @Test
    void theMinuteCheckRefreshesWhatIsDueByTheGlobalOrTheFeedsOwnInterval(@TempDir Path dir) throws IOException {
        RssService s = service(dir, 30);
        stub.answers.put("https://x/a", answer("A", "", item("a1", null)));
        stub.answers.put("https://x/b", answer("B", "", item("b1", null)));
        Feed a = new Feed("fa", "https://x/a", "A", 0);
        Feed b = new Feed("fb", "https://x/b", "B", 10);
        s.addFeed(a);
        s.addFeed(b);
        assertEquals(List.of("https://x/a", "https://x/b"), stub.asked);

        assertEquals(List.of(), s.refreshDue(clocks.now.plusSeconds(9 * 60)), "nothing due at nine minutes");
        assertEquals(List.of("fb"), s.refreshDue(clocks.now.plusSeconds(10 * 60)), "b's own ten minutes");
        clocks.now = clocks.now.plusSeconds(10 * 60);
        assertEquals(
                List.of("fa", "fb"), s.refreshDue(clocks.now.plusSeconds(20 * 60)), "a at the global thirty, b again");
        assertEquals(5, stub.asked.size());

        assertTrue(RssService.due(FeedState.EMPTY, a, 30, clocks.now), "never fetched is due");
        FeedState just = FeedState.EMPTY.answered("", "", clocks.now.toEpochMilli(), List.of());
        assertFalse(RssService.due(just, a, 30, clocks.now.plusSeconds(29 * 60)));
        assertTrue(RssService.due(just, a, 30, clocks.now.plusSeconds(30 * 60)));
        assertTrue(RssService.due(just, a, 1, clocks.now.plusSeconds(5 * 60)), "the global floor is five minutes");
        assertFalse(RssService.due(just, a, 1, clocks.now.plusSeconds(4 * 60)));
    }

    @Test
    void editingTheUrlForgetsTheValidatorsAndRemovingAFeedRemovesItsArticles(@TempDir Path dir) throws IOException {
        RssService s = service(dir, 30);
        stub.answers.put("https://x/a", answer("A", "\"v1\"", item("a1", null)));
        stub.answers.put("https://x/b", answer("B", "\"v9\"", item("b1", null)));
        Feed a = new Feed("fa", "https://x/a", "A", 0);
        s.addFeed(a);
        assertEquals("\"v1\"", s.state("fa").etag());
        s.updateFeed(new Feed("fa", "https://x/a", "Renamed", 0));
        assertEquals(1, stub.asked.size(), "a rename fetches nothing");
        assertEquals("Renamed", s.feed("fa").name());
        s.updateFeed(new Feed("fa", "https://x/b", "Renamed", 0));
        assertEquals("|", stub.validators.get(stub.validators.size() - 1), "a new URL is asked without validators");
        assertEquals(
                List.of("b1", "a1"),
                s.articles("fa").stream().map(Article::guid).toList(),
                "merged on top");
        s.removeFeed("fa");
        assertTrue(s.feeds().isEmpty());
        assertEquals(FeedState.EMPTY, s.state("fa"));
        assertEquals(0, s.unreadTotal());
    }

    @Test
    void aFeedIsFetchedOnceAtATimeAndAllArticlesAreNewestFirstAcrossFeeds(@TempDir Path dir) throws IOException {
        RssStore store = new RssStore(dir);
        List<Runnable> queued = new ArrayList<>();
        RssService s = new RssService(store, stub, () -> 30, queued::add, Runnable::run, clocks.clock());
        stub.answers.put("https://x/a", answer("A", "", item("a1", "2026-10-18T10:00:00Z")));
        stub.answers.put("https://x/b", answer("B", "", item("b1", "2026-10-18T11:00:00Z"), item("b0", null)));
        s.addFeed(new Feed("fa", "https://x/a", "A", 0));
        s.addFeed(new Feed("fb", "https://x/b", "B", 0));
        s.refreshAll();
        assertEquals(2, queued.size(), "a feed already being fetched is not started again");
        assertTrue(s.status("fa").refreshing());
        for (Runnable r : queued) r.run();
        assertFalse(s.status("fa").refreshing());
        assertEquals(
                List.of("b0", "b1", "a1"),
                s.allArticles().stream().map(Article::guid).toList());
        assertEquals(Map.of("fa", "A", "fb", "B"), s.namesById());
    }

    private static Article torrent(String guid, String title, String when) {
        return new Article(
                "",
                guid,
                title,
                "https://x/" + guid,
                "magnet:?xt=urn:btih:" + "0".repeat(40) + "&dn=" + guid,
                when == null ? null : Instant.parse(when),
                "",
                false);
    }

    @Test
    void aNewArticleARuleWantsIsDownloadedOnceAndMarked(@TempDir Path dir) throws IOException {
        RssService s = service(dir, 30);
        List<String> downloads = new CopyOnWriteArrayList<>();
        List<String> reports = new CopyOnWriteArrayList<>();
        AtomicBoolean on = new AtomicBoolean(true);
        s.setDownloader(
                (rule, article) -> {
                    if (article.title().contains("broken")) throw new IOException("the server answered 404");
                    downloads.add(rule.name() + ":" + article.guid());
                },
                on::get,
                (message, failed) -> reports.add((failed ? "! " : "") + message));
        s.putRule(null, Rule.blank("Debian").withEnabled(true));
        s.putRule(
                "Debian",
                new Rule("Debian", true, "debian", "", false, "", true, List.of(), 0, null, "", "", null, List.of()));
        assertEquals(1, s.rules().size(), "a rename or an edit replaces");
        assertEquals("debian", s.rule("Debian").mustContain());
        assertThrows(IllegalArgumentException.class, () -> s.putRule(null, Rule.blank("Debian")), "names are unique");

        stub.answers.put(
                "https://x/rss",
                answer(
                        "Tracker",
                        "\"v1\"",
                        torrent("a", "Debian 12.7.0 netinst", "2026-10-18T10:00:00Z"),
                        torrent("b", "Ubuntu 24.04", null),
                        torrent("c", "Debian broken link", null)));
        Feed f = Feed.of("https://x/rss", "", 0);
        s.addFeed(f);
        assertEquals(List.of("Debian:a"), downloads, "the one the rule wants, once");
        assertEquals(
                List.of(
                        "! Rule 'Debian' could not add Debian broken link from feed Tracker: the server answered 404",
                        "Rule 'Debian' added Debian 12.7.0 netinst from feed Tracker"),
                reports,
                "in the merged order, undated first; the feed's new name is known already");
        List<Article> articles = s.articles(f.id());
        Article a =
                articles.stream().filter(x -> x.guid().equals("a")).findFirst().orElseThrow();
        assertTrue(a.downloaded(), "marked downloaded");
        assertTrue(a.read(), "and read");
        assertFalse(articles.stream()
                .filter(x -> x.guid().equals("c"))
                .findFirst()
                .orElseThrow()
                .downloaded());
        assertEquals(1, s.unread(f.id()) - 1, "b and c stay unread");
        assertEquals(clocks.now, s.rule("Debian").lastMatch());
        assertEquals(List.of("debian 12 7 0 netinst"), s.rule("Debian").previouslyMatched());

        stub.answers.put(
                "https://x/rss",
                answer(
                        "Tracker",
                        "\"v2\"",
                        torrent("a", "Debian 12.7.0 netinst", "2026-10-18T10:00:00Z"),
                        torrent("d", "Debian 12.8.0 netinst", "2026-10-19T10:00:00Z")));
        s.refresh(f.id());
        assertEquals(List.of("Debian:a", "Debian:d"), downloads, "the old one is not added again; the new one is");

        on.set(false);
        stub.answers.put("https://x/rss", answer("Tracker", "\"v3\"", torrent("e", "Debian 13 alpha", null)));
        s.refresh(f.id());
        assertEquals(2, downloads.size(), "the switch off: nothing added by a refresh");
        assertFalse(s.articles(f.id()).stream()
                .filter(x -> x.guid().equals("e"))
                .findFirst()
                .orElseThrow()
                .downloaded());

        reports.clear();
        s.runRulesNow();
        assertEquals(List.of("Debian:a", "Debian:d", "Debian:e"), downloads, "Run rules now ignores the switch");
        assertEquals("RSS rules added 1 torrent", reports.get(reports.size() - 1));
        s.runRulesNow();
        assertTrue(
                reports.get(reports.size() - 1).startsWith("RSS rules: nothing new to add among"), reports.toString());
        assertEquals(3, downloads.size());

        s.markRead(f.id(), "g:b", true);
        s.markDownloaded(f.id(), "g:b");
        RssService reopened = service(dir, 30);
        assertTrue(reopened.articles(f.id()).stream()
                .filter(x -> x.guid().equals("b"))
                .findFirst()
                .orElseThrow()
                .downloaded());
        assertEquals(1, reopened.rules().size(), "rules on disk");
        reopened.removeRule("Debian");
        assertTrue(reopened.rules().isEmpty());
        reopened.setDownloader(null, null, null);
        reopened.runRulesNow();
    }
}
