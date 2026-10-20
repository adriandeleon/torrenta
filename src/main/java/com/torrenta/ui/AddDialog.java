package com.torrenta.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.CheckBoxTreeTableCell;
import javafx.scene.control.cell.ComboBoxTreeTableCell;
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

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.TorrentPreview;

/**
 * The Add dialog: what the torrent is, where it goes, how it starts, which files.
 *
 * <p>One dialog for all three sources. A {@code .torrent} (from disk or a URL) comes with a
 * {@link TorrentPreview} and shows its files; a magnet has no metadata yet and shows what the link
 * says. Enter adds, Escape cancels, every control is reachable by keyboard.
 */
final class AddDialog {

    private final Stage stage = new Stage();
    private final TextField savePath = new TextField();
    private final Label freeSpace = new Label();
    private final CheckBox startPaused = new CheckBox("Start paused");
    private final CheckBox sequential = new CheckBox("Sequential download");
    private final CheckBox skipCheck = new CheckBox("Skip hash check (the files are already complete)");
    private final ChoiceBox<AddRequest.StopCondition> stop = new ChoiceBox<>();
    private final Button add = new Button("Add");
    private final TreeTableView<FileTree.Node> tree = new TreeTableView<>();
    private final AddRequest.Source source;
    private final TorrentPreview preview;
    private AddRequest result;

    /**
     * @param preview the torrent's contents, or null for a magnet
     * @param origin where it came from: a path, a URL, or the magnet link
     */
    AddDialog(Window owner, AddRequest.Source source, TorrentPreview preview, String origin, Path defaultSavePath) {
        this.source = source;
        this.preview = preview;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(preview == null ? "Add Magnet Link" : "Add Torrent");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        form.addRow(row++, muted("Source"), selectable(origin));
        form.addRow(row++, muted("Name"), selectable(nameOf(source, preview)));
        form.addRow(
                row++,
                muted("Size"),
                new Label(preview == null ? "Unknown until the metadata arrives" : Bytes.human(preview.size())));

        savePath.setText(defaultSavePath.toString());
        savePath.textProperty().addListener((o, was, now) -> updateFreeSpace());
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> browse());
        HBox.setHgrow(savePath, Priority.ALWAYS);
        HBox pathRow = new HBox(6, savePath, browse);
        form.addRow(row++, muted("Save to"), pathRow);
        freeSpace.getStyleClass().add("dialog-hint");
        form.add(freeSpace, 1, row++);
        updateFreeSpace();

        stop.getItems().addAll(AddRequest.StopCondition.values());
        stop.setValue(AddRequest.StopCondition.NONE);
        stop.setConverter(new StringConverter<>() {
            @Override
            public String toString(AddRequest.StopCondition c) {
                return c == null ? "" : c.label();
            }

            @Override
            public AddRequest.StopCondition fromString(String s) {
                return null;
            }
        });
        VBox options = new VBox(6, startPaused, sequential, skipCheck, new HBox(8, new Label("Stop:"), stop));
        ((HBox) options.getChildren().get(3)).setAlignment(Pos.CENTER_LEFT);
        form.addRow(row++, muted("Options"), options);

        VBox content = new VBox(12, form);
        content.setPadding(new Insets(14));
        if (preview != null && !preview.files().isEmpty()) {
            buildTree();
            VBox.setVgrow(tree, Priority.ALWAYS);
            content.getChildren().addAll(muted("Files"), tree);
        }

