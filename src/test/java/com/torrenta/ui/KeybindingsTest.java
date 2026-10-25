package com.torrenta.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which chord each command ends up on, from the defaults and a hand-editable file. */
class KeybindingsTest {

    static final KeyCombination RECHECK = Chords.of("recheck");
    static final KeyCombination LIMITS = Chords.of("limits");
    static final KeyCombination PALETTE = Chords.of("palette");

    /** Recheck (Shortcut+Shift+R), Limits (Shortcut+L), the palette (Alt+X), and About, which has no chord. */
    static Map<String, KeyCombination> defaults() {
        Map<String, KeyCombination> defaults = new LinkedHashMap<>();
        defaults.put("recheck", RECHECK);
        defaults.put("limits", LIMITS);
        defaults.put("palette", PALETTE);
        defaults.put("about", null);
        return defaults;
    }

    static KeyCombination ctrl(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.CONTROL_DOWN);
    }

    static KeyCombination ctrlShift(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }

    @Test
    void nothingStoredIsTheDefaults() {
        assertEquals(defaults(), Keybindings.resolve(defaults(), Map.of(), false));
    }

    @Test
    void aStoredChordReplacesTheDefault() {
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("recheck", "Ctrl+Shift+Y"), false);
        assertEquals(ctrlShift(KeyCode.Y), on.get("recheck"));
        assertEquals(LIMITS, on.get("limits"));
    }

    @Test
    void aCommandWithNoDefaultCanBeGivenOne() {
        assertEquals(
                ctrlShift(KeyCode.Y),
                Keybindings.resolve(defaults(), Map.of("about", "Ctrl+Shift+Y"), false)
                        .get("about"));
    }

    @Test
    void noneUnbinds() {
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("recheck", "none"), false);
        assertTrue(on.containsKey("recheck"));
        assertNull(on.get("recheck"));
    }

    @Test
    void anUnreadableOrBlankLineIsTheDefault() {
        // Hand-edited: clamp on read, as every other setting does.
        Map<String, String> stored = Map.of("recheck", "Banana+X", "limits", "  ", "palette", "Ctrl+");
        assertEquals(defaults(), Keybindings.resolve(defaults(), stored, false));
    }

    @Test
    void aCharacterChordIsNotOneTheTableCanCompare() {
        // valueOf reads an unknown key name as a character combination, which matches on what a
        // key types; the recorder never writes one, so nothing could be seen to collide with it.
        assertNull(Keybindings.parse("Ctrl+'q'"));
        assertEquals(ctrlShift(KeyCode.P), Keybindings.parse("Ctrl+Shift+P"));
    }

    @Test
    void anUnknownCommandIsIgnored() {
        assertEquals(defaults(), Keybindings.resolve(defaults(), Map.of("no-such-command", "Ctrl+Shift+D"), false));
    }

    @Test
    void aStoredChordTakesADefaultFromTheCommandThatHadIt() {
        // The user put Limits on Recheck's chord: Recheck has lost it, rather than the edit
        // silently not applying because Recheck came first in the table.
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("limits", "Shortcut+Shift+R"), false);
        assertTrue(Keybindings.same(RECHECK, on.get("limits"), false));
        assertNull(on.get("recheck"));
    }

    @Test
    void twoStoredLinesOnOneChordKeepTheFirstAndTheSecondFallsBack() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("recheck", "Ctrl+Shift+Y");
        stored.put("limits", "Ctrl+Shift+Y");
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), stored, false);
        assertEquals(ctrlShift(KeyCode.Y), on.get("recheck"));
        assertEquals(LIMITS, on.get("limits"));
    }

    @Test
    void noChordEndsUpOnTwoCommands() {
        Map<String, String> stored = Map.of("palette", "Ctrl+L", "about", "Alt+X");
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), stored, false);
        java.util.Set<KeyCombination> seen = new java.util.HashSet<>();
        for (KeyCombination chord : on.values()) {
            if (chord != null) assertTrue(seen.add(Keybindings.normalize(chord, false)), on::toString);
        }
    }

    @Test
    void tableOrderIsKept() {
        assertEquals(
                java.util.List.copyOf(defaults().keySet()),
                java.util.List.copyOf(Keybindings.resolve(defaults(), Map.of("about", "Alt+R"), false)
                        .keySet()));
    }

    @Test
    void shortcutIsTheKeyItIsOnThisPlatform() {
        // The defaults say Shortcut, the recorder says Ctrl or Meta, and equals() compares them as
        // written — two commands on one keystroke, unequal.
        KeyCombination shortcut = new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN);
        KeyCombination control = new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN);
        KeyCombination meta = new KeyCodeCombination(KeyCode.T, KeyCombination.META_DOWN);
        assertFalse(shortcut.equals(control));
        assertTrue(Keybindings.same(shortcut, control, false));
        assertFalse(Keybindings.same(shortcut, meta, false));
        assertTrue(Keybindings.same(shortcut, meta, true));
        assertFalse(Keybindings.same(shortcut, control, true));
    }

    @Test
    void portableWritesThePlatformsCommandKeyAsShortcut() {
        KeyCombination shortcutK = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
        assertEquals("Shortcut+K", Keybindings.stored(Keybindings.portable(ctrl(KeyCode.K), false)));
        assertEquals(
                "Shortcut+K",
                Keybindings.stored(
                        Keybindings.portable(new KeyCodeCombination(KeyCode.K, KeyCombination.META_DOWN), true)));
        assertEquals("Shortcut+K", Keybindings.stored(Keybindings.portable(shortcutK, true)));
        assertEquals("Ctrl+K", Keybindings.stored(Keybindings.portable(ctrl(KeyCode.K), true)), "Control on a Mac");
        assertEquals(
                "Shortcut+Ctrl+Shift+K",
                Keybindings.stored(Keybindings.portable(Keybindings.of(KeyCode.K, true, false, true, true), true)));
        assertEquals(
                "Alt+Shift+F5",
                Keybindings.stored(Keybindings.portable(Keybindings.of(KeyCode.F5, false, true, false, true), false)));
        assertTrue(Keybindings.same(ctrl(KeyCode.K), Keybindings.portable(ctrl(KeyCode.K), false), false));
    }

    @Test
    void theRealTableResolvesToItselfWithNothingStored() {
        Map<String, KeyCombination> table = Chords.rebindable();
        assertEquals(table, Keybindings.resolve(table, Map.of(), false));
        assertEquals(table, Keybindings.resolve(table, Map.of(), true));
    }

    @Test
    void noChordIsTheSameAsNothing() {
        assertFalse(Keybindings.same(null, null, false));
        assertFalse(Keybindings.same(RECHECK, null, false));
    }

    @Test
    void whatIsStoredReadsBack() {
        java.util.List<KeyCombination> chords =
                new java.util.ArrayList<>(defaults().values());
        chords.add(Keybindings.of(KeyCode.F5, true, true, true, true));
        chords.add(Keybindings.of(KeyCode.OPEN_BRACKET, true, false, false, true));
        chords.add(Keybindings.of(KeyCode.PAGE_UP, false, true, false, false));
        for (KeyCombination chord : chords) {
            if (chord != null) assertEquals(chord, Keybindings.parse(Keybindings.stored(chord)), chord::getName);
        }
    }

    @Test
    void whatIsStoredReadsTheWayPeopleWriteIt() {
        assertEquals("Ctrl+Shift+Y", Keybindings.stored(ctrlShift(KeyCode.Y)));
        assertEquals("Shortcut+Shift+R", Keybindings.stored(RECHECK));
    }
}
