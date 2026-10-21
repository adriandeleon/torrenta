package com.torrenta.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PaletteKeyTest {

    @Test
    void readsItsIdsAndFallsBack() {
        assertEquals(PaletteKey.SHORTCUT_SHIFT_P, PaletteKey.byId("shortcut-shift-p", PaletteKey.DEFAULT));
        assertEquals(PaletteKey.ALT_X, PaletteKey.byId(" Alt-X ", PaletteKey.SHORTCUT_SHIFT_P));
        assertEquals(PaletteKey.DEFAULT, PaletteKey.byId("f1", PaletteKey.DEFAULT));
        assertEquals(PaletteKey.DEFAULT, PaletteKey.byId(null, PaletteKey.DEFAULT));
        assertEquals(PaletteKey.ALT_X, PaletteKey.DEFAULT, "Editora's M-x");
    }

    @Test
    void theChordsAreTheOnesTheMenusShow() {
        assertEquals(new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN), PaletteKey.ALT_X.chord());
        assertEquals(
                new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                PaletteKey.SHORTCUT_SHIFT_P.chord());
        for (PaletteKey k : PaletteKey.values()) {
            assertEquals(k.chord().getDisplayText(), k.toString());
            assertFalse(k.toString().isBlank());
        }
    }
}
