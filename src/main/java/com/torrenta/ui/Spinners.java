package com.torrenta.ui;

import javafx.scene.control.Spinner;

/** What every editable spinner in the interface needs, in one place. */
final class Spinners {

    private Spinners() {}

    /**
     * A typed value commits on focus loss as well as Enter, or a number typed before Tab (or a
     * click elsewhere) is dropped and the old one stays. Text that is not a number puts the value
     * back rather than throwing on the FX thread. Returns the spinner, for chaining.
     */
    static <T> Spinner<T> commitOnBlur(Spinner<T> spinner) {
        spinner.focusedProperty().addListener((o, was, now) -> {
            if (now) return;
            try {
                spinner.commitValue();
            } catch (RuntimeException notANumber) {
                spinner.cancelEdit();
            }
        });
        return spinner;
    }
}
