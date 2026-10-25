package com.torrenta.session;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.store.Settings;

import static com.torrenta.i18n.Messages.tr;

/**
 * The torrent session without a window (roadmap 1.2, item 1): the engine polled once a second on a
 * thread of its own, the share limits enforced on each poll, the speed schedule looked at every
 * minute. A window listens to the polls and draws; a headless run, later, will listen and log. No
 * JavaFX here, ever ({@code SessionTest} fails on an import of it), so every callback runs on the
 * poll thread or the caller's, and an owner that draws hops to its own toolkit thread.
 *
 * <p>The engine itself is still started and switched by its owner, which hands it over with {@link
 * #start} and takes it back with {@link #stop}; that moves here in a later step.
 */
public final class Session {

    /** How often the engine is polled. */
    public static final long POLL_MS = 1000;

    /** What the session tells its owner; any thread, so an owner that draws must hop. */
    public interface Host {

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
    }

    /** One poll: the engine it was taken from, its torrents and rates, and when. */
    public record Poll(Engine engine, List<Torrent> torrents, Engine.Rates rates, long nowSeconds) {}

    private static final Logger LOG = Logger.getLogger(Session.class.getName());

    private final Settings settings;
    private final Host host;
    private final List<Consumer<Poll>> listeners = new CopyOnWriteArrayList<>();
    private volatile Engine engine;
    private volatile ScheduledExecutorService poller;
    private volatile ShareLimit globalShareLimit = ShareLimit.NONE;
    private volatile Map<String, ShareLimit> shareOverrides = Map.of();

    public Session(Settings settings, Host host) {
        this.settings = settings;
        this.host = host;
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
}
