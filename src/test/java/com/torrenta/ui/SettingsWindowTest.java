package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search rule; the window itself is driven by the capture hook. */
class SettingsWindowTest {

    @Test
    void blankMatchesEverythingAndTheRestIsASubstringIgnoringCase() {
        assertTrue(SettingsWindow.matches("", "Download folder | where files go"));
        assertTrue(SettingsWindow.matches(null, "anything"));
        assertTrue(SettingsWindow.matches("FOLDER", "Download folder | where files go"));
        assertTrue(SettingsWindow.matches("  files ", "Download folder | where files go"));
        assertFalse(SettingsWindow.matches("peers", "Download folder | where files go"));
    }
}
