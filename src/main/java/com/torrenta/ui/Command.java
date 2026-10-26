package com.torrenta.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

import org.jspecify.annotations.Nullable;

/**
 * One action, defined once and used three times: as a menu item, as a context-menu item, and as a
 * palette entry. A chord reaches the palette by being on the command, so the three cannot drift.
 *
 * @param id stable name, for the capture hook and the tests
 * @param label menu text
 * @param accelerator the chord registered on the menu item, or null
 * @param hint the chord as shown when it is not an accelerator (Space, Delete: keys that must not
 *     fire from a text field, so the table handles them itself); the menus show it all the same
 * @param enabled when the item is live; a selection-bound action is disabled with nothing selected
 * @param run what to do, on the FX thread; long work goes to a worker inside
 */
public record Command(
        String id,
        String label,
        @Nullable KeyCombination accelerator,
        String hint,
        ObservableBooleanValue enabled,
        Runnable run) {

    public Command {
        // Code outside the null checking (the tests) may still pass none.
        if (hint == null) hint = accelerator == null ? "" : accelerator.getDisplayText();
    }

    /** With the accelerator's own text as the hint, or none. */
    public Command(
            String id,
            String label,
            @Nullable KeyCombination accelerator,
            ObservableBooleanValue enabled,
            Runnable run) {
        this(id, label, accelerator, accelerator == null ? "" : accelerator.getDisplayText(), enabled, run);
    }

    /**
     * What the menu item's accelerator column shows: the chord itself, or a {@link HintCombination}
     * that names the key without ever firing it, or null for a command with neither.
     */
    @Nullable
    KeyCombination menuAccelerator() {
        if (accelerator != null) return accelerator;
        return hint.isEmpty() ? null : new HintCombination(hint);
    }

    /**
     * This command on {@code chord}: the user's binding laid over the default it was built with
     * ({@code this}). A command a handler of the window's own fires ({@code byScene}: a scene
     * accelerator or key filter) carries no accelerator and shows the chord as its hint; any other
     * carries the chord as its accelerator. With no chord, a key the list handles itself (Space,
     * Delete) is still shown, and a moved accelerator leaves nothing.
     */
    Command rebound(@Nullable KeyCombination chord, boolean byScene) {
        if (byScene) return new Command(id, label, null, chord == null ? "" : chord.getDisplayText(), enabled, run);
        if (chord != null) return new Command(id, label, chord, chord.getDisplayText(), enabled, run);
        return new Command(id, label, null, accelerator != null ? "" : hint, enabled, run);
    }

    /** A menu item wired to this command; one per menu, since an item has one parent. */
    public MenuItem menuItem() {
        MenuItem item = new MenuItem(label);
        item.setAccelerator(menuAccelerator());
        item.disableProperty().bind(Bindings.not(enabled));
        item.setOnAction(e -> run.run());
        return item;
    }
}
