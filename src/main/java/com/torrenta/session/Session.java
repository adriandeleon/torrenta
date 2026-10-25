package com.torrenta.session;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.content.ContentCheck;
import com.torrenta.content.UnsafeContent;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.Priority;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.WatchFolders;
import com.torrenta.net.TorrentFetch;
import com.torrenta.rss.Article;
import com.torrenta.rss.FeedFetcher;
import com.torrenta.rss.RssService;
import com.torrenta.rss.RssStore;
import com.torrenta.rss.Rule;
import com.torrenta.store.LabelStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;

import static com.torrenta.i18n.Messages.tr;

/**
 * The torrent session without a window (roadmap 1.2, item 1): the engine polled once a second on a
 * thread of its own, the share limits enforced on each poll, the speed schedule looked at every
 * minute, the watch folders, the RSS feeds and their rules, and the IP filter. A window listens to
 * the polls and draws; a headless run, later, will listen and log. No JavaFX here, ever ({@code
 * SessionTest} fails on any of it), so every callback runs on a thread of the session's or the
 * caller's, and an owner that draws hops to its own toolkit thread.
 *
 * <p>The lifecycle: {@link #open} starts what needs no engine (the feeds); {@link #startEngine}
 * builds the profile's engine ({@link EngineFactory}), starts it on a thread of its own, and then
 * the poll, the IP filter and the watch folders ({@link #start}); {@link #switchTo} replaces it
 * with another profile's; {@link #stopEngine} ends it, and {@link #close} the feeds too. An add
 * without a dialog, a watch folder's, an RSS rule's or the owner's own, goes through {@link #add}:
 * the content check ({@code add.unsafeContent}) before or, for a magnet, once its files are known.
 */
public final class Session {

    /** How often the engine is polled. */
    public static final long POLL_MS = 1000;

    /** Builds the engine for a profile: the application's, since the stores and settings are its to hand over. */
    public interface EngineFactory {
        Engine create(EngineProfile profile);
    }

    /** What the session tells its owner; any thread, so an owner that draws must hop. */
    public interface Host {

        /** An engine was built and is about to start: the moment to listen to its events, from the first. */
        default void engineCreated(Engine engine) {}

        /**
         * The engine started, and the session polls it, applies the IP filter and watches the
         * folders. The engine-start thread; a poll may be heard before this is.
         */
        default void engineReady(Engine engine) {}

        /** The engine could not be built or started; the session has none. The engine-start thread. */
        default void engineFailed(EngineException failure) {}

        /**
         * A finished torrent reached its share limit and was paused, removed or super seeded (or the
         * attempt failed, which the event says): a {@link EngineEvent.Kind#SEEDING_DONE} event and
         * the snapshot it was decided on, since a removed torrent can no longer be looked up.
         */
        void seedingDone(EngineEvent event, Torrent torrent, boolean removed);

        /** The speed schedule was looked at: whether the alternative pair is in force now. */
        void limitsRefreshed(boolean alternative);

        /** Something failed that a person should hear of. */
        void problem(EngineEvent event);

        /** A line for the log and the status line: a watch folder's add, a rule's, what the content check found. */
        default void report(EngineEvent event) {}

        /** The watch folders started, stopped, or found a folder gone: the Settings page's line. */
        default void watchStatusChanged() {}

        /** The IP filter, or the engine's own blocklist, said what it loaded or why not. */
        default void ipFilterReport(String text) {}
    }

    /** One poll: the engine it was taken from, its torrents and rates, and when. */
    public record Poll(Engine engine, List<Torrent> torrents, Engine.Rates rates, long nowSeconds) {}

    private static final Logger LOG = Logger.getLogger(Session.class.getName());

    private final Settings settings;
    private final LabelStore labels;
    private final WatchFolderStore watchStore;
    private final Host host;
    private final IpFilterController ipFilter;
    private final RssService rss;
    private volatile EngineFactory engineFactory;
    private volatile EngineProfile profile = EngineProfile.LOCAL;
    /** The engine started last and not yet stopped: what {@link #stopEngine} and {@link #switchTo} end. */
    private volatile Engine running;
    /**
     * Counts {@link #startEngine} calls: an engine that finishes starting after a newer start was
     * asked for (a switch while it was still connecting) is stopped, never polled beside the next.
     */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    /** Counted down at the first {@link #start}, and never again: a rule's add waits on it. */
    private final CountDownLatch engineUp = new CountDownLatch(1);

