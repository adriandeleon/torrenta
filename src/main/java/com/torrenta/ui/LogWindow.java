package com.torrenta.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.AppInfo;
import com.torrenta.engine.EngineEvent;

import static com.torrenta.i18n.Messages.tr;

/**
 * The event log in a window of its own: time, level, torrent, message; a level filter; copy and
 * clear. Non-modal and reusable: closing hides it, the next Ctrl+Shift+L shows it again with the
 * log it kept scrolling through.
 */
final class LogWindow {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Stage stage = new Stage();
    private final EventLog log;
    private final FilteredList<EngineEvent> visible;
    private final CheckBox info = new CheckBox(tr("eventLog.info"));
    private final CheckBox warnings = new CheckBox(tr("eventLog.warnings"));
    private final CheckBox errors = new CheckBox(tr("eventLog.errors"));

    LogWindow(Window owner, EventLog log) {
        this.log = log;
        this.visible = new FilteredList<>(log.events());
        stage.initOwner(owner);
        stage.setTitle(tr("eventLog.title", AppInfo.NAME));

        TableView<EngineEvent> table = new TableView<>(visible);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(tr("eventLog.empty")));
        table.getStyleClass().add("event-table");
        TableColumn<EngineEvent, String> time =
                column(tr("eventLog.column.time"), 80, e -> TIME.format(e.at().atZone(ZoneId.systemDefault())));
        TableColumn<EngineEvent, String> level = column(tr("eventLog.column.level"), 80, e -> levelLabel(e.level()));
        TableColumn<EngineEvent, String> torrent = column(tr("eventLog.column.torrent"), 200, EngineEvent::torrent);
        TableColumn<EngineEvent, String> message = column(tr("eventLog.column.message"), 400, EngineEvent::message);
        table.getColumns().addAll(List.of(time, level, torrent, message));
        table.setRowFactory(v -> new TableRow<>() {
            @Override
            protected void updateItem(EngineEvent item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("event-warning", "event-error");
                if (!empty && item != null) {
                    if (item.level() == EngineEvent.Level.WARNING)
                        getStyleClass().add("event-warning");
                    if (item.level() == EngineEvent.Level.ERROR) getStyleClass().add("event-error");
                }
            }
        });
        // New events at the bottom: follow them, as a log does.
        log.events().addListener((javafx.collections.ListChangeListener<EngineEvent>) c -> {
            if (!visible.isEmpty()) table.scrollTo(visible.size() - 1);
        });

        for (CheckBox box : List.of(info, warnings, errors)) {
            box.setSelected(true);
            box.selectedProperty().addListener((o, was, now) -> refilter());
        }
        Button copy = new Button(tr("common.copy"));
        copy.setOnAction(e -> copyVisible());
        Button clear = new Button(tr("eventLog.clear"));
        clear.setOnAction(e -> log.clear());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, new Label(tr("eventLog.show")), info, warnings, errors, spacer, copy, clear);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("event-bar");

        BorderPane root = new BorderPane(table);
        root.setTop(bar);
        Scene scene = new Scene(root, 820, 420);
        scene.getStylesheets()
                .add(LogWindow.class.getResource("/com/torrenta/styles/app.css").toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });
        stage.setScene(scene);
    }

    /** Shows, or brings to the front; hidden by its own close button or Esc. */
    void show() {
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
        } else {
            stage.show();
        }
    }

    /** For a capture of the log itself. */
    Scene scene() {
        return stage.getScene();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    static String levelLabel(EngineEvent.Level level) {
        return switch (level) {
            case INFO -> tr("eventLog.level.info");
            case WARNING -> tr("eventLog.level.warning");
            case ERROR -> tr("eventLog.level.error");
        };
    }

    private void refilter() {
        boolean i = info.isSelected();
        boolean w = warnings.isSelected();
        boolean e = errors.isSelected();
        visible.setPredicate(event -> switch (event.level()) {
            case INFO -> i;
            case WARNING -> w;
            case ERROR -> e;
        });
    }

    private void copyVisible() {
        StringBuilder text = new StringBuilder();
        for (EngineEvent e : visible) {
            text.append(TIME.format(e.at().atZone(ZoneId.systemDefault())))
                    .append('\t')
                    .append(levelLabel(e.level()))
                    .append('\t')
                    .append(e.torrent())
                    .append('\t')
                    .append(e.message())
                    .append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static TableColumn<EngineEvent, String> column(
            String title, double width, Function<EngineEvent, String> text) {
        TableColumn<EngineEvent, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
                cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(text.apply(cell.getValue())));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });
        return column;
    }

    /** Local alias so the row factory reads without the full generic spelling. */
    private abstract static class TableRow<T> extends javafx.scene.control.TableRow<T> {}
}
