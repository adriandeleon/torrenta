package com.torrenta.ui;

import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static com.torrenta.ui.KeybindingsTest.LIMITS;
import static com.torrenta.ui.KeybindingsTest.RECHECK;
import static com.torrenta.ui.KeybindingsTest.ctrl;
import static com.torrenta.ui.KeybindingsTest.ctrlShift;
import static com.torrenta.ui.KeybindingsTest.defaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the keyboard settings store for a rebind, a clear and a reset. */
class KeybindingEditsTest {

    private static Map<String, KeyCombination> on(Map<String, String> stored) {
        return Keybindings.resolve(defaults(), stored, false);
    }

    @Test
    void aRebindToAFreeChordStoresIt() {
        Map<String, String> stored =
                KeybindingEdits.rebind(defaults(), Map.of(), "recheck", ctrlShift(KeyCode.Y), false);
        assertEquals(Map.of("recheck", "Shortcut+Shift+Y"), stored, "Ctrl is written as Shortcut, to sync");
        assertTrue(Keybindings.same(ctrlShift(KeyCode.Y), on(stored).get("recheck"), false));
    }

    @Test
    void aRebindTakesTheChordFromTheCommandThatHadIt() {
        Map<String, String> stored =
                KeybindingEdits.rebind(defaults(), Map.of(), "limits", ctrlShift(KeyCode.R), false);
        assertEquals("none", stored.get("recheck"), "Recheck lost its chord, and the file says so");
        assertTrue(Keybindings.same(RECHECK, on(stored).get("limits"), false));
        assertNull(on(stored).get("recheck"));
    }

    @Test
    void aRebindBackToTheDefaultLeavesNoLine() {
        // A command moved back to where it started reads as unchanged, not as overridden with its
        // own default — which would stop following the default if a later version moved it.
        Map<String, String> moved =
                KeybindingEdits.rebind(defaults(), Map.of(), "recheck", ctrlShift(KeyCode.Y), false);
        Map<String, String> back = KeybindingEdits.rebind(defaults(), moved, "recheck", ctrlShift(KeyCode.R), false);
        assertEquals(Map.of(), back);
    }

    @Test
    void aRebindOnAMacWritesCommandAsShortcut() {
        KeyCombination cmdShiftY =
                new KeyCodeCombination(KeyCode.Y, KeyCombination.META_DOWN, KeyCombination.SHIFT_DOWN);
        assertEquals(
                Map.of("recheck", "Shortcut+Shift+Y"),
                KeybindingEdits.rebind(defaults(), Map.of(), "recheck", cmdShiftY, true));
        KeyCombination ctrlY = new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN);
        assertEquals(
                Map.of("recheck", "Ctrl+Y"),
                KeybindingEdits.rebind(defaults(), Map.of(), "recheck", ctrlY, true),
                "Control on a Mac is not its command key, and stays Control");
    }

    @Test
    void aRebindToNothingIsAClear() {
        assertEquals(Map.of("recheck", "none"), KeybindingEdits.rebind(defaults(), Map.of(), "recheck", null, false));
    }

    @Test
    void clearingACommandWithADefaultStoresNone() {
        Map<String, String> stored = KeybindingEdits.clear(defaults(), Map.of(), "limits");
        assertEquals(Map.of("limits", "none"), stored);
        assertNull(on(stored).get("limits"));
    }

    @Test
    void clearingACommandWithNoDefaultLeavesNoLine() {
        Map<String, String> given = Map.of("about", "Ctrl+Shift+Y");
        assertEquals(Map.of(), KeybindingEdits.clear(defaults(), given, "about"));
    }

    @Test
    void resetDropsTheLine() {
        Map<String, String> moved = Map.of("recheck", "Ctrl+Shift+Y");
        assertEquals(Map.of(), KeybindingEdits.reset(defaults(), moved, "recheck", false));
    }

    @Test
    void resetTakesTheDefaultBackFromWhoeverHasIt() {
        // Limits took Recheck's chord. Resetting Recheck without taking it back left Recheck
        // unbound under a Reset button that said it had been reset — Editora shipped that once.
        Map<String, String> taken = KeybindingEdits.rebind(defaults(), Map.of(), "limits", ctrlShift(KeyCode.R), false);
        assertEquals("limits", KeybindingEdits.taken(defaults(), taken, "recheck", false));
        Map<String, String> reset = KeybindingEdits.reset(defaults(), taken, "recheck", false);
        assertTrue(Keybindings.same(RECHECK, on(reset).get("recheck"), false));
        assertNull(on(reset).get("limits"));
    }

    @Test
    void nothingIsTakenWhenTheDefaultIsFree() {
        assertNull(KeybindingEdits.taken(defaults(), Map.of(), "recheck", false));
        assertNull(KeybindingEdits.taken(defaults(), Map.of(), "about", false), "no default, nothing to take");
    }

    @Test
    void aConflictNamesTheOtherCommandNeverTheSameOne() {
        Map<String, KeyCombination> on = on(Map.of());
        assertEquals("limits", KeybindingEdits.conflict(on, ctrl(KeyCode.L), "recheck", false));
        assertNull(KeybindingEdits.conflict(on, ctrl(KeyCode.L), "limits", false));
        assertNull(KeybindingEdits.conflict(on, ctrlShift(KeyCode.Y), "recheck", false));
    }

    @Test
    void changedMeansAnywhereButTheDefault() {
        assertFalse(KeybindingEdits.isChanged(defaults(), on(Map.of()), "recheck", false));
        assertFalse(KeybindingEdits.isChanged(defaults(), on(Map.of()), "about", false));
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("recheck", "none")), "recheck", false));
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("about", "Alt+R")), "about", false));
        // Lost to another command counts: it is not on its default any more.
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("limits", "Ctrl+Shift+R")), "recheck", false));
    }

    @Test
    void theDefaultSpelledAnotherWayIsNotAChange() {
        // A hand-edited Ctrl+L is Limits's Shortcut+L off macOS, and its Reset button would do
        // nothing.
        Map<String, KeyCombination> on = on(Map.of("limits", "Ctrl+L"));
        assertTrue(Keybindings.same(LIMITS, on.get("limits"), false));
        assertFalse(KeybindingEdits.isChanged(defaults(), on, "limits", false));
    }
}
