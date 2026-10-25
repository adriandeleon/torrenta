package com.torrenta.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import com.torrenta.store.Settings;

/**
 * The chord each command is on: its default ({@link Chords}) unless the user's key bindings say
 * otherwise. Termina's, with one addition: a chord is stored with {@code Shortcut} for the
 * platform's own command key ({@link #portable}), because the settings sync carries the lines to
 * other machines, and a {@code Ctrl+Shift+K} recorded on Linux would be Control, not Command, on a
 * Mac.
 *
 * <p>Resolved as a whole table rather than per command, because the one rule that matters is
 * between commands: a chord answers to one of them. The Keyboard page keeps the stored table
 * consistent on the way in ({@link KeybindingEdits}); this is the backstop for a hand-edited or
 * merged file, and like every other read of that file it clamps rather than throws.
 *
 * <p>Pure, and the platform is a parameter, for the usual reason.
 */
final class Keybindings {

    private Keybindings() {}

    /** Read once: what Shortcut means. */
    static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /**
     * One rebindable command, as the Keyboard page lists it.
     *
     * @param id what the settings file calls it ({@code keys.<id>})
     * @param group the heading it is listed under, the menu it lives in
     * @param label what it does, as the menu says it
     * @param defaultChord the chord it has unless the user says otherwise, or null for none
     */
    record Command(String id, String group, String label, KeyCombination defaultChord) {}

    /** The defaults of a command list, by id, in its order: what {@link #resolve} takes. */
    static Map<String, KeyCombination> defaults(List<Command> commands) {
        Map<String, KeyCombination> defaults = new LinkedHashMap<>();
        for (Command command : commands) defaults.put(command.id(), command.defaultChord());
        return defaults;
    }

