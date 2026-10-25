package com.torrenta.ui;

import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * What a key pressed into the Keyboard page's recording field becomes. Termina's.
 *
 * <p>A chord is recorded by pressing it, not by spelling it, so the field decides per press whether
 * what arrived is a chord at all. A modifier on its own is the start of one and is waited out. A
 * bare letter or digit is refused: bound in the scene it would be taken from the filter box on
 * every press. Function keys are chords in their own right.
 */
final class ShortcutCapture {

    /** What a press into the recording field means. */
    enum Verdict {
        /** A modifier on its own, or a key with no code: wait for the rest. */
        IGNORE,
        /** A key a text field would own: refuse, and say why. */
        NEEDS_MODIFIER,
        /** A chord worth binding. */
        OK
    }

    private ShortcutCapture() {}

    /** Decides a press. Shift alone is not a modifier here: Shift+X is a capital letter. Pure. */
    static Verdict decide(KeyCode code, boolean control, boolean alt, boolean meta) {
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) return Verdict.IGNORE;
        if (code.isFunctionKey()) return Verdict.OK;
        return control || alt || meta ? Verdict.OK : Verdict.NEEDS_MODIFIER;
    }

    /** The chord a press names, with exactly the modifiers held; null for a press that is not one. */
    static KeyCombination combination(KeyEvent e) {
        return combination(e.getCode(), e.isControlDown(), e.isAltDown(), e.isMetaDown(), e.isShiftDown());
    }

    /** Pure form of {@link #combination(KeyEvent)}. */
    static KeyCombination combination(KeyCode code, boolean control, boolean alt, boolean meta, boolean shift) {
        if (decide(code, control, alt, meta) != Verdict.OK) return null;
        return Keybindings.of(code, control, alt, meta, shift);
    }

    /**
     * The name of the fixed key {@code chord} is, or null: a chord the window answers to that no
     * row can move ({@link Chords#fixed}), which a row may not take either, or the two would both
     * claim it and only one would ever fire.
     */
    static String fixedOwner(KeyCombination chord, boolean mac) {
        for (Map.Entry<String, KeyCombination> entry : Chords.fixed().entrySet()) {
            if (Keybindings.same(entry.getValue(), chord, mac)) return entry.getKey();
        }
        return null;
    }
}
