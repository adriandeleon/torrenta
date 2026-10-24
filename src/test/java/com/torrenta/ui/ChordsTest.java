package com.torrenta.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChordsTest {

    /**
     * No key is handed out twice. Compared by the text the platform shows, so Shortcut and Control
     * meet where they are the same key (off macOS); a scene holds one action per key, and the
     * second of two never fires.
     */
    @Test
    void noTwoActionsShareAChord() {
        Map<String, String> owner = new HashMap<>();
        Chords.all().forEach((name, chord) -> {
            String other = owner.putIfAbsent(chord.getDisplayText(), name);
            assertNull(other, chord.getDisplayText() + " is both " + other + " and " + name);
        });
    }

    /** Either palette key can be chosen in Settings, so neither may be a chord of the table's. */
    @Test
    void neitherPaletteKeyIsTaken() {
        List<String> taken = Chords.all().values().stream()
                .map(KeyCombination::getDisplayText)
                .toList();
        for (PaletteKey k : PaletteKey.values()) {
            assertFalse(taken.contains(k.chord().getDisplayText()), k + " is taken");
        }
    }

    @Test
    void setLocationKeepsItsChordAndTheDetailsPaneHasItsOwn() {
        assertEquals(KeyCombination.keyCombination("Shortcut+Shift+D"), Chords.of("set-location"));
        assertEquals(KeyCombination.keyCombination("Shortcut+I"), Chords.of("toggle-detail"));
    }

    @Test
    void everyViewHasItsDigit() {
        for (View v : View.values()) {
            assertEquals(KeyCombination.keyCombination("Shortcut+" + v.number()), Chords.view(v));
        }
    }

    @Test
    void anUnknownNameFailsAtOnce() {
        assertThrows(IllegalArgumentException.class, () -> Chords.of("no-such-command"));
    }
}
