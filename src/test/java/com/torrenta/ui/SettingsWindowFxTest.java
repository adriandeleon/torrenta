package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.stage.Stage;

import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window built and shown for real, on the headless toolkit. */
@ExtendWith(FxToolkit.class)
class SettingsWindowFxTest {

    private static SettingsWindow open(Settings settings) {
        return Fx.call(() -> {
            Stage owner = new Stage();
            owner.show();
            SettingsWindow window = new SettingsWindow(settings);
            window.show(owner);
            return window;
        });
    }

    @Test
    void everyPageShowsRows(@TempDir Path dir) {
        SettingsWindow window = open(Fx.quietSettings(dir));
        for (SettingsWindow.Category category : SettingsWindow.Category.values()) {
            Fx.run(() -> window.selectPageForCapture(category.name()));
            Fx.pulse();
            assertEquals(
                    "query=\"\" page=" + category.name(),
                    Fx.call(window::searchStateForCapture),
                    "the sidebar went to " + category);
        }
    }

    @Test
    void theSearchBoxKeepsTheRowsThatMatch(@TempDir Path dir) {
        SettingsWindow window = open(Fx.quietSettings(dir));
        List<String> all = Fx.call(window::visibleRowsForCapture);
        Fx.run(() -> window.searchForCapture("port"));
        Fx.pulse();
        List<String> found = Fx.call(window::visibleRowsForCapture);
        assertFalse(found.isEmpty(), "something matches");
        assertTrue(found.size() < all.size(), found + " is fewer than every row");
    }
}
