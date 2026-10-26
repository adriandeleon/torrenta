package com.torrenta.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.migrate.ImportPlan;

import static com.torrenta.i18n.Messages.tr;

/**
 * File → Import from another client…: a source (the qBittorrent folders found on this machine, or
 * one chosen), its torrents with a verdict each, ticked as the plan advises, and Import. Reading a
 * folder asks the engine which torrents it has, so it happens on a worker through {@code load}; the
 * table fills when it answers. Shaped as the other dialogs: its own stage, the app's stylesheet
 * and font, Escape cancels.
 */
final class ImportDialog {

    /** A torrent in the table, ticked or not. */
    static final class Row {
        final ImportPlan.Item item;
        final BooleanProperty chosen;

        Row(ImportPlan.Item item) {
            this.item = item;
            this.chosen = new SimpleBooleanProperty(ImportText.tickedAtFirst(item));
        }
    }

    /** "Choose a folder…" at the end of the picker. */
    private static final Path CHOOSE = Path.of("");

    private final Stage stage = new Stage();
    private final Function<Path, ImportText.Loaded> load;
    private final ComboBox<Path> source = new ComboBox<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label status = new Label();
    private final VBox notes = new VBox(4);
    private final Button importButton = new Button();
    private List<ImportPlan.Item> result;
    private Path shown;
    /** The capture hook asked for Import before the rows were in: pressed once they are. */
    private boolean confirmWhenLoaded;

    /**
     * @param found the folders found on this machine; may be empty
     * @param load reads a folder and plans it; called on a worker, never the FX thread
     */
    ImportDialog(Window owner, List<Path> found, Function<Path, ImportText.Loaded> load) {
        this.load = load;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(tr("dialog.import.title"));

        source.getItems().addAll(found);
        source.getItems().add(CHOOSE);
        source.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(source, Priority.ALWAYS);
        source.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Path p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : label(p));
            }
        });
        source.setButtonCell(source.getCellFactory().call(null));
        source.valueProperty().addListener((o, was, now) -> {
            if (now == null) return;
            if (now == CHOOSE) {
                Platform.runLater(this::choose);
                return;
            }
            read(now);
        });
        Label sourceLabel = new Label(tr("dialog.import.source"));
        sourceLabel.getStyleClass().add("dialog-label");
        sourceLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(10, sourceLabel, source);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        TableColumn<Row, Boolean> tick = new TableColumn<>();
        tick.setPrefWidth(36);
        tick.setSortable(false);
        tick.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            @Override
            protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setGraphic(null);
                    return;
                }
                box.selectedProperty().unbind();
                box.setSelected(row.chosen.get());
                box.setDisable(!row.item.verdict().choosable());
                box.setOnAction(e -> {
                    row.chosen.set(box.isSelected());
                    updateButton();
                });
                setGraphic(box);
            }
        });
        tick.setCellValueFactory(c -> c.getValue().chosen);
        TableColumn<Row, String> name = new TableColumn<>(tr("dialog.import.name"));
        name.setCellValueFactory(
                c -> new SimpleStringProperty(c.getValue().item.candidate().shownName()));
        name.setPrefWidth(260);
        TableColumn<Row, String> progress = new TableColumn<>(tr("dialog.import.progress"));
        progress.setCellValueFactory(c ->
                new SimpleStringProperty(ImportText.progress(c.getValue().item.candidate())));
        progress.setPrefWidth(110);
        TableColumn<Row, String> verdict = new TableColumn<>(tr("dialog.import.verdict"));
        verdict.setCellValueFactory(
                c -> new SimpleStringProperty(c.getValue().item.reason()));
        verdict.setPrefWidth(360);
        table.getColumns().addAll(List.of(tick, name, progress, verdict));
        table.setPlaceholder(status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);
        Headers.uppercase(table);

        notes.getStyleClass().add("dialog-hint");

        importButton.setDefaultButton(true);
        importButton.setOnAction(e -> confirm());
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, importButton);
        updateButton();

        VBox content = new VBox(12, top, table, notes, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 840, 480);
        scene.getStylesheets()
                .add(ImportDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        status.setText(found.isEmpty() ? tr("dialog.import.none", com.torrenta.migrate.QbittorrentReader.CLIENT) : "");
        if (!found.isEmpty()) source.getSelectionModel().select(0);
    }

    private static String label(Path p) {
        return p == CHOOSE ? tr("dialog.import.choose") : ImportText.source(p);
    }

    /** A folder of a person's choosing, added to the picker and read. */
    private void choose() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.import.chooser", com.torrenta.migrate.QbittorrentReader.CLIENT));
        File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            source.getSelectionModel().select(shown);
            return;
        }
        showFolder(chosen.toPath());
    }

    /** The folder in the picker and read: a chosen one, or the capture hook's. */
    void showFolder(Path folder) {
        if (!source.getItems().contains(folder))
            source.getItems().add(source.getItems().size() - 1, folder);
        source.getSelectionModel().select(folder);
    }

    private void read(Path folder) {
        shown = folder;
        rows.clear();
        notes.getChildren().clear();
        status.setText(tr("dialog.import.reading", folder.toString()));
        updateButton();
        Thread.ofVirtual().name("import-read").start(() -> {
            ImportText.Loaded loaded = load.apply(folder);
            Platform.runLater(() -> {
                if (!folder.equals(shown)) return;
                List<Row> fresh = new ArrayList<>();
                for (ImportPlan.Item item : loaded.items()) fresh.add(new Row(item));
                rows.setAll(fresh);
                status.setText(ImportText.notes(loaded).isEmpty() ? "" : tr("dialog.import.nothing"));
                for (String line : ImportText.notes(loaded)) {
                    Label note = new Label(line);
                    note.setWrapText(true);
                    notes.getChildren().add(note);
                }
                updateButton();
                if (confirmWhenLoaded) confirm();
            });
        });
    }

    private List<ImportPlan.Item> chosen() {
        return rows.stream().filter(r -> r.chosen.get()).map(r -> r.item).toList();
    }

    private void updateButton() {
        int n = chosen().size();
        importButton.setText(ImportText.importButton(n));
        importButton.setDisable(n == 0);
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<List<ImportPlan.Item>> showAndWait() {
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    Scene scene() {
        return stage.getScene();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    String title() {
        return stage.getTitle();
    }

    /** Each row and the notes, for the capture hook. */
    List<String> linesForCapture() {
        List<String> out = new ArrayList<>();
        out.add("import-source=" + (shown == null ? "" : shown) + " rows=" + rows.size() + " button="
                + importButton.getText() + " enabled=" + !importButton.isDisabled());
        for (Row r : rows)
            out.add("import=" + (r.chosen.get() ? "[x] " : "[ ] ")
                    + r.item.candidate().shownName() + " | " + ImportText.progress(r.item.candidate()) + " | "
                    + r.item.verdict() + " | " + r.item.reason());
        for (javafx.scene.Node n : notes.getChildren()) if (n instanceof Label l) out.add("import-note=" + l.getText());
        return out;
    }

    /** Closes without importing, as Escape would. */
    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Import as soon as the folder has been read: the capture hook's, which runs before it is. */
    void confirmWhenLoaded() {
        confirmWhenLoaded = true;
        if (!rows.isEmpty()) confirm();
    }

    /** Presses Import. */
    void confirm() {
        List<ImportPlan.Item> items = chosen();
        if (items.isEmpty()) return;
        result = items;
        stage.hide();
    }
}
