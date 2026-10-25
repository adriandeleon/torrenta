package com.torrenta;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
import com.torrenta.os.SingleInstance;
import com.torrenta.rss.RssStore;
import com.torrenta.session.Session;
import com.torrenta.store.LabelStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;
import com.torrenta.ui.DebugLog;

import static com.torrenta.i18n.Messages.tr;

/**
 * {@code torrenta --headless} (roadmap 1.2, item 2): the {@link Session} without a window, for a
 * server. Everything that arrives is added without asking, through the content check
 * ({@code add.unsafeContent}); the engine's events and the session's lines go to stdout and to
 * the session log ({@link DebugLog}); SIGTERM, or Ctrl+C, stops the engine through its own stop,
 * which waits (bounded) for the last resume data. The toolkit never loads: nothing here touches a
 * JavaFX class, and {@code App.main} comes here before it would launch one.
 */
final class Headless {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Headless() {}

    /** Runs until the process is told to stop, or the engine cannot start; the exit status. */
    static int run(CommandLine cli, Path configDir, SingleInstance instance) {
        Settings settings = new Settings(configDir.resolve(Settings.FILE_NAME));
        settings.load();
        DebugLog.attachFile(configDir);
        Startup.language(settings);
        Startup.nativeDir(configDir);
        LabelStore labels = new LabelStore(configDir);
        labels.load();
        WatchFolderStore watchStore = new WatchFolderStore(configDir);
        watchStore.load();
        RssStore rssStore = new RssStore(configDir);
        rssStore.load();

        CountDownLatch failed = new CountDownLatch(1);
        Output out = new Output(System.out);
        Session session = new Session(
                settings, configDir, labels, watchStore, rssStore, Runnable::run, new Host(settings, out, failed));
        session.setEngineFactory(profile -> Startup.createEngine(profile, settings, configDir));
        session.setInputHandler(text -> Thread.ofVirtual().name("headless-add").start(() -> add(session, text, out)));
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            out.say(EngineEvent.Level.INFO, tr("headless.stopping"));
                            session.stopEngine();
                            session.close();
                            if (instance != null) instance.close();
                        },
                        "headless-stop"));

        out.say(EngineEvent.Level.INFO, tr("headless.starting", AppInfo.NAME, AppInfo.VERSION, configDir));
        session.open();
        cli.inputs().forEach(session::input);
        // Another Torrenta on this config folder (a browser's magnet link, a second command) hands
        // its inputs here, as it would to a window.
        if (instance != null) instance.deliverTo(inputs -> inputs.forEach(session::input));
        session.startEngine(Startup.profile(configDir, settings));
        try {
            failed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 1;
    }

    /**
     * A worker: one input added as "add without asking" adds it. The engine says the add itself;
     * only a failure is this one's to say.
     */
    private static void add(Session session, String text, Output out) {
        try {
            session.addWithoutAsking(text);
        } catch (EngineException | java.io.IOException | RuntimeException e) {
            out.say(EngineEvent.Level.WARNING, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** One line of output: the time, the level, the words. Pure. */
    static String line(LocalTime at, EngineEvent.Level level, String message) {
        return TIME.format(at) + " " + String.format(Locale.ROOT, "%-7s", level.name()) + " " + message;
    }

    /** Stdout and the session log, the same line in both. */
    private record Output(PrintStream stream) {
        void say(EngineEvent.Level level, String message) {
            String text = line(LocalTime.now(), level, message);
            stream.println(text);
            DebugLog.note(level.name() + " " + message);
        }
    }

    /** What the session tells a headless run: every line to the output, a failed start to the exit. */
    private static final class Host implements Session.Host {
        private final Settings settings;
        private final Output out;
        private final CountDownLatch failed;
        private final AtomicReference<Boolean> alternative = new AtomicReference<>();

        Host(Settings settings, Output out, CountDownLatch failed) {
            this.settings = settings;
            this.out = out;
            this.failed = failed;
        }

        @Override
        public void event(EngineEvent event) {
            out.say(event.level(), event.message());
        }

        @Override
        public void report(EngineEvent event) {
            out.say(event.level(), event.message());
        }

        @Override
        public void seedingDone(EngineEvent event, Torrent torrent, boolean removed) {
            out.say(event.level(), event.message());
            // A removed torrent takes its own share limit with it, as the window's does.
            if (removed && settings.shareOverride(torrent.id()).isPresent()) {
                settings.setShareOverride(torrent.id(), null);
                settings.saveQuietly();
            }
        }

        @Override
        public void limitsRefreshed(boolean on) {
            Boolean was = alternative.getAndSet(on);
            if (was != null && was != on)
                out.say(EngineEvent.Level.INFO, on ? tr("status.altSpeedLimitsOn") : tr("status.altSpeedLimitsOff"));
        }

        @Override
        public void problem(EngineEvent event) {
            out.say(event.level(), event.message());
        }

        @Override
        public void engineFailed(EngineException failure) {
            out.say(EngineEvent.Level.ERROR, failure.getMessage() == null ? failure.toString() : failure.getMessage());
            failed.countDown();
        }

        @Override
        public void ipFilterReport(String text) {
            out.say(EngineEvent.Level.INFO, text);
        }
    }
}
