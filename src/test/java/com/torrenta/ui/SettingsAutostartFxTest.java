package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javafx.scene.control.CheckBox;

import com.torrenta.os.Autostart;
import com.torrenta.os.Handlers;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** General → Operating system's start-at-login rows, on an entry in a test folder. */
@ExtendWith(FxToolkit.class)
class SettingsAutostartFxTest {

    private static final String LAUNCHER = "/opt/torrenta/bin/Torrenta";

    private static Autostart linux(Path home, Optional<String> launcher) {
        return new Autostart(Handlers.Platform.LINUX, launcher, home, Map.of(), argv -> {
            throw new AssertionError("no command on Linux: " + argv);
        });
    }

    private static Path entry(Path home) {
        return home.resolve(".config/autostart/torrenta.desktop");
    }

    @Test
    void aRunFromSourceShowsTheBoxesUnavailable(@TempDir Path dir, @TempDir Path home) {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.GENERAL);
        Fx.run(() -> f.window.setAutostart(linux(home, Optional.empty())));
        CheckBox box = f.control("settings.general.autostart", CheckBox.class);
        CheckBox inTray = f.control("settings.general.autostartInTray", CheckBox.class);
        Fx.waitUntil(
                "the entry read",
                () -> Fx.call(() -> f.window.autostartDescription()).equals("box=false enabled=false inTray=true"));
        assertTrue(Fx.call(inTray::isDisabled));
        assertFalse(Fx.call(box::isSelected));
    }

    @Test
    void tickingWritesTheEntryAndUntickingRemovesIt(@TempDir Path dir, @TempDir Path home) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.GENERAL);
        Fx.run(() -> f.window.setAutostart(linux(home, Optional.of(LAUNCHER))));
        CheckBox box = f.control("settings.general.autostart", CheckBox.class);
        CheckBox inTray = f.control("settings.general.autostartInTray", CheckBox.class);
        Fx.waitUntil("the box enabled", () -> !Fx.call(box::isDisabled));
        assertTrue(Fx.call(inTray::isDisabled), "nothing to start in the tray while it is off");

        Fx.run(() -> box.setSelected(true));
        Fx.waitUntil("the entry written", () -> f.told.contains("autostart ON"));
        assertTrue(Files.readString(entry(home)).contains("Exec=\"" + LAUNCHER + "\" --minimized"));
        assertFalse(Fx.call(inTray::isDisabled));

        // The tray box is a setting, and the entry carries it.
        Fx.run(() -> inTray.setSelected(false));
        Fx.waitUntil("the entry written again", () -> {
            try {
                return !Files.readString(entry(home)).contains("--minimized");
            } catch (java.io.IOException e) {
                return false;
            }
        });
        assertEquals("false", f.saved().get(Settings.OS_AUTOSTART_IN_TRAY));

        Fx.run(() -> box.setSelected(false));
        Fx.waitUntil("the entry removed", () -> f.told.contains("autostart OFF"));
        assertFalse(Files.exists(entry(home)));
    }

    @Test
    void theBoxShowsAnEntryAlreadyThereAndOneTheDesktopTurnedOff(@TempDir Path dir, @TempDir Path home)
            throws Exception {
        Autostart autostart = linux(home, Optional.of(LAUNCHER));
        autostart.set(true, true);
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.GENERAL);
        Fx.run(() -> f.window.setAutostart(autostart));
        Fx.waitUntil(
                "ticked",
                () -> Fx.call(() -> f.window.autostartDescription()).equals("box=true enabled=true inTray=true"));

        Files.writeString(entry(home), Files.readString(entry(home)) + "Hidden=true\n");
        Fx.run(f.window::reloadFromSettings);
        Fx.waitUntil(
                "unticked",
                () -> Fx.call(() -> f.window.autostartDescription()).equals("box=false enabled=true inTray=true"));
        assertTrue(Files.exists(entry(home)), "reading it never removes the person's own off");
        assertFalse(f.told.stream().anyMatch(t -> t.startsWith("autostart")), "a read tells nobody");
    }
}
