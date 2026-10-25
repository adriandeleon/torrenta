package com.torrenta.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A command laid on the chord the user's bindings give it. */
class CommandReboundTest {

    private static final KeyCombination CTRL_K = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
    private static final Runnable RUN = () -> {};

    private static Command command(KeyCombination accelerator, String hint) {
        return new Command("id", "Label", accelerator, hint, new SimpleBooleanProperty(true), RUN);
    }

    @Test
    void anAcceleratedCommandMovesItsAccelerator() {
        Command moved = command(Chords.of("recheck"), null).rebound(CTRL_K, false);
        assertEquals(CTRL_K, moved.accelerator());
        assertEquals(CTRL_K.getDisplayText(), moved.hint());
        assertSame(RUN, moved.run(), "the same action, only the chord moves");
    }

    @Test
    void anAcceleratedCommandLeftWithNoneShowsNothing() {
        Command none = command(Chords.of("recheck"), null).rebound(null, false);
        assertNull(none.accelerator());
        assertEquals("", none.hint());
        assertNull(none.menuAccelerator());
    }

    @Test
    void aSceneHandledCommandKeepsItsChordAsAHintOnly() {
        // The filter's Ctrl+K is the scene's accelerator; a menu item carrying it too would be a
        // second owner of the key.
        Command filter = command(null, "Ctrl+K").rebound(CTRL_K, true);
        assertNull(filter.accelerator());
        assertEquals(CTRL_K.getDisplayText(), filter.hint());
        assertEquals("", command(null, "Ctrl+K").rebound(null, true).hint());
    }

    @Test
    void aKeyTheListHandlesStaysShownUntilAChordIsGiven() {
        Command space = command(null, "Space");
        assertEquals("Space", space.rebound(null, false).hint());
        Command given = space.rebound(CTRL_K, false);
        assertEquals(CTRL_K, given.accelerator());
        assertEquals(CTRL_K.getDisplayText(), given.hint());
    }
}
