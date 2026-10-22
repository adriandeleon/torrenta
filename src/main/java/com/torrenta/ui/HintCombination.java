package com.torrenta.ui;

import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * A key combination that only shows itself: a menu item draws its accelerator's display text in
 * the right-hand column, and the menu bar registers that accelerator on the scene, where it would
 * fire on any unconsumed key press. This one never matches, so Space and Delete stay the table's
 * (a space typed into the filter must not pause the selection) and the menu still names them.
 */
final class HintCombination extends KeyCombination {

    private final String text;

    HintCombination(String text) {
        this.text = text;
    }

    @Override
    public boolean match(KeyEvent event) {
        return false;
    }

    @Override
    public String getDisplayText() {
        return text;
    }

    /**
     * Identity: KeyCombination's own equality compares the modifiers alone, so two hints would be
     * one key in the scene's accelerator map and a hint could stand for a real chord.
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return text;
    }
}
