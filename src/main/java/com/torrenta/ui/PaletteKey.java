package com.torrenta.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * The chord that opens the command palette: Editora's {@code M-x} (Alt+X) by default, or the
 * {@code Shortcut+Shift+P} of the first releases for the hands that learnt it. The choice is
 * {@code ui.paletteKey}; the menu item, the scene accelerator, the toolbar tooltip and the
 * palette's own chip all read the one chord, so none of them can disagree.
 */
public enum PaletteKey {
    ALT_X("alt-x", new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN)),
    SHORTCUT_SHIFT_P(
            "shortcut-shift-p",
            new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));

    /** The first-run chord, and the fallback for a value the settings file holds that is not one. */
    public static final PaletteKey DEFAULT = ALT_X;

    private final String id;
    private final KeyCombination chord;

    PaletteKey(String id, KeyCombination chord) {
        this.id = id;
        this.chord = chord;
    }

    public String id() {
        return id;
    }

    public KeyCombination chord() {
        return chord;
    }

    public static PaletteKey byId(String id, PaletteKey fallback) {
        if (id != null) {
            for (PaletteKey k : values()) if (k.id.equalsIgnoreCase(id.trim())) return k;
        }
        return fallback;
    }

    /** The chord as the platform writes it ({@code Alt+X}, {@code ⌥X}): the choice box's text. */
    @Override
    public String toString() {
        return chord.getDisplayText();
    }
}
