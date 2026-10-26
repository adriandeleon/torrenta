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

import com.torrenta.engine.LinkRequest;
import com.torrenta.engine.direct.DirectNames;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Add Link dialog, for a link that is not a torrent: which folder on this machine, under what
 * name, started or paused. Shaped as the Set Location dialog (its own stage, the app's stylesheet
 * and font, Enter downloads, Escape cancels), with the folder's free space under it. The name
 * starts as the link's own; left so, the server may still give the file a better one, while a
 * name typed here is kept. "Add without asking" skips the dialog.
 */
final class LinkDialog {

    private final Stage stage = new Stage();
    private final String url;
    private final String linkName;
    private final TextField folder = new TextField();
    private final Button browse = new Button(tr("common.browse"));
    private final Label freeSpace = new Label();
    private final TextField name = new TextField();
    private final Label nameProblem = new Label();
    private final CheckBox paused = new CheckBox(tr("dialog.link.paused"));
    private final Button download = new Button(tr("dialog.link.download"));
    private @Nullable LinkRequest result;

    LinkDialog(Window owner, String url, Path defaultFolder, boolean startPaused) {
        this.url = url.strip();
        this.linkName = DirectNames.fromUrl(this.url);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(tr("dialog.link.title"));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        Label link = new Label(this.url);
        link.getStyleClass().addAll("dialog-hint", "mono");
        link.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        link.setMinWidth(0);
        link.setMaxWidth(Double.MAX_VALUE);
        form.addRow(0, muted(tr("dialog.link.link")), link);

        folder.setText(defaultFolder.toString());
        HBox.setHgrow(folder, Priority.ALWAYS);
        browse.setOnAction(e -> browse());
        form.addRow(1, muted(tr("dialog.link.folder")), new HBox(8, folder, browse));

        freeSpace.getStyleClass().add("dialog-hint");
        freeSpace.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        freeSpace.setMinWidth(0);
        freeSpace.setMaxWidth(Double.MAX_VALUE);
        form.add(freeSpace, 1, 2);

        name.setText(linkName);
        form.addRow(3, muted(tr("dialog.link.name")), name);
        nameProblem.getStyleClass().add("dialog-hint");
        nameProblem.setWrapText(true);
        // Takes no room while the name is fine.
        nameProblem.managedProperty().bind(nameProblem.textProperty().isNotEmpty());
        nameProblem.visibleProperty().bind(nameProblem.textProperty().isNotEmpty());
        form.add(nameProblem, 1, 4);

        paused.setSelected(startPaused);
        form.add(paused, 1, 5);

        folder.textProperty().addListener((o, was, text) -> updateFreeSpace());
        name.textProperty()
                .addListener((o, was, text) -> nameProblem.setText(problem(text).orElse("")));
        updateFreeSpace();

        download.setDefaultButton(true);
        download.setOnAction(e -> confirm());
        download.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> folder.getText().isBlank()
                                || problem(name.getText()).isPresent(),
                        folder.textProperty(),
                        name.textProperty()));
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, download);

        VBox content = new VBox(12, form, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 560, 260);
        scene.getStylesheets()
                .add(LinkDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        DialogFit.install(stage);
    }

    /**
     * Why a name will not do, or empty when it will. Pure. Blank is no name; one with a folder
     * separator, a character Windows forbids, a leading dot or a trailing dot or space would be
     * changed on disk, so it is refused here rather than changed behind the person's back.
     */
    static Optional<String> problem(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return Optional.of(tr("dialog.link.noName"));
        if (!DirectNames.safe(n).equals(n)) return Optional.of(tr("dialog.link.badName"));
        return Optional.empty();
    }

    /**
     * What Download hands the engine. Pure. The link's own name, left as it was, is no choice: the
     * server's name for the file may still replace it.
     */
    static LinkRequest request(String url, String folder, String typedName, String linkName, boolean paused) {
        String n = typedName == null ? "" : typedName.strip();
        return new LinkRequest(url, Path.of(folder.strip()), n.equals(linkName) ? null : n, paused);
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<LinkRequest> showAndWait() {
        name.requestFocus();
        int dot = linkName.lastIndexOf('.');
        name.selectRange(0, dot > 0 ? dot : linkName.length());
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

    /** The folder's free-space line and the name, for the capture hook. */
    String describe() {
        return "folder=" + folder.getText() + " name=" + name.getText() + " paused=" + paused.isSelected() + " hint="
                + freeSpace.getText() + " problem=" + nameProblem.getText();
    }

    /** Types a name, for the capture hook. */
    void setNameForCapture(String text) {
        name.setText(text);
    }

    /** Closes without downloading, as Escape would. */
    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Download. */
    void confirm() {
        if (folder.getText().isBlank() || problem(name.getText()).isPresent()) return;
        result = request(url, folder.getText(), name.getText(), linkName, paused.isSelected());
        stage.hide();
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.link.chooser"));
        File current = new File(folder.getText().trim());
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) folder.setText(chosen.getAbsolutePath());
    }

    /** The volume the folder is on; the file's size is not known before the server answers. */
    private void updateFreeSpace() {
        String text = folder.getText().trim();
        Optional<FreeSpace.Disk> disk;
        try {
            disk = text.isEmpty() ? Optional.empty() : FreeSpace.disk(Path.of(text));
        } catch (RuntimeException e) {
            disk = Optional.empty();
        }
        freeSpace.setText(disk.map(d -> FreeSpace.sentence(d, -1))
                .orElse(text.isEmpty() ? "" : tr("dialog.add.freeSpaceUnknown")));
    }

    /** The label column never shrinks, as in the Set Location dialog. */
    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
