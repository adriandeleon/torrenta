package com.torrenta;

import java.io.IOException;
import java.nio.file.Path;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.engine.transmission.TransmissionEngine;
import com.torrenta.os.AwtUse;
import com.torrenta.os.SingleInstance;
import com.torrenta.store.ConfigDir;
import com.torrenta.store.ProfileStore;
import com.torrenta.store.Settings;
import com.torrenta.store.StatsStore;
import com.torrenta.store.TorrentStore;
import com.torrenta.ui.MainWindow;
import com.torrenta.ui.Theme;

/** Torrenta — a keyboard-first BitTorrent client on JavaFX. */
public final class App extends Application {

    /** How the launcher is invoked, for the usage text. */
    private static final String LAUNCH_NAME = "torrenta";

    /** The lock on the config folder, held for the life of the process; null when none could be taken. */
    private static SingleInstance instance;

    private MainWindow window;

    public static void main(String[] args) {
        // Parsed before anything else starts. --version is what somebody pastes into a bug report,
        // so it must not depend on a display, a config file, or the toolkit coming up at all.
        CommandLine cli = CommandLine.parse(args);
        if (cli.error() != null) {
            System.err.println(AppInfo.NAME + ": " + cli.error());
            System.err.println("Try '" + LAUNCH_NAME + " --help' for the options.");
            System.exit(2);
        }
        if (cli.help()) {
            System.out.println(CommandLine.usage(LAUNCH_NAME));
            // Not a bare return: launching an Application subclass in module mode starts the FX
            // toolkit, whose non-daemon thread would keep the process alive after main returns.
            System.exit(0);
        }
        if (cli.version()) {
            System.out.println(AppInfo.NAME + " " + AppInfo.VERSION);
            System.exit(0);
        }

        // One Torrenta per config folder: a second one hands its command line to the first and is
        // gone before the toolkit would have come up, with nothing printed. A file manager's
        // double-click and a browser's magnet link both arrive this way.
        Path configDir = ConfigDir.resolve(cli.configDir());
        try {
            SingleInstance.Outcome outcome =
                    SingleInstance.claim(configDir, cli.inputs(), Path.of("").toAbsolutePath());
            if (outcome instanceof SingleInstance.Outcome.HandedOff) System.exit(0);
            instance = ((SingleInstance.Outcome.Primary) outcome).instance();
        } catch (IOException e) {
            // A config folder that cannot be written is a bigger problem the window will say;
            // this one runs unlocked rather than not at all.
            System.err.println(AppInfo.NAME + ": no instance lock on " + configDir + ": " + e.getMessage());
        }

        // Before any AWT class can load: java.desktop is required for Java2D, and on macOS an
        // initialised AWT contends with JavaFX for the AppKit run loop, so AWT stays headless
        // except where something AWT is wanted: the tray icon on Windows and macOS, the dock
        // badge and the open-file handlers on macOS. Linux's tray and badge are D-Bus and need
        // no AWT. The settings are read here, once, which is why those apply on restart.
        Settings early = new Settings(configDir.resolve(Settings.FILE_NAME));
        early.load();
        boolean headful =
                AwtUse.headful(early.tray(), early.osBadge(), early.osHandler(), System.getProperty("os.name"));
        System.setProperty("java.awt.headless", String.valueOf(!headful));
        // Before the toolkit: a packaged app has no console, so whatever is logged or thrown from
        // here on is kept for the Debug Log window and the session file.
        com.torrenta.ui.DebugLog.install();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        CommandLine cli = CommandLine.parse(getParameters().getRaw().toArray(String[]::new));

        // Before any stylesheet: CSS naming a font that is not yet registered does not wait for it,
        // it silently resolves to the system face.
        com.torrenta.ui.Fonts.load();

        Path configDir = ConfigDir.resolve(cli.configDir());
        Settings settings = new Settings(configDir.resolve(Settings.FILE_NAME));
        settings.load();
        com.torrenta.ui.DebugLog.attachFile(configDir);

        // The catalogue before any text is built: the window, the dialogs and the palette read it as
        // they are made, so a change in Settings relabels on restart (Termina's rule). Only an
        // explicit choice moves the JVM's default locale, so JavaFX's own OK/Cancel and the number
        // formats follow; "System" keeps the OS locale whole, country and all.
        String language = com.torrenta.i18n.Messages.resolve(
                com.torrenta.i18n.Messages.isAutomatic(settings.uiLanguage()) ? null : settings.uiLanguage(),
                com.torrenta.i18n.Messages.available().keySet(),
                java.util.Locale.getDefault().getLanguage());
        com.torrenta.i18n.Messages.init(language);
        java.util.Locale.setDefault(com.torrenta.i18n.Messages.defaultLocale(
                settings.uiLanguage(), language, java.util.Locale.getDefault()));

        // Applied before any window is built, so the first frame is already themed.
        Application.setUserAgentStylesheet(
                Theme.byId(settings.theme(), Theme.DEFAULT).stylesheet());

        window = new MainWindow(
                stage, settings, configDir, uri -> getHostServices().showDocument(uri));
        window.show();
        cli.inputs().forEach(window::addInput);
        // What a second instance sends from here on, and what arrived while the window was being
        // built, goes through the same add path as a dropped file; the window hops to its thread.
        if (instance != null) {
            window.setInstanceDescription("primary, port " + instance.port());
            instance.deliverTo(inputs -> Platform.runLater(() -> window.openFromOutside(
                    inputs, com.torrenta.i18n.Messages.tr("app.origin.anotherInstance", AppInfo.NAME))));
        }

        // The unpacked engine lives beside the settings unless a -D says otherwise (the tests do).
        if (System.getProperty("torrenta.nativeDir") == null) {
            System.setProperty("torrenta.nativeDir", configDir.resolve("native").toString());
        }

        // The engine loads a native library, opens sockets and re-adds every stored torrent, or
        // reaches a daemon over the network: hundreds of milliseconds that the first frame should
        // not wait for. The window is up with "Starting the engine…" meanwhile. Which engine is the
        // profile's (Settings → Engine); a profile that is gone from the file falls back to the
        // local one, said in the status bar.
        ProfileStore profiles = new ProfileStore(configDir);
        profiles.load();
        EngineProfile profile = profiles.byId(settings.engineProfile()).orElse(EngineProfile.LOCAL);
        window.setEngineFactory(p -> createEngine(p, settings, configDir));
        window.startEngine(profile);

        if (DevCapture.requested()) DevCapture.schedule(stage, window);
    }

    /** libtorrent in this process with its stores, or a Transmission daemon reached as the profile says. */
    static Engine createEngine(EngineProfile profile, Settings settings, Path configDir) {
        if (profile.isLocal()) {
            return new LibtorrentEngine(
                    settings::downloadDir,
                    new TorrentStore(configDir.resolve(TorrentStore.FOLDER)),
                    settings.engineSettings(),
                    new StatsStore(configDir.resolve(StatsStore.FILE_NAME)));
        }
        return new TransmissionEngine(profile, settings::downloadDir, settings.engineSettings());
    }

    @Override
    public void stop() {
        if (window != null) {
            window.stopPolling();
            window.stopEngine();
        }
        if (window != null) {
            window.disposeTray();
            window.disposeBadge();
        }
        if (instance != null) instance.close();
        // Both tray backends leave non-daemon threads behind (the AWT event thread, D-Bus workers),
        // so a quit JVM would otherwise stay alive with no window. The engine has stopped and
        // written its last resume data by here; nothing else needs an orderly end.
        System.exit(0);
    }
}
