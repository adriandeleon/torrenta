package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;

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

    /** The torrent the list shows under this id now, after the filter, or null. FX thread. */
    public com.torrenta.engine.Torrent shown(String id) {
        if (stage.getScene() == null) return null;
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".table-view")) {
            if (node instanceof TableView<?> table && table.getId() == null) {
                for (Object item : table.getItems()) {
                    if (item instanceof TorrentRow row && row.id().equals(id)) return row.torrent();
                }
            }
        }
        return null;
    }

    /** How many rows are selected in the torrent list now. FX thread. */
    public int selected() {
        if (stage.getScene() == null) return 0;
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".table-view")) {
            if (node instanceof TableView<?> table
                    && table.getId() == null
                    && !table.getItems().isEmpty()
                    && table.getItems().get(0) instanceof TorrentRow)
                return table.getSelectionModel().getSelectedItems().size();
        }
        return 0;
    }

    /** A capture action, as {@code -Dtorrenta.captureAction=} runs it; see {@code CLAUDE.md}. */
    public void action(String action) {
        Fx.run(() -> window.runForCapture(action));
        Fx.pulse();
    }

    /**
     * A capture action that opens a modal dialog, posted and not waited for: its {@code
     * showAndWait} holds the call until the dialog closes. The calls after it run inside the
     * dialog's nested loop, which is where a test answers it ({@link #pressDefault}, {@link
     * #cancelDialogs}, another {@link #action}).
     */
    public void actionLater(String action) {
        Platform.runLater(() -> window.runForCapture(action));
    }

    /** A window other than the main one showing with this title, once one is. FX thread or not. */
    public Stage awaitWindow(String title) {
        Fx.waitUntil("a window titled " + title, () -> showing(title) != null);
        return Fx.call(() -> showing(title));
    }

    private Stage showing(String title) {
        for (Window w : Window.getWindows()) {
            if (w != stage && w.isShowing() && w instanceof Stage s && title.equals(s.getTitle())) return s;
        }
        return null;
    }

    /** Whether a window with this title shows now. */
    public boolean isShowing(String title) {
        return Fx.call(() -> showing(title) != null);
    }

    /**
     * Presses the default button (OK, Add, Download …) of the window titled {@code title}, once it
     * shows; a JavaFX {@code Dialog}'s editor, if any, first gets {@code text} when not null.
     */
    public void pressDefault(String title, String text) {
        Stage dialog = awaitWindow(title);
        Fx.run(() -> {
            if (text != null && dialog.getScene().getRoot() instanceof DialogPane pane) {
                Node editor = pane.lookup(".text-field");
                if (editor instanceof javafx.scene.control.TextField field) field.setText(text);
            }
            for (Node n : dialog.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.isDefaultButton() && !b.isDisabled()) {
                    b.fire();
                    return;
                }
            }
            throw new AssertionError("no default button in " + title);
        });
        Fx.pulse();
    }

    public void pressDefault(String title) {
        pressDefault(title, null);
    }

    /** Every dialog cancelled, as the capture hook does before it exits; then a pulse. */
    public void cancelDialogs() {
        Fx.run(window::closeDialogsForCapture);
        Fx.pulse();
    }

    /** The status bar's message now. */
    public String status() {
        return Fx.call(window::statusText);
    }

    /** Waits for the status bar to say something containing {@code part}. */
    public void awaitStatus(String part) {
        Fx.waitUntil(
                "the status bar to say \"" + part + "\"",
                () -> window.statusText().contains(part));
    }

    /** Waits for the engine to have been asked {@code call}, as {@link FakeEngine#calls()} writes it. */
    public void awaitCall(String call) {
        Fx.waitUntil("the engine to be asked " + call, () -> engine.calls().contains(call));
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
