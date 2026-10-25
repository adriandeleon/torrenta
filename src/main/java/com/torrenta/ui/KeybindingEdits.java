package com.torrenta.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.input.KeyCombination;

import com.torrenta.store.Settings;

/**
 * The Keyboard page's edits: given the defaults and the stored bindings, the stored bindings after
 * a rebind, a clear or a reset. Termina's, keyed by command ({@code keys.recheck=…}); every edit
 * returns the whole new table, so a rebind that takes a chord from another command is one write,
 * and the window never sees the chord on both.
 *
 * <p>Pure. The window persists what these return.
 */
final class KeybindingEdits {

    private KeybindingEdits() {}

    /**
     * Puts {@code id} on {@code chord}, stored as {@link Keybindings#portable}. Any other command on
     * that chord loses it and is left with none: the caller has already asked ({@link #conflict}). A
     * chord that is the command's own default is stored as no line at all, so a command rebound to
     * where it started reads as unchanged rather than as overridden with its default.
     */
    static Map<String, String> rebind(
            Map<String, KeyCombination> defaults,
            Map<String, String> stored,
            String id,
            KeyCombination chord,
            boolean mac) {
        if (chord == null) return clear(defaults, stored, id);
        Map<String, KeyCombination> effective = Keybindings.resolve(defaults, stored, mac);
        Map<String, String> next = new LinkedHashMap<>(stored);
        effective.forEach((other, on) -> {
            if (!other.equals(id) && Keybindings.same(on, chord, mac)) next.put(other, Settings.UNBOUND);
        });
        if (Keybindings.same(defaults.get(id), chord, mac)) {
            next.remove(id);
        } else {
            next.put(id, Keybindings.stored(Keybindings.portable(chord, mac)));
        }
        return next;
    }

    /** Takes {@code id} off every chord. A command with no default needs no line to say so. */
    static Map<String, String> clear(Map<String, KeyCombination> defaults, Map<String, String> stored, String id) {
        Map<String, String> next = new LinkedHashMap<>(stored);
        if (defaults.get(id) == null) {
            next.remove(id);
        } else {
            next.put(id, Settings.UNBOUND);
        }
        return next;
    }

    /**
     * Puts {@code id} back on its default, taking the chord from whichever command has it now: that
     * one is left with none, as a rebind leaves it. Without the second half a command whose chord
     * had been given away stayed unbound after Reset (Editora shipped that once). The caller asks
     * first when {@link #taken} names someone.
     */
    static Map<String, String> reset(
            Map<String, KeyCombination> defaults, Map<String, String> stored, String id, boolean mac) {
        Map<String, String> next = new LinkedHashMap<>(stored);
        next.remove(id);
        String holder = taken(defaults, stored, id, mac);
        if (holder != null) next.put(holder, Settings.UNBOUND);
        return next;
    }

    /** The command now on {@code id}'s default chord, other than {@code id} itself, or null. */
    static String taken(Map<String, KeyCombination> defaults, Map<String, String> stored, String id, boolean mac) {
        KeyCombination home = defaults.get(id);
        if (home == null) return null;
        return conflict(Keybindings.resolve(defaults, stored, mac), home, id, mac);
    }

    /**
     * The command already on {@code chord}, other than {@code id}, or null when it is free.
     *
     * @param effective what {@link Keybindings#resolve} says every command is on
     */
    static String conflict(Map<String, KeyCombination> effective, KeyCombination chord, String id, boolean mac) {
        for (Map.Entry<String, KeyCombination> entry : effective.entrySet()) {
            if (!entry.getKey().equals(id) && Keybindings.same(entry.getValue(), chord, mac)) return entry.getKey();
        }
        return null;
    }

    /** Whether {@code id} is anywhere but its default. */
    static boolean isChanged(
            Map<String, KeyCombination> defaults, Map<String, KeyCombination> effective, String id, boolean mac) {
        KeyCombination home = defaults.get(id);
        KeyCombination now = effective.get(id);
        return home == null ? now != null : !Keybindings.same(home, now, mac);
    }
}
