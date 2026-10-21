package com.torrenta.os;

import java.util.Locale;

/**
 * Whether AWT must be headful for what the settings ask: the AWT tray (Windows, macOS), the dock
 * badge and the open-file handlers (macOS). Pure: {@code App.main} decides {@code java.awt.headless}
 * from it before any AWT class loads, and keeps the guard everywhere else (on macOS a live AWT
 * contends with JavaFX for the AppKit run loop, which is why the sibling applications run headless
 * on purpose). Linux never needs it: its tray and badge are D-Bus, its handlers the desktop entry.
 */
public final class AwtUse {

    private AwtUse() {}

    public static boolean headful(boolean tray, boolean badge, boolean handler, String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return false;
        boolean mac = os.contains("mac") || os.contains("darwin");
        if (mac) return tray || badge || handler;
        // Windows: the tray is AWT's; the badge is skipped there and the handlers are the registry's.
        return tray;
    }
}