    private volatile WatchFolders watcher;
    /** What an engine that fetches its own blocklist (a daemon) said last. */
    private volatile String blocklistStatus = "";
    /** Torrents whose content-check mark a poll is handling; keeps the next tick off them meanwhile. */
    private final Set<String> screening = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** A content-check mark set and saved as one: marks come from the adds' threads and the poll's. */
    private final Object marks = new Object();

    private final List<Consumer<Poll>> listeners = new CopyOnWriteArrayList<>();
    private volatile Engine engine;
    private volatile ScheduledExecutorService poller;
    private volatile ShareLimit globalShareLimit = ShareLimit.NONE;
    private volatile Map<String, ShareLimit> shareOverrides = Map.of();

    /**
     * @param configDir where the IP filter keeps its cache
     * @param notify where the feeds' listeners are told of a change: a window's toolkit thread
     */
    public Session(
            Settings settings,
            Path configDir,
            LabelStore labels,
            WatchFolderStore watchStore,
            RssStore rssStore,
            Executor notify,
            Host host) {
        this.settings = settings;
        this.labels = labels;
        this.watchStore = watchStore;
        this.host = host;
        this.ipFilter = new IpFilterController(settings, configDir);
        this.rss = new RssService(
                rssStore,
                new FeedFetcher(),
                settings::rssRefreshMinutes,
                r -> Thread.ofVirtual().name("rss-fetch").start(r),
                notify);
        rss.setDownloader(
                this::downloadForRule,
                settings::rssAutoDownload,
                (message, failed) -> host.report(
                        EngineEvent.session(failed ? EngineEvent.Level.WARNING : EngineEvent.Level.INFO, message)));
    }

    /** The feeds, their articles and rules: the window's RSS tab reads and changes them. */
    public RssService rss() {
        return rss;
    }

    /** The IP filter, for its status line. */
    public IpFilterController ipFilter() {
        return ipFilter;
    }

    /** What an engine that fetches its own blocklist said last; empty before it said anything. */
    public String blocklistStatus() {
        return blocklistStatus;
    }

    /** The watch folders while an engine is up; null otherwise. */
    public WatchFolders watcher() {
        return watcher;
    }

    public void setEngineFactory(EngineFactory factory) {
        this.engineFactory = factory;
    }

    /** The profile the engine is, or is being, started from; the local engine's at first. */
    public EngineProfile profile() {
        return profile;
    }

