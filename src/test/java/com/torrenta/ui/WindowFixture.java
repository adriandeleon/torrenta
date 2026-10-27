package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.scene.control.TableView;
import javafx.stage.Stage;

import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.FakeEngine;
import com.torrenta.store.Settings;

/**
 * The real main window on a {@link FakeEngine}, built and shown as {@code App.start} does, with
 * {@link Fx#quietSettings} in a test's own folder: {@link #open} returns once the engine is up
 * in the window and its first poll has drawn. {@link #close} stops the poll and the engine as
 * {@code App.stop} does, so no thread outlives the test. Links the window would open in a browser
 * are kept in {@link #opened}.
 */
public final class WindowFixture implements AutoCloseable {

    public final FakeEngine engine;
    public final Settings settings;
    public final MainWindow window;
    public final Stage stage;
    public final List<String> opened;

    private WindowFixture(FakeEngine engine, Settings settings, MainWindow window, Stage stage, List<String> opened) {
        this.engine = engine;
        this.settings = settings;
        this.window = window;
        this.stage = stage;
        this.opened = opened;
    }

    public static WindowFixture open(Path dir, FakeEngine engine) {
        Settings settings = Fx.quietSettings(dir);
        List<String> opened = new CopyOnWriteArrayList<>();
        WindowFixture fixture = Fx.call(() -> {
            Stage stage = new Stage();
            MainWindow window = new MainWindow(stage, settings, dir, opened::add);
            WindowFixture f = new WindowFixture(engine, settings, window, stage, opened);
            window.setEngineFactory(profile -> engine);
            window.show();
            window.startEngine(EngineProfile.LOCAL);
            return f;
        });
        Fx.waitUntil(
                "the engine to be up in the window",
                () -> engine.description().equals(fixture.window.engineDescription()));
        int expected = engine.torrents().size();
        Fx.waitUntil("the first poll to draw " + expected + " rows", () -> fixture.rows() == expected);
        return fixture;
    }

    /** How many rows the torrent list shows now, after the filter. FX thread. */
    public int rows() {
        if (stage.getScene() == null) return -1;
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".table-view")) {
            if (node instanceof TableView<?> table
                    && table.getId() == null
                    && table.isVisible()
                    && !table.getItems().isEmpty()
                    && table.getItems().get(0) instanceof TorrentRow)
                return table.getItems().size();
        }
        return 0;
    }

    /** A capture action, as {@code -Dtorrenta.captureAction=} runs it; see {@code CLAUDE.md}. */
    public void action(String action) {
        Fx.run(() -> window.runForCapture(action));
        Fx.pulse();
    }

    @Override
    public void close() {
        Fx.run(() -> {
            window.closeDialogsForCapture();
            window.stopPolling();
            window.stopEngine();
            stage.hide();
        });
    }
}
