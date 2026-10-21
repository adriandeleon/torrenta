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
 * moving between families needs its CSS moved too (see app.css). The toolbar is all line glyphs,
 * as the redesign draws it; the Material ones that used to sit among them were redrawn.
 *
 * <p>Each call returns a fresh node: a JavaFX node can only have one parent.
 */
final class Icons {

    /** Every toolbar glyph is 17px tall, the redesign's size: a 16-unit kit box scaled up a hair. */
    private static final double LINE_SCALE = 17.0 / 16.0;
    /** A Material 24dp glyph at the same visual size. None is on the toolbar now; kept for dialogs. */
    private static final double ICON_SCALE = 17.0 / 24.0;
    /** A glyph beside a sidebar row: smaller than the toolbar's. */
    private static final double SMALL_SCALE = 13.0 / 16.0;

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
        return line(content, LINE_SCALE);
    }

    private static Node line(String content, double scale) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("icon-line");
        svg.setScaleX(scale);
        svg.setScaleY(scale);
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

    /** A page with a plus, Editora's new-file glyph, for "New Torrent". */
    static Node newFile() {
        return line("M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9M8.25 8.2v3.2M6.65 9.8h3.2");
    }

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

    /** Force resume: the play triangle against a bar, which it ignores (the queue). */
    static Node forceRun() {
        return line("M3.4 3.4v9.2l6.2-4.6zM12.6 3.4v9.2");
    }

    /** Recheck: an open arc with an arrowhead, in the line style like the rest of the row. */
    static Node refresh() {
        return line("M13.3 8A5.3 5.3 0 1 1 11.8 4.2M13.3 2.7V6H10");
    }

    /** Remove: a lid over an outlined bin, in the line style. */
    static Node trash() {
        return line("M2.7 4.7h10.6M6 4.7V2.7h4v2M4 4.7l.7 8.6h6.6l.7-8.6");
    }

    /** A folder beside a category in the sidebar. */
    static Node folderSmall() {
        return line("M2 12.5V3.5h4.2l1.5 2h5.8v2M2 12.5l2.2-5h10.3l-2.2 5z", SMALL_SCALE);
    }

    static Node find() {
        return line("M2.5999999999999996 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14");
    }

    /**
     * Search: the kit's magnifier over three result lines, so it is not the filter's plain lens.
     */
    static Node search() {
        return line("M8.2 6.2a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0M10.3 8.9L13.9 12.5"
                + "M1.5 3.5H6M1.5 6.5H5.5M1.5 9.5H7.5M1.5 12.5H9");
    }

<<<<<<< HEAD
    /** A clock face, for the event log, in the line style. */
=======
    /** RSS: the dot and two arcs, in the kit's line style. */
    static Node rss() {
        return line("M3 4a9 9 0 0 1 9 9M3 8a5 5 0 0 1 5 5M3 12.2a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0");
    }

    /** A clock face, for the event log. */
>>>>>>> origin/master
    static Node history() {
        return line("M2.8 8a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0-10.4 0M8 5.2v2.9l2 1.3");
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