    /**
     * Any thread. Builds the engine for {@code next} and starts it on an {@code engine-start}
     * thread (loading a native library or reaching a daemon takes longer than a caller should
     * wait), then polls it ({@link #start}); the host hears {@link Host#engineReady} or {@link
     * Host#engineFailed}. The host hears {@link Host#engineCreated} first, on this thread.
     */
    public void startEngine(EngineProfile next) {
        EngineFactory factory = engineFactory;
        if (factory == null) throw new IllegalStateException("no engine factory");
        profile = next;
        long mine = generation.incrementAndGet();
        Engine fresh;
        try {
            fresh = factory.create(next);
        } catch (RuntimeException e) {
            host.engineFailed(new EngineException(e.getMessage() == null ? e.toString() : e.getMessage(), e));
            return;
        }
        host.engineCreated(fresh);
        Thread starter = new Thread(
                () -> {
                    try {
                        fresh.start();
                    } catch (EngineException e) {
                        // A start overtaken by a newer one says nothing: the newer one will.
                        if (generation.get() == mine) host.engineFailed(e);
                        return;
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "The engine failed to start", e);
                        if (generation.get() == mine)
                            host.engineFailed(
                                    new EngineException(e.getMessage() == null ? e.toString() : e.getMessage(), e));
                        return;
                    }
                    synchronized (this) {
                        if (generation.get() != mine) {
                            fresh.stop();
                            return;
                        }
                        running = fresh;
                        start(fresh);
                    }
                    host.engineReady(fresh);
                },
                "engine-start");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Any thread. Another profile's engine in place of the one running: the poll and the watch
     * folders stop first, the old engine stops on a thread of its own, the next one starts as
     * {@link #startEngine} starts it.
     */
    public void switchTo(EngineProfile next) {
        Engine old = running;
        running = null;
        stop();
        if (old != null) Thread.ofVirtual().name("engine-stop").start(old::stop);
        startEngine(next);
    }

    /** Any thread, at quit: the poll and the watch folders, then the engine itself, on this thread. */
    public void stopEngine() {
        generation.incrementAndGet();
        Engine e = running;
        running = null;
        stop();
        if (e != null) e.stop();
    }

    /** Starts what needs no engine: the feeds, which fetch on their schedule from now. */
    public void open() {
        rss.start();
    }

    /** Ends everything: the poll, the watch folders, the feeds. Any thread, idempotent. */
    public void close() {
        stop();
        rss.stop();
    }

    /** Hears every poll, on the poll thread, after the share limits have been applied. */
    public void addListener(Consumer<Poll> listener) {
        listeners.add(listener);
    }

    /** The engine being polled; null before {@link #start} and after {@link #stop}. */
    public Engine engine() {
        return engine;
    }

    /**
     * Begins polling {@code engine}: once a second, and the speed schedule once a minute, the
     * first of each at once. A session already polling another engine is stopped first.
     */
    public synchronized void start(Engine engine) {
        stop();
        this.engine = engine;
        globalShareLimit = settings.shareLimit();
        ScheduledExecutorService p = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-poll");
            t.setDaemon(true);
            return t;
        });
        poller = p;
        p.scheduleAtFixedRate(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
        p.scheduleAtFixedRate(this::refreshLimits, 0, 60, TimeUnit.SECONDS);
        engineUp.countDown();
        startIpFilter(engine);
        watcher = new WatchFolders(engine, new Watching());
        restartWatching();
    }

