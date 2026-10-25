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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The first releases' second palette chord is free, so an old settings file that chose it
     * (read as {@code keys.palette}) takes no default from anyone.
     */
    @Test
    void theOldPaletteChordIsFree() {
        List<String> taken = Chords.all().values().stream()
                .map(KeyCombination::getDisplayText)
                .toList();
        assertFalse(
                taken.contains(KeyCombination.keyCombination("Shortcut+Shift+P").getDisplayText()));
        assertEquals(KeyCombination.keyCombination("Alt+X"), Chords.of("palette"), "Editora's M-x");
    }

    @Test
    void everyEntryIsListedUnderAMenu() {
        for (String name : Chords.all().keySet()) {
            assertTrue(List.of("file", "torrent", "view").contains(Chords.group(name)), name);
        }
        assertEquals("torrent", Chords.group("undo"));
        assertEquals("view", Chords.group("palette"));
    }

    @Test
    void selectAllIsFixedAndEverythingElseRebindable() {
        assertEquals(Map.of("select-all", Chords.of("select-all")), Chords.fixed());
        assertFalse(Chords.rebindable().containsKey("select-all"));
        assertEquals(Chords.all().size() - 1, Chords.rebindable().size());
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
