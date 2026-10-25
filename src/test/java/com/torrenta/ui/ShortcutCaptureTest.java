package com.torrenta.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import com.torrenta.ui.ShortcutCapture.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What the settings window's chord-recording field makes of a press. */
class ShortcutCaptureTest {

    @Test
    void aModifierOnItsOwnIsWaitedOut() {
        assertEquals(Verdict.IGNORE, ShortcutCapture.decide(KeyCode.CONTROL, true, false, false));
        assertEquals(Verdict.IGNORE, ShortcutCapture.decide(KeyCode.ALT, false, true, false));
        assertEquals(Verdict.IGNORE, ShortcutCapture.decide(KeyCode.SHIFT, false, false, false));
        assertEquals(Verdict.IGNORE, ShortcutCapture.decide(KeyCode.UNDEFINED, false, false, false));
        assertEquals(Verdict.IGNORE, ShortcutCapture.decide(null, false, false, false));
    }

    @Test
    void aBareKeyIsRefusedBecauseTextFieldsOwnIt() {
        assertEquals(Verdict.NEEDS_MODIFIER, ShortcutCapture.decide(KeyCode.X, false, false, false));
        assertEquals(Verdict.NEEDS_MODIFIER, ShortcutCapture.decide(KeyCode.DIGIT1, false, false, false));
        assertEquals(Verdict.NEEDS_MODIFIER, ShortcutCapture.decide(KeyCode.ENTER, false, false, false));
    }

    @Test
    void shiftAloneIsNotAModifier() {
        // Shift+X is a capital letter, which the filter box wants as much as the lower-case one.
        assertNull(ShortcutCapture.combination(KeyCode.X, false, false, false, true));
    }

    @Test
    void aChordWithControlAltOrCommandIsAccepted() {
        assertEquals(Verdict.OK, ShortcutCapture.decide(KeyCode.X, false, true, false));
        assertEquals(Verdict.OK, ShortcutCapture.decide(KeyCode.P, true, false, false));
        assertEquals(Verdict.OK, ShortcutCapture.decide(KeyCode.P, false, false, true));
    }

    @Test
    void aFunctionKeyIsAChordOnItsOwn() {
        assertEquals(Verdict.OK, ShortcutCapture.decide(KeyCode.F1, false, false, false));
        assertEquals(
                new KeyCodeCombination(KeyCode.F1),
                ShortcutCapture.combination(KeyCode.F1, false, false, false, false));
    }

    @Test
    void theCombinationCarriesExactlyTheModifiersHeld() {
        assertEquals(
                new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN),
                ShortcutCapture.combination(KeyCode.X, false, true, false, false));
        assertEquals(
                new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                ShortcutCapture.combination(KeyCode.P, true, false, false, true));
        assertNull(ShortcutCapture.combination(KeyCode.CONTROL, true, false, false, false));
    }

    @Test
    void theRecordedChordSurvivesTheSettingsFile() {
        // Stored by name and read back by valueOf: the two notations have to agree, or the chord
        // recorded would not be the chord bound.
        KeyCombination recorded = ShortcutCapture.combination(KeyCode.X, true, true, false, true);
        assertEquals(recorded, Keybindings.parse(Keybindings.stored(recorded)));
    }

    @Test
    void selectAllIsFixedAndNoRowMayTakeIt() {
        assertEquals(
                "select-all",
                ShortcutCapture.fixedOwner(new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN), false));
        assertEquals(
                "select-all",
                ShortcutCapture.fixedOwner(new KeyCodeCombination(KeyCode.A, KeyCombination.META_DOWN), true));
        assertNull(ShortcutCapture.fixedOwner(new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN), true));
        assertNull(ShortcutCapture.fixedOwner(Chords.of("recheck"), false));
    }
}
