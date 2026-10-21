package com.torrenta.ui;

import java.io.InputStream;

import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.text.Font;

/**
 * The bundled interface font, Inter — the same face Editora and Termina use, so the three read as
 * one family on any machine.
 *
 * <p>{@link #load()} must run before any stylesheet is applied, or the CSS naming Inter resolves to
 * nothing and silently falls back to the system font.
 */
public final class Fonts {

    public static final String UI = "Inter";
    /** For hashes, paths and addresses; the {@code mono} style class in ui-font.css names it. */
    public static final String MONO = "JetBrains Mono";

    private static final String[] FILES = {
        "inter/Inter-Regular.ttf",
        "inter/Inter-Bold.ttf",
        "inter/Inter-Italic.ttf",
        "inter/Inter-BoldItalic.ttf",
        "jetbrains-mono/JetBrainsMono-Regular.ttf",
        "jetbrains-mono/JetBrainsMono-Bold.ttf",
    };

    private static final String SHEET = "/com/torrenta/styles/ui-font.css";

    private Fonts() {}

    /** Registers the bundled faces with the toolkit. A file that fails to load is skipped. */
    public static void load() {
        for (String file : FILES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/com/torrenta/fonts/" + file)) {
                if (in != null) Font.loadFont(in, 12);
            } catch (Exception ignored) {
                // One unreadable font is not worth failing a launch over; the rest still load.
            }
        }
    }

    /**
     * Puts the interface font on a scene. Per scene, not once in app.css: every window and dialog
     * is a scene of its own, and a scene sheet survives a runtime theme switch.
     */
    public static void installUiFont(Scene scene) {
        var css = Fonts.class.getResource(SHEET);
        if (css != null && !scene.getStylesheets().contains(css.toExternalForm()))
            scene.getStylesheets().add(css.toExternalForm());
    }

    /** The same, for a {@code Dialog}'s pane, whose scene does not exist until it is shown. */
    public static void installUiFont(DialogPane pane) {
        var css = Fonts.class.getResource(SHEET);
        if (css != null) pane.getStylesheets().add(css.toExternalForm());
    }
}
