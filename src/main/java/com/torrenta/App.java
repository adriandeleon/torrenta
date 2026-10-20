package com.torrenta;

import java.nio.file.Path;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import com.torrenta.cli.CommandLine;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.store.ConfigDir;
import com.torrenta.store.Settings;
import com.torrenta.store.TorrentStore;
import com.torrenta.ui.MainWindow;
import com.torrenta.ui.Theme;

/** Torrenta — a keyboard-first BitTorrent client on JavaFX. */
public final class App extends Application {

    /** How the launcher is invoked, for the usage text. */
    private static final String LAUNCH_NAME = "torrenta";

    private Engine engine;

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

        // First statement before any AWT class can load: java.desktop is required for ImageIO,
        // and on macOS an initialised AWT contends with JavaFX for the AppKit run loop.
        System.setProperty("java.awt.headless", "true");
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        CommandLine cli = CommandLine.parse(getParameters().getRaw().toArray(String[]::new));

        // Applied before any window is built, so the first frame is already themed.
        Application.setUserAgentStylesheet(Theme.EDITORA_DARK.stylesheet());

        Path configDir = ConfigDir.resolve(cli.configDir());
        Settings settings = new Settings(configDir.resolve(Settings.FILE_NAME));
        settings.load();

        MainWindow window =
                new MainWindow(stage, settings, uri -> getHostServices().showDocument(uri));
        window.show();
        cli.inputs().forEach(window::addInput);

        // The unpacked engine lives beside the settings unless a -D says otherwise (the tests do).
        if (System.getProperty("torrenta.nativeDir") == null) {
            System.setProperty("torrenta.nativeDir", configDir.resolve("native").toString());
        }

        // The engine loads a native library, opens sockets and re-adds every stored torrent:
        // hundreds of milliseconds that the first frame should not wait for. The window is up with
        // "Starting the engine…" meanwhile.
        engine = new LibtorrentEngine(settings.downloadDir(), new TorrentStore(configDir.resolve(TorrentStore.FOLDER)));
        window.listenTo(engine);
        Thread starter = new Thread(
                () -> {
                    try {
                        engine.start();
                        Platform.runLater(() -> window.engineReady(engine));
                    } catch (EngineException e) {
                        Platform.runLater(() -> window.engineFailed(e));
                    }
                },
                "engine-start");
        starter.setDaemon(true);
        starter.start();

        if (DevCapture.requested()) DevCapture.schedule(stage, window);
    }

    @Override
    public void stop() {
        if (engine != null) engine.stop();
    }
}
