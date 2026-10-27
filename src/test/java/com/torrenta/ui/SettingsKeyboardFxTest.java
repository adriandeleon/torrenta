package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Keyboard page: recording a chord into a row, a chord another command has, Reset and Reset All. */
@ExtendWith(FxToolkit.class)
class SettingsKeyboardFxTest {

    private static final List<Keybindings.Command> COMMANDS = List.of(
            new Keybindings.Command("recheck", "Torrent", "Force Recheck", KeyCombination.keyCombination("Shortcut+R")),
            new Keybindings.Command("limits", "Torrent", "Limits…", null),
            new Keybindings.Command("palette", "View", "Command Palette", KeyCombination.keyCombination("Shortcut+K")));

    private static SettingsFixture open(Path dir, AtomicInteger changed) {
        SettingsFixture f = SettingsFixture.open(dir);
        Fx.run(() -> f.window.setKeyboard(COMMANDS, changed::incrementAndGet));
        f.page(SettingsWindow.Category.KEYBOARD);
        return f;
    }

    private static String press(SettingsFixture f, String spec) {
        return Fx.call(() -> f.window.pressKeysForCapture(spec));
    }

    @Test
    void aChordPressedIsStoredAndTheRowCanBeResetToItsDefault(@TempDir Path dir) {
        AtomicInteger changed = new AtomicInteger();
        SettingsFixture f = open(dir, changed);
        String said = press(f, "limits:Shortcut+Shift+L");
        assertTrue(said.contains("(reset on)"), said);
        assertEquals("Shortcut+Shift+L", f.settings.keyBindings().get("limits"));
        assertEquals(1, changed.get(), "the window moves its chords once");

        // Backspace takes the chord off; a command with no default then has nothing stored.
        said = press(f, "limits:BS");
        assertTrue(said.contains("[" + tr("settings.keys.none") + "]"), said);
        assertEquals(Map.of(), f.settings.keyBindings());
        assertEquals(2, changed.get());

        press(f, "recheck:BS");
        assertEquals("none", f.settings.keyBindings().get("recheck"), "a default taken off is stored as none");
        List<Button> resets = Fx.call(
                () -> Modals.all(f.root(), Button.class, b -> b.getText().equals(tr("settings.keys.reset"))));
        assertEquals(3, resets.size());
        Fx.run(() -> resets.getFirst().fire());
        assertEquals(Map.of(), f.settings.keyBindings(), "Reset puts recheck back on its default, nobody holding it");
    }

    @Test
    void aChordAnotherCommandHasMovesOnlyWhenPressedTwice(@TempDir Path dir) {
        SettingsFixture f = open(dir, new AtomicInteger());
        String said = press(f, "limits:Shortcut+R");
        String ctrlR = KeyCombination.keyCombination("Shortcut+R").getDisplayText();
        assertTrue(said.contains(tr("settings.keys.taken", ctrlR, "Force Recheck")), said);
        assertEquals(Map.of(), f.settings.keyBindings(), "the first press asks");

        press(f, "limits:Shortcut+R|Shortcut+R");
        assertEquals("Shortcut+R", f.settings.keyBindings().get("limits"));
        assertEquals("none", f.settings.keyBindings().get("recheck"), "recheck is left with none");

        // Reset on recheck would take the chord back from limits: it asks first.
        Button resetRecheck = Fx.call(() -> Modals.all(
                        f.root(), Button.class, b -> b.getText().equals(tr("settings.keys.reset")))
                .getFirst());
        Modals.later(resetRecheck::fire);
        Modals.answer(tr("settings.keys.reset"), false);
        assertEquals("Shortcut+R", f.settings.keyBindings().get("limits"), "a no keeps it where it is");
        Modals.later(resetRecheck::fire);
        Modals.answer(tr("settings.keys.reset"), true);
        Fx.pulse();
        assertEquals(null, f.settings.keyBindings().get("recheck"), "recheck is on its default again");
        assertEquals("none", f.settings.keyBindings().get("limits"), "and limits has none");
    }

    @Test
    void aBareKeyAndSelectAllAreRefused(@TempDir Path dir) {
        SettingsFixture f = open(dir, new AtomicInteger());
        String said = press(f, "limits:K");
        assertTrue(said.contains(tr("settings.keys.needsModifier")), said);
        said = press(f, "limits:Shortcut+A");
        String ctrlA = KeyCombination.keyCombination("Shortcut+A").getDisplayText();
        assertTrue(said.contains(tr("settings.keys.fixed", ctrlA)), said);
        said = press(f, "limits:Shortcut+");
        assertTrue(said.contains("=unreadable"), said);
        said = press(f, "nothing:Shortcut+J;limits");
        assertTrue(said.contains("nothing=no such row"), said);
        assertEquals(Map.of(), f.settings.keyBindings());
    }

    @Test
    void tabAndEscapeLeaveTheFieldAndAShiftAloneIsIgnored(@TempDir Path dir) {
        SettingsFixture f = open(dir, new AtomicInteger());
        TextField field = Fx.call(() -> Modals.first(
                        f.root(), TextField.class, t -> t.getStyleClass().contains("shortcut-capture"))
                .orElseThrow());
        for (KeyCode code : List.of(KeyCode.TAB, KeyCode.ESCAPE, KeyCode.SHIFT)) {
            KeyEvent press = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
            Fx.run(() -> Event.fireEvent(field, press));
        }
        assertEquals(Map.of(), f.settings.keyBindings());
        // Focus empties the field so its prompt shows; leaving it puts the chord back.
        Fx.run(field::requestFocus);
        Fx.pulse();
        if (Fx.call(field::isFocused)) {
            assertEquals("", Fx.call(field::getText));
            Fx.run(() -> f.window.scene().getRoot().requestFocus());
            Fx.pulse();
        }
        assertFalse(Fx.call(field::getText).isEmpty());
    }

    @Test
    void resetAllAsksAndForgetsEveryChord(@TempDir Path dir) {
        AtomicInteger changed = new AtomicInteger();
        SettingsFixture f = open(dir, changed);
        press(f, "limits:Shortcut+Shift+L");
        Button resetAll = f.button("settings.keys.resetAll");
        Modals.later(resetAll::fire);
        Modals.answer(tr("settings.keys.resetAll"), false);
        assertEquals("Shortcut+Shift+L", f.settings.keyBindings().get("limits"));
        Modals.later(resetAll::fire);
        Modals.answer(tr("settings.keys.resetAll"), true);
        Fx.pulse();
        assertEquals(Map.of(), f.settings.keyBindings());
        assertEquals(2, changed.get());
    }
}
