package com.torrenta.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;

import com.torrenta.content.FileKind;

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
 * <p>Each call returns a fresh node: a JavaFX node can only have one parent. A glyph is 17px as
 * made, the toolbar's size; {@link Glyph#at} resizes it for a menu item or a tab (16px, beside
 * 13px text), and {@link #forCommand} is the rule for which command shows which glyph.
 */
final class Icons {

    /** A glyph: a Group holding one scaled SVGPath, which knows its own box so it can be resized. */
    static final class Glyph extends Group {
        private final SVGPath path;
        private final double box;

        private Glyph(SVGPath path, double box) {
            super(path);
            this.path = path;
            this.box = box;
        }

        /** This glyph at {@code px} tall (its box scaled to it); returns itself for chaining. */
        Glyph at(double px) {
            path.setScaleX(px / box);
            path.setScaleY(px / box);
            return this;
        }
    }

    /** A glyph beside a menu item or in a tab: 16px, a hair under the toolbar's. */
    static final double MENU_SIZE = 16;

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
    static Glyph line(String content) {
        return line(content, LINE_SCALE);
    }

    private static Glyph line(String content, double scale) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("icon-line");
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        return new Glyph(svg, 16);
    }

    /** A Material 24dp glyph (filled); see {@code .toolbar-icon} in app.css. */
    static Glyph of(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("toolbar-icon");
        svg.setScaleX(ICON_SCALE);
        svg.setScaleY(ICON_SCALE);
        return new Glyph(svg, 24);
    }

    /**
     * The glyph a command shows beside its name in the menus, or null for a command that has none
     * (the Show: filters, the toggles the palette alone lists). The toolbar's commands show the
     * toolbar's glyph, so the two cannot disagree.
     */
    static Glyph forCommand(String id) {
        return switch (id) {
            case "add-file" -> open();
            case "add-magnet" -> magnet();
            case "add-clipboard" -> clipboard();
            case "pause-resume" -> pause();
            case "force-resume" -> forceRun();
            case "recheck" -> refresh();
            case "reannounce" -> broadcast();
            case "sequential" -> sequential();
            case "queue-top" -> moveTop();
            case "queue-up" -> moveUp();
            case "queue-down" -> moveDown();
            case "queue-bottom" -> moveBottom();
            case "queue" -> queue();
            case "set-location" -> folderMove();
            case "choose-files" -> files();
            case "super-seed" -> superSeed();
            case "limits" -> gauge();
            case "set-category" -> open();
            case "edit-tags" -> tag();
            case "manage-categories" -> tag();
            case "copy-name" -> copy();
            case "copy-hash" -> hash();
            case "copy-magnet" -> magnet();
            case "copy-updatable-link" -> magnet();
            case "new-version" -> newFile();
            case "rename" -> rename();
            case "export-torrent" -> exportFile();
            case "import-from-client" -> importInto();
            case "open-folder" -> open();
            case "remove" -> trash();
            case "remove-data" -> trashData();
            case "check-updates" -> download();
            case "about" -> info();
            case "alt-speed" -> altSpeed();
            case "quit" -> power();
            case "settings" -> settings();
            case "search" -> search();
            case "search-clear" -> trash();
            case "rss" -> rss();
            case "view-next" -> viewNext();
            case "view-previous" -> viewPrevious();
            case "transfers" -> transfers();
            case "filter" -> find();
            case "undo" -> history();
            case "save-view" -> eye();
            case "select-all" -> selectAll();
            case "event-log" -> history();
            case "statistics" -> gauge();
            case "message-log" -> history();
            case "debug-log" -> info();
            case "toggle-toolbar" -> toolbar();
            case "toggle-sidebar", "focus-sidebar" -> sidebar();
            case "toggle-detail" -> detailPane();
            default -> null;
        };
    }

    /**
     * The glyph beside a Settings page in the window's sidebar, by the page's id
     * ({@code SettingsWindow.Category.id}); null for an id that is not a page. Where a page holds
     * what a command does, it shows that command's glyph (Search, RSS, Speed, Queue, Categories and
     * tags), so the menus and the sidebar agree.
     */
    static Glyph forSettingsPage(String id) {
        return switch (id) {
            case "general" -> sliders();
            case "keyboard" -> palette();
            case "adding" -> download();
            case "watchFolders" -> eye();
            case "automation" -> terminal();
            case "search" -> search();
            case "rss" -> rss();
            case "sync" -> sync();
            case "remote" -> remote();
            case "labels" -> tag();
            case "engine" -> server();
            case "connection" -> globe();
            case "protocol" -> network();
            case "ipFilter" -> shield();
            case "speed" -> gauge();
            case "queue" -> queue();
            default -> null;
        };
    }

    // ---- glyphs; the paths are Editora's, by the same name --------------------------------------

    /** An arrow into a tray, for "Import from another client": torrents coming in from elsewhere. */
    static Glyph importInto() {
        return line("M8 2v7.5M5 6.5l3 3 3-3M2.5 9.5v4h11v-4");
    }

    /** A page with a plus, Editora's new-file glyph, for "New Torrent". */
    static Glyph newFile() {
        return line("M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9M8.25 8.2v3.2M6.65 9.8h3.2");
    }

    /** RSS: the dot and two arcs, in the kit's line style. */
    static Glyph rss() {
        return line("M3 4a9 9 0 0 1 9 9M3 8a5 5 0 0 1 5 5M3 12.2a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0");
    }

    /** Open: a folder, for "Add Torrent File". */
    static Glyph open() {
        return line("M2 12.5V3.5h4.2l1.5 2h5.8v2M2 12.5l2.2-5h10.3l-2.2 5z");
    }

    /** A horseshoe magnet, drawn in the kit's line style, for "Add Magnet Link". */
    static Glyph magnet() {
        return line("M4.2 2.5v5.6a3.8 3.8 0 0 0 7.6 0V2.5M6.8 2.5v5.6a1.2 1.2 0 0 0 2.4 0V2.5"
                + "M4.2 2.5h2.6M9.2 2.5h2.6M4.2 5.1h2.6M9.2 5.1h2.6");
    }

    /** Two bars, in the kit's line style, to pair with {@link #run}. */
    static Glyph pause() {
        return line("M5.6 3.4v9.2M10.4 3.4v9.2");
    }

    /** Run: a play triangle. */
    static Glyph run() {
        return line("M5.2 3.4v9.2l7.4-4.6z");
    }

    /** Force resume: the play triangle against a bar, which it ignores (the queue). */
    static Glyph forceRun() {
        return line("M3.4 3.4v9.2l6.2-4.6zM12.6 3.4v9.2");
    }

    /** Recheck: an open arc with an arrowhead, in the line style like the rest of the row. */
    static Glyph refresh() {
        return line("M13.3 8A5.3 5.3 0 1 1 11.8 4.2M13.3 2.7V6H10");
    }

    /** Super seeding: an arrow up out of a seed's dot, fanning to three. */
    static Glyph superSeed() {
        return line("M8 13.5a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3M8 10.5V4M8 4L4 7.5M8 4l4 3.5M5.5 2.5L8 4l2.5-1.5");
    }

    /** Rename: a text cursor beside a line of text. */
    static Glyph rename() {
        return line("M2.5 8h6.5M2.5 5h4M11.5 3v10M10 3h3M10 13h3");
    }

    /** Export .torrent: a page with an arrow leaving it. */
    static Glyph exportFile() {
        return line("M9.5 13.5h-5v-11h4.6l2.9 2.9v2.6M9.1 2.5v2.9h2.9M9 11h5.5M12.3 8.8l2.2 2.2-2.2 2.2");
    }

    /** Remove: a lid over an outlined bin, in the line style. */
    static Glyph trash() {
        return line("M2.7 4.7h10.6M6 4.7V2.7h4v2M4 4.7l.7 8.6h6.6l.7-8.6");
    }

    /** A folder beside a category in the sidebar, and beside a folder in the Files tab. */
    /**
     * Two links of a chain: a download from a link rather than a torrent, beside its name in the
     * list and its entry in the sidebar.
     */
    static Glyph link() {
        return line(
                "M6.6 9.4l2.8-2.8M7.4 4.6l1.1-1.1a2.6 2.6 0 0 1 3.7 3.7l-1.1 1.1M8.6 11.4l-1.1 1.1a2.6 2.6 0 0 1-3.7-3.7l1.1-1.1");
    }

    /** {@link #link} at the sidebar's size. */
    static Glyph linkSmall() {
        return line(
                "M6.6 9.4l2.8-2.8M7.4 4.6l1.1-1.1a2.6 2.6 0 0 1 3.7 3.7l-1.1 1.1M8.6 11.4l-1.1 1.1a2.6 2.6 0 0 1-3.7-3.7l1.1-1.1",
                SMALL_SCALE);
    }

    static Glyph folderSmall() {
        return line("M2 12.5V3.5h4.2l1.5 2h5.8v2M2 12.5l2.2-5h10.3l-2.2 5z", SMALL_SCALE);
    }

    /** A section's fold in the sidebar: points down while open, turned to point right when folded. */
    static Glyph chevronSmall() {
        return line("M4.5 6.2 8 9.7l3.5-3.5", SMALL_SCALE);
    }

    static Glyph find() {
        return line("M2.5999999999999996 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14");
    }

    /**
     * Search: the kit's magnifier over three result lines, so it is not the filter's plain lens.
     */
    static Glyph search() {
        return line("M8.2 6.2a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0M10.3 8.9L13.9 12.5"
                + "M1.5 3.5H6M1.5 6.5H5.5M1.5 9.5H7.5M1.5 12.5H9");
    }

    /** A clock face, for the event log, in the line style. */
    static Glyph history() {
        return line("M2.8 8a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0-10.4 0M8 5.2v2.9l2 1.3");
    }

    static Glyph settings() {
        return line("M6.16 3.57L6.63 1.54L9.37 1.54L9.84 3.57L11.59 2.46L13.54 4.41L12.43 6.16L14.46 6.63L14.46 "
                + "9.37L12.43 9.84L13.54 11.59L11.59 13.54L9.84 12.43L9.37 14.46L6.63 14.46L6.16 12.43L4.41 "
                + "13.54L2.46 11.59L3.57 9.84L1.54 9.37L1.54 6.63L3.57 6.16L2.46 4.41L4.41 2.46Z"
                + "M5.5 8a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0");
    }

    static Glyph palette() {
        return line("M 1.75 4.25 H 14.25 V 11.75 H 1.75 Z M 4.25 6.75 H 4.5 M 7 6.75 H 7.25"
                + " M 9.75 6.75 H 10 M 12 6.75 H 12.25 M 5 9.25 H 11");
    }

    // ---- glyphs for the menus and the detail tabs ------------------------------------------------

    /** A clipboard, for "Add from Clipboard" (Editora's paste). */
    static Glyph clipboard() {
        return line("M5 3h6a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5v-8A1.5 1.5 0 0 1 5 3z"
                + "M7 1.6h2a1 1 0 0 1 1 1v.8a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-.8a1 1 0 0 1 1-1z");
    }

    /** A power mark, for Quit. */
    static Glyph power() {
        return line("M8 2.5v5.5M4.66 4.52a5.2 5.2 0 1 0 6.68 0");
    }

    /** A dot broadcasting two arcs each way: a tracker, and the reannounce to it. */
    static Glyph broadcast() {
        return line("M7.2 8a.8.8 0 1 0 1.6 0a.8.8 0 1 0-1.6 0"
                + "M5.2 10.8a4 4 0 0 1 0-5.6M10.8 5.2a4 4 0 0 1 0 5.6"
                + "M3.1 12.9a7 7 0 0 1 0-9.8M12.9 3.1a7 7 0 0 1 0 9.8");
    }

    /** Three lines in order with an arrow down them: sequential download. */
    static Glyph sequential() {
        return line("M2.5 4h6M2.5 8h6M2.5 12h6M12 3.5v9M9.8 10.3l2.2 2.2 2.2-2.2");
    }

    /** A gauge with its needle, for Limits. */
    static Glyph gauge() {
        return line("M2.8 11.5a5.2 5.2 0 1 1 10.4 0M8 11.5l2.8-3.4");
    }

    /** A tag with its hole, for tags. */
    static Glyph tag() {
        return line("M2.5 2.5h5.5l5.5 5.5-5.5 5.5-5.5-5.5zM5.4 5.4l.1.1");
    }

    /** Two sheets, one over the other: Copy (Editora's). */
    static Glyph copy() {
        return line(
                "M7 5.5h5a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 1-1.5 1.5H7a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 7 5.5z"
                        + "M3 10.5V3.6A1.1 1.1 0 0 1 4.1 2.5H11");
    }

    /** A circle struck through: Ban Peer. */
    static Glyph ban() {
        return line("M2.8 8a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0-10.4 0M4.3 4.3l7.4 7.4");
    }

    /** The gauge with its needle low: the alternative (slower) speed limits. */
    static Glyph altSpeed() {
        return line("M2.8 11.5a5.2 5.2 0 1 1 10.4 0M8 11.5L5.2 8.1M2.5 13.5h11");
    }

    /** A hash sign, for the info-hash. */
    static Glyph hash() {
        return line("M6 2.5l-1.5 11M11.5 2.5L10 13.5M3 6h10.5M2.5 10H13");
    }

    /** The bin with the files' lines in it: Remove with Data. */
    static Glyph trashData() {
        return line("M2.7 4.7h10.6M6 4.7V2.7h4v2M4 4.7l.7 8.6h6.6l.7-8.6M6.6 7.2l.3 4M9.4 7.2l-.3 4");
    }

    /** An arrow down into a tray: an update to fetch. */
    static Glyph download() {
        return line("M8 2.5v8M5.3 7.8L8 10.5l2.7-2.7M2.5 11v2.5h11V11");
    }

    /** A play mark against a bar: skip. */
    static Glyph skip() {
        return line("M3.5 3.5l6 4.5-6 4.5zM12.5 3.5v9");
    }

    /** A ring with an i in it: About, and the General tab. */
    static Glyph info() {
        return line("M2.8 8a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0-10.4 0M8 7.3v3.7M7.95 5.2h.1");
    }

    /** Arrows up and down: the transfers. */
    static Glyph transfers() {
        return line("M5.5 13V3.5M2.8 6.2l2.7-2.7 2.7 2.7M10.5 3v9.5M7.8 9.8l2.7 2.7 2.7-2.7");
    }

    /** A frame drawn by its corners: Select All. */
    static Glyph selectAll() {
        return line("M3 6V3h3M10 3h3v3M13 10v3h-3M6 13H3v-3");
    }

    /** A window with a band across its top: the toolbar. */
    static Glyph toolbar() {
        return line("M2 3.5h12v9H2zM2 6.5h12");
    }

    /** A window with a band down its left: the sidebar. */
    static Glyph sidebar() {
        return line("M2 3.5h12v9H2zM5.5 3.5v9");
    }

    /** A window with a band across its foot: the detail pane. */
    static Glyph detailPane() {
        return line("M2 3.5h12v9H2zM2 9h12");
    }

    /** Two tall rows: comfortable density. */
    static Glyph rowsComfortable() {
        return line("M2 3h12v4H2zM2 9h12v4H2z");
    }

    /** Three lines close together: compact density. */
    static Glyph rowsCompact() {
        return line("M2 4h12M2 8h12M2 12h12");
    }

    /** A sheet with its corner turned and lines on it: the Files tab. */
    static Glyph files() {
        return line("M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9M6.5 9h3M6.5 11h3");
    }

    // ---- the Files tab's rows, by FileKind -------------------------------------------------------

    /** The sheet with its corner turned, for {@link #file} and the kinds drawn on it. */
    private static final String SHEET = "M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9";

    /** A closed folder: a folder row. */
    static Glyph folder() {
        return line("M2 12.5V3.5h4.2l1.5 2h6.3v7z");
    }

    /** A blank sheet: a file of no kind we know. */
    static Glyph file() {
        return line(SHEET);
    }

    /** A sheet with a play mark: video. */
    static Glyph fileVideo() {
        return line(SHEET + "M7 8v3.6l3-1.8z");
    }

    /** A sheet with a note: audio. */
    static Glyph fileAudio() {
        return line(SHEET + "M6.2 11.4a1 1 0 1 0 2 0a1 1 0 1 0-2 0M8.2 11.4V7.6l1.9.8");
    }

    /** A sheet with a hill and a sun: an image. */
    static Glyph fileImage() {
        return line(SHEET + "M5.6 12.4l1.9-2.4 1.3 1.5.9-1 1.4 1.9M6.4 7.6h.1");
    }

    /** A sheet with a zip down it: an archive. */
    static Glyph fileArchive() {
        return line(SHEET + "M7.3 2.5v1.2M8.3 4.2v1.2M7.3 5.9v1.2M7.3 8.8h2v2.4h-2z");
    }

    /** An open book: an ebook or a comic. */
    static Glyph book() {
        return line("M8 4.3C6.6 3.3 4.6 2.9 2.5 3.1v9.4c2.1-.2 4.1.2 5.5 1.2 1.4-1 3.4-1.4 5.5-1.2V3.1"
                + "c-2.1-.2-4.1.2-5.5 1.2zM8 4.3v9.4");
    }

    /** A screen with caption lines: subtitles. */
    static Glyph subtitles() {
        return line("M2 3.5h12v9H2zM4.5 9.8h4.2M10.2 9.8h1.6");
    }

    /** A disc with its hole: a disc or disk image. */
    static Glyph disc() {
        return line("M2.8 8a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0-10.4 0M6.7 8a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0");
    }

    /** A cylinder in three bands: data. */
    static Glyph database() {
        return line("M3.5 4.2c0-1 2-1.7 4.5-1.7s4.5.7 4.5 1.7-2 1.7-4.5 1.7-4.5-.7-4.5-1.7z"
                + "M3.5 4.2v7.6c0 1 2 1.7 4.5 1.7s4.5-.7 4.5-1.7V4.2M3.5 8c0 1 2 1.7 4.5 1.7s4.5-.7 4.5-1.7");
    }

    /** A package: a program or an installer. */
    static Glyph box() {
        return line("M2.5 5L8 2.5 13.5 5v6L8 13.5 2.5 11zM2.5 5L8 7.5 13.5 5M8 7.5v6");
    }

    /** A folder with an arrow out of it: Open Folder in the Files tab. */
    static Glyph folderOpen() {
        return line("M2 12.5V3.5h4.2l1.5 2h6.3v7zM6 10.5l3.5-3.5M6.8 7h2.7v2.7");
    }

    /** A folder with an arrow up out of it: Open Parent Folder. */
    static Glyph folderUp() {
        return line("M2 12.5V3.5h4.2l1.5 2h6.3v7zM8 11V7.2M6.3 8.8L8 7.1l1.7 1.7");
    }

    /** Two people: the Peers tab. */
    static Glyph peers() {
        return line("M4 5.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0M1.8 13.2c0-2.4 1.9-3.9 4.2-3.9s4.2 1.5 4.2 3.9"
                + "M10.3 3.7a2 2 0 0 1 0 3.6M11.6 9.6c1.6.5 2.6 1.8 2.6 3.6");
    }

    /** Four squares: the Pieces tab. */
    static Glyph pieces() {
        return line("M2.5 2.5h4.5v4.5H2.5zM9 2.5h4.5v4.5H9zM2.5 9h4.5v4.5H2.5zM9 9h4.5v4.5H9z");
    }

    /** A plus, for Add. */
    static Glyph plus() {
        return line("M8 3v10M3 8h10");
    }

    /** A box with an arrow out of its corner: a page to open elsewhere. */
    static Glyph external() {
        return line("M7 3.5H3.5v9h9V9M9.5 3.5h3v3M12.5 3.5L7.5 8.5");
    }

    /** Three stacked lines: the queue, for the Queue submenu. */
    static Glyph queue() {
        return line("M3 4.5h10M3 8h10M3 11.5h10");
    }

    /** An arrow up against a bar: to the top of the queue. */
    static Glyph moveTop() {
        return line("M3.5 3h9M8 13V6M5 9l3-3 3 3");
    }

    /** An arrow up: one place up the queue. */
    static Glyph moveUp() {
        return line("M8 13V3.5M4.5 7l3.5-3.5L11.5 7");
    }

    /** An arrow down: one place down the queue. */
    static Glyph moveDown() {
        return line("M8 3v9.5M4.5 9l3.5 3.5L11.5 9");
    }

    /** An arrow down against a bar: to the bottom of the queue. */
    static Glyph moveBottom() {
        return line("M3.5 13h9M8 3v7M5 7l3 3 3-3");
    }

    /** A folder with an arrow into it: Set Location. */
    static Glyph folderMove() {
        return line("M2 12.5V3.5h4.2l1.5 2h5.8v7z M5.5 8.5h5M8.5 6.5l2 2-2 2");
    }

    /** An arrow right against a bar: the next view. */
    static Glyph viewNext() {
        return line("M2.5 8h9M8.5 5l3 3-3 3M13.5 3.5v9");
    }

    /** An arrow left against a bar: the previous view. */
    static Glyph viewPrevious() {
        return line("M13.5 8h-9M7.5 5l-3 3 3 3M2.5 3.5v9");
    }

    // ---- the Settings window's pages -------------------------------------------------------------

    /** Three sliders at different settings: General. */
    static Glyph sliders() {
        return line("M2.5 4h5.3M10.7 4h2.8M7.8 4a1.45 1.45 0 1 0 2.9 0a1.45 1.45 0 1 0-2.9 0"
                + "M2.5 8h1.3M6.7 8h6.8M3.8 8a1.45 1.45 0 1 0 2.9 0a1.45 1.45 0 1 0-2.9 0"
                + "M2.5 12h7.3M12.7 12h.8M9.8 12a1.45 1.45 0 1 0 2.9 0a1.45 1.45 0 1 0-2.9 0");
    }

    /** An open eye: Watch folders. */
    static Glyph eye() {
        return line("M1.5 8c1.7-3 4-4.5 6.5-4.5s4.8 1.5 6.5 4.5c-1.7 3-4 4.5-6.5 4.5S3.2 11 1.5 8z"
                + "M6.3 8a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0");
    }

    /** A terminal with its prompt: Automation, which runs a command. */
    static Glyph terminal() {
        return line("M2 3.5h12v9H2zM4.5 6.5l2 1.5-2 1.5M8 10h3.5");
    }

    /** Two arrows chasing round a circle: Sync. */
    static Glyph sync() {
        return line("M3 7a5 5 0 0 1 9-2.2M12 2.5v2.3H9.7M13 9a5 5 0 0 1-9 2.2M4 13.5v-2.3h2.3");
    }

    /** Two stacked units with their lights: an engine, local or remote. */
    /** A phone, for Remote access: what most of its clients run on. */
    static Glyph remote() {
        return line("M5 2.5h6v11H5zM7.25 11.25h1.5");
    }

    static Glyph server() {
        return line("M2.5 2.5h11V7h-11zM2.5 9h11v4.5h-11zM5 4.75h.1M5 11.25h.1M8 4.75h3M8 11.25h3");
    }

    /** A globe with its equator and a meridian: Connection. */
    static Glyph globe() {
        return line("M2.5 8a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0-11 0M2.5 8h11"
                + "M8 2.5c-1.8 1.6-2.5 3.5-2.5 5.5s.7 3.9 2.5 5.5c1.8-1.6 2.5-3.5 2.5-5.5S9.8 4.1 8 2.5z");
    }

    /** Three nodes joined: Protocol (DHT, PEX, local discovery). */
    static Glyph network() {
        return line("M6.5 3.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M2 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"
                + "M11 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M7.3 4.8L4.2 10.7M8.7 4.8l3.1 5.9M5 12h6");
    }

    /** A shield with a tick: IP filter. */
    static Glyph shield() {
        return line("M8 2l5 2v4c0 3-2.2 5-5 6-2.8-1-5-3-5-6V4zM5.8 8l1.6 1.6 2.9-3");
    }

    /** A file kind's glyph ({@link FileKind}), fresh, at the menus' size. */
    static Glyph forKind(FileKind kind) {
        Glyph g =
                switch (kind) {
                    case FOLDER -> folder();
                    case VIDEO -> fileVideo();
                    case AUDIO -> fileAudio();
                    case IMAGE -> fileImage();
                    case BOOK -> book();
                    case DOCUMENT -> files();
                    case SUBTITLE -> subtitles();
                    case ARCHIVE -> fileArchive();
                    case DISC -> disc();
                    case DATA -> database();
                    case PROGRAM -> box();
                    case OTHER -> file();
                };
        return g.at(MENU_SIZE);
    }
}
