package com.torrenta.ui;

import javafx.application.ColorScheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules; the header itself is driven by the capture hook with {@code ui.extendedWindow=true}. */
class ExtendedWindowTest {

    @Test
    void linuxTheBsdsAndWindowsMayUseIt() {
        assertTrue(ExtendedWindow.supportedOn("Linux"));
        assertTrue(ExtendedWindow.supportedOn("FreeBSD"));
        assertTrue(ExtendedWindow.supportedOn("Windows 11"));
        assertTrue(ExtendedWindow.supportedOn("Windows Server 2022"));
    }

    @Test
    void macIsExcludedBecauseTheMenuBelongsToTheSystemBar() {
        assertFalse(ExtendedWindow.supportedOn("Mac OS X"));
        assertFalse(ExtendedWindow.supportedOn("Darwin"));
    }

    @Test
    void anUnknownOsIsNotAssumedToWork() {
        // This decides how the window is decorated, and guessing wrong means a window that will not open.
        assertFalse(ExtendedWindow.supportedOn(""));
        assertFalse(ExtendedWindow.supportedOn(null));
        assertFalse(ExtendedWindow.supportedOn("Some Future OS"));
    }

    @Test
    void theSettingAndThePlatformMustBothAgree() {
        assertTrue(ExtendedWindow.enabled(true, "Linux"));
        assertFalse(ExtendedWindow.enabled(false, "Linux"), "off is off even where it would work");
        assertFalse(ExtendedWindow.enabled(true, "Mac OS X"), "and on is not enough where it should not");
        assertTrue(ExtendedWindow.enabled(true, "Windows 11"));
    }

    @Test
    void aRestartIsNeededOnlyWhenTheSettingAndTheWindowDisagree() {
        assertTrue(ExtendedWindow.restartNeeded(true, false), "asked for, shown without");
        assertTrue(ExtendedWindow.restartNeeded(false, true), "switched off under a merged window");
        assertFalse(ExtendedWindow.restartNeeded(true, true));
        assertFalse(ExtendedWindow.restartNeeded(false, false), "the default, on every platform");
    }

    @Test
    void theWindowButtonsFollowTheThemeNotTheDesktop() {
        assertEquals(ColorScheme.DARK, ExtendedWindow.buttonScheme(true), "a dark theme: light glyphs");
        assertEquals(ColorScheme.LIGHT, ExtendedWindow.buttonScheme(false));
    }
}
