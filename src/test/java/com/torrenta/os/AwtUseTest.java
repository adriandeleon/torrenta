package com.torrenta.os;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwtUseTest {

    /** App.main decides java.awt.headless from this before any AWT class loads. */
    @Test
    void awtIsHeadfulOnlyWhereSomethingAwtIsWanted() {
        assertFalse(AwtUse.headful(true, true, true, "Linux"), "D-Bus tray and badge, desktop-entry handlers");
        assertTrue(AwtUse.headful(true, false, false, "Mac OS X"), "the menu-bar icon");
        assertTrue(AwtUse.headful(false, true, false, "Mac OS X"), "the dock badge");
        assertTrue(AwtUse.headful(false, false, true, "Mac OS X"), "the open-file handlers");
        assertFalse(AwtUse.headful(false, false, false, "Mac OS X"), "the sibling applications' guard stays");
        assertTrue(AwtUse.headful(true, true, true, "Windows 11"), "the AWT tray");
        assertFalse(AwtUse.headful(false, true, true, "Windows 11"), "no badge, registry handlers: no AWT");
        assertTrue(AwtUse.headful(true, false, false, null), "an unknown platform is not Linux");
    }

    /** Nothing AWT touches the toolkit under the guard; the reason is said, never thrown. */
    @Test
    void theMacHandlersSayWhyWhenTheyCannotBeInstalled() {
        if (!java.awt.GraphicsEnvironment.isHeadless()) return; // a headful developer machine
        String installed = MacHandlers.install(inputs -> {});
        assertTrue(installed.startsWith("none"), installed);
    }
}
