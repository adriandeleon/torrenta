package com.torrenta.automation;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;

import static com.torrenta.i18n.Messages.tr;

/**
 * Runs the person's commands and shows notifications when a torrent is added, finishes or is
 * done seeding, and notifies on errors. Owned by the window, fed every engine event on whatever
 * thread raises it; everything that takes time runs on a virtual thread of its own, and the
 * outcome goes back to the host as events for the log.
 *
 * <p>{@link #plan} is the rule, pure and tested; the rest is plumbing.
 */
public final class Automation {

    /** A command still running after this is killed and said so in the log. */
    public static final Duration COMMAND_CEILING = Duration.ofMinutes(10);

    /** How much of a command's output is kept, and how many lines of it reach the log. */
    static final int KEEP_CHARS = 4000;

    static final int LOG_LINES = 3;

    /** A command's output lines are logged indented by this; a status bar can leave them out. */
    public static final String OUTPUT_PREFIX = "  ";

    static final String TITLE = Notifiers.APP_NAME;

    /** Where the outcomes go; called on the automation's own threads, so an FX host hops. */
    public interface Host {
        void report(EngineEvent event);
    }

    /**
     * What one event calls for: the command's tokens before substitution (empty for none) and the
     * notification's text (empty for none).
     */
    public record Plan(List<String> command, Optional<String> notification) {
        public boolean isEmpty() {
            return command.isEmpty() && notification.isEmpty();
        }
    }

    private final Engine engine;
    private final Supplier<AutomationSettings> settings;
    private final Notifier notifier;
    private final Host host;
    private final AtomicBoolean notifierReported = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** The last error notified per torrent: a tracker that cannot be reached says so on every retry. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> lastErrors =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Automation(Engine engine, Supplier<AutomationSettings> settings, Notifier notifier, Host host) {
        this.engine = engine;
        this.settings = settings;
        this.notifier = notifier;
        this.host = host;
    }

    /** What an event calls for under these settings. */
    public static Plan plan(EngineEvent event, AutomationSettings settings) {
        EngineEvent.Kind kind = event.kind();
        List<String> command = EventCommand.tokens(settings.command(kind));
        Optional<String> notification =
                settings.notifies(kind) ? Optional.of(notificationText(event)) : Optional.empty();
        return new Plan(command, notification);
    }

    /** {@code <name> finished}, {@code <name> added}, {@code <name> finished seeding}, or the error. */
    public static String notificationText(EngineEvent event) {
        String name =
                event.torrent() == null || event.torrent().isBlank() ? tr("automation.aTorrent") : event.torrent();
        return switch (event.kind()) {
            case ADDED -> tr("automation.notify.added", name);
            case FINISHED -> tr("automation.notify.finished", name);
            case SEEDING_DONE -> tr("automation.notify.seedingDone", name);
            case ERROR, OTHER ->
                event.message().startsWith(name)
                        ? event.message()
                        : tr("automation.notify.error", name, event.message());
        };
    }

    /** "On finished", for the log. */
    static String label(EngineEvent.Kind kind) {
        return switch (kind) {
            case ADDED -> tr("settings.automation.onAdded");
            case FINISHED -> tr("settings.automation.onFinished");
            case SEEDING_DONE -> tr("settings.automation.onSeedingDone");
            case ERROR -> tr("automation.label.error");
            case OTHER -> tr("automation.label.other");
        };
    }

    /** Any thread; returns at once. The torrent is looked up by the event's id. */
    public void handle(EngineEvent event) {
        handle(event, null);
    }

