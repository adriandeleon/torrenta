package com.torrenta.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * Every chord the main window binds <em>by default</em>, in one table: under the command's id where
 * it is a command's accelerator or the hint a command shows, under a name of its own for a scene
 * accelerator no command carries. The user's key bindings are laid over it ({@link Keybindings});
 * the window reads the result, so a key cannot be handed out twice without {@code ChordsTest}
 * seeing it: Ctrl+Shift+D was once both Set Location and the details pane, and only one of them
 * ever fired.
 */
final class Chords {

    /** The menu each entry is listed under on the Keyboard page: {@code file}, {@code torrent}, {@code view}. */
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();

    private static final Map<String, KeyCombination> TABLE = table();

    /**
     * Chords the window answers to that no binding can move: Select All is the list's own Ctrl+A
     * (TableView's behaviour, and every text field's), so a row for it would move nothing.
     */
    private static final Map<String, KeyCombination> FIXED = Map.of("select-all", TABLE.get("select-all"));

    private Chords() {}

    private static Map<String, KeyCombination> table() {
        Map<String, KeyCombination> t = new LinkedHashMap<>();
        // File
        t.put("create", chord(KeyCode.N));
        t.put("add-file", chord(KeyCode.O));
        t.put("add-magnet", chord(KeyCode.M));
        t.put("add-clipboard", chord(KeyCode.V));
        t.put("quit", chord(KeyCode.Q));
        t.put("settings", chord(KeyCode.COMMA));
        listUnder(t, "file");
        // Torrent. Undo is a filter of the scene's, like Ctrl+Tab: a text field keeps Ctrl+Z for
        // its own undo.
        t.put("undo", chord(KeyCode.Z));
        t.put("force-resume", shiftChord(KeyCode.U));
        t.put("recheck", shiftChord(KeyCode.R));
        t.put("reannounce", shiftChord(KeyCode.A));
        t.put("sequential", shiftChord(KeyCode.S));
        t.put("limits", chord(KeyCode.L));
        t.put("queue-top", shiftChord(KeyCode.HOME));
        t.put("queue-up", shiftChord(KeyCode.UP));
        t.put("queue-down", shiftChord(KeyCode.DOWN));
        t.put("queue-bottom", shiftChord(KeyCode.END));
        t.put("set-location", shiftChord(KeyCode.D));
        t.put("set-category", shiftChord(KeyCode.G));
        t.put("edit-tags", chord(KeyCode.T));
        t.put("copy-name", chord(KeyCode.C));
        t.put("copy-hash", shiftChord(KeyCode.H));
        t.put("copy-magnet", shiftChord(KeyCode.C));
        t.put("export-torrent", shiftChord(KeyCode.E));
        t.put("open-folder", shiftChord(KeyCode.O));
        t.put("alt-speed", shiftChord(KeyCode.J));
        listUnder(t, "torrent");
        // View. The palette's Alt+X is Editora's M-x; the first releases offered Shift+Shortcut+P
        // too, which an old settings file still reads as (Settings.keyBindings).
        t.put("palette", new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN));
        t.put("search", shiftChord(KeyCode.F));
        t.put("rss", chord(KeyCode.R));
        for (View v : View.values()) t.put(viewName(v), chord(KeyCode.valueOf("DIGIT" + v.number())));
        // Control on macOS too, where Cmd+Tab belongs to the system.
        t.put("view-next", new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN));
        t.put(
                "view-previous",
                new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        t.put("filter", chord(KeyCode.K));
        t.put("save-view", chord(KeyCode.S));
        // The first nine saved views. A filter of the scene's, not accelerators: Ctrl+Alt is AltGr
        // on many layouts, and AltGr+2 types an @ that a text field must keep.
        for (int n = 1; n <= 9; n++)
            t.put(
                    "saved-view-" + n,
                    new KeyCodeCombination(
                            KeyCode.valueOf("DIGIT" + n), KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        t.put("filter-find", chord(KeyCode.F));
        t.put("select-all", chord(KeyCode.A));
        t.put("event-log", shiftChord(KeyCode.L));
        t.put("statistics", shiftChord(KeyCode.T));
        t.put("message-log", shiftChord(KeyCode.M));
        t.put("toggle-sidebar", shiftChord(KeyCode.B));
        t.put("focus-sidebar", chord(KeyCode.B));
        t.put("toggle-detail", chord(KeyCode.I));
        listUnder(t, "view");
        return Collections.unmodifiableMap(t);
    }

    /** Puts every entry of {@code t} that has no group yet under {@code group}. */
    private static void listUnder(Map<String, KeyCombination> t, String group) {
        for (String name : t.keySet()) GROUPS.putIfAbsent(name, group);
    }

    /** The chord named, a command's id or one of the table's own names; a typo fails at once. */
    static KeyCombination of(String name) {
        KeyCombination c = TABLE.get(name);
        if (c == null) throw new IllegalArgumentException("No chord is named " + name);
        return c;
    }

    /** Shortcut and the view's place on the strip: Ctrl+1 for Transfers. */
    static KeyCombination view(View v) {
        return of(viewName(v));
    }

    /** The whole table, in the menus' order, for the test that no key is in it twice. */
    static Map<String, KeyCombination> all() {
        return TABLE;
    }

    /** What the Keyboard page can move: the table without {@link #fixed}, in the menus' order. */
    static Map<String, KeyCombination> rebindable() {
        Map<String, KeyCombination> out = new LinkedHashMap<>(TABLE);
        out.keySet().removeAll(FIXED.keySet());
        return Collections.unmodifiableMap(out);
    }

    /** The chords no binding moves, by name. */
    static Map<String, KeyCombination> fixed() {
        return FIXED;
    }

    /** The menu an entry is listed under ({@code file}, {@code torrent}, {@code view}), or null for no entry. */
    static String group(String name) {
        return GROUPS.get(name);
    }

    /** The table's name for the chord that shows view {@code v} by its place: {@code view-1}. */
    static String viewName(View v) {
        return "view-" + v.number();
    }

    private static KeyCombination chord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    private static KeyCombination shiftChord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }
}
