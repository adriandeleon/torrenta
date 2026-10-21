package com.torrenta.ui;

import java.util.Locale;

import javafx.application.ColorScheme;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The window drawn without a system title bar, with the menu bar in its place
 * ({@link StageStyle#EXTENDED}, JavaFX 27+). Editora's, with the names changed.
 *
 * <p>{@code EXTENDED} is the reason this is worth doing at all: it drops the title bar while <b>keeping
 * the system window buttons</b>, so none of minimise/maximise/close, drag-to-move, double-click-to-
 * maximise, edge resize or snapping has to be reimplemented, which is what {@code UNDECORATED} would
 * have meant. {@link HeaderBar} reports where those buttons are ({@code leftSystemInset} on macOS,
 * {@code rightSystemInset} on Windows and Linux) and reserves the space itself.
 *
 * <p><b>Experimental.</b> Not because the API is: it is cross-platform. Because a menu bar drawn
 * inside the window is <em>wrong</em> on macOS, where the menu belongs to the system bar at the top
 * of the screen, and because window decoration is the most platform-specific thing an application
 * does. Every rule here is pure and takes what it decides on, so it is tested off the platform it
 * gates.
 */
public final class ExtendedWindow {

    private ExtendedWindow() {}

    /**
     * Whether the extended window may be used on this OS: <b>Linux, the BSDs and Windows</b>.
     *
     * <p>macOS is excluded, and not because the API is missing there: it is the one platform where this
     * is the wrong idea. The menu belongs to the system menu bar at the top of the screen (and that is
     * where {@code setUseSystemMenuBar} puts it), so drawing it inside the window would be a worse
     * window, not a taller one.
     *
     * <p>An unrecognised {@code os.name} is refused rather than assumed to work: this decides how the
     * window is decorated, and the failure mode of guessing wrong is a window that will not open.
     */
    public static boolean supportedOn(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return false;
        return os.contains("win") || os.contains("linux") || os.contains("bsd") || os.contains("unix");
    }

    /** Whether a window built now should be extended: the user asked for it and the OS allows it. */
    public static boolean enabled(boolean setting, String osName) {
        return setting && supportedOn(osName);
    }

    /**
     * Whether a restart is needed: the setting asks for one style and the window shown has the
     * other. A window's style is fixed once it has been shown, so the setting is read at construction.
     */
    public static boolean restartNeeded(boolean wanted, boolean built) {
        return wanted != built;
    }

    /**
     * The header that replaces the title bar: the menu bar on the left, the window title in the middle.
     *
     * <p>The title is drawn here because nothing else will: the OS is no longer painting one.
     *
     * <p>The menu bar is marked {@link HeaderDragType#NONE}: a header is draggable by default, and a menu
     * bar that begins a window drag instead of opening its menu is unusable.
     *
     * <p>The title is {@link HeaderDragType#DRAGGABLE_SUBTREE}, not {@code DRAGGABLE}: the latter covers only
     * the label itself, and a press on the words lands on the skin's text node inside it, which then blocks
     * the drag (Editora's bug: the window moved from the gaps around the title but not from the title).
     */
    public static HeaderBar header(Node menuBar, Stage stage) {
        Label title = new Label();
        title.getStyleClass().add("window-header-title");
        title.textProperty().bind(stage.titleProperty());
        HeaderBar.setDragType(title, HeaderDragType.DRAGGABLE_SUBTREE);
        HeaderBar.setDragType(menuBar, HeaderDragType.NONE);

        HeaderBar header = new HeaderBar();
        header.getStyleClass().add("window-header");
        header.setLeft(menuBar);
        header.setCenter(title);
        // Reserve for the system buttons on whichever side this platform puts them; the unused side
        // reports a zero inset, so asking for both is right rather than merely harmless.
        header.setLeftSystemPadding(true);
        header.setRightSystemPadding(true);
        return header;
    }

    /**
     * Draws the system window buttons (minimise, maximise, close) for a light or dark theme.
     *
     * <p>JavaFX picks their glyph colour from the scene's colour scheme, which defaults to the <em>OS</em>
     * setting, not from what the header actually looks like. Torrenta themes itself, so on a light desktop
     * with a dark theme the buttons came out dark on dark and all but invisible (Editora's bug). Only the
     * buttons are told: the scene's own colour-scheme preference is left alone, since it reaches further.
     *
     * <p>Called on every appearance change, so a theme switch takes effect without a restart. A stage
     * that is not extended has no such buttons, and is left untouched.
     */
    public static void matchButtonsToTheme(Stage stage, boolean darkTheme) {
        if (stage != null && stage.getStyle() == StageStyle.EXTENDED) {
            HeaderBar.setSystemColorScheme(stage, buttonScheme(darkTheme));
        }
    }

    /** The button colour scheme for a light or dark theme. Pure. */
    public static ColorScheme buttonScheme(boolean darkTheme) {
        return darkTheme ? ColorScheme.DARK : ColorScheme.LIGHT;
    }
}