    /**
     * Any thread; returns at once. {@code known} is the torrent as the caller last saw it, for an
     * event raised after the torrent left the session (the share limit removed it); null to look
     * it up.
     */
    public void handle(EngineEvent event, Torrent known) {
        if (event == null || event.kind() == EngineEvent.Kind.OTHER) return;
        Plan planned = plan(event, settings.get());
        Plan plan =
                event.kind() == EngineEvent.Kind.ERROR && planned.notification().isPresent() && repeated(event)
                        ? new Plan(planned.command(), Optional.empty())
                        : planned;
        if (plan.isEmpty()) return;
        inFlight.incrementAndGet();
        Thread.ofVirtual().name("automation").start(() -> {
            try {
                plan.notification().ifPresent(this::notify);
                if (!plan.command().isEmpty()) runCommand(event, plan.command(), known);
            } catch (RuntimeException e) {
                host.report(EngineEvent.error(
                        event.torrentId(), event.torrent(), tr("automation.failed", label(event.kind()), e)));
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /** Whether this torrent's last notified error was this same one; remembers it either way. */
    private boolean repeated(EngineEvent event) {
        String key = event.torrentId() != null ? event.torrentId() : String.valueOf(event.torrent());
        String previous = lastErrors.put(key, event.message());
        return event.message().equals(previous);
    }

    /** The Settings page's button: shows one and reports how it went, every time. */
    public void testNotification() {
        inFlight.incrementAndGet();
        Thread.ofVirtual().name("automation-test").start(() -> {
            try {
                Optional<String> problem = notifier.show(TITLE, tr("settings.automation.testNotification"));
                host.report(
                        problem.isEmpty()
                                ? EngineEvent.session(
                                        EngineEvent.Level.INFO, tr("automation.testShown", notifier.name()))
                                : EngineEvent.session(
                                        EngineEvent.Level.WARNING, tr("automation.testFailed", problem.get())));
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /** Tests: waits until nothing started here is still running. */
    public boolean awaitIdle(Duration atMost) throws InterruptedException {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(10);
        }
        return true;
    }

    private void notify(String text) {
        Optional<String> problem = notifier.show(TITLE, text);
        // Said once: a machine without notify-send would otherwise hear about it on every event.
        if (problem.isPresent() && notifierReported.compareAndSet(false, true)) {
            host.report(EngineEvent.session(EngineEvent.Level.WARNING, tr("automation.unavailable", problem.get())));
        }
    }

    private void runCommand(EngineEvent event, List<String> tokens, Torrent known) {
        Torrent torrent = known != null ? known : find(event.torrentId());
        TorrentDetail detail = null;
        if (engine != null && torrent != null && event.torrentId() != null) {
            try {
                detail = engine.detail(event.torrentId());
            } catch (EngineException | RuntimeException e) {
                detail = null; // gone, or a magnet without metadata: the subject does without
            }
        }
        if (torrent == null) torrent = placeholder(event);
        List<String> argv = EventCommand.substitute(tokens, EventCommand.subject(torrent, detail));
        String what = tr("automation.command", label(event.kind()), torrent.name(), argv.get(0));
        Subprocess.Result result = Subprocess.run(argv, COMMAND_CEILING, KEEP_CHARS);
        if (!result.started()) {
            host.report(EngineEvent.error(
                    torrent.id(), torrent.name(), tr("automation.couldNotRun", what, result.failure())));
            return;
        }
        if (result.timedOut()) {
            host.report(EngineEvent.warning(
                    torrent.id(), torrent.name(), tr("automation.killed", what, COMMAND_CEILING.toMinutes())));
        } else {
            EngineEvent.Level level = result.code() == 0 ? EngineEvent.Level.INFO : EngineEvent.Level.WARNING;
            host.report(EngineEvent.of(
                    EngineEvent.Kind.OTHER,
                    level,
                    torrent.id(),
                    torrent.name(),
                    tr("automation.exited", what, String.valueOf(result.code()))));
        }
        for (String line : result.firstLines(LOG_LINES)) {
            host.report(EngineEvent.info(torrent.id(), torrent.name(), OUTPUT_PREFIX + line));
        }
    }

    private Torrent find(String id) {
        if (id == null) return null;
        for (Torrent t : engine.torrents()) if (t.id().equals(id)) return t;
        return null;
    }

    /** A torrent that is gone: the event's name and id are all that is left of it. */
    private static Torrent placeholder(EngineEvent event) {
        String id = event.torrentId() == null ? "" : event.torrentId();
        return new Torrent(
                id,
                event.torrent() == null ? id : event.torrent(),
                0,
                0,
                0,
                com.torrenta.engine.TorrentState.UNKNOWN,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                false,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false);
    }
}
