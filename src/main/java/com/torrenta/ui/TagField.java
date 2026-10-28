package com.torrenta.ui;

import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.stage.Popup;

/**
 * A tags field that shows the tags that exist: a chip for each under the field, lit when the tag
 * is in it and toggling it when pressed, and a list of suggestions under the caret's tag as it is
 * typed (Down opens it on an empty one). The rules are {@link TagInput}'s.
 *
 * <p>The keyboard stays in the field: Down and Up move through the suggestions, Enter or Tab takes
 * one, Escape closes them; with the list shut those keys do what they do in the dialog (Enter
 * adds, Escape cancels). The chips are for the mouse and are not focus-traversable.
 */
final class TagField {

    /** About five rows of chips. */
    private static final double MAX_CHIPS_HEIGHT = 140;

    private final TextField field;
    private final FlowPane chips = new FlowPane(6, 4);
    private final ScrollPane chipsBox = new ScrollPane(chips);
    private final ListView<String> list = new ListView<>();
    private final Popup popup = new Popup();
    private List<String> existing = List.of();
    private boolean offered = true;
    /** Set while a chip writes the field, which should not open the list. */
    private boolean quiet;

    TagField(TextField field) {
        this.field = field;
        chips.getStyleClass().add("tag-chips");
        chipsBox.setFitToWidth(true);
        chipsBox.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // As tall as the chips, up to about five rows; more scroll. Never squeezed below that.
        chipsBox.setMinHeight(Region.USE_PREF_SIZE);
        chips.heightProperty()
                .addListener(
                        (o, was, now) -> chipsBox.setPrefViewportHeight(Math.min(now.doubleValue(), MAX_CHIPS_HEIGHT)));
        chipsBox.setFocusTraversable(false);
        chipsBox.getStyleClass().add("tag-chips-box");
        shown();

        list.getStyleClass().add("tag-suggestions");
        list.setFocusTraversable(false);
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) accept();
        });
        popup.getContent().add(list);
        popup.setAutoHide(true);
        Scene popupScene = popup.getScene();
        popupScene
                .getStylesheets()
                .add(TagField.class.getResource("/com/torrenta/styles/app.css").toExternalForm());
        Fonts.installUiFont(popupScene);

        field.textProperty().addListener((o, was, now) -> {
            lightChips();
            // The caret moves after the text does: read both once the edit is over.
            if (field.isFocused() && !quiet)
                Platform.runLater(() -> {
                    if (field.isFocused()) suggest(false);
                });
        });
        field.caretPositionProperty().addListener((o, was, now) -> {
            if (field.isFocused() && popup.isShowing()) suggest(false);
        });
        field.focusedProperty().addListener((o, was, now) -> {
            if (!now) popup.hide();
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
    }

    /** The chips' row, to sit under the field; hidden while no tag exists. */
    ScrollPane chips() {
        return chipsBox;
    }

    /** The tags that exist, from the store. */
    void setExisting(List<String> tags) {
        existing = List.copyOf(tags);
        chips.getChildren().clear();
        for (String tag :
                existing.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            ToggleButton chip = new ToggleButton(tag);
            chip.getStyleClass().add("tag-chip");
            chip.setFocusTraversable(false);
            chip.setOnAction(e -> {
                quiet = true;
                try {
                    field.setText(TagInput.toggle(field.getText(), tag));
                } finally {
                    quiet = false;
                }
                field.end();
                popup.hide();
            });
            chips.getChildren().add(chip);
        }
        shown();
        lightChips();
    }

    /** Whether the chips may show at all (Simple mode hides the tags); they also need a tag. */
    void setOffered(boolean on) {
        offered = on;
        shown();
    }

    /** Types {@code text} with the caret at its end and opens the list at once, as the pulse after a keystroke would. */
    void typeForCapture(String text) {
        quiet = true;
        try {
            field.setText(text);
        } finally {
            quiet = false;
        }
        field.end();
        suggest(false);
    }

    /** What the list offers now; empty while it is shut. Read by tests and the capture hook. */
    List<String> suggestions() {
        return popup.isShowing() ? List.copyOf(list.getItems()) : List.of();
    }

    /** The chips lit, in order; read by tests and the capture hook. */
    List<String> litChips() {
        return chips.getChildren().stream()
                .map(n -> (ToggleButton) n)
                .filter(ToggleButton::isSelected)
                .map(ToggleButton::getText)
                .toList();
    }

    private void key(KeyEvent e) {
        KeyCode code = e.getCode();
        if (!popup.isShowing()) {
            if (code == KeyCode.DOWN && !e.isShortcutDown()) {
                suggest(true);
                if (popup.isShowing()) e.consume();
            }
            return;
        }
        if (e.isShiftDown() || e.isShortcutDown() || e.isAltDown()) return;
        switch (code) {
            case DOWN -> move(1);
            case UP -> move(-1);
            case ENTER, TAB -> accept();
            case ESCAPE -> popup.hide();
            default -> {
                return;
            }
        }
        e.consume();
    }

    private void move(int by) {
        int size = list.getItems().size();
        int at = Math.floorMod(list.getSelectionModel().getSelectedIndex() + by, size);
        list.getSelectionModel().select(at);
        list.scrollTo(at);
    }

    private void accept() {
        String tag = list.getSelectionModel().getSelectedItem();
        popup.hide();
        if (tag == null) return;
        TagInput.Edit edit = TagInput.complete(field.getText(), field.getCaretPosition(), tag);
        field.setText(edit.text());
        field.positionCaret(edit.caret());
    }

    /** Opens, refreshes or shuts the list; an empty tag opens it only when asked ({@code always}). */
    private void suggest(boolean always) {
        String text = field.getText();
        int caret = field.getCaretPosition();
        boolean typed = !TagInput.at(text, caret).text().isEmpty();
        List<String> found = typed || always ? TagInput.suggest(text, caret, existing) : List.of();
        if (found.isEmpty()) {
            popup.hide();
            return;
        }
        list.getItems().setAll(found);
        list.getSelectionModel().select(0);
        list.setPrefHeight(Math.min(found.size(), TagInput.LIMIT) * 26 + 4);
        list.setPrefWidth(Math.max(field.getWidth(), 160));
        if (!popup.isShowing() && field.getScene() != null && field.getScene().getWindow() != null) {
            Bounds at = field.localToScreen(field.getBoundsInLocal());
            if (at != null) popup.show(field, at.getMinX(), at.getMaxY() + 2);
        }
    }

    private void lightChips() {
        String text = field.getText() == null ? "" : field.getText();
        for (var node : chips.getChildren()) {
            ToggleButton chip = (ToggleButton) node;
            chip.setSelected(TagInput.has(text, chip.getText()));
        }
    }

    private void shown() {
        boolean on = offered && !existing.isEmpty();
        chipsBox.setVisible(on);
        chipsBox.setManaged(on);
    }
}
