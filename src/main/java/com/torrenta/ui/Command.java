package com.torrenta.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

/**
 * One action, defined once and used three times: as a menu item, as a context-menu item, and as a
 * palette entry. A chord reaches the palette by being on the command, so the three cannot drift.
 *
 * @param id stable name, for the capture hook and the tests
 * @param label menu text
 * @param accelerator the chord registered on the menu item, or null
 * @param hint the chord as shown when it is not an accelerator (Space, Delete: keys that must not
 *     fire from a text field, so the table handles them itself)
 * @param enabled when the item is live; a selection-bound action is disabled with nothing selected
 * @param run what to do, on the FX thread; long work goes to a worker inside
 */
public record Command(
        String id,
        String label,
        KeyCombination accelerator,
        String hint,
        ObservableBooleanValue enabled,
        Runnable run) {

    public Command {
        if (hint == null) hint = accelerator == null ? "" : accelerator.getDisplayText();
    }

    /** A menu item wired to this command; one per menu, since an item has one parent. */
    public MenuItem menuItem() {
        MenuItem item = new MenuItem(label);
        if (accelerator != null) item.setAccelerator(accelerator);
        item.disableProperty().bind(Bindings.not(enabled));
        item.setOnAction(e -> run.run());
        return item;
    }
}
