package com.torrenta.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.torrenta.engine.Engine;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plain pages of the Settings window, driven as a hand would: every control writes its key
 * through at once, tells whoever acts on it, and shows the stored value again on a reload.
 */
@ExtendWith(FxToolkit.class)
class SettingsPagesFxTest {

    /** The pages whose every row is a control over one or two settings keys. */
    private static final List<SettingsWindow.Category> PLAIN = List.of(
            SettingsWindow.Category.GENERAL,
            SettingsWindow.Category.ADDING,
            SettingsWindow.Category.AUTOMATION,
            SettingsWindow.Category.CONNECTION,
            SettingsWindow.Category.PROTOCOL,
            SettingsWindow.Category.IP_FILTER,
            SettingsWindow.Category.SPEED,
            SettingsWindow.Category.QUEUE,
            SettingsWindow.Category.REMOTE);

    @AfterEach
    void restoreTheTheme() {
        Fx.run(() -> Application.setUserAgentStylesheet(Theme.DEFAULT.stylesheet()));
    }

    /** One row: its title and the controls in it a person can change. */
    private record RowControls(String title, List<Node> controls) {}

    private static List<RowControls> rows(SettingsFixture f) {
        return Fx.call(() -> {
            List<RowControls> out = new ArrayList<>();
            for (HBox row : Modals.all(
                    f.window.scene().getRoot(),
                    HBox.class,
                    h -> h.getStyleClass().contains("settings-row"))) {
                String title = ((Label) ((VBox) row.getChildren().getFirst())
                                .getChildren()
                                .getFirst())
                        .getText();
                List<Node> controls = new ArrayList<>(Modals.all(
                        row.getChildren().get(1),
                        Node.class,
                        n -> n instanceof CheckBox
                                || n instanceof ChoiceBox<?>
                                || n instanceof Spinner<?>
                                || (n instanceof TextInputControl t
                                        && t.isEditable()
                                        && !(n instanceof PasswordField)
                                        && !(n.getParent() instanceof Spinner<?>))));
                out.add(new RowControls(title, controls));
            }
            return out;
        });
    }

    /** Changes a control as a person would, to something other than it shows. FX thread. */
    @SuppressWarnings("unchecked")
    private static void change(Node control, Path dir, int n) {
        switch (control) {
            case CheckBox box -> box.setSelected(!box.isSelected());
            case ChoiceBox<?> choice -> {
                ChoiceBox<Object> c = (ChoiceBox<Object>) choice;
                int at = c.getItems().indexOf(c.getValue());
                c.setValue(c.getItems().get((at + 1) % c.getItems().size()));
            }
            case Spinner<?> spinner -> {
                Object before = spinner.getValue();
                spinner.getValueFactory().increment(1);
                if (spinner.getValue().equals(before)) spinner.getValueFactory().decrement(1);
            }
            case TextInputControl field -> {
                String text = field.getText();
                if (Settings.parseMinutes(text).isPresent()) field.setText(text.equals("07:30") ? "08:15" : "07:30");
                else field.setText(dir.resolve("chosen-" + n).toString());
            }
            default -> throw new AssertionError(control);
        }
    }

    @Test
    void everyControlOnThePlainPagesWritesItsKeyThrough(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        List<String> silent = new ArrayList<>();
        int n = 0;
        for (SettingsWindow.Category page : PLAIN) {
            f.page(page);
            for (RowControls row : rows(f)) {
                for (Node control : row.controls()) {
                    Map<String, String> before = f.saved();
                    int at = n++;
                    Fx.run(() -> change(control, dir, at));
                    if (before.equals(f.saved()))
                        silent.add(page + "/" + row.title() + "/"
                                + control.getClass().getSimpleName());
                }
            }
        }
        // The CheckBox beside the incomplete folder only turns the field on; each of these is written.
        assertEquals(List.of(), silent, "controls that wrote nothing");
        assertTrue(n > 80, n + " controls changed");
        for (String hook : List.of("appearance", "simple", "tray", "handler", "badge", "engine", "ip-filter false"))
            assertTrue(f.told.contains(hook), hook + " was told: " + f.told);
    }

