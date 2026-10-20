package com.torrenta.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Every command in the window, findable by typing part of its name. Carried over from Termina.
 *
 * <p>An in-scene overlay rather than a window of its own: a second Stage would take OS focus and
 * getting it back is the thing that has to work. Whatever the user does here, the next keystroke
 * belongs to the list again.
 */
final class CommandPalette {

    /** Enough rows to be useful without covering the list it is being used on. */
    private static final int VISIBLE_ROWS = 10;

    private final StackPane host;
    private final Runnable onHidden;

    private final TextField query = new TextField();
    private final ListView<Command> results = new ListView<>();
    private final VBox card = new VBox();
    private final Region backdrop = new Region();

    private List<Command> commands = List.of();

    CommandPalette(StackPane host, Runnable onHidden) {
        this.host = host;
        this.onHidden = onHidden;

        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMousePressed(e -> hide());

        query.getStyleClass().add("palette-input");
        query.setPromptText("Type a command…");
        query.textProperty().addListener((o, was, now) -> refilter(now));

        results.getStyleClass().add("palette-list");
        results.setPlaceholder(new Label("No matching command"));
        results.setCellFactory(view -> new CommandCell());
        results.setOnMousePressed(e -> {
            if (e.getClickCount() >= 2) runSelected();
        });

        card.getStyleClass().add("palette-card");
        card.getChildren().addAll(query, results);
        card.setMaxWidth(560);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(card, Pos.TOP_CENTER);

        // On the card, so it runs before the ListView's own handling of Up/Down and before the
        // TextField swallows anything. Enter and Escape would otherwise never reach us.
        card.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** The visible result labels, so a capture run can assert on the ranking rather than a picture. */
    List<String> visibleCommands() {
        return results.getItems().stream().map(Command::label).toList();
    }

    /** Types into the query field as the user would, running the same filter. */
    void setQueryForCapture(String text) {
        query.setText(text);
    }

    boolean isShowing() {
        return host.getChildren().contains(card);
    }

    /** Opens over the commands that are live right now; disabled ones are not offered. */
    void show(List<Command> commands) {
        if (isShowing()) {
            hide();
            return;
        }
        this.commands = commands.stream().filter(c -> c.enabled().get()).toList();
        query.setText("");
        refilter("");
        host.getChildren().addAll(backdrop, card);
        // The height is only knowable once the rows exist; a fixed one either wastes space on a
        // short list or scrolls a list that would have fitted.
        results.setPrefHeight(
                Math.min(VISIBLE_ROWS, Math.max(1, results.getItems().size())) * 28 + 8);
        query.requestFocus();
    }

    void hide() {
        if (!isShowing()) return;
        host.getChildren().removeAll(backdrop, card);
        onHidden.run();
    }

    private void refilter(String text) {
        results.getItems().setAll(rank(text, commands));
        if (!results.getItems().isEmpty()) results.getSelectionModel().selectFirst();
    }

    /** The commands that match, best first; ties keep the given order, which is the menus' order. */
    static List<Command> rank(String query, List<Command> commands) {
        record Scored(Command command, int score, int order) {}
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            int score = CommandMatcher.score(query, command.label());
            if (score != CommandMatcher.NO_MATCH) scored.add(new Scored(command, score, i));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
        return scored.stream().map(Scored::command).toList();
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                runSelected();
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            default -> {
                // typing: let it reach the field
            }
        }
    }

    private void move(int delta) {
        int count = results.getItems().size();
        if (count == 0) return;
        int index = results.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod(index + delta, count);
        results.getSelectionModel().select(next);
        results.scrollTo(next);
    }

    private void runSelected() {
        Command command = results.getSelectionModel().getSelectedItem();
        // Hidden first: the command may open a dialog, and the palette must not still be sitting
        // over it — or, worse, take the focus back afterwards.
        hide();
        if (command != null) command.run().run();
    }

    /** A row: the command on the left, its shortcut right-aligned, as the menus show it. */
    private static final class CommandCell extends ListCell<Command> {
        private final Label name = new Label();
        private final Label chord = new Label();
        private final HBox layout = new HBox(name, spacer(), chord);

        private static Region spacer() {
            Region region = new Region();
            HBox.setHgrow(region, Priority.ALWAYS);
            return region;
        }

        CommandCell() {
            name.getStyleClass().add("palette-name");
            chord.getStyleClass().add("palette-chord");
            layout.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Command item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            name.setText(item.label());
            chord.setText(item.hint());
            setGraphic(layout);
        }
    }
}
