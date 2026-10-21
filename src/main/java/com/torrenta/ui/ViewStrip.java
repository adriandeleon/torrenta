package com.torrenta.ui;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;

/**
 * The thin row above the centre that says which view is showing and takes the mouse to another:
 * one entry per {@link View}, its name and the chord that reaches it as a key badge, the current
 * one marked {@code :selected}. Never focus-traversable (the chrome's rule): the chords and the
 * View menu are the keyboard's way, and the strip is the indicator.
 */
final class ViewStrip extends HBox {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Map<View, HBox> entries = new EnumMap<>(View.class);

    /** @param onChoose told of an entry clicked, on the FX thread */
    ViewStrip(Consumer<View> onChoose) {
        getStyleClass().add("view-strip");
        setAlignment(Pos.CENTER_LEFT);
        setFocusTraversable(false);
        for (View v : View.values()) {
            Label name = new Label(v.label());
            name.getStyleClass().add("view-tab-name");
            HBox entry = new HBox(name);
            entry.getStyleClass().add("view-tab");
            entry.setAlignment(Pos.CENTER_LEFT);
            entry.setFocusTraversable(false);
            entry.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) onChoose.accept(v);
            });
            entries.put(v, entry);
            getChildren().add(entry);
        }
        select(View.TRANSFERS);
    }

    /**
     * Put the key that shows each view beside its name, as the menus print it; none where the
     * function gives an empty string. Once, after the commands exist.
     */
    void showChords(Function<View, String> chord) {
        entries.forEach((v, entry) -> {
            String key = chord.apply(v);
            if (key.isEmpty()) return;
            Label badge = new Label(key);
            badge.getStyleClass().add("key-badge");
            entry.getChildren().add(badge);
        });
    }

    /** Mark this view as the one showing; the others lose the mark. */
    void select(View view) {
        entries.forEach((v, entry) -> entry.pseudoClassStateChanged(SELECTED, v == view));
    }

    /** The strip, left to right, the chosen entry starred: "*Transfers Esc | Search Ctrl+Shift+F | …". */
    String describe() {
        StringBuilder out = new StringBuilder();
        entries.forEach((v, entry) -> {
            if (!out.isEmpty()) out.append(" | ");
            if (entry.getPseudoClassStates().contains(SELECTED)) out.append('*');
            entry.getChildren().forEach(n -> {
                if (n != entry.getChildren().getFirst()) out.append(' ');
                out.append(((Label) n).getText());
            });
        });
        return out.toString();
    }
}