    @Test
    void aReloadShowsWhatTheFileSaysNow(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.CONNECTION);
        Fx.run(() -> {
            f.settings.set(Settings.CONN_PORT, "51413");
            f.window.reloadFromSettings();
        });
        assertEquals(
                51413,
                Fx.call(() ->
                        f.control("settings.connection.port", Spinner.class).getValue()));
        assertEquals(List.of(), f.told, "a reload tells nobody");
    }

    @Test
    void theButtonsOnThePlainPagesReachTheirHooks(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.GENERAL);
        Fx.run(() -> f.button("settings.general.debugLog.button").fire());
        f.page(SettingsWindow.Category.AUTOMATION);
        Fx.run(() -> f.button("settings.automation.testNotification").fire());
        f.page(SettingsWindow.Category.IP_FILTER);
        Fx.run(() -> f.button("settings.ipFilter.downloadNow").fire());
        Fx.run(f.window::refreshIpFilterStatus);
        assertEquals(List.of("debug-log", "test-notification", "ip-filter true"), f.told);
        assertTrue(f.says("filter status"), "the status line asks the window");
    }

    @Test
    void resetPutsTheOwnedKeysBackOnlyWhenTheAnswerIsYes(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        Fx.run(() -> {
            f.settings.setTheme("nord-dark");
            f.settings.setTray(true);
            f.settings.set(Settings.CONN_PORT, "51413");
            f.settings.saveQuietly();
            f.window.reloadFromSettings();
        });
        javafx.scene.control.Button reset = f.button("settings.reset");
        Modals.later(reset::fire);
        Modals.answer(tr("settings.reset"), false);
        assertEquals("51413", f.saved().get(Settings.CONN_PORT), "a no keeps everything");
        assertEquals(List.of(), f.told);

        Modals.later(reset::fire);
        Modals.answer(tr("settings.reset"), true);
        Fx.pulse();
        Map<String, String> saved = f.saved();
        assertFalse(saved.containsKey(Settings.CONN_PORT), "the port is back to its default: " + saved);
        assertFalse(saved.containsKey(Settings.THEME));
        assertTrue(
                f.told.containsAll(List.of("appearance", "engine", "ip-filter false", "tray", "handler", "badge")),
                "the theme, the tray, the handler and the badge changed, so each is told: " + f.told);
    }

    @Test
    void escapeClearsTheSearchFirstAndThenCloses(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        Fx.run(() -> f.window.searchForCapture("proxy"));
        assertEquals("query=\"proxy\" page=CONNECTION", Fx.call(f.window::searchStateForCapture));
        Fx.run(() -> f.window.keyForCapture(KeyCode.ESCAPE));
        assertEquals("query=\"\" page=CONNECTION", Fx.call(f.window::searchStateForCapture));
        assertTrue(Fx.call(f.window::isShowing));
        Fx.run(() -> f.window.keyForCapture(KeyCode.ESCAPE));
        assertFalse(Fx.call(f.window::isShowing), "the second Escape closes");

        Fx.run(() -> f.window.searchForCapture("nothing answers this"));
        Fx.run(() -> f.window.show(f.owner));
        assertEquals("query=\"\" page=CONNECTION", Fx.call(f.window::searchStateForCapture), "opens on every row");
        Fx.run(() -> f.window.show(f.owner));
        assertTrue(Fx.call(f.window::isShowing), "a second show brings it to the front");
    }

    @Test
    void anEngineWithoutACapabilityLosesItsRowsAndAPageLeftEmpty(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.IP_FILTER);
        List<String> all = Fx.call(f.window::visibleRowsForCapture);
        Fx.run(() -> f.window.applyCapabilities(Engine.Capabilities.of(Engine.Capability.LOCAL_FILES)));
        List<String> fewer = Fx.call(f.window::visibleRowsForCapture);
        assertTrue(fewer.size() < all.size(), fewer.size() + " < " + all.size());
        assertFalse(fewer.contains(tr("settings.ipFilter.banned")), "the bans need the IP filter");
        assertTrue(fewer.contains(tr("settings.general.downloadDir")), "the download folder needs local files");
        assertNotEquals(
                "query=\"\" page=IP_FILTER",
                Fx.call(f.window::searchStateForCapture),
                "a page the engine cannot show is left");

        Fx.run(() -> f.window.applyCapabilities(Engine.Capabilities.ALL));
        assertEquals(all, Fx.call(f.window::visibleRowsForCapture), "everything comes back");
    }
}
