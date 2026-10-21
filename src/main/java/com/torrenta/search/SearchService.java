package com.torrenta.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a search across providers: each on its own virtual thread with a timeout, the answers
 * merged and ranked by {@link ResultRanker}, and what did not answer said in the outcome.
 *
 * <p>Polite to the indexers: a provider is asked at most once every {@link #MIN_INTERVAL}, and a
 * (provider, query, category) that was answered is not asked again for the life of the service.
 * Blocking; the window calls it off the FX thread.
 */
public final class SearchService {

    /** How long a provider has to answer before the outcome says it timed out. */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** The least time between two requests to one provider. */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(2);

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

    /** When a provider was last asked, by name; the lock serialises requests to one provider. */
    private static final class Gate {
        long lastNanos;
        boolean asked;
    }

    private final Duration timeout;
    private final Duration minInterval;
    private final Ticker ticker;
    private final Map<Key, List<SearchResult>> cache = new ConcurrentHashMap<>();
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();

    public SearchService() {
        this(TIMEOUT, MIN_INTERVAL, Ticker.SYSTEM);
    }

    public SearchService(Duration timeout, Duration minInterval, Ticker ticker) {
        this.timeout = timeout;
        this.minInterval = minInterval;
        this.ticker = ticker;
    }

    /**
     * Asks every provider at once and waits up to the timeout for all of them. A provider that
     * throws, times out or is interrupted is a line in {@link SearchOutcome#failures()}; the rest
     * are merged. A blank query asks nobody.
     */
    public SearchOutcome search(String query, SearchCategory category, List<SearchProvider> providers) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || providers.isEmpty()) return new SearchOutcome(List.of(), Map.of(), 0);
        SearchCategory cat = category == null ? SearchCategory.ALL : category;

        List<List<SearchResult>> answers = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<SearchProvider> toAsk = new ArrayList<>();
        for (SearchProvider provider : providers) {
            List<SearchResult> cached = cache.get(new Key(provider.name(), q.toLowerCase(Locale.ROOT), cat));
            if (cached != null) answers.add(cached);
            else toAsk.add(provider);
        }
        if (!toAsk.isEmpty()) {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<List<SearchResult>>> futures = new ArrayList<>();
                for (SearchProvider provider : toAsk) futures.add(pool.submit(() -> ask(provider, q, cat)));
                long deadline = System.nanoTime() + timeout.toNanos();
                for (int i = 0; i < toAsk.size(); i++) {
                    SearchProvider provider = toAsk.get(i);
                    Future<List<SearchResult>> future = futures.get(i);
                    try {
                        List<SearchResult> results =
                                future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        cache.put(new Key(provider.name(), q.toLowerCase(Locale.ROOT), cat), results);
                        answers.add(results);
                    } catch (TimeoutException e) {
                        future.cancel(true);
                        failures.put(provider.name(), "timed out after " + timeout.toSeconds() + " s");
                    } catch (CancellationException e) {
                        failures.put(provider.name(), "cancelled");
                    } catch (ExecutionException e) {
                        failures.put(provider.name(), reason(e.getCause()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        for (Future<?> f : futures) f.cancel(true);
                        failures.put(provider.name(), "interrupted");
                        for (int j = i + 1; j < toAsk.size(); j++)
                            failures.put(toAsk.get(j).name(), "interrupted");
                        break;
                    }
                }
            }
        }
        return new SearchOutcome(ResultRanker.merge(answers, q), failures, providers.size());
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
