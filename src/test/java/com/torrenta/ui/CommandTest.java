package com.torrenta.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CommandTest {

    private static Command command(KeyCombination accelerator, String hint) {
        return new Command("id", "Label", accelerator, hint, new SimpleBooleanProperty(true), () -> {});
    }

    private static KeyEvent press(KeyCode code, boolean shift, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }

    @Test
    void aRealChordIsTheMenuItemsAccelerator() {
        KeyCombination chord = new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN);
        Command c = command(chord, null);
        assertSame(chord, c.menuAccelerator());
        assertSame(chord, c.menuItem().getAccelerator());
    }

    /** Space and Delete are named in the menu but never fire from it: the table handles them. */
    @Test
    void aHintIsShownButNeverMatches() {
        Command c = command(null, "Space");
        KeyCombination shown = c.menuItem().getAccelerator();
        assertInstanceOf(HintCombination.class, shown);
        assertEquals("Space", shown.getDisplayText());
        assertFalse(shown.match(press(KeyCode.SPACE, false, false)));
        assertFalse(command(null, "Shift+Delete").menuAccelerator().match(press(KeyCode.DELETE, true, false)));
    }

    @Test
    void aCommandWithNeitherHasNoAccelerator() {
        assertNull(command(null, null).menuAccelerator());
        assertNull(command(null, "").menuItem().getAccelerator());
    }

    /**
     * KeyCombination's equality looks at the modifiers alone; two hints, or a hint and a real
     * chord, must stay distinct keys in the scene's accelerator map.
     */
    @Test
    void hintsAreDistinctFromEachOtherAndFromChords() {
        KeyCombination a = new HintCombination("Space");
        KeyCombination b = new HintCombination("Delete");
        assertNotEquals(a, b);
        assertNotEquals(a, new HintCombination("Space"));
        assertNotEquals(a, new KeyCodeCombination(KeyCode.SPACE));
    }
}
