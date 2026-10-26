package com.torrenta.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
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

import com.torrenta.engine.Engine.Capabilities;
import com.torrenta.engine.Engine.Capability;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Set Location dialog: where the selection's files are now, and where they are to go.
 *
 * <p>Shaped as the Limits dialog: its own stage, the app's stylesheet and font, Enter moves,
 * Escape cancels. The folder is typed or browsed; under it the volume's free space against what
 * the selection weighs, as the Add dialog shows it. For an engine whose files are elsewhere
 * ({@link Capability#LOCAL_FILES} missing) the field is a plain path on the daemon's machine:
 * no Browse, no free space. The files are moved unless the box says they are there already.
 */
final class LocationDialog {

    /** What Move hands back: the folder as typed, and whether the files are to be moved there. */
    record Result(String path, boolean moveFiles) {}

    private final Stage stage = new Stage();
    private final TextField path = new TextField();
    private final Button browse = new Button(tr("common.browse"));
    private final Label freeSpace = new Label();
    private final CheckBox moveFiles = new CheckBox(tr("dialog.location.moveFiles"));
    private final Button move = new Button(tr("dialog.location.move"));
    private final long wanted;
    private boolean localFiles = true;
    private Result result;

    /**
     * @param subject the torrent's name, or what the selection is ("3 torrents")
     * @param current where the first selected torrent's files are now
     * @param wanted what the selection weighs in bytes, for the free-space line; negative when
     *     unknown (a magnet before its metadata)
     */
    LocationDialog(Window owner, String subject, String current, long wanted) {
        this.wanted = wanted;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(tr("dialog.location.title", subject));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        Label now = new Label(current);
        now.getStyleClass().add("dialog-hint");
        now.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        now.setMinWidth(0);
        now.setMaxWidth(Double.MAX_VALUE);
        form.addRow(0, muted(tr("dialog.location.now")), now);

        path.setText(current);
        path.setPromptText(tr("dialog.location.prompt"));
        HBox.setHgrow(path, Priority.ALWAYS);
        browse.setOnAction(e -> browse());
        HBox where = new HBox(8, path, browse);
        form.addRow(1, muted(tr("dialog.location.moveTo")), where);

        freeSpace.getStyleClass().add("dialog-hint");
        freeSpace.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        freeSpace.setMinWidth(0);
        freeSpace.setMaxWidth(Double.MAX_VALUE);
        form.add(freeSpace, 1, 2);

        moveFiles.setSelected(true);
        Label moveHint = hint(tr("dialog.location.moveFiles.hint"));
        moveHint.setMaxWidth(Double.MAX_VALUE);
        form.add(new VBox(2, moveFiles, moveHint), 1, 3);

        path.textProperty().addListener((o, was, text) -> updateFreeSpace());
        updateFreeSpace();

        move.setDefaultButton(true);
        move.setOnAction(e -> confirm());
        move.disableProperty()
                .bind(Bindings.createBooleanBinding(() -> path.getText().isBlank(), path.textProperty()));
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, move);

        VBox content = new VBox(12, form, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 560, 220);
        scene.getStylesheets()
                .add(LocationDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        DialogFit.install(stage);
    }

    /** Hides Browse and the free-space line for an engine whose files are on another machine. Before showing. */
    void applyCapabilities(Capabilities caps) {
        localFiles = caps.has(Capability.LOCAL_FILES);
        browse.setVisible(localFiles);
        browse.setManaged(localFiles);
        freeSpace.setVisible(localFiles);
        freeSpace.setManaged(localFiles);
        if (!localFiles) {
            path.setPromptText(tr("dialog.location.prompt.remote"));
            stage.setHeight(200);
        }
        updateFreeSpace();
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<Result> showAndWait() {
        path.requestFocus();
        path.selectAll();
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

    /** The free-space line under the folder, for the capture hook. */
    String hint() {
        return freeSpace.getText();
    }

    /** Closes without moving, as Escape would. */
    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Move. */
    void confirm() {
        String text = path.getText().trim();
        if (text.isEmpty()) return;
        result = new Result(text, moveFiles.isSelected());
        stage.hide();
    }

    /** Types a folder, for the capture hook. */
    void setPathForCapture(String folder) {
        path.setText(folder);
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.location.chooser"));
        File current = new File(path.getText().trim());
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) path.setText(chosen.getAbsolutePath());
    }

    /** The volume the typed folder is on, and whether the selection fits; as the Add dialog says it. */
    private void updateFreeSpace() {
        if (!localFiles) {
            freeSpace.setText("");
            return;
        }
        String text = path.getText().trim();
        Optional<FreeSpace.Disk> disk;
        try {
            disk = text.isEmpty() ? Optional.empty() : FreeSpace.disk(Path.of(text));
        } catch (RuntimeException e) {
            disk = Optional.empty();
        }
        freeSpace.setText(disk.map(d -> FreeSpace.sentence(d, wanted))
                .orElse(text.isEmpty() ? "" : tr("dialog.add.freeSpaceUnknown")));
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        label.setWrapText(true);
        return label;
    }

    /** The label column never shrinks, as in the Limits dialog. */
    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
