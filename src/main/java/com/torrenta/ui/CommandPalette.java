package com.torrenta.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import static com.torrenta.i18n.Messages.tr;

/**
 * Every command in the window, findable by typing part of its name. Editora's palette: the input
 * is the first row, led by a chip naming the chord that opened it; rows embolden the characters
 * the query matched and show the chord in the keyboard's own hue; a legend closes the card.
 *
 * <p>An in-scene overlay rather than a window of its own: a second Stage would take OS focus and
 * getting it back is the thing that has to work. Whatever the user does here, the next keystroke
 * belongs to the list again.
 */
final class CommandPalette {

    /** Height of one command row, and how many the list shows before it scrolls; the kit's. */
    static final double ROW_HEIGHT = 36;

    static final int VISIBLE_ROWS = 8;

    private final StackPane host;
    private final Runnable onHidden;
    private final Label prefixChip = new Label();
    private final TextField query = new TextField();
    private final ListView<Command> results = new ListView<>();
    private final Label hint = new Label(tr("palette.legend"));
    private final VBox card = new VBox(6);
    private final Region backdrop = new Region();
    private List<Command> commands = List.of();
    /** The query of the current filter pass, read by the cells to embolden the matched characters. */
    private String currentQuery = "";

    /**
     * @param openerChord the chord that opens the palette, shown as a chip leading the input so the
     *     palette introduces itself by its keyboard identity; blank for none
     */
    CommandPalette(StackPane host, Runnable onHidden, String openerChord) {
        this.host = host;
        this.onHidden = onHidden;
        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMousePressed(e -> hide());

        // Kit shape: no title header; the first row IS the input, led by the chord chip.
        prefixChip.getStyleClass().add("palette-prefix");
        prefixChip.setText(openerChord == null ? "" : openerChord);
        prefixChip.setVisible(!prefixChip.getText().isEmpty());
        prefixChip.setManaged(prefixChip.isVisible());
        query.getStyleClass().add("palette-input");
        query.setPromptText(tr("palette.prompt"));
        query.textProperty().addListener((o, was, now) -> refilter(now));
        HBox inputRow = new HBox(9, prefixChip, query);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.getStyleClass().add("palette-input-row");
        HBox.setHgrow(query, Priority.ALWAYS);

        results.getStyleClass().add("palette-list");
        results.setPlaceholder(new Label(tr("palette.noMatch")));
        results.setCellFactory(view -> new CommandCell());
        // A whole number of rows: a fixed height cut the last row in half.
        results.setFixedCellSize(ROW_HEIGHT);
        results.setPrefHeight(listHeight(0));
        results.insetsProperty()
                .addListener((o, was, in) -> results.setPrefHeight(listHeight(in.getTop() + in.getBottom())));

        hint.getStyleClass().add("palette-hint");

        card.getStyleClass().add("command-palette");
        card.getChildren().addAll(inputRow, results, hint);
        card.setPrefWidth(620);
        card.setMaxSize(620, Region.USE_PREF_SIZE); // hug the content; never stretch to fill the overlay
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        // On the card, so it runs before the ListView's own handling of Up/Down and before the
        // TextField swallows anything. Enter and Escape would otherwise never reach us.
        card.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** A new opener chord for the chip, when the setting changes while the window is up. */
    void setOpenerChord(String openerChord) {
        prefixChip.setText(openerChord == null ? "" : openerChord);
        prefixChip.setVisible(!prefixChip.getText().isEmpty());
        prefixChip.setManaged(prefixChip.isVisible());
    }

    /** The list height for {@link #VISIBLE_ROWS} rows, or fewer when there are fewer, inside {@code insets}. */
    double listHeight(double insets) {
        int rows = Math.min(VISIBLE_ROWS, Math.max(1, results.getItems().size()));
        return rows * ROW_HEIGHT + insets;
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
        query.requestFocus();
    }

    void hide() {
        if (!isShowing()) return;
        host.getChildren().removeAll(backdrop, card);
        onHidden.run();
    }

    private void refilter(String text) {
        currentQuery = text == null ? "" : text;
        results.getItems().setAll(rank(text, commands));
        if (!results.getItems().isEmpty()) results.getSelectionModel().selectFirst();
        Insets in = results.getInsets();
        results.setPrefHeight(listHeight(in == null ? 0 : in.getTop() + in.getBottom()));
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
        // over it, or, worse, take the focus back afterwards.
        hide();
        if (command != null) command.run().run();
    }

    /** A row: the title as styled runs on the left, the chord right-aligned, as the menus show it. */
    private final class CommandCell extends ListCell<Command> {
        private final TextFlow title = new TextFlow();
        private final Label key = new Label();
        private final HBox box = new HBox(10, title, spacer(), key);

        private static Region spacer() {
            Region region = new Region();
            HBox.setHgrow(region, Priority.ALWAYS);
            return region;
        }

        CommandCell() {
            key.getStyleClass().add("keybinding");
            box.setAlignment(Pos.CENTER_LEFT);
            // A click runs the row; the keyboard runs the selected one on Enter.
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    runSelected();
                }
            });
        }

        @Override
        protected void updateItem(Command item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.getChildren().setAll(MatchText.runs(item.label(), currentQuery));
            key.setText(item.hint());
            setGraphic(box);
        }
    }
}
