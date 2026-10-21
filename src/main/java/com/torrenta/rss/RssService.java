package com.torrenta.rss;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The reader: keeps the feeds and their articles through an {@link RssStore}, refreshes each feed
 * when it is due (every minute a check; the global interval, or the feed's own), or when asked,
 * one fetch per feed at a time on the executor given (a virtual thread each in the window), and
 * tells its listeners through the other executor given (the FX thread in the window; inline in a
 * test). New articles are merged by identity, read flags kept; a feed's failure is its status.
 *
 * <p>The auto-download rules run here too, on the fetch thread, never the notify one: when the
 * switch is on and a refresh brings new articles, {@link RuleEngine} says which to add and the
 * {@link Downloader} (the window's add path) adds them, each marked downloaded and said through
 * the {@link Reporter}; {@link #runRulesNow()} runs them over everything unread and not yet
 * downloaded. An article is never added twice: the flag is kept with it.
 *
 * <p>Nothing here touches the engine or JavaFX. The window owns one for its life.
 */
public final class RssService {

    private static final Logger LOG = Logger.getLogger(RssService.class.getName());

    /** Told on the notify executor; {@code feedId} is null when the feed list itself changed. */
    public interface Listener {
        void changed(String feedId);
    }

    /** Adds an article's torrent as a rule says, on the fetch thread; throws with the reason when it cannot. */
    public interface Downloader {
        void download(Rule rule, Article article) throws IOException;
    }

    /** Told, on the fetch thread, of each add a rule made and each it could not. */
    public interface Reporter {
        void report(String message, boolean failed);
    }

    /** A feed's refresh facts as the lists show them. */
    public record Status(long refreshedEpochMs, String error, boolean refreshing, int unread) {
        public boolean failed() {
            return !error.isEmpty();
        }
    }

    private final RssStore store;
    private final FeedSource source;
    private final IntSupplier globalMinutes;
    private final Executor fetchExecutor;
    private final Executor notifyExecutor;
    private final Clock clock;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> refreshing = new HashSet<>();
    private final Object rulesLock = new Object();
    private ScheduledExecutorService scheduler;
    private volatile Downloader downloader;
    private volatile Reporter reporter = (message, failed) -> {};
    private volatile BooleanSupplier autoDownload = () -> false;

    public RssService(
            RssStore store,
            FeedSource source,
            IntSupplier globalMinutes,
            Executor fetchExecutor,
            Executor notifyExecutor) {
        this(store, source, globalMinutes, fetchExecutor, notifyExecutor, Clock.systemUTC());
    }

    public RssService(
            RssStore store,
            FeedSource source,
            IntSupplier globalMinutes,
            Executor fetchExecutor,
            Executor notifyExecutor,
            Clock clock) {
        this.store = store;
        this.source = source;
        this.globalMinutes = globalMinutes;
        this.fetchExecutor = fetchExecutor;
        this.notifyExecutor = notifyExecutor;
        this.clock = clock;
    }

    /** Where the feeds come from, for a dialog that wants to try one before adding it. */
    public FeedSource source() {
        return source;
    }

    /**
     * Wires the rules to an add path: {@code on} is the global switch, read at each refresh;
     * {@code reporter} hears of every add (the window puts it in the event log).
     */
    public void setDownloader(Downloader downloader, BooleanSupplier on, Reporter reporter) {
        this.downloader = downloader;
        this.autoDownload = on == null ? () -> false : on;
        this.reporter = reporter == null ? (message, failed) -> {} : reporter;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Starts the minute check; the feeds due now are refreshed at once. */
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rss-schedule");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> refreshDue(Instant.now(clock)), 0, 1, TimeUnit.MINUTES);
    }

    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    // ---- reading --------------------------------------------------------------------------------

    public List<Feed> feeds() {
        return store.feeds();
    }

    public Feed feed(String id) {
        return store.feed(id);
    }

    public FeedState state(String feedId) {
        return store.state(feedId);
    }

    public List<Article> articles(String feedId) {
        return store.state(feedId).articles();
    }

    /** Every feed's articles, newest first across feeds; undated ones keep their feed's order, first. */
    public List<Article> allArticles() {
        List<Article> all = new ArrayList<>();
        for (Feed f : store.feeds()) all.addAll(store.state(f.id()).articles());
        all.sort(BY_DATE);
        return all;
    }

    public synchronized Status status(String feedId) {
        FeedState s = store.state(feedId);
        return new Status(s.refreshedEpochMs(), s.error(), refreshing.contains(feedId), s.unread());
    }

    public int unread(String feedId) {
        return store.state(feedId).unread();
    }

    public int unreadTotal() {
        int n = 0;
        for (Feed f : store.feeds()) n += store.state(f.id()).unread();
        return n;
    }

    /** A feed's name for a display: the feed's, or empty when the id is unknown. */
    public Map<String, String> namesById() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Feed f : store.feeds()) out.put(f.id(), f.displayName());
        return out;
    }

    // ---- the list -------------------------------------------------------------------------------

    /** Adds the feed and refreshes it at once. */
    public void addFeed(Feed feed) throws IOException {
        synchronized (this) {
            List<Feed> list = new ArrayList<>(store.feeds());
            list.removeIf(f -> f.id().equals(feed.id()));
            list.add(feed);
            store.setFeeds(list);
        }
        notify(null);
        refresh(feed.id());
    }

    /** Replaces the feed with the same id; a changed URL forgets the validators so the next fetch is full. */
    public void updateFeed(Feed feed) throws IOException {
        boolean urlChanged = false;
        synchronized (this) {
            List<Feed> list = new ArrayList<>(store.feeds());
            int at = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(feed.id())) at = i;
            if (at < 0) {
                list.add(feed);
            } else {
                urlChanged = !list.get(at).url().equals(feed.url());
                list.set(at, feed);
            }
            store.setFeeds(list);
            if (urlChanged) {
                FeedState s = store.state(feed.id());
                store.setState(feed.id(), new FeedState("", "", s.refreshedEpochMs(), s.error(), s.articles()));
            }
        }
        notify(null);
        if (urlChanged) refresh(feed.id());
    }

    public void removeFeed(String feedId) throws IOException {
        synchronized (this) {
            List<Feed> list = new ArrayList<>(store.feeds());
            if (!list.removeIf(f -> f.id().equals(feedId))) return;
            store.setFeeds(list);
            refreshing.remove(feedId);
        }
        notify(null);
    }

    // ---- read state -----------------------------------------------------------------------------

    /** Marks one article, by {@link Article#identity()}; nothing happens when it already is. */
    public void markRead(String feedId, String identity, boolean read) {
        boolean changed = false;
        synchronized (this) {
            FeedState s = store.state(feedId);
            List<Article> list = new ArrayList<>(s.articles());
            for (int i = 0; i < list.size(); i++) {
                Article a = list.get(i);
                if (a.identity().equals(identity) && a.read() != read) {
                    list.set(i, a.withRead(read));
                    changed = true;
                }
            }
            if (changed) save(feedId, s.withArticles(list));
        }
        if (changed) notify(feedId);
    }

    /** Marks every article of the feed read. */
    public void markFeedRead(String feedId) {
        boolean changed = false;
        synchronized (this) {
            FeedState s = store.state(feedId);
            if (s.unread() > 0) {
                save(
                        feedId,
                        s.withArticles(
                                s.articles().stream().map(a -> a.withRead(true)).toList()));
                changed = true;
            }
        }
        if (changed) notify(feedId);
    }

    public void markAllRead() {
        for (Feed f : store.feeds()) markFeedRead(f.id());
    }

    /** Marks one article downloaded, and read: its torrent has been added. Any thread. */
    public void markDownloaded(String feedId, String identity) {
        boolean changed = false;
        synchronized (this) {
            FeedState s = store.state(feedId);
            List<Article> list = new ArrayList<>(s.articles());
            for (int i = 0; i < list.size(); i++) {
                Article a = list.get(i);
                if (a.identity().equals(identity) && !(a.downloaded() && a.read())) {
                    list.set(i, a.withDownloaded(true).withRead(true));
                    changed = true;
                }
            }
            if (changed) save(feedId, s.withArticles(list));
        }
        if (changed) notify(feedId);
    }

    private synchronized boolean isDownloaded(Article article) {
        for (Article a : store.state(article.feedId()).articles()) {
            if (a.identity().equals(article.identity())) return a.downloaded();
        }
        return false;
    }

    // ---- rules ----------------------------------------------------------------------------------

    public List<Rule> rules() {
        return store.rules();
    }

    public Rule rule(String name) {
        for (Rule r : store.rules()) if (r.name().equals(name)) return r;
        return null;
    }

    /** Replaces the rules; listeners hear a list change. */
    public void setRules(List<Rule> list) throws IOException {
        synchronized (rulesLock) {
            store.setRules(list);
        }
        notify(null);
    }

    /**
     * Adds the rule, or replaces the one named {@code replacing} (a rename keeps its place); a
     * rule of the new name that is not the one replaced is refused.
     *
     * @throws IllegalArgumentException when another rule has the name already
     */
    public void putRule(String replacing, Rule rule) throws IOException {
        synchronized (rulesLock) {
            List<Rule> list = new ArrayList<>(store.rules());
            int at = -1;
            for (int i = 0; i < list.size(); i++) {
                String name = list.get(i).name();
                if (name.equals(rule.name()) && !name.equals(replacing))
                    throw new IllegalArgumentException("A rule named \"" + rule.name() + "\" exists already");
                if (replacing != null && name.equals(replacing)) at = i;
            }
            if (at < 0) list.add(rule);
            else list.set(at, rule);
            store.setRules(list);
        }
        notify(null);
    }

    public void removeRule(String name) throws IOException {
        synchronized (rulesLock) {
            List<Rule> list = new ArrayList<>(store.rules());
            if (!list.removeIf(r -> r.name().equals(name))) return;
            store.setRules(list);
        }
        notify(null);
    }

    /**
     * Runs every enabled rule over every article unread and not yet downloaded, on the fetch
     * executor, whether or not the switch is on: the menu's command. The reporter hears the
     * outcome in a line.
     */
    public void runRulesNow() {
        fetchExecutor.execute(() -> {
            List<Article> candidates = new ArrayList<>();
            for (Article a : allArticles()) if (!a.read() && !a.downloaded() && a.hasTorrent()) candidates.add(a);
            int added = runRules(candidates);
            reporter.report(
                    store.rules().isEmpty()
                            ? "No RSS rules yet: Settings → RSS adds one"
                            : added == 0
                                    ? "RSS rules: nothing new to add among " + candidates.size() + " unread articles"
                                    : "RSS rules added " + (added == 1 ? "1 torrent" : added + " torrents"),
                    false);
        });
    }

    /**
     * The fetch thread. The rules over the candidates, one download at a time under the rules
     * lock, so two feeds finishing together cannot take the same episode twice; returns how many
     * went through.
     */
    private int runRules(List<Article> candidates) {
        Downloader d = downloader;
        if (d == null || candidates.isEmpty()) return 0;
        synchronized (rulesLock) {
            List<Rule> rules = store.rules();
            if (rules.isEmpty()) return 0;
            Instant now = Instant.now(clock);
            List<RuleEngine.Download> wanted = RuleEngine.apply(rules, candidates, now);
            List<RuleEngine.Download> done = new ArrayList<>();
            Map<String, String> names = namesById();
            for (RuleEngine.Download w : wanted) {
                Article a = w.article();
                if (isDownloaded(a)) continue; // by hand, meanwhile
                String feedName = names.getOrDefault(a.feedId(), "");
                try {
                    d.download(w.rule(), a);
                } catch (IOException | RuntimeException e) {
                    String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                    reporter.report(RuleEngine.describeFailure(w.rule(), a, feedName, reason), true);
                    continue;
                }
                markDownloaded(a.feedId(), a.identity());
                done.add(w);
                reporter.report(RuleEngine.describe(w.rule(), a, feedName), false);
            }
            if (done.isEmpty()) return 0;
            try {
                store.setRules(RuleEngine.record(store.rules(), done, now));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not write the rules", e);
            }
            notify(null);
            return done.size();
        }
    }

    private void save(String feedId, FeedState state) {
        try {
            store.setState(feedId, state);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the articles of feed " + feedId, e);
        }
    }

    // ---- refreshing -----------------------------------------------------------------------------

    public void refreshAll() {
        startFetches(store.feeds());
    }

    public void refresh(String feedId) {
        Feed f = store.feed(feedId);
        if (f != null) startFetches(List.of(f));
    }

    /** The minute check: every feed whose interval has passed; returns the ids started. */
    public List<String> refreshDue(Instant now) {
        List<Feed> due = new ArrayList<>();
        int global = globalMinutes.getAsInt();
        for (Feed f : store.feeds()) {
            if (due(store.state(f.id()), f, global, now)) due.add(f);
        }
        return startFetches(due);
    }

    /** Whether the feed is due: never fetched, or its interval (own, else global) has passed since. */
    public static boolean due(FeedState state, Feed feed, int globalMinutes, Instant now) {
        if (state.refreshedEpochMs() <= 0) return true;
        long interval = feed.effectiveMinutes(globalMinutes) * 60_000L;
        return now.toEpochMilli() - state.refreshedEpochMs() >= interval;
    }

    private List<String> startFetches(List<Feed> feeds) {
        List<Feed> started = new ArrayList<>();
        synchronized (this) {
            for (Feed f : feeds) {
                if (refreshing.add(f.id())) started.add(f);
            }
        }
        for (Feed f : started) {
            notify(f.id());
            fetchExecutor.execute(() -> fetch(f));
        }
        return started.stream().map(Feed::id).toList();
    }

    /** On the fetch executor: the network, then the merge under the lock. */
    private void fetch(Feed feed) {
        FeedState before = store.state(feed.id());
        FeedSource.Result result = null;
        String error = null;
        try {
            result = source.fetch(feed, before.etag(), before.lastModified());
        } catch (FeedException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = "failed: " + e;
        }
        finish(feed, result, error);
    }

    private void finish(Feed fetched, FeedSource.Result result, String error) {
        boolean renamed = false;
        List<Article> fresh = List.of();
        synchronized (this) {
            refreshing.remove(fetched.id());
            Feed feed = store.feed(fetched.id());
            if (feed == null) return; // removed while the fetch ran
            FeedState before = store.state(feed.id());
            long now = clock.millis();
            FeedState after;
            if (error != null) {
                after = before.failed(now, error);
            } else if (result.changed()) {
                List<Article> merged = merge(
                        before.articles(),
                        result.articles().stream()
                                .map(a -> a.withFeedId(feed.id()))
                                .toList());
                after = before.answered(result.etag(), result.lastModified(), now, merged);
                fresh = newOnes(before.articles(), merged);
            } else {
                after = before.answered(before.etag(), before.lastModified(), now, before.articles());
            }
            save(feed.id(), after);
            if (result != null
                    && result.changed()
                    && feed.name().isEmpty()
                    && !result.title().isEmpty()) {
                List<Feed> list = new ArrayList<>(store.feeds());
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id().equals(feed.id()))
                        list.set(i, list.get(i).withName(result.title()));
                }
                try {
                    store.setFeeds(list);
                    renamed = true;
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not write the feed list", e);
                }
            }
        }
        if (!fresh.isEmpty() && autoDownload.getAsBoolean()) runRules(fresh);
        if (renamed) notify(null);
        notify(fetched.id());
    }

    /** The articles of {@code merged} that {@code existing} did not have, by identity, in merged order. */
    static List<Article> newOnes(List<Article> existing, List<Article> merged) {
        Set<String> had = new HashSet<>();
        for (Article a : existing) had.add(a.identity());
        List<Article> out = new ArrayList<>();
        for (Article a : merged) if (!had.contains(a.identity())) out.add(a);
        return out;
    }

    /** Newest first; an article without a date sorts as newest, so a dateless feed keeps its order. */
    static final Comparator<Article> BY_DATE =
            Comparator.comparing(Article::published, Comparator.nullsFirst(Comparator.reverseOrder()));

    /**
     * The fetched items over the kept ones: an item already kept takes the fetched copy's fields
     * and keeps its read flag; a kept item the feed no longer lists stays (that is what the cap
     * is for); the result is newest first, and among equal dates the feed's order, then the
     * kept order.
     */
    public static List<Article> merge(List<Article> existing, List<Article> incoming) {
        Map<String, Article> kept = new LinkedHashMap<>();
        for (Article a : existing) kept.putIfAbsent(a.identity(), a);
        List<Article> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Article a : incoming) {
            if (!seen.add(a.identity())) continue;
            Article was = kept.get(a.identity());
            out.add(was == null ? a : a.withFlagsOf(was));
        }
        for (Article a : existing) {
            if (seen.add(a.identity())) out.add(a);
        }
        out.sort(BY_DATE);
        return out;
    }

    private void notify(String feedId) {
        if (listeners.isEmpty()) return;
        notifyExecutor.execute(() -> {
            for (Listener l : listeners) l.changed(feedId);
        });
    }
}