    /**
     * Any thread, idempotent. Ends the polling before the engine stops: a poll of a stopped engine
     * sees no torrents, which a window would take for the torrents having gone. A poll in flight
     * finishes first, bounded to three seconds.
     */
    public synchronized void stop() {
        ScheduledExecutorService p = poller;
        poller = null;
        engine = null;
        WatchFolders w = watcher;
        watcher = null;
        // stop() waits for a folder's poll in progress, which may be inside the engine.
        if (w != null) Thread.ofVirtual().name("watch-folders-stop").start(w::stop);
        if (p == null) return;
        p.shutdownNow();
        try {
            p.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs {@code task} on the poll thread every {@code period}, never overlapping a poll; false once stopped. */
    public boolean schedule(Runnable task, long initialDelay, long period, TimeUnit unit) {
        ScheduledExecutorService p = poller;
        if (p == null) return false;
        try {
            p.scheduleAtFixedRate(task, initialDelay, period, unit);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Runs {@code task} once on the poll thread, after the poll in progress if any; false once stopped. */
    public boolean execute(Runnable task) {
        ScheduledExecutorService p = poller;
        if (p == null) return false;
        try {
            p.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** The engine settings changed: the global share limit is read again, the speeds at once. */
    public void settingsChanged(EngineSettings current) {
        globalShareLimit = current.queue().share();
    }

    /** The share limit in force for a torrent: its own, else the global one. */
    public ShareLimit shareLimitFor(Torrent t) {
        ShareLimit own = shareOverrides.get(t.id());
        return own != null ? own : globalShareLimit;
    }

    /**
     * Any thread (the poll's, once a minute, and after a settings change): the schedule decides
     * which pair of speed limits is in force, and the owner hears which.
     */
    public void refreshLimits() {
        Engine e = engine;
        if (e == null) return;
        try {
            host.limitsRefreshed(e.refreshLimits(LocalDateTime.now()));
        } catch (EngineException ex) {
            host.problem(EngineEvent.session(EngineEvent.Level.ERROR, ex.getMessage()));
        }
    }

    /**
     * Poll thread. One snapshot, the share limits applied to it, then every listener. A task of a
     * {@code scheduleAtFixedRate} that throws is never run again, so nothing may escape: a failing
     * listener is logged and the next one, and the next poll, still run.
     */
    private void poll() {
        Engine e = engine;
        if (e == null) return;
        List<Torrent> torrents;
        Engine.Rates rates;
        try {
            torrents = e.torrents();
            rates = e.rates();
            enforceShareLimits(e, torrents);
            screenPending(e, torrents);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Poll failed", ex);
            return;
        }
        Poll poll = new Poll(e, torrents, rates, System.currentTimeMillis() / 1000);
        for (Consumer<Poll> listener : listeners) {
            try {
                listener.accept(poll);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "A poll listener failed", ex);
            }
        }
    }

    /**
     * Poll thread. libtorrent ranks seeds by the share limits but pauses nothing by them, so the
     * rule is applied here, once a second: a finished torrent past its own or the global limit is
     * paused, removed (with its files or without) or super seeded, and the owner hears of it. A
     * paused one no longer qualifies, so it is done once; resuming it by hand seeds it until the
     * next tick, raising the limit seeds it for good.
     */
    private void enforceShareLimits(Engine engine, List<Torrent> torrents) {
        ShareLimit global = globalShareLimit;
        Map<String, ShareLimit> overrides = settings.shareOverrides();
        shareOverrides = overrides;
        if (!global.limited() && overrides.isEmpty()) return;
        for (Torrent t : torrents) {
            Optional<ShareLimit.Action> due = ShareLimit.decide(t, global, overrides.get(t.id()));
            if (due.isEmpty()) continue;
            // A synced setting can ask a daemon for super seeding, which it lacks; failing that
            // every second would fill the log, so the limit does nothing there.
            if (due.get() == ShareLimit.Action.SUPER_SEED
                    && engine.capabilities().lacks(Engine.Capability.SUPER_SEEDING)) continue;
            String text;
            try {
                text = switch (due.get()) {
                    case PAUSE -> {
                        engine.pause(t.id());
                        yield tr("log.shareLimit.paused", t.name());
                    }
                    case REMOVE -> {
                        engine.remove(t.id(), false);
                        yield tr("log.shareLimit.removed", t.name());
                    }
                    case REMOVE_DATA -> {
                        engine.remove(t.id(), true);
                        yield tr("log.shareLimit.removedData", t.name());
                    }
                    case SUPER_SEED -> {
                        engine.setSuperSeeding(t.id(), true);
                        yield tr("log.shareLimit.superSeeding", t.name());
                    }
                };
            } catch (EngineException ex) {
                text = tr("log.shareLimit.failed", t.name(), ex.getMessage());
            }
            boolean removed = due.get() == ShareLimit.Action.REMOVE || due.get() == ShareLimit.Action.REMOVE_DATA;
            host.seedingDone(
                    EngineEvent.of(EngineEvent.Kind.SEEDING_DONE, EngineEvent.Level.INFO, t.id(), t.name(), text),
                    t,
                    removed);
        }
    }

    // ---- watch folders --------------------------------------------------------------------------

    /** Any thread. (Re)starts the watch folders on the stored list; nothing until an engine is up. */
    public void restartWatching() {
        WatchFolders w = watcher;
        if (w == null) return;
        List<com.torrenta.engine.WatchFolder> entries = watchStore.entries();
        // start() waits for a poll in progress, which may be inside the engine: never on the caller's thread.
        Thread.ofVirtual().name("watch-folders-start").start(() -> {
            w.start(entries);
            host.watchStatusChanged();
        });
    }

    /** What the watch folders need: the settings, the categories, the owner's screened add. */
    private final class Watching implements WatchFolders.Host {
        @Override
        public Path defaultDownloadDir() {
            return settings.downloadDir();
        }

        @Override
        public Optional<Category> category(String name) {
            return labels.category(name);
        }

        @Override
        public AddRequest.Storage storage(Path destination) {
            return settings.storage(destination);
        }

        @Override
        public void report(EngineEvent event) {
            host.report(event);
        }

        @Override
        public void statusChanged() {
            host.watchStatusChanged();
        }

        @Override
        public Torrent add(Engine engine, AddRequest request) throws EngineException {
            return Session.this.add(engine, request);
        }
    }

    // ---- RSS rules ------------------------------------------------------------------------------

    /**
     * The fetch thread: a rule matched an article. The feeds due at start are fetched before the
     * engine is up, so the add waits for it, bounded; a failure is the rule's to report.
     */
    private void downloadForRule(Rule rule, Article article) throws IOException {
        try {
            engineUp.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        Engine e = engine;
        if (e == null) throw new IOException(tr("status.engineNotRunning"));
        AddRequest.Source source = RuleAdd.sourceOf(article, new TorrentFetch()::fetch);
        Optional<Path> categoryFolder = labels.category(rule.category()).flatMap(Category::path);
        try {
            add(e, RuleAdd.request(rule, source, addDefaults(), categoryFolder));
        } catch (EngineException ex) {
            throw new IOException(ex.getMessage() == null ? ex.toString() : ex.getMessage(), ex);
        }
    }

    // ---- IP filter ------------------------------------------------------------------------------

    /**
     * At start: the filter from disk first, so no peer slips in while a download runs, then the
     * fetch if due, then every hour. An engine that fetches its own blocklist (a daemon) is told
     * the URL instead, and only when a blocklist is wanted.
     */
    private void startIpFilter(Engine engine) {
        if (engine.capabilities().has(Engine.Capability.IP_FILTER)) {
            ipFilter.setEngine(engine);
            Thread.ofVirtual().name("ip-filter").start(() -> {
                ipFilter.applyStored(host::ipFilterReport);
                ipFilter.refreshIfDue(host::ipFilterReport);
            });
            schedule(() -> ipFilter.refreshIfDue(host::ipFilterReport), 1, 1, TimeUnit.HOURS);
        } else if (engine.capabilities().has(Engine.Capability.BLOCKLIST_URL) && settings.ipFilterEnabled()) {
            refreshBlocklist();
        }
    }

    /** Any thread: the IP filter's settings or bans changed; {@code force} fetches even when not due. */
    public void refreshIpFilter(boolean force) {
        Engine e = engine;
        if (e == null) return;
        if (e.capabilities().has(Engine.Capability.IP_FILTER)) {
            Thread.ofVirtual().name("ip-filter").start(() -> ipFilter.refresh(force, host::ipFilterReport));
        } else if (e.capabilities().has(Engine.Capability.BLOCKLIST_URL)) {
            refreshBlocklist();
        }
    }

    /**
     * Any thread. An engine that fetches its own blocklist is told the URL and the switch from the
     * settings, and asked to fetch now; what it says is the owner's to show.
     */
    private void refreshBlocklist() {
        Engine e = engine;
        if (e == null) return;
        String url = settings.ipFilterUrl();
        boolean on = settings.ipFilterEnabled();
        Thread.ofVirtual().name("blocklist").start(() -> {
            String text;
            try {
                long ranges = e.setBlocklist(url, on);
                text = !on || url.isBlank()
                        ? tr("status.blocklist.off")
                        : tr("status.blocklist.applied", ranges < 0 ? 0 : ranges, ranges < 0 ? 0 : 1);
            } catch (EngineException ex) {
                text = tr("status.blocklist.failed", ex.getMessage());
            }
            blocklistStatus = text;
            host.ipFilterReport(text);
        });
    }

    // ---- adds without a dialog ------------------------------------------------------------------

    /**
     * What an add without a dialog starts from: the settings' defaults, with a daemon's own
     * download folder when the files are not on this machine.
     */
    public AddDefaults addDefaults() {
        AddDefaults d = AddDefaults.from(settings);
        Engine e = engine;
        if (e != null && e.capabilities().lacks(Engine.Capability.LOCAL_FILES)) {
            String remote = e.downloadDir();
            if (!remote.isEmpty()) return d.withSavePath(Path.of(remote));
        }
        return d;
    }

    /**
     * Any thread but a toolkit's. An add without a dialog, through the content check ({@link
     * UnsafeContent}, {@code add.unsafeContent}): a {@code .torrent} is checked before it is
     * added, its flagged files skipped or the add refused; a magnet has no files yet, so it is
     * added to stop once its metadata is in and marked for the poll to check ({@link
     * #screenPending}). What was found is the host's to log; a refusal is thrown, so each path
     * reports it as it reports a failed add. A torrent already in the list is left as it is: the
     * engine hands it back, and its files are not this add's to change.
     */
    public Torrent add(Engine e, AddRequest request) throws EngineException {
        UnsafeContent mode = UnsafeContent.byName(settings.addUnsafeContent());
        Set<String> before = new HashSet<>();
        for (Torrent t : e.torrents()) before.add(t.id());
        if (request.source() instanceof AddRequest.TorrentFile file) {
            TorrentPreview preview = e.inspect(file.bytes());
            if (before.contains(preview.id())) return e.add(request);
            ContentCheck check = UnsafeContent.check(preview, request);
            UnsafeContent.Decision decision = UnsafeContent.decide(mode, check, UnsafeContent.wanted(preview, request));
            if (decision.refused()) throw new UnsafeContent.Refused(preview.name(), decision, check);
            Torrent added = e.add(
                    decision == UnsafeContent.Decision.SKIP
                            ? UnsafeContent.withSkipped(request, check.indices())
                            : request);
            String line = UnsafeContent.logLine(decision, added.name(), check, false);
            if (!line.isEmpty()) host.report(EngineEvent.warning(added.id(), added.name(), line));
            return added;
        }
        // A magnet: stopped once its files are known, unless a person chose otherwise or the
        // engine cannot stop on metadata (a daemon); then it is checked while it runs.
        boolean stop = mode != UnsafeContent.ADD
                && request.stopCondition() == AddRequest.StopCondition.NONE
                && e.capabilities().has(Engine.Capability.STOP_CONDITION);
        Torrent added = e.add(stop ? request.withStopCondition(AddRequest.StopCondition.METADATA_RECEIVED) : request);
        if (before.contains(added.id())) return added;
        mark(added.id(), new UnsafeContent.Pending(mode, stop && !request.startPaused()).format());
        return added;
    }

    /**
     * Poll thread. A magnet added without a dialog waits, stopped, for its file list; once it has
     * one, the content check runs under the setting it was added with: started as it is (a
     * warning when something was found), started without the flagged files, or removed before
     * anything was downloaded. {@link #screening} keeps a torrent from being handled twice.
     */
    private void screenPending(Engine engine, List<Torrent> snapshot) {
        Map<String, String> pending = settings.unsafeContentPending();
        if (pending.isEmpty()) return;
        Map<String, Torrent> byId = new HashMap<>();
        for (Torrent t : snapshot) byId.put(t.id(), t);
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            String id = entry.getKey();
            Torrent t = byId.get(id);
            if (t == null) {
                // Marked after this tick's snapshot was taken, or removed since: the next tick knows.
                if (!screening.add(id)) continue;
                boolean gone;
                try {
                    gone = engine.torrents().stream().noneMatch(x -> x.id().equals(id));
                } catch (RuntimeException ex) {
                    gone = false;
                }
                if (gone) clearPending(id);
                else screening.remove(id);
                continue;
            }
            if (t.state() == TorrentState.FETCHING_METADATA || !screening.add(id)) continue;
            try {
                List<TorrentDetail.FileStatus> files = engine.detail(id).files();
                if (files.isEmpty()) {
                    screening.remove(id);
                    continue;
                }
                UnsafeContent.Pending p = UnsafeContent.Pending.parse(entry.getValue());
                ContentCheck check = ContentCheck.of(
                        UnsafeContent.previewOf(t, files),
                        i -> files.stream().anyMatch(f -> f.index() == i && f.priority() != Priority.IGNORE));
                int wanted = (int) files.stream()
                        .filter(f -> f.priority() != Priority.IGNORE)
                        .count();
                UnsafeContent.Decision decision = UnsafeContent.decide(p.mode(), check, wanted);
                if (decision.refused()) {
                    engine.remove(id, false);
                } else {
                    if (decision == UnsafeContent.Decision.SKIP)
                        for (int index : check.indices()) engine.setFilePriority(id, index, Priority.IGNORE);
                    if (p.resume()) engine.resume(id);
                }
                String line = UnsafeContent.logLine(decision, t.name(), check, true);
                if (!line.isEmpty()) host.report(EngineEvent.warning(id, t.name(), line));
            } catch (EngineException ex) {
                host.report(EngineEvent.warning(id, t.name(), ex.getMessage()));
            }
            clearPending(id);
        }
    }

    /** A torrent's content-check mark set, or with {@code null} cleared, and saved. */
    private void mark(String id, String value) {
        synchronized (marks) {
            settings.setUnsafeContentPending(id, value);
            settings.saveQuietly();
        }
    }

    /** A torrent's mark cleared and saved, and the guard let go after. */
    private void clearPending(String id) {
        mark(id, null);
        screening.remove(id);
    }
}
