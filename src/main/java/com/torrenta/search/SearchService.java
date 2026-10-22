package com.torrenta.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a search across providers: each on its own virtual thread with a timeout, the answers
 * merged and ranked by {@link ResultRanker}, and what did not answer said in the outcome.
 *
 * <p>Polite to the indexers: a provider is asked at most once every {@link #MIN_INTERVAL}, and a
 * (provider, query, category) that was answered is not asked again for {@link #CACHE_LIFETIME}, so
 * the same search run again a while later finds what was added since. Blocking; the window calls
 * it off the FX thread.
 */
public final class SearchService {

    /** How long a provider has to answer before the outcome says it timed out. */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** The least time between two requests to one provider. */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(2);

    /** How long an answer is reused for the same (provider, query, category). */
    public static final Duration CACHE_LIFETIME = Duration.ofMinutes(5);

    /** The clock and the sleep, so the rate limit is tested without waiting. */
    public interface Ticker {
        long nanos();

        void sleep(Duration duration) throws InterruptedException;

        /** The real one. */
        Ticker SYSTEM = new Ticker() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                Thread.sleep(duration);
            }
        };
    }

    private record Key(String provider, String query, SearchCategory category) {}

    /** An answer and when it came, on the ticker's clock. */
    private record Answer(List<SearchResult> results, long atNanos) {}

    /** When a provider was last asked, by name; the lock serialises requests to one provider. */
    private static final class Gate {
        long lastNanos;
        boolean asked;
    }

    private final Duration timeout;
    private final Duration minInterval;
    private final Ticker ticker;
    private final Duration lifetime;
    private final Map<Key, Answer> cache = new ConcurrentHashMap<>();
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();

    public SearchService() {
        this(TIMEOUT, MIN_INTERVAL, Ticker.SYSTEM);
    }

    public SearchService(Duration timeout, Duration minInterval, Ticker ticker) {
        this(timeout, minInterval, ticker, CACHE_LIFETIME);
    }

    public SearchService(Duration timeout, Duration minInterval, Ticker ticker, Duration lifetime) {
        this.timeout = timeout;
        this.minInterval = minInterval;
        this.ticker = ticker;
        this.lifetime = lifetime;
    }

    /**
     * Whether an answer that came at {@code atNanos} may still stand in for asking at
     * {@code nowNanos}: younger than the lifetime. Compared as a difference, as nanoTime must be.
     */
    static boolean fresh(long atNanos, long nowNanos, Duration lifetime) {
        return nowNanos - atNanos < lifetime.toNanos();
    }

    /** The cached answer while it is fresh; a stale one is forgotten on the way. */
    private List<SearchResult> cached(Key key) {
        Answer answer = cache.get(key);
        if (answer == null) return null;
        if (fresh(answer.atNanos(), ticker.nanos(), lifetime)) return answer.results();
        cache.remove(key, answer);
        return null;
    }

    /**
     * Told of each provider as it answers or fails, in the order they do, on the searching thread:
     * the tab shows what has arrived while the slow ones are still being waited for.
     */
    public interface Progress {
        /** The provider answered with these results (empty when nothing matched). */
        void answered(String provider, List<SearchResult> results);

        /** The provider could not answer; the reason is what the status line says. */
        void failed(String provider, String reason);

        /** Nobody listening. */
        Progress NONE = new Progress() {
            @Override
            public void answered(String provider, List<SearchResult> results) {}

            @Override
            public void failed(String provider, String reason) {}
        };
    }

    /**
     * Asks every provider at once and waits up to the timeout for all of them. A provider that
     * throws, times out or is interrupted is a line in {@link SearchOutcome#failures()}; the rest
     * are merged. A blank query asks nobody.
     */
    public SearchOutcome search(String query, SearchCategory category, List<SearchProvider> providers) {
        return search(query, category, providers, Progress.NONE);
    }

    /**
     * {@link #search(String, SearchCategory, List)}, reporting each provider's answer or failure
     * as it comes in. Interrupting the calling thread cancels every provider still being waited
     * for and marks it "interrupted"; the outcome still holds what arrived before.
     */
    public SearchOutcome search(
            String query, SearchCategory category, List<SearchProvider> providers, Progress progress) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || providers.isEmpty()) return new SearchOutcome(List.of(), Map.of(), 0);
        SearchCategory cat = category == null ? SearchCategory.ALL : category;
        String lower = q.toLowerCase(Locale.ROOT);

        List<List<SearchResult>> answers = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<SearchProvider> toAsk = new ArrayList<>();
        for (SearchProvider provider : providers) {
            List<SearchResult> cached = cached(new Key(provider.name(), lower, cat));
            if (cached != null) {
                answers.add(cached);
                progress.answered(provider.name(), cached);
            } else {
                toAsk.add(provider);
            }
        }
        if (!toAsk.isEmpty()) {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletionService<List<SearchResult>> done = new ExecutorCompletionService<>(pool);
                Map<Future<List<SearchResult>>, SearchProvider> pending = new LinkedHashMap<>();
                for (SearchProvider provider : toAsk) pending.put(done.submit(() -> ask(provider, q, cat)), provider);
                long deadline = System.nanoTime() + timeout.toNanos();
                String leftover = null;
                while (!pending.isEmpty()) {
                    Future<List<SearchResult>> future;
                    try {
                        future = done.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        leftover = "interrupted";
                        break;
                    }
                    if (future == null) {
                        leftover = "timed out after " + timeout.toSeconds() + " s";
                        break;
                    }
                    SearchProvider provider = pending.remove(future);
                    try {
                        List<SearchResult> results = future.get();
                        cache.put(new Key(provider.name(), lower, cat), new Answer(results, ticker.nanos()));
                        answers.add(results);
                        progress.answered(provider.name(), results);
                    } catch (CancellationException e) {
                        failures.put(provider.name(), "cancelled");
                        progress.failed(provider.name(), "cancelled");
                    } catch (ExecutionException e) {
                        failures.put(provider.name(), reason(e.getCause()));
                        progress.failed(provider.name(), reason(e.getCause()));
                    } catch (InterruptedException e) {
                        // Cannot happen: the future is done. Treated as the interruption it says.
                        Thread.currentThread().interrupt();
                        leftover = "interrupted";
                        pending.put(future, provider);
                        break;
                    }
                }
                if (leftover != null) {
                    for (Map.Entry<Future<List<SearchResult>>, SearchProvider> e : pending.entrySet()) {
                        e.getKey().cancel(true);
                        failures.put(e.getValue().name(), leftover);
                        progress.failed(e.getValue().name(), leftover);
                    }
                }
            }
        }
        // Failures in the providers' order, whatever order they arrived in.
        Map<String, String> ordered = new LinkedHashMap<>();
        for (SearchProvider provider : providers) {
            String reason = failures.get(provider.name());
            if (reason != null) ordered.put(provider.name(), reason);
        }
        return new SearchOutcome(ResultRanker.merge(answers, q), ordered, providers.size());
    }

    /** Waits its turn at the provider's gate, then asks. */
    private List<SearchResult> ask(SearchProvider provider, String query, SearchCategory category) throws Exception {
        Gate gate = gates.computeIfAbsent(provider.name(), n -> new Gate());
        synchronized (gate) {
            if (gate.asked) {
                long wait = gate.lastNanos + minInterval.toNanos() - ticker.nanos();
                if (wait > 0) ticker.sleep(Duration.ofNanos(wait));
            }
            gate.lastNanos = ticker.nanos();
            gate.asked = true;
            List<SearchResult> results = provider.search(query, category);
            return results == null ? List.of() : List.copyOf(results);
        }
    }

    private static String reason(Throwable cause) {
        if (cause == null) return "failed";
        if (cause instanceof InterruptedException) return "interrupted";
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return cause.getClass().getSimpleName();
        return message;
    }

    /** Forgets every cached answer, so the next search asks again. */
    public void clearCache() {
        cache.clear();
    }

    /** How many (provider, query, category) answers are held. */
    public int cachedAnswers() {
        return cache.size();
    }
}
