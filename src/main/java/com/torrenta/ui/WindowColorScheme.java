package com.torrenta.ui;

import javafx.application.ColorScheme;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * Tells every window whether the theme is light or dark, so the parts JavaFX and the OS draw match it.
 * Editora's and Termina's.
 *
 * <p>Torrenta themes itself, but JavaFX picks the look of the window chrome it draws from the scene's
 * colour scheme ({@link Scene.Preferences#colorSchemeProperty()}), which defaults to the <em>desktop</em>'s
 * setting: the minimise/maximise/close buttons of the extended window (their {@code dark} style follows
 * {@code HeaderBar.systemColorScheme}, which falls back to the scene's) and, on Windows and macOS, the
 * frame of every decorated window ({@code setDarkFrame}; GTK ignores it, so on Linux the frame follows the
 * desktop whatever the app says). With a dark theme on a light desktop the buttons came out dark on dark
 * (Editora's bug), and the Settings window and dialogs kept a light frame. So each window's scene is given
 * the theme's scheme, when the theme changes and as each window opens.
 *
 * <p>Popups (menus, tooltips, the palette's lists) have no frame or buttons and are skipped. Nothing
 * Torrenta draws reads the scheme back: its stylesheets come from the theme, not from
 * {@code prefers-color-scheme}.
 */
public final class WindowColorScheme {

    private static final String HOOKED = WindowColorScheme.class.getName();

    private static volatile boolean dark;
    private static boolean installed;

    private WindowColorScheme() {}

    /**
     * Starts giving each window the scheme as it opens; once, on the FX thread, before the first window is
     * shown. A window joins {@link Window#getWindows()} as it is shown, before its first frame, so it never
     * draws with the desktop's scheme first. Later calls only set the scheme.
     */
    public static void install(boolean darkTheme) {
        dark = darkTheme;
        if (installed) {
            apply(darkTheme);
            return;
        }
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(WindowColorScheme::hook);
            }
        });
        Window.getWindows().forEach(WindowColorScheme::hook);
    }

    /** Gives every open window a light or dark theme's scheme; the ones opened later get it from {@link #install}. */
    public static void apply(boolean darkTheme) {
        dark = darkTheme;
        for (Window window : Window.getWindows()) {
            if (!(window instanceof PopupWindow)) applyTo(window.getScene(), darkTheme);
        }
    }

    /** The scheme for a light or dark theme. Pure. */
    public static ColorScheme scheme(boolean darkTheme) {
        return darkTheme ? ColorScheme.DARK : ColorScheme.LIGHT;
    }

    private static void hook(Window window) {
        if (window instanceof PopupWindow) return;
        applyTo(window.getScene(), dark);
        // A window whose scene is replaced later keeps the scheme too. Once per window: a window joins the
        // list again each time it is shown (Settings, the Statistics window).
        if (window.getProperties().putIfAbsent(HOOKED, Boolean.TRUE) == null) {
            window.sceneProperty().addListener((o, was, scene) -> applyTo(scene, dark));
        }
    }

    private static void applyTo(@Nullable Scene scene, boolean darkTheme) {
        if (scene != null) scene.getPreferences().setColorScheme(scheme(darkTheme));
    }
}
