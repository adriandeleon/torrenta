package com.torrenta.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.torrenta.engine.EngineEvent;

import static com.torrenta.i18n.Messages.tr;

/**
 * The {@link MessageLog} as a card above the status bar, newest first, each line with its time.
 * Editora's. A filter box leads it (the log fills fast); Copy takes the selected lines or all of
 * them; Clear empties the session's log; Esc or a click outside closes it.
 *
 * <p>An overlay in the window's own scene, like the {@link CommandPalette}: no second window, so
 * it needs no owner, no font install, and leaves with the window.
 */
final class MessageLogPopup {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Card width; the list shows this many lines before it scrolls. */
    static final double WIDTH = 640;

    static final double LIST_HEIGHT = 300;

    private final StackPane host;
    private final Node anchor;
    private final Runnable onHidden;
    private final MessageLog log;
    private final TextField filter = new TextField();
    private final ListView<MessageLog.Entry> list = new ListView<>();
    private final VBox card = new VBox(6);
    private final Region backdrop = new Region();
    /** Every line; the list shows the ones the filter lets through. */
    private List<MessageLog.Entry> all = List.of();

    /**
     * @param anchor the status bar; the card sits just above it, at the left, where the words it
     *     collects are shown
     */
    MessageLogPopup(StackPane host, Node anchor, MessageLog log, Runnable onHidden) {
        this.host = host;
        this.anchor = anchor;
        this.log = log;
        this.onHidden = onHidden;
        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMousePressed(e -> hide());

        Label header = new Label(tr("messageLog.title"));
        header.getStyleClass().add("message-log-header");

        filter.getStyleClass().add("message-log-filter");
        filter.setPromptText(tr("messageLog.filter.prompt"));
        filter.textProperty().addListener((o, was, now) -> applyFilter());

        list.getStyleClass().add("message-log-list");
        list.setPrefSize(WIDTH, LIST_HEIGHT);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Label empty = new Label(tr("messageLog.empty"));
        empty.getStyleClass().add("message-log-empty");
        list.setPlaceholder(empty);
        list.setCellFactory(view -> new EntryCell());

        Button copy = new Button(tr("common.copy"));
        copy.getStyleClass().add("flat");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> {
            if (list.getSelectionModel().getSelectedItems().isEmpty())
                list.getSelectionModel().selectAll();
            copySelection();
        });
        Button clear = new Button(tr("messageLog.clear"));
        clear.getStyleClass().add("flat");
        clear.setFocusTraversable(false);
        clear.setOnAction(e -> {
            log.clear();
            reload();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label(tr(
                "messageLog.hint", new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).getDisplayText()));
        hint.getStyleClass().add("message-log-hint");
        HBox footer = new HBox(8, copy, clear, spacer, hint);
        footer.getStyleClass().add("message-log-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        card.getStyleClass().add("message-log");
        card.getChildren().addAll(header, filter, list, footer);
        card.setPrefWidth(WIDTH);
        card.setMaxSize(WIDTH, Region.USE_PREF_SIZE); // hug the content; never stretch to fill the overlay
        StackPane.setAlignment(card, Pos.BOTTOM_LEFT);
        // On the card, so Esc is seen before the text field or the list swallow it; Down from the
        // filter steps into the lines without leaving the keyboard.
        card.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    boolean isShowing() {
        return host.getChildren().contains(card);
    }

    /** Opens the card, or closes it when it is open: a click on the status bar toggles. */
    void toggle() {
        if (isShowing()) hide();
        else show();
    }

    void show() {
        if (isShowing()) return;
        filter.clear(); // a query from last time would hide the lines that have just arrived
        reload();
        // Just above the status bar, in from the left edge as the status text is.
        StackPane.setMargin(card, new Insets(0, 0, anchor.getLayoutBounds().getHeight() + 6, 10));
        host.getChildren().addAll(backdrop, card);
        filter.requestFocus();
    }

    void hide() {
        if (!isShowing()) return;
        host.getChildren().removeAll(backdrop, card);
        onHidden.run();
    }

    /** Types into the filter as the user would. */
    void setFilterForCapture(String text) {
        filter.setText(text);
    }

    /** The visible lines, newest first, as "Level text", so a capture run can assert on them. */
    List<String> visibleLines() {
        return list.getItems().stream()
                .map(e -> LogWindow.levelLabel(e.level()) + " " + e.text())
                .toList();
    }

    private void reload() {
        all = log.entries();
        applyFilter();
        if (!list.getItems().isEmpty()) list.getSelectionModel().clearAndSelect(0); // the newest, so Ctrl+C copies it
    }

    private void applyFilter() {
        String q = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        list.getItems()
                .setAll(
                        q.isEmpty()
                                ? all
                                : all.stream()
                                        .filter(e -> e.text()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(q))
                                        .toList());
    }

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            hide();
            e.consume();
        } else if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
            copySelection();
            e.consume();
        } else if (e.getCode() == KeyCode.DOWN && filter.isFocused()) {
            list.requestFocus();
            if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty())
                list.getSelectionModel().select(0);
            e.consume();
        }
    }

    private void copySelection() {
        String text = list.getSelectionModel().getSelectedItems().stream()
                .filter(Objects::nonNull)
                .map(e -> TIME.format(e.at().atZone(ZoneId.systemDefault())) + "  " + e.text())
                .collect(Collectors.joining("\n"));
        if (text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Time in the margin, the words beside it; a warning or an error colours the words, not the time. */
    private final class EntryCell extends ListCell<MessageLog.Entry> {
        private final Label time = new Label();
        private final Label text = new Label();
        private final HBox box = new HBox(10, time, text);

        EntryCell() {
            time.getStyleClass().add("message-log-time");
            time.setMinWidth(Region.USE_PREF_SIZE);
            text.getStyleClass().add("message-log-text");
            text.setWrapText(true);
            text.setMinHeight(Region.USE_PREF_SIZE);
            // A definite width is what lets a long line wrap and grow the row instead of scrolling sideways.
            text.maxWidthProperty().bind(list.widthProperty().subtract(110));
            box.setAlignment(Pos.TOP_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(MessageLog.Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            time.setText(TIME.format(item.at().atZone(ZoneId.systemDefault())));
            text.setText(item.text());
            text.getStyleClass().removeAll("message-log-warning", "message-log-error");
            if (item.level() == EngineEvent.Level.ERROR) text.getStyleClass().add("message-log-error");
            else if (item.level() == EngineEvent.Level.WARNING)
                text.getStyleClass().add("message-log-warning");
            setGraphic(box);
        }
    }
}
