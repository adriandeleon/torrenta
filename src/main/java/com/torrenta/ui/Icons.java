package com.torrenta.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;

/**
 * Toolbar icons, the glyphs Editora draws, returned wrapped in a {@link Group} and scaled down. The
 * Group's layout bounds reflect the scaled size, so buttons sized to their graphic shrink with it.
 *
 * <p>Two families, as in Editora: {@link #line} is the UI Kit's 16-unit outline glyph, <b>stroked</b>
 * and coloured through the {@code icon-line} style class; {@link #of} is a Material Design 24dp
 * single-path glyph, <b>filled</b> and coloured through {@code toolbar-icon}. They are not
 * interchangeable: one is coloured by {@code -fx-stroke}, the other by {@code -fx-fill}, so a glyph
 * moving between families needs its CSS moved too (see app.css).
 *
 * <p>Each call returns a fresh node: a JavaFX node can only have one parent.
 */
final class Icons {

    /** Scale applied to every toolbar icon. */
    private static final double ICON_SCALE = 0.8;
    /** Scale for a kit line glyph: its 16-unit box rendered at the same visual size as a 24dp one. */
    private static final double LINE_SCALE = ICON_SCALE * 24.0 / 16.0;

    private Icons() {}

    /**
     * An icon-only button that still has a name: {@code name} becomes both its tooltip and its
     * accessible text, so a screen reader announces "Settings" rather than "button".
     *
     * @param action run on activation; {@code null} when the caller wires its own handler
     */
    static Button button(Node icon, String name, Runnable action, String... styleClasses) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll(styleClasses);
        name(b, name);
        if (action != null) b.setOnAction(e -> action.run());
        return b;
    }

    /** {@link #button} for the toolbar: reached by its command or the mouse, not by Tab. */
    static Button toolbarButton(Node icon, String name, Runnable action) {
        Button b = button(icon, name, action, "toolbar-button");
        b.setFocusTraversable(false);
        return b;
    }

    /** Sets a control's tooltip and accessible name together. */
    static void name(Control control, String name) {
        control.setAccessibleText(name);
        control.setTooltip(new Tooltip(name));
    }

    /** A UI Kit line glyph (16-unit box, stroked not filled); see {@code .icon-line} in app.css. */
    static Node line(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("icon-line");
        svg.setScaleX(LINE_SCALE);
        svg.setScaleY(LINE_SCALE);
        return new Group(svg);
    }

    /** A Material 24dp glyph (filled); see {@code .toolbar-icon} in app.css. */
    static Node of(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("toolbar-icon");
        svg.setScaleX(ICON_SCALE);
        svg.setScaleY(ICON_SCALE);
        return new Group(svg);
    }

    // ---- glyphs; the paths are Editora's, by the same name --------------------------------------

    /** Open: a folder, for "Add Torrent File". */
    static Node open() {
        return line("M2 12.5V3.5h4.2l1.5 2h5.8v2M2 12.5l2.2-5h10.3l-2.2 5z");
    }

    /** A horseshoe magnet, drawn in the kit's line style, for "Add Magnet Link". */
    static Node magnet() {
        return line("M4.2 2.5v5.6a3.8 3.8 0 0 0 7.6 0V2.5M6.8 2.5v5.6a1.2 1.2 0 0 0 2.4 0V2.5"
                + "M4.2 2.5h2.6M9.2 2.5h2.6M4.2 5.1h2.6M9.2 5.1h2.6");
    }

    /** Two bars, in the kit's line style, to pair with {@link #run}. */
    static Node pause() {
        return line("M5.6 3.4v9.2M10.4 3.4v9.2");
    }

    /** Run: a play triangle. */
    static Node run() {
        return line("M5.2 3.4v9.2l7.4-4.6z");
    }

    static Node refresh() {
        return of("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 "
                + "6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 "
                + "3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z");
    }

    static Node trash() {
        return of("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z");
    }

    static Node find() {
        return line("M2.5999999999999996 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14");
    }

    /** A clock face, for the event log. */
    static Node history() {
        return of("M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 "
                + "2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 "
                + "3.15.75-1.23-4.5-2.67z");
    }

    static Node settings() {
        return line("M6.16 3.57L6.63 1.54L9.37 1.54L9.84 3.57L11.59 2.46L13.54 4.41L12.43 6.16L14.46 6.63L14.46 "
                + "9.37L12.43 9.84L13.54 11.59L11.59 13.54L9.84 12.43L9.37 14.46L6.63 14.46L6.16 12.43L4.41 "
                + "13.54L2.46 11.59L3.57 9.84L1.54 9.37L1.54 6.63L3.57 6.16L2.46 4.41L4.41 2.46Z"
                + "M5.5 8a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0");
    }

    static Node palette() {
        return line("M 1.75 4.25 H 14.25 V 11.75 H 1.75 Z M 4.25 6.75 H 4.5 M 7 6.75 H 7.25"
                + " M 9.75 6.75 H 10 M 12 6.75 H 12.25 M 5 9.25 H 11");
    }
}
