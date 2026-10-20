package com.torrenta.ui;

import java.io.File;
import java.util.List;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.torrenta.engine.Category;
import com.torrenta.engine.WatchFolder;

/**
 * One watch folder, added or edited: the folder, what becomes of a file once added, where the
 * torrent saves, its category, and whether it starts paused.
 *
 * <p>Shaped as the Add dialog: its own stage, the app's stylesheet and font, Enter saves, Escape
 * cancels. Save is held back while there is no folder, or "another folder" names none.
 */
final class WatchFolderDialog {

    private final Stage stage = new Stage();
    private final TextField folder = new TextField();
    private final ChoiceBox<WatchFolder.SaveTo> saveTo = new ChoiceBox<>();
    private final TextField savePath = new TextField();
    private final Button browseSave = new Button("Browse…");
    private final ChoiceBox<WatchFolder.AfterAdd> afterAdd = new ChoiceBox<>();
    private final ChoiceBox<String> category = new ChoiceBox<>();
    private final CheckBox startPaused = new CheckBox("Start paused");
    private final Button save = new Button("Save");
    private WatchFolder result;

    /**
     * @param existing the entry being edited, or null for a new one
     * @param categories the categories that exist, offered for the torrents
     */
    WatchFolderDialog(Window owner, WatchFolder existing, List<Category> categories) {
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(existing == null ? "Add Watch Folder" : "Edit Watch Folder");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        folder.setPromptText("a folder to watch for .torrent and .magnet files");
        HBox.setHgrow(folder, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> browse("Folder to watch", folder));
        form.addRow(row++, muted("Watch"), new HBox(6, folder, browse));

        saveTo.getItems().addAll(WatchFolder.SaveTo.values());
        saveTo.setConverter(new StringConverter<>() {
            @Override
            public String toString(WatchFolder.SaveTo s) {
                return s == null ? "" : s.label();
            }

            @Override
            public WatchFolder.SaveTo fromString(String s) {
                return null;
            }
        });
        savePath.setPromptText("the folder the torrents save to");
        HBox.setHgrow(savePath, Priority.ALWAYS);
        browseSave.setOnAction(e -> browse("Save path", savePath));
        HBox saveRow = new HBox(6, savePath, browseSave);
        savePath.disableProperty().bind(saveTo.valueProperty().isNotEqualTo(WatchFolder.SaveTo.FOLDER));
        browseSave.disableProperty().bind(savePath.disableProperty());
        form.addRow(row++, muted("Save to"), saveTo);
        form.add(saveRow, 1, row++);

        afterAdd.getItems().addAll(WatchFolder.AfterAdd.values());
        afterAdd.setConverter(new StringConverter<>() {
            @Override
            public String toString(WatchFolder.AfterAdd a) {
                return a == null ? "" : a.label();
            }

            @Override
            public WatchFolder.AfterAdd fromString(String s) {
                return null;
            }
        });
        form.addRow(row++, muted("After adding"), afterAdd);

        category.getItems().add("");
        for (Category c : categories) category.getItems().add(c.name());
        category.setConverter(new StringConverter<>() {
            @Override
            public String toString(String name) {
                return name == null || name.isEmpty() ? "None" : name;
            }

            @Override
            public String fromString(String s) {
                return null;
            }
        });
        form.addRow(row++, muted("Category"), category);
        form.addRow(row++, new Label(), startPaused);

        Label hint = hint("A .torrent is added once it has stopped growing; a .magnet file holds one magnet"
                + " link per line. A file that is not a torrent is renamed .invalid and left alone.");
        hint.setMaxWidth(Double.MAX_VALUE);

        save.setDefaultButton(true);
        save.setOnAction(e -> confirm());
        save.disableProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> folder.getText().isBlank()
                                || (saveTo.getValue() == WatchFolder.SaveTo.FOLDER
                                        && savePath.getText().isBlank()),
                        folder.textProperty(),
                        saveTo.valueProperty(),
                        savePath.textProperty()));
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, save);

        VBox content = new VBox(12, form, hint, buttons);
        content.setPadding(new Insets(14));
        // Tall enough for the hint to wrap: a VBox short of room gives a wrapping label one line.
        Scene scene = new Scene(content, 560, 370);
        scene.getStylesheets()
                .add(WatchFolderDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);

        if (existing == null) {
            saveTo.setValue(WatchFolder.SaveTo.DEFAULT);
            afterAdd.setValue(WatchFolder.AfterAdd.RENAME);
            category.setValue("");
        } else {
            folder.setText(existing.folder());
            saveTo.setValue(existing.saveTo());
            savePath.setText(existing.savePath());
            afterAdd.setValue(existing.afterAdd());
            category.setValue(category.getItems().contains(existing.category()) ? existing.category() : "");
            startPaused.setSelected(existing.startPaused());
        }
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<WatchFolder> showAndWait() {
        folder.requestFocus();
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

    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Save. */
    void confirm() {
        if (save.isDisabled()) return;
        result = new WatchFolder(
                folder.getText(),
                afterAdd.getValue(),
                saveTo.getValue(),
                savePath.getText(),
                category.getValue(),
                startPaused.isSelected());
        stage.hide();
    }

    private void browse(String title, TextField field) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        File current = new File(field.getText().trim());
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) field.setText(chosen.getAbsolutePath());
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        label.setWrapText(true);
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
