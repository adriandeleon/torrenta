package com.torrenta.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    /** Answers after a delay, or throws; counts how often it was asked. */
    private static final class Fake implements SearchProvider {
        final String name;
        final List<SearchResult> answer;
        final Duration delay;
        final String failure;
        final AtomicInteger calls = new AtomicInteger();

        Fake(String name, List<SearchResult> answer, Duration delay, String failure) {
            this.name = name;
            this.answer = answer;
            this.delay = delay;
            this.failure = failure;
        }

        static Fake answering(String name, SearchResult... results) {
            return new Fake(name, List.of(results), Duration.ZERO, null);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<SearchResult> search(String query, SearchCategory category) throws SearchException {
            calls.incrementAndGet();
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SearchException("interrupted", e);
                }
            }
            if (failure != null) throw new SearchException(failure);
            return answer;
        }
    }

    /** A clock that stands still and a sleep that only takes notes. */
    private static final class StillTicker implements SearchService.Ticker {
        final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanos() {
            return 1_000_000_000L;
        }

        @Override
        public synchronized void sleep(Duration duration) {
            sleeps.add(duration);
        }
    }

    private static SearchResult hit(String name, int seeds, String hash, String provider) {
        return new SearchResult(
                name,
                1,
                seeds,
                0,
                null,
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:" + hash),
                hash,
                provider,
                "");
    }

    private static final String H1 = "2aa4f5a7e209e54b32803d43670971c4c8caaa05";
    private static final String H2 = "4e3d2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e";

    private static SearchService quick(Duration timeout) {
        return new SearchService(timeout, Duration.ZERO, SearchService.Ticker.SYSTEM);
    }

    @Test
    void providersRunTogetherAndASlowOneTimesOutAlone() {
        Fake fast = Fake.answering("Fast", hit("a", 5, H1, "Fast"));
        Fake alsoFast = new Fake("Also", List.of(hit("b", 3, H2, "Also")), Duration.ofMillis(150), null);
        Fake slow = new Fake("Slow", List.of(), Duration.ofSeconds(30), null);
        long started = System.nanoTime();
        SearchOutcome outcome =
                quick(Duration.ofMillis(800)).search("x", SearchCategory.ALL, List.of(fast, alsoFast, slow));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(tookMs < 5_000, "the slow one did not hold the answer: " + tookMs + " ms");
        assertEquals(
                List.of("a", "b"),
                outcome.results().stream().map(SearchResult::name).toList());
        assertEquals(Map.of("Slow", "timed out after 0 s"), outcome.failures());
        assertEquals(3, outcome.asked());
    }

    @Test
    void aFailingProviderIsALineNotAnException() {
        Fake good = Fake.answering("Good", hit("a", 5, H1, "Good"));
        Fake bad = new Fake("Bad", List.of(), Duration.ZERO, "the API key was refused (401)");
        SearchOutcome outcome = quick(Duration.ofSeconds(5)).search("x", SearchCategory.ALL, List.of(good, bad));
        assertEquals(1, outcome.results().size());
        assertEquals(Map.of("Bad", "the API key was refused (401)"), outcome.failures());
        assertTrue(!outcome.complete());
    }

    @Test
    void theSameTorrentFromTwoProvidersIsOneResult() {
        Fake a = Fake.answering("A", hit("ubuntu", 10, H1, "A"), hit("other", 1, H2, "A"));
        Fake b = Fake.answering("B", hit("ubuntu", 50, H1.toUpperCase(), "B"));
        SearchOutcome outcome = quick(Duration.ofSeconds(5)).search("ubuntu", SearchCategory.ALL, List.of(a, b));
        assertEquals(2, outcome.results().size());
        assertEquals("B", outcome.results().get(0).provider(), "the better-seeded copy, first by rank");
        assertEquals(50, outcome.results().get(0).seeds());
    }

    @Test
    void anAnswerIsCachedPerProviderQueryAndCategory() {
        Fake a = Fake.answering("A", hit("a", 5, H1, "A"));
        SearchService service = quick(Duration.ofSeconds(5));
        SearchOutcome first = service.search("Ubuntu", SearchCategory.ALL, List.of(a));
        SearchOutcome again = service.search(" ubuntu ", SearchCategory.ALL, List.of(a));
        assertEquals(first.results(), again.results());
        assertEquals(1, a.calls.get(), "the second search was answered from the cache");
        service.search("ubuntu", SearchCategory.MOVIES, List.of(a));
        assertEquals(2, a.calls.get(), "another category is another question");
        service.search("debian", SearchCategory.ALL, List.of(a));
        assertEquals(3, a.calls.get());
        assertEquals(3, service.cachedAnswers());
        service.clearCache();
        service.search("ubuntu", SearchCategory.ALL, List.of(a));
        assertEquals(4, a.calls.get());

        Fake failing = new Fake("F", List.of(), Duration.ZERO, "down");
        service.search("x", SearchCategory.ALL, List.of(failing));
        service.search("x", SearchCategory.ALL, List.of(failing));
        assertEquals(2, failing.calls.get(), "a failure is not cached");
    }

    @Test
    void aProviderIsAskedAtMostOnceEveryTwoSeconds() {
        StillTicker ticker = new StillTicker();
        SearchService service = new SearchService(Duration.ofSeconds(5), Duration.ofSeconds(2), ticker);
        Fake a = Fake.answering("A", hit("a", 5, H1, "A"));
        Fake b = Fake.answering("B", hit("b", 5, H2, "B"));
        service.search("one", SearchCategory.ALL, List.of(a, b));
        assertTrue(ticker.sleeps.isEmpty(), "the first question waits for nobody");
        service.search("two", SearchCategory.ALL, List.of(a));
        assertEquals(List.of(Duration.ofSeconds(2)), ticker.sleeps, "the clock stood still, so the whole interval");
        service.search("three", SearchCategory.ALL, List.of(a, b));
        assertEquals(3, ticker.sleeps.size(), "each provider waits at its own gate");
        service.search("two", SearchCategory.ALL, List.of(a));
        assertEquals(3, ticker.sleeps.size(), "a cached answer asks nothing and waits for nothing");
    }

    /** Writes down each call as it comes, with the thread it came on. */
    private static final class Notes implements SearchService.Progress {
        final List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Thread> threads = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void answered(String provider, List<SearchResult> results) {
            lines.add(provider + "=" + results.size());
            threads.add(Thread.currentThread());
        }

        @Override
        public void failed(String provider, String reason) {
            lines.add(provider + "!" + reason);
            threads.add(Thread.currentThread());
        }
    }

    @Test
    void eachProviderIsReportedAsItAnswersOnTheCallingThread() {
        Fake fast = Fake.answering("Fast", hit("a", 5, H1, "Fast"));
        Fake slow = new Fake("Slow", List.of(hit("b", 3, H2, "Slow")), Duration.ofMillis(300), null);
        Fake bad = new Fake("Bad", List.of(), Duration.ofMillis(100), "the API key was refused (401)");
        Notes notes = new Notes();
        SearchOutcome outcome =
                quick(Duration.ofSeconds(5)).search("x", SearchCategory.ALL, List.of(slow, bad, fast), notes);
        assertEquals(List.of("Fast=1", "Bad!the API key was refused (401)", "Slow=1"), notes.lines, "arrival order");
        assertTrue(notes.threads.stream().allMatch(t -> t == Thread.currentThread()));
        assertEquals(2, outcome.results().size(), "the outcome is the same whole as without a listener");
        assertEquals(Map.of("Bad", "the API key was refused (401)"), outcome.failures());
    }

    @Test
    void aTimedOutProviderIsReportedAsSuchAndACachedAnswerAtOnce() {
        Fake fast = Fake.answering("Fast", hit("a", 5, H1, "Fast"));
        Fake slow = new Fake("Slow", List.of(), Duration.ofSeconds(30), null);
        SearchService service = quick(Duration.ofMillis(400));
        Notes notes = new Notes();
        service.search("x", SearchCategory.ALL, List.of(fast, slow), notes);
        assertEquals(List.of("Fast=1", "Slow!timed out after 0 s"), notes.lines);
        Notes again = new Notes();
        service.search("x", SearchCategory.ALL, List.of(fast), again);
        assertEquals(List.of("Fast=1"), again.lines, "from the cache, before anything is asked");
        assertEquals(1, fast.calls.get());
    }

    @Test
    void interruptingTheSearchMarksWhatHadNotAnsweredAndKeepsWhatHad() throws Exception {
        Fake fast = Fake.answering("Fast", hit("a", 5, H1, "Fast"));
        Fake slow = new Fake("Slow", List.of(), Duration.ofSeconds(30), null);
        Notes notes = new Notes();
        SearchOutcome[] outcome = new SearchOutcome[1];
        Thread searcher = new Thread(() ->
                outcome[0] = quick(Duration.ofSeconds(30)).search("x", SearchCategory.ALL, List.of(fast, slow), notes));
        searcher.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (notes.lines.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        searcher.interrupt();
        searcher.join(Duration.ofSeconds(5).toMillis());
        assertEquals(List.of("Fast=1", "Slow!interrupted"), notes.lines);
        assertEquals(1, outcome[0].results().size());
        assertEquals(Map.of("Slow", "interrupted"), outcome[0].failures());
    }

    @Test
    void aBlankQueryAsksNobody() {
        Fake a = Fake.answering("A", hit("a", 5, H1, "A"));
        SearchOutcome outcome = quick(Duration.ofSeconds(5)).search("  ", SearchCategory.ALL, List.of(a));
        assertEquals(0, a.calls.get());
        assertEquals(new SearchOutcome(List.of(), Map.of(), 0), outcome);
    }
}
