package com.torrenta.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search and reset rules; the window itself is driven by the capture hook. */
class SettingsWindowTest {

    @Test
    void blankMatchesEverythingAndTheRestIsASubstringIgnoringCase() {
        assertTrue(SettingsWindow.matches("", "Download folder | where files go"));
        assertTrue(SettingsWindow.matches(null, "anything"));
        assertTrue(SettingsWindow.matches("FOLDER", "Download folder | where files go"));
        assertTrue(SettingsWindow.matches("  files ", "Download folder | where files go"));
        assertFalse(SettingsWindow.matches("peers", "Download folder | where files go"));
    }

    /** A Reset touches the desktop (stylesheet, tray, handlers, badge) only for a key it changed. */
    @Test
    void resetRunsOnlyTheHooksWhoseKeysChanged() {
        Map<String, String> before = new HashMap<>();
        before.put(Settings.THEME, "nord-light");
        before.put(Settings.UI_TRAY, "true");
        before.put(Settings.OS_BADGE, "false");
        Map<String, String> after = new HashMap<>();
        assertEquals(
                Set.of(SettingsWindow.ResetHook.THEME, SettingsWindow.ResetHook.TRAY, SettingsWindow.ResetHook.BADGE),
                SettingsWindow.resetHooks(before, after));
        assertEquals(Set.of(), SettingsWindow.resetHooks(after, new HashMap<>()));
        Map<String, String> closeOnly = Map.of(Settings.UI_CLOSE_TO_TRAY, "false");
        assertEquals(Set.of(SettingsWindow.ResetHook.TRAY), SettingsWindow.resetHooks(closeOnly, after));
        assertEquals(
                Set.of(SettingsWindow.ResetHook.HANDLER),
                SettingsWindow.resetHooks(Map.of(Settings.OS_HANDLER, "true"), after));
    }
}