    /**
     * The chord every command is on, in table order; a null value is a command with none.
     *
     * <p>Two passes, so that the user's choice beats a default whatever order the table is in. First
     * every stored chord that parses is placed, the earlier of two on the same chord keeping it and
     * the later falling back to its default. Then every remaining command gets its default if that
     * is still free, and none if a stored chord took it: the user moved a chord onto another
     * command, so this one has lost it, which is what the page's "takes it over" means too.
     *
     * @param defaults command id to its default chord (null for a command with none), in table order
     * @param stored command id to what the settings file says: a chord, or {@link Settings#UNBOUND}.
     *     Ids that name no command are ignored and kept in the file: a binding for a command a later
     *     version drops is not this read's to delete.
     */
    static Map<String, KeyCombination> resolve(
            Map<String, KeyCombination> defaults, Map<String, String> stored, boolean mac) {
        Map<String, KeyCombination> placed = new LinkedHashMap<>();
        Set<KeyCombination> taken = new HashSet<>();
        Set<String> decided = new HashSet<>();
        for (String id : defaults.keySet()) {
            String text = stored.get(id);
            if (text == null || text.isBlank()) continue;
            if (Settings.UNBOUND.equalsIgnoreCase(text.trim())) {
                placed.put(id, null);
                decided.add(id);
                continue;
            }
            KeyCombination chord = parse(text);
            if (chord == null || !taken.add(normalize(chord, mac))) continue;
            placed.put(id, chord);
            decided.add(id);
        }
        Map<String, KeyCombination> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, KeyCombination> entry : defaults.entrySet()) {
            String id = entry.getKey();
            if (decided.contains(id)) {
                ordered.put(id, placed.get(id));
                continue;
            }
            KeyCombination fallback = entry.getValue();
            ordered.put(id, fallback != null && taken.add(normalize(fallback, mac)) ? fallback : null);
        }
        return ordered;
    }

    /**
     * A stored chord, or null when the line names none this table can bind.
     *
     * <p>Only a key-code chord: {@link KeyCombination#valueOf} reads an unrecognised key name as a
     * <em>character</em> combination, which matches on what a key types rather than which key it
     * is, so it could never be compared with what the recorder writes.
     */
    static KeyCombination parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            KeyCombination chord = KeyCombination.valueOf(text.trim());
            return chord instanceof KeyCodeCombination ? chord : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code chord} with Shortcut spelled out as the key it is on this platform.
     *
     * <p>{@code KeyCombination.equals} compares the modifiers as written, so the defaults' {@code
     * Shortcut+Shift+T} and a hand-written {@code Ctrl+Shift+T} are unequal while matching the same
     * keystroke on Linux: two commands on one chord, and nothing to say so. Every comparison goes
     * through here.
     */
    static KeyCombination normalize(KeyCombination chord, boolean mac) {
        if (!(chord instanceof KeyCodeCombination code)) return chord;
        boolean shortcut = code.getShortcut() == KeyCombination.ModifierValue.DOWN;
        boolean control = code.getControl() == KeyCombination.ModifierValue.DOWN || (shortcut && !mac);
        boolean meta = code.getMeta() == KeyCombination.ModifierValue.DOWN || (shortcut && mac);
        return of(code.getCode(), control, code.getAlt() == KeyCombination.ModifierValue.DOWN, meta, shift(code));
    }

    /**
     * {@code chord} with this platform's command key written as Shortcut: Ctrl off macOS, Cmd on it.
     * What a recorded chord is stored as, so the line means the same key on every machine it syncs
     * to. The other of the two stays as pressed: Ctrl+Cmd+K on a Mac is Shortcut+Ctrl+K.
     */
    static KeyCombination portable(KeyCombination chord, boolean mac) {
        if (!(normalize(chord, mac) instanceof KeyCodeCombination code)) return chord;
        boolean control = code.getControl() == KeyCombination.ModifierValue.DOWN;
        boolean meta = code.getMeta() == KeyCombination.ModifierValue.DOWN;
        boolean shortcut = mac ? meta : control;
        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        if (shortcut) modifiers.add(KeyCombination.SHORTCUT_DOWN);
        if (control && mac) modifiers.add(KeyCombination.CONTROL_DOWN);
        if (code.getAlt() == KeyCombination.ModifierValue.DOWN) modifiers.add(KeyCombination.ALT_DOWN);
        if (meta && !mac) modifiers.add(KeyCombination.META_DOWN);
        if (shift(code)) modifiers.add(KeyCombination.SHIFT_DOWN);
        return new KeyCodeCombination(code.getCode(), modifiers.toArray(KeyCombination.Modifier[]::new));
    }

    /** Whether two chords answer to the same keystroke on this platform. Null is no chord, and equals nothing. */
    static boolean same(KeyCombination a, KeyCombination b, boolean mac) {
        return a != null && b != null && normalize(a, mac).equals(normalize(b, mac));
    }

    /**
     * What the settings file stores for a chord, which {@link #parse} reads back: the modifiers in
     * the order people write them, {@code Shortcut+Shift+K}. {@code getName()} would do, but spells
     * it {@code Shift+Shortcut+K}, and this is a line someone will read in a text editor.
     */
    static String stored(KeyCombination chord) {
        if (!(chord instanceof KeyCodeCombination code)) return chord.getName();
        StringBuilder out = new StringBuilder();
        if (code.getShortcut() == KeyCombination.ModifierValue.DOWN) out.append("Shortcut+");
        if (code.getControl() == KeyCombination.ModifierValue.DOWN) out.append("Ctrl+");
        if (code.getAlt() == KeyCombination.ModifierValue.DOWN) out.append("Alt+");
        if (code.getMeta() == KeyCombination.ModifierValue.DOWN) out.append("Meta+");
        if (shift(code)) out.append("Shift+");
        return out.append(code.getCode().getName()).toString();
    }

    static KeyCodeCombination of(KeyCode code, boolean control, boolean alt, boolean meta, boolean shift) {
        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        if (control) modifiers.add(KeyCombination.CONTROL_DOWN);
        if (alt) modifiers.add(KeyCombination.ALT_DOWN);
        if (meta) modifiers.add(KeyCombination.META_DOWN);
        if (shift) modifiers.add(KeyCombination.SHIFT_DOWN);
        return new KeyCodeCombination(code, modifiers.toArray(KeyCombination.Modifier[]::new));
    }

    private static boolean shift(KeyCodeCombination code) {
        return code.getShift() == KeyCombination.ModifierValue.DOWN;
    }
}