        add.setDefaultButton(true);
        add.setOnAction(e -> confirm());
        add.disableProperty().bind(savePath.textProperty().isEmpty());
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, add);
        content.getChildren().add(buttons);

        Scene scene =
                new Scene(content, 640, preview != null && !preview.files().isEmpty() ? 560 : 330);
        scene.getStylesheets()
                .add(AddDialog.class.getResource("/com/torrenta/styles/app.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });
        stage.setScene(scene);
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<AddRequest> showAndWait() {
        savePath.requestFocus();
        savePath.end();
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    /** The dialog's scene, for the capture hook to photograph. */
    Scene scene() {
        return stage.getScene();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    String title() {
        return stage.getTitle();
    }

    /** Closes without adding, as Escape would. */
    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Add, for the capture hook. */
    void confirm() {
        if (add.isDisabled()) return;
        result = new AddRequest(
                source,
                Path.of(savePath.getText().trim()),
                startPaused.isSelected(),
                sequential.isSelected(),
                skipCheck.isSelected(),
                stop.getValue(),
                filePriorities());
        stage.hide();
    }

    private Map<Integer, com.torrenta.engine.Priority> filePriorities() {
        Map<Integer, com.torrenta.engine.Priority> out = new HashMap<>();
        if (tree.getRoot() == null) return out;
        collect(tree.getRoot(), out);
        return out;
    }

    private static void collect(TreeItem<FileTree.Node> item, Map<Integer, com.torrenta.engine.Priority> out) {
        FileTree.Node node = item.getValue();
        if (node.isFile()) {
            boolean wanted = !(item instanceof CheckBoxTreeItem<?> box) || box.isSelected();
            com.torrenta.engine.Priority priority =
                    wanted ? node.priorityProperty().get() : com.torrenta.engine.Priority.IGNORE;
            if (priority != com.torrenta.engine.Priority.NORMAL) out.put(node.index(), priority);
        }
        for (TreeItem<FileTree.Node> child : item.getChildren()) collect(child, out);
    }

    private void buildTree() {
        FileTree.Node root = FileTree.build(preview);
        CheckBoxTreeItem<FileTree.Node> rootItem = toItem(root);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
        tree.setShowRoot(preview.hasRootFolder());
        tree.setEditable(true);
        tree.getStyleClass().add("file-tree");
        tree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TreeTableColumn<FileTree.Node, FileTree.Node> name = new TreeTableColumn<>("File");
        name.setPrefWidth(360);
        name.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().getValue()));
        name.setCellFactory(CheckBoxTreeTableCell.forTreeTableColumn(
                index -> ((CheckBoxTreeItem<FileTree.Node>) tree.getTreeItem(index)).selectedProperty(),
                new StringConverter<>() {
                    @Override
                    public String toString(FileTree.Node node) {
                        return node == null ? "" : node.name();
                    }

                    @Override
                    public FileTree.Node fromString(String s) {
                        return null;
                    }
                }));

        TreeTableColumn<FileTree.Node, String> size = new TreeTableColumn<>("Size");
        size.setPrefWidth(90);
        size.setCellValueFactory(p ->
                new ReadOnlyObjectWrapper<>(Bytes.human(p.getValue().getValue().size())));

        TreeTableColumn<FileTree.Node, com.torrenta.engine.Priority> priority = new TreeTableColumn<>("Priority");
        priority.setPrefWidth(110);
        priority.setCellValueFactory(
                p -> p.getValue().getValue().isFile() ? p.getValue().getValue().priorityProperty() : null);
        priority.setCellFactory(ComboBoxTreeTableCell.forTreeTableColumn(
                new StringConverter<>() {
                    @Override
                    public String toString(com.torrenta.engine.Priority value) {
                        return value == null ? "" : value.label();
                    }

                    @Override
                    public com.torrenta.engine.Priority fromString(String s) {
                        return null;
                    }
                },
                com.torrenta.engine.Priority.LOW,
                com.torrenta.engine.Priority.NORMAL,
                com.torrenta.engine.Priority.HIGH));
        priority.setOnEditCommit(e -> {
            FileTree.Node node = e.getRowValue().getValue();
            if (node.isFile() && e.getNewValue() != null)
                node.priorityProperty().set(e.getNewValue());
        });
        tree.getColumns().addAll(List.of(name, size, priority));
    }

    private static CheckBoxTreeItem<FileTree.Node> toItem(FileTree.Node node) {
        CheckBoxTreeItem<FileTree.Node> item = new CheckBoxTreeItem<>(node, null, true);
        for (FileTree.Node child : node.children()) item.getChildren().add(toItem(child));
        return item;
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Save to");
        File current = new File(savePath.getText().trim());
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) savePath.setText(chosen.getAbsolutePath());
    }

    private void updateFreeSpace() {
        String text = savePath.getText().trim();
        OptionalLong free = text.isEmpty() ? OptionalLong.empty() : FreeSpace.of(Path.of(text));
        freeSpace.setText(free.isPresent() ? Bytes.human(free.getAsLong()) + " free" : "");
    }

    static String nameOf(AddRequest.Source source, TorrentPreview preview) {
        if (preview != null) return preview.name();
        if (source instanceof AddRequest.Magnet magnet) {
            String dn = MagnetNames.displayName(magnet.uri());
            return dn.isEmpty() ? "Unknown until the metadata arrives" : dn;
        }
        return "";
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        return label;
    }

    private static TextField selectable(String text) {
        TextField field = new TextField(text);
        field.setEditable(false);
        field.getStyleClass().add("dialog-value");
        return field;
    }
}
