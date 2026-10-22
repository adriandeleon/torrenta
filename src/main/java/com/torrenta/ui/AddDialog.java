package com.torrenta.ui;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.DownloadPlan;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentPreview;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Add dialog: what the torrent is, which files, where it goes, how it starts.
 *
 * <p>Two columns: the file tree on the left, everything else on the right. One dialog for all
 * three sources. A {@code .torrent} (from disk or a URL) comes with a {@link TorrentPreview} and
 * shows its files at once. A magnet has none yet, and the dialog never waits for it: the window
 * fetches the metadata while the dialog is open ({@link #showFiles} fills the tree in), and until
 * then everything but the files can be set and <em>Add now, pick files when ready</em> adds the
 * magnet to stop when its metadata arrives.
 *
 * <p>Enter adds, Shift+Enter adds paused, Escape cancels. In the tree Space ticks, + and −
 * change the priority, ← and → fold. A category with a save path fills the folder when chosen
 * (automatic torrent management, as qBittorrent calls it); the folder stays editable afterwards.
 * The folder is where the files end up; when the incomplete folder is on, the lines under it say
 * that they start there.
 *
 * <p>The same dialog, with the right column left out, chooses the files of a torrent already in
 * the list that was added before its file list arrived ({@link #chooseFiles}).
 */
final class AddDialog {

    /**
     * What the person decided.
     *
     * @param request what to add, or for {@link #chooseFiles} the file priorities and whether to
     *     start ({@code startPaused} false)
     * @param firstAndLastFirst the torrent's limits get first-and-last-pieces-first after the add
     * @param filesLater added before its file list; its files are to be chosen when it arrives
     * @param withoutAsking from now on, add with these settings and do not open the dialog
     */
    record Choice(AddRequest request, boolean firstAndLastFirst, boolean filesLater, boolean withoutAsking) {}

    /** Where the input came from, for the header's small caps. */
    enum Via {
        FILE,
        URL,
        MAGNET,
        CLIPBOARD,
        SEARCH,
        RSS
    }

    private final Stage stage = new Stage();
    private final boolean existing;
    private final Label kicker = new Label();
    private final Label title = new Label();
    private final Label summary = new Label();
    private final TextField savePath = new TextField();

    {
        savePath.getStyleClass().add("mono-field");
    }

    private final DiskBar diskBar = new DiskBar();
    /** One label per line: the disk sentence, then where the files start if not here. */
    private final VBox diskText = new VBox(1);

    private final CheckBox sequential = new CheckBox(tr("dialog.add.sequential"));
    private final CheckBox firstLast = new CheckBox(tr("dialog.add.firstLast"));
    private final CheckBox skipCheck = new CheckBox(tr("dialog.add.skipCheck"));
    private final ChoiceBox<AddRequest.StopCondition> stop = new ChoiceBox<>();
    private Label stopLabel;
    private Button browse;
    private final ChoiceBox<String> category = new ChoiceBox<>();
    private final TextField tags = new TextField();
    private final CheckBox withoutAsking = new CheckBox(tr("dialog.add.withoutAsking"));
    private final List<Category> categories;
    private final Defaults defaults;
    /** Enter adds paused and Shift+Enter starts, rather than the other way round. */
    private final boolean startPausedDefault;

    private final StackPane treeHost = new StackPane();
    private final TreeTableView<FileTree.Node> tree = new TreeTableView<>();
    private final CheckBox allFiles = new CheckBox();
    private final Label fetching = new Label();
    private final VBox placeholder;
    private FileTree.Node root;

    private final Button cancel = keyed(tr("common.cancel"), tr("dialog.common.key.esc"));
    private final Button add = keyed(tr("common.add"), tr("dialog.common.key.enter"));
    private static final KeyCombination OTHER = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination WAIT = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN);

    private final Button addOther = keyed(tr("dialog.add.addPaused"), keyLabel(OTHER.getDisplayText()));
    private final Button addNow = keyed(tr("dialog.add.addNow"), tr("dialog.common.key.enter"));
    private final Button wait = keyed(tr("dialog.add.wait"), keyLabel(WAIT.getDisplayText()));

    /** Files are known: a .torrent, or a magnet whose metadata has arrived. */
    private final BooleanProperty hasFiles = new SimpleBooleanProperty();
    /** The person chose to wait in the dialog for a magnet's file list. */
    private final BooleanProperty waiting = new SimpleBooleanProperty();
    /** A magnet's file list may still arrive: false once there is no fetch or it failed. */
    private final BooleanProperty fetchAlive = new SimpleBooleanProperty(true);
    /** The engine can add a torrent that stops once its metadata is in (Add now). */
    private final BooleanProperty canStop = new SimpleBooleanProperty(true);

    private final Instant opened = Instant.now();
    /** The priorities a {@link #chooseFiles} tree starts with, by index; empty for an add. */
    private Map<Integer, com.torrenta.engine.Priority> initial = Map.of();

    private AddRequest.Source source;
    private TorrentPreview preview;
    private Choice result;

    /**
     * What the settings say a new torrent starts as.
     *
     * @param savePath the download folder
     * @param startPaused the Start paused box's starting position
     * @param sequential the Sequential box's
     * @param incompleteEnabled whether downloads start in the incomplete folder
     * @param incompleteDir that folder
     * @param partSuffix whether incomplete files carry {@code .part}
     * @param preallocate whether files are allocated in full at the start
     */
    record Defaults(
            Path savePath,
            boolean startPaused,
            boolean sequential,
            boolean incompleteEnabled,
            Path incompleteDir,
            boolean partSuffix,
            boolean preallocate) {

        static Defaults from(com.torrenta.store.Settings settings) {
            return new Defaults(
                    settings.downloadDir(),
                    settings.addStartPaused(),
                    settings.addSequential(),
                    settings.incompleteEnabled(),
                    settings.incompleteDir(),
                    settings.partSuffix(),
                    settings.preallocate());
        }

        /** The same defaults with another folder: a remote engine's own, when the files are not here. */
        Defaults withSavePath(Path path) {
            return new Defaults(
                    path, startPaused, sequential, incompleteEnabled, incompleteDir, partSuffix, preallocate);
        }

        /** Where a torrent saved to {@code destination} starts and ends. */
        DownloadPlan plan(Path destination) {
            return DownloadPlan.of(destination, incompleteEnabled, incompleteDir);
        }

        AddRequest.Storage storage(Path destination) {
            return AddRequest.Storage.of(plan(destination), partSuffix, preallocate);
        }
    }

    /**
     * @param preview the torrent's contents, or null for a magnet
     * @param via where it came from, for the header
     * @param defaults the folder, Start paused (which makes Enter add paused and Shift+Enter
     *     start), Sequential and the storage plan, from the settings
     * @param categories the categories that exist, offered as the torrent's
     */
    AddDialog(
            Window owner,
            AddRequest.Source source,
            TorrentPreview preview,
            Via via,
            Defaults defaults,
            List<Category> categories) {
        this(owner, source, preview, via, defaults, categories, false);
    }

    /**
     * For a torrent already in the list: its files with the priorities they have, a Start and a
     * Keep paused, nothing else. The choice's request carries only the priorities and whether to
     * start; its source and folder mean nothing.
     */
    static AddDialog chooseFiles(
            Window owner, TorrentPreview preview, Map<Integer, com.torrenta.engine.Priority> priorities) {
        AddDialog dialog = new AddDialog(
                owner,
                new AddRequest.Magnet(""),
                null,
                Via.FILE,
                new Defaults(Path.of(""), false, false, false, Path.of(""), false, false),
                List.of(),
                true);
        dialog.initial = Map.copyOf(priorities);
        dialog.showFiles(dialog.source, preview);
        return dialog;
    }

    private AddDialog(
            Window owner,
            AddRequest.Source source,
            TorrentPreview preview,
            Via via,
            Defaults defaults,
            List<Category> categories,
            boolean existing) {
        this.source = source;
        this.preview = preview;
        this.existing = existing;
        this.defaults = defaults;
        this.startPausedDefault = defaults.startPaused();
        this.categories = List.copyOf(categories);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(
                existing
                        ? tr("dialog.add.title.chooseFiles")
                        : preview == null ? tr("dialog.add.title.magnet") : tr("dialog.add.title.torrent"));

        kicker.setText(kicker(via, existing));
        kicker.getStyleClass().add("add-kicker");
        title.setText(nameOf(source, preview));
        title.getStyleClass().add("add-title");
        summary.getStyleClass().add("add-summary");
        summary.setText(preview != null ? summary(preview) : magnetSummary(source));
        VBox header = new VBox(2, kicker, title, summary);

        // ---- left: the files ----
        Label hint = new Label(tr("dialog.add.treeHint"));
        hint.getStyleClass().add("dialog-hint");
        fetching.getStyleClass().add("add-fetching");
        placeholder = new VBox(10, bar(0.78), bar(0.56), bar(0.67), fetching);
        placeholder.getStyleClass().add("add-placeholder");
        placeholder.setPadding(new Insets(16));
        treeHost.getStyleClass().add("add-files");
        buildTree();
        VBox.setVgrow(treeHost, Priority.ALWAYS);
        VBox left = new VBox(6, treeHost, hint);
        HBox.setHgrow(left, Priority.ALWAYS);
        left.setMinWidth(320);

        // ---- right: where and how ----
        savePath.setText(defaults.savePath().toString());
        savePath.textProperty().addListener((o, was, now) -> updateDisk());
        browse = new Button(tr("common.browse"));
        browse.setOnAction(e -> browse());
        HBox.setHgrow(savePath, Priority.ALWAYS);
        HBox pathRow = new HBox(6, savePath, browse);
        diskText.setMinWidth(0);

        sequential.setSelected(defaults.sequential());
        stop.getItems().addAll(AddRequest.StopCondition.values());
        stop.setValue(AddRequest.StopCondition.NONE);
        stop.setConverter(converter(c -> c == null ? "" : c.label()));
        stop.setMaxWidth(Double.MAX_VALUE);

        category.getItems().add("");
        for (Category c : this.categories) category.getItems().add(c.name());
        category.setValue("");
        category.setConverter(converter(name -> name == null || name.isEmpty() ? tr("common.none") : name));
        category.setMaxWidth(Double.MAX_VALUE);
        category.valueProperty().addListener((o, was, now) -> categoryPath(now).ifPresent(savePath::setText));
        tags.setPromptText(tr("dialog.add.tags.prompt"));

        GridPane filing = new GridPane();
        filing.setHgap(10);
        filing.setVgap(4);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        filing.getColumnConstraints().addAll(half, half);
        stopLabel = muted(tr("dialog.add.stopWhen"));
        filing.addRow(0, muted(tr("dialog.common.category")), stopLabel);
        filing.addRow(1, category, stop);

        // First-and-last is piece priorities, which need the metadata: not for "Add now".
        firstLast.disableProperty().bind(hasFiles.not());
        VBox options = new VBox(6, sequential, firstLast, skipCheck);

        VBox right = new VBox(
                4,
                muted(tr("dialog.common.saveTo")),
                pathRow,
                spaced(diskBar, 8),
                diskText,
                spaced(filing, 12),
                spaced(muted(tr("dialog.common.tags")), 12),
                tags,
                spaced(muted(tr("dialog.add.options")), 12),
                options);
        right.setPrefWidth(300);
        right.setMinWidth(300);
        right.setMaxWidth(300);

        HBox body = new HBox(18, left);
        if (!existing) body.getChildren().add(right);
        VBox.setVgrow(body, Priority.ALWAYS);

        // ---- footer ----
        add.setDefaultButton(true);
        add.setOnAction(e -> confirm(startPausedDefault));
        addOther.setOnAction(e -> confirm(!startPausedDefault));
        if (startPausedDefault) {
            add.setText(existing ? tr("dialog.add.keepPaused") : tr("dialog.add.addPaused"));
            addOther.setText(existing ? tr("dialog.add.start") : tr("dialog.add.addAndStart"));
        } else if (existing) {
            add.setText(tr("dialog.add.start"));
            addOther.setText(tr("dialog.add.keepPaused"));
        }
        addNow.setOnAction(e -> addNow());
        wait.setOnAction(e -> waitHere());
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());

        // Which buttons show is buttons(…), the rule: Add and its partner once there are files (or
        // while waiting for them), Add now and Wait while a magnet's metadata is on its way, never
        // Wait once it cannot come, never Add now on an engine that cannot stop after metadata.
        java.util.function.Supplier<Buttons> rule =
                () -> buttons(hasFiles.get(), waiting.get(), fetchAlive.get(), canStop.get());
        Observable[] inputs = {hasFiles, waiting, fetchAlive, canStop};
        var showAddNow = Bindings.createBooleanBinding(() -> rule.get().addNow(), inputs);
        var showWait = Bindings.createBooleanBinding(() -> rule.get().waitHere(), inputs);
        var showAdd = Bindings.createBooleanBinding(() -> rule.get().add(), inputs);
        var blind = Bindings.createBooleanBinding(() -> rule.get().blind(), inputs);
        addNow.visibleProperty().bind(showAddNow);
        addNow.managedProperty().bind(showAddNow);
        wait.visibleProperty().bind(showWait);
        wait.managedProperty().bind(showWait);
        for (Button b : List.of(add, addOther)) {
            b.visibleProperty().bind(showAdd);
            b.managedProperty().bind(showAdd);
        }
        add.defaultButtonProperty().bind(showAddNow.not());
        addNow.defaultButtonProperty().bind(showAddNow);
        var noPath = existing
                ? new SimpleBooleanProperty(false)
                : savePath.textProperty().isEmpty();
        var noFiles = hasFiles.not().and(blind.not());
        add.disableProperty().bind(noPath.or(noFiles));
        addOther.disableProperty().bind(noPath.or(noFiles));
        addNow.disableProperty().bind(noPath);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, spacer, cancel, wait, addOther, addNow, add);
        // Wraps rather than clips where the chords are spelled out long (Shift+Enter).
        withoutAsking.setWrapText(true);
        withoutAsking.setMinWidth(180);
        buttons.setAlignment(Pos.CENTER_LEFT);
        // The checkbox shares the buttons' line, except beside Add now and Wait, which need it all.
        HBox above = new HBox();
        VBox footer = new VBox(8, above, buttons);
        footer.getStyleClass().add("add-footer");
        if (!existing) {
            Runnable place = () -> {
                above.getChildren().clear();
                buttons.getChildren().remove(withoutAsking);
                if (!showAdd.get()) above.getChildren().add(withoutAsking);
                else buttons.getChildren().add(0, withoutAsking);
                above.setManaged(!showAdd.get());
            };
            // showAdd, not a negation of it: the buttons' visibility holds it, so it cannot be collected.
            showAdd.addListener((o, was, now) -> place.run());
            place.run();
        } else {
            above.setManaged(false);
        }

        VBox content = new VBox(14, header, body, footer);
        content.setPadding(new Insets(18, 20, 16, 20));
        content.getStyleClass().add("add-dialog");

        Scene scene = new Scene(content, existing ? 560 : 780, 560);
        scene.getStylesheets()
                .add(AddDialog.class.getResource("/com/torrenta/styles/app.css").toExternalForm());
        scene.getStylesheets()
                .add(AddDialog.class
                        .getResource("/com/torrenta/styles/add-dialog.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.getAccelerators().put(OTHER, () -> fire(addOther));
        scene.getAccelerators().put(WAIT, () -> fire(wait));
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);

        if (preview != null) showFiles(source, preview);
        else if (!existing) showFetching(0);
        updateDisk();
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<Choice> showAndWait() {
        if (existing) {
            tree.requestFocus();
        } else {
            savePath.requestFocus();
            savePath.end();
        }
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    /**
     * The magnet's metadata has arrived: the tree fills in and Add takes over from Add now. The
     * source becomes the fetched {@code .torrent}, so the files chosen here are the ones added.
     * Ignored once the dialog has closed or files are already there.
     */
    void showFiles(AddRequest.Source fetched, TorrentPreview files) {
        if (hasFiles.get() || (result != null)) return;
        this.source = fetched;
        this.preview = files;
        title.setText(nameOf(fetched, files));
        summary.setText(summary(files));
        root = FileTree.build(files);
        CheckBoxTreeItem<FileTree.Node> rootItem = toItem(root);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
        tree.setShowRoot(files.hasRootFolder());
        for (FileTree.Node file : FileTree.files(root)) {
            com.torrenta.engine.Priority own = initial.get(file.index());
            if (own == com.torrenta.engine.Priority.IGNORE)
                file.wantedProperty().set(false);
            else if (own != null) file.priorityProperty().set(own);
            file.wantedProperty().addListener((o, was, now) -> filesChanged());
        }
        treeHost.getChildren().setAll(tree);
        hasFiles.set(true);
        filesChanged();
        if (tree.getSelectionModel().isEmpty()) tree.getSelectionModel().select(0);
        // The tree takes the focus only from a button (Wait was pressed) or from nothing: never
        // from a field the person is typing in when the files arrive.
        javafx.scene.Node owner = stage.getScene().getFocusOwner();
        if (existing || owner == null || owner instanceof Button) tree.requestFocus();
    }

    /** Once a second while the file list is on its way: how many peers are being asked. */
    void showFetching(int peers) {
        if (hasFiles.get()) return;
        fetching.setText(
                fetchingLine(peers, Duration.between(opened, Instant.now()).toSeconds()));
        if (!treeHost.getChildren().contains(placeholder))
            treeHost.getChildren().setAll(placeholder);
    }

    /**
     * There is no fetch to wait for: the engine lists the files once the torrent is added. Said in
     * the tree's place, not as a failure; Add adds the magnet blind.
     */
    void noFetch(String message) {
        if (hasFiles.get()) return;
        fetching.setText(message);
        waiting.set(false);
        fetchAlive.set(false);
    }

    /**
     * Hides the choices the engine has no capability for: skip check (seed mode), the stop
     * condition, first and last pieces, sequential, and Browse with the free-space readout when
     * the files are not on this machine. Before {@link #show}.
     */
    void applyCapabilities(com.torrenta.engine.Engine.Capabilities caps) {
        shown(skipCheck, caps.has(com.torrenta.engine.Engine.Capability.SEED_MODE));
        boolean stopping = caps.has(com.torrenta.engine.Engine.Capability.STOP_CONDITION);
        shown(stop, stopping);
        shown(stopLabel, stopping);
        canStop.set(stopping);
        shown(firstLast, caps.has(com.torrenta.engine.Engine.Capability.FIRST_LAST_PIECES));
        shown(sequential, caps.has(com.torrenta.engine.Engine.Capability.SEQUENTIAL_DOWNLOAD));
        boolean local = caps.has(com.torrenta.engine.Engine.Capability.LOCAL_FILES);
        shown(browse, local);
        shown(diskBar, local);
        shown(diskText, local);
    }

    private static void shown(javafx.scene.Node node, boolean on) {
        node.setVisible(on);
        node.setManaged(on);
    }

    /** The fetch could not start (already in the list) or failed; Add now still works. */
    void fetchFailed(String message) {
        if (hasFiles.get()) return;
        fetching.setText(message);
        fetching.getStyleClass().add("add-fetch-failed");
        waiting.set(false);
        fetchAlive.set(false);
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

    /** Whether it is still waiting on a magnet's file list. */
    boolean awaitingFiles() {
        return !hasFiles.get();
    }

    /** Closes without adding, as Escape would. */
    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses the default button (Add, or Add now while fetching), for the capture hook. */
    void confirm() {
        fire(addNow.isVisible() ? addNow : add);
    }

    /** Presses a button by name ({@code paused}, {@code now}, {@code wait}), for the capture hook. */
    void pressForCapture(String which) {
        switch (which) {
            case "paused" -> fire(startPausedDefault ? add : addOther);
            case "now" -> fire(addNow);
            case "wait" -> fire(wait);
            default -> System.err.println("capture: no dialog button " + which);
        }
    }

    /** What a screenshot cannot show: the header, the disk line, the buttons, the files. */
    String summaryForCapture() {
        List<String> buttons = new ArrayList<>();
        for (Button b : List.of(cancel, wait, addOther, addNow, add)) {
            if (b.isVisible())
                buttons.add(b.getText() + (b.isDefaultButton() ? "*" : "") + (b.isDisabled() ? "(off)" : ""));
        }
        String files = hasFiles.get()
                ? FileTree.files(root).size() + " files, " + Bytes.human(FileTree.wantedSize(root)) + " wanted"
                : "placeholder: " + fetching.getText();
        return kicker.getText() + " | " + title.getText() + " | " + summary.getText() + " | " + hint() + " | " + files
                + " | buttons=" + buttons;
    }

    /** Picks a category as a person would, for the capture hook. */
    void selectCategoryForCapture(String name) {
        if (category.getItems().contains(name)) category.setValue(name);
    }

    /** Presses a key in the tree, as a person would, for the capture hook. */
    void keyForCapture(KeyCode code) {
        if (tree.getRoot() == null) return;
        tree.requestFocus();
        if (tree.getSelectionModel().isEmpty()) tree.getSelectionModel().select(0);
        tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private void confirm(boolean paused) {
        boolean blind = buttons(hasFiles.get(), waiting.get(), fetchAlive.get(), canStop.get())
                .blind();
        if ((!hasFiles.get() && !blind) || (!existing && savePath.getText().isBlank())) return;
        Path destination = existing ? Path.of("") : Path.of(savePath.getText().trim());
        result = new Choice(
                new AddRequest(
                        source,
                        destination,
                        paused,
                        sequential.isSelected(),
                        skipCheck.isSelected(),
                        stop.getValue(),
                        root == null ? Map.of() : FileTree.priorities(root),
                        labels(),
                        existing ? AddRequest.Storage.DEFAULT : defaults.storage(destination)),
                firstLast.isSelected(),
                false,
                withoutAsking.isSelected());
        stage.hide();
    }

    /** Adds the magnet now, to stop once its metadata is in; the files are chosen then. */
    private void addNow() {
        if (hasFiles.get() || savePath.getText().isBlank()) return;
        Path destination = Path.of(savePath.getText().trim());
        result = new Choice(
                new AddRequest(
                        source,
                        destination,
                        false,
                        sequential.isSelected(),
                        skipCheck.isSelected(),
                        AddRequest.StopCondition.METADATA_RECEIVED,
                        Map.of(),
                        labels(),
                        defaults.storage(destination)),
                false,
                true,
                withoutAsking.isSelected());
        stage.hide();
    }

    private void waitHere() {
        waiting.set(true);
        if (!tags.isFocused() && !savePath.isFocused()) add.requestFocus();
    }

    private TorrentLabels labels() {
        return new TorrentLabels(category.getValue(), List.of(tags.getText()));
    }

    private static void fire(Button button) {
        if (button.isVisible() && !button.isDisabled()) button.fire();
    }

    /** The folder a category brings with it, if it has one. */
    private Optional<String> categoryPath(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        return categories.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .flatMap(Category::path)
                .map(Path::toString);
    }

    // ---- the tree --------------------------------------------------------------------------------

    private void buildTree() {
        tree.setEditable(true); // the checkbox cells take clicks only in an editable table
        tree.getStyleClass().add("file-tree");
        tree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TreeTableColumn<FileTree.Node, FileTree.Node> name = new TreeTableColumn<>();
        Label filesHeader = new Label(tr("dialog.add.column.files"));
        HBox nameHeader = new HBox(8, allFiles, filesHeader);
        nameHeader.setAlignment(Pos.CENTER_LEFT);
        name.setGraphic(nameHeader);
        name.setSortable(false);
        name.setPrefWidth(300);
        allFiles.setFocusTraversable(false);
        allFiles.setOnAction(e -> setWanted(root, allFiles.isSelected()));
        name.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().getValue()));
        name.setCellFactory(column -> new NameCell());

        TreeTableColumn<FileTree.Node, String> size = new TreeTableColumn<>(tr("dialog.add.column.size"));
        size.setSortable(false);
        size.setPrefWidth(92);
        size.setMinWidth(92);
        size.setMaxWidth(110);
        size.getStyleClass().add("add-size-column");
        size.setCellValueFactory(p ->
                new ReadOnlyObjectWrapper<>(Bytes.human(p.getValue().getValue().size())));

        TreeTableColumn<FileTree.Node, String> priority = new TreeTableColumn<>(tr("dialog.add.column.priority"));
        priority.setSortable(false);
        priority.setEditable(false);
        priority.setPrefWidth(72);
        priority.setMinWidth(72);
        priority.setMaxWidth(110);
        priority.setCellValueFactory(p -> {
            FileTree.Node node = p.getValue().getValue();
            List<Observable> deps = new ArrayList<>();
            for (FileTree.Node f : FileTree.files(node)) {
                deps.add(f.wantedProperty());
                deps.add(f.priorityProperty());
            }
            return Bindings.createStringBinding(() -> FileTree.label(node), deps.toArray(Observable[]::new));
        });
        priority.setCellFactory(column -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("priority-high", "priority-skip");
                if (!empty && com.torrenta.engine.Priority.HIGH.label().equals(item))
                    getStyleClass().add("priority-high");
                if (!empty && com.torrenta.engine.Priority.IGNORE.label().equals(item))
                    getStyleClass().add("priority-skip");
            }
        });
        tree.getColumns().addAll(List.of(name, size, priority));

        ContextMenu menu = new ContextMenu();
        for (com.torrenta.engine.Priority p : List.of(
                com.torrenta.engine.Priority.HIGH,
                com.torrenta.engine.Priority.NORMAL,
                com.torrenta.engine.Priority.LOW,
                com.torrenta.engine.Priority.IGNORE)) {
            MenuItem item = new MenuItem(p.label());
            item.setOnAction(e -> selectedNodes().forEach(n -> setPriority(n, p)));
            menu.getItems().add(item);
        }
        tree.setContextMenu(menu);
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (treeKey(e.getCode())) e.consume();
        });
    }

    /** Space, + and −; ← and → are the tree's own. True when the key was taken. */
    private boolean treeKey(KeyCode code) {
        List<FileTree.Node> nodes = selectedNodes();
        if (nodes.isEmpty()) return false;
        switch (code) {
            case SPACE -> {
                boolean all = nodes.stream()
                        .flatMap(n -> FileTree.files(n).stream())
                        .allMatch(f -> f.wantedProperty().get());
                nodes.forEach(n -> setWanted(n, !all));
            }
            case PLUS, ADD, EQUALS -> nodes.forEach(n -> FileTree.step(n, +1));
            case MINUS, SUBTRACT -> nodes.forEach(n -> FileTree.step(n, -1));
            default -> {
                return false;
            }
        }
        return true;
    }

    private List<FileTree.Node> selectedNodes() {
        return tree.getSelectionModel().getSelectedItems().stream()
                .filter(java.util.Objects::nonNull)
                .map(TreeItem::getValue)
                .toList();
    }

    private static void setWanted(FileTree.Node node, boolean wanted) {
        if (node == null) return;
        for (FileTree.Node f : FileTree.files(node)) f.wantedProperty().set(wanted);
    }

    private static void setPriority(FileTree.Node node, com.torrenta.engine.Priority p) {
        for (FileTree.Node f : FileTree.files(node)) {
            if (p == com.torrenta.engine.Priority.IGNORE) {
                f.wantedProperty().set(false);
            } else {
                f.priorityProperty().set(p);
                f.wantedProperty().set(true);
            }
        }
    }

    /**
     * The name with its checkbox. The stock {@code CheckBoxTreeTableCell} draws only "selected",
     * so a folder with some files ticked looked fully ticked; this one shows the item's
     * indeterminate state too, and cuts long names in the middle so the extension stays.
     */
    private static final class NameCell extends TreeTableCell<FileTree.Node, FileTree.Node> {
        private final CheckBox box = new CheckBox();
        private final javafx.beans.value.ChangeListener<Boolean> mixed = (o, was, now) -> box.setIndeterminate(now);
        private CheckBoxTreeItem<FileTree.Node> bound;

        NameCell() {
            box.setFocusTraversable(false);
            setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
            setGraphicTextGap(8);
        }

        @Override
        protected void updateItem(FileTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            if (bound != null) {
                box.selectedProperty().unbindBidirectional(bound.selectedProperty());
                bound.indeterminateProperty().removeListener(mixed);
                bound = null;
            }
            if (empty
                    || node == null
                    || !(getTableRow().getTreeItem() instanceof CheckBoxTreeItem<FileTree.Node> item)) {
                setText(null);
                setGraphic(null);
                return;
            }
            bound = item;
            box.setIndeterminate(item.isIndeterminate());
            box.selectedProperty().bindBidirectional(item.selectedProperty());
            item.indeterminateProperty().addListener(mixed);
            setText(node.name());
            setGraphic(box);
        }
    }

    /** The header box and the disk bar follow the ticks. */
    private void filesChanged() {
        if (root == null) return;
        List<FileTree.Node> files = FileTree.files(root);
        long wanted = files.stream().filter(f -> f.wantedProperty().get()).count();
        allFiles.setIndeterminate(wanted > 0 && wanted < files.size());
        allFiles.setSelected(wanted == files.size());
        updateDisk();
    }

    /** Files are bound to their node's wanted flag; folders follow their files, as the item does. */
    private static CheckBoxTreeItem<FileTree.Node> toItem(FileTree.Node node) {
        CheckBoxTreeItem<FileTree.Node> item = new CheckBoxTreeItem<>(node, null, true);
        for (FileTree.Node child : node.children()) item.getChildren().add(toItem(child));
        if (node.isFile()) item.selectedProperty().bindBidirectional(node.wantedProperty());
        return item;
    }

    // ---- the right column ------------------------------------------------------------------------

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.common.saveTo"));
        File current = new File(savePath.getText().trim());
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) savePath.setText(chosen.getAbsolutePath());
    }

    private void updateDisk() {
        if (existing) return;
        String text = savePath.getText().trim();
        Path destination;
        try {
            destination = text.isEmpty() ? null : Path.of(text);
        } catch (RuntimeException e) {
            destination = null;
        }
        Optional<FreeSpace.Disk> disk = destination == null ? Optional.empty() : FreeSpace.disk(destination);
        long wanted = root == null ? -1 : FileTree.wantedSize(root);
        diskBar.set(disk.map(d -> FreeSpace.bar(d, wanted)).orElse(new FreeSpace.Bar(0, 0, true)));
        String line = disk.map(d -> FreeSpace.sentence(d, wanted))
                .orElse(text.isEmpty() ? "" : tr("dialog.add.freeSpaceUnknown"));
        // With the incomplete folder on, the files start elsewhere: say where, and its free space.
        DownloadPlan plan = destination == null ? null : defaults.plan(destination);
        if (plan != null && plan.viaIncomplete()) {
            line += "\n" + FreeSpace.hint(plan, FreeSpace.of(plan.start()), java.util.OptionalLong.empty());
        }
        diskText.getChildren().clear();
        for (String part : line.split("\n")) {
            if (part.isEmpty()) continue;
            Label label = new Label(part);
            label.getStyleClass().add("dialog-hint");
            // A long folder is cut in the middle, keeping both its root and its last folder.
            label.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
            label.setMinWidth(0);
            label.setMaxWidth(Double.MAX_VALUE);
            diskText.getChildren().add(label);
        }
    }

    /** The lines under the save path, for the capture hook; joined with " / ". */
    String hint() {
        return String.join(
                " / ",
                diskText.getChildren().stream().map(n -> ((Label) n).getText()).toList());
    }

    /** A thin bar: what is used of the volume, then in the accent what the torrent will take. */
    private static final class DiskBar extends Region {
        private final Region used = new Region();
        private final Region torrent = new Region();
        private FreeSpace.Bar bar = new FreeSpace.Bar(0, 0, true);

        DiskBar() {
            getStyleClass().add("disk-bar");
            used.getStyleClass().add("disk-used");
            torrent.getStyleClass().add("disk-torrent");
            getChildren().addAll(used, torrent);
            setMinHeight(6);
            setPrefHeight(6);
            setMaxHeight(6);
        }

        void set(FreeSpace.Bar bar) {
            this.bar = bar;
            torrent.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("over"), !bar.fits());
            requestLayout();
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            double u = Math.round(w * bar.used());
            double t = bar.torrent() > 0 ? Math.max(2, Math.round(w * bar.torrent())) : 0;
            used.resizeRelocate(0, 0, u, h);
            torrent.resizeRelocate(u, 0, Math.min(t, w - u), h);
        }
    }

    // ---- the rules -------------------------------------------------------------------------------

    /**
     * The footer's buttons: {@code addNow} (Add now, pick files when ready), {@code waitHere} (Wait
     * here for the file list), {@code add} (Add and its partner), and {@code blind} when Add takes
     * the magnet without its file list.
     */
    record Buttons(boolean addNow, boolean waitHere, boolean add, boolean blind) {}

    /**
     * Which buttons the footer shows. With files, Add. Waiting on a fetch that may still answer,
     * Add, off until the files come. Otherwise Wait only while the fetch is alive (a failed or
     * absent fetch never answers, and waiting on it was a dead end), and Add now only where the
     * engine can stop the torrent once its metadata is in; an engine that cannot gets the plain
     * Add and Add paused, adding the magnet blind, since Add now would download every file.
     */
    static Buttons buttons(boolean hasFiles, boolean waiting, boolean fetchAlive, boolean canStop) {
        if (hasFiles) return new Buttons(false, false, true, false);
        if (waiting && fetchAlive) return new Buttons(false, false, true, false);
        return new Buttons(canStop, fetchAlive, !canStop, !canStop);
    }

    /** "ADD TORRENT · FROM FILE", or "CHOOSE FILES" for a torrent already in the list. */
    static String kicker(Via via, boolean existing) {
        if (existing) return tr("dialog.add.kicker.chooseFiles");
        return tr("dialog.add.kicker", tr("dialog.add.via." + via.name().toLowerCase(Locale.ROOT)));
    }

    /** "5.9 GiB · 3 files · hybrid v1+v2 · 4 trackers · “Encoded by …”". */
    static String summary(TorrentPreview p) {
        List<String> parts = new ArrayList<>();
        parts.add(Bytes.human(p.size()));
        parts.add(tr("dialog.add.files", p.files().size()));
        String version = p.v1() && p.v2() ? tr("dialog.add.version.hybrid") : p.v2() ? "v2" : p.v1() ? "v1" : "";
        if (!version.isEmpty()) parts.add(version);
        if (p.isPrivate()) parts.add(tr("dialog.add.private"));
        parts.add(tr("dialog.add.trackers", p.trackers().size()));
        String comment = p.comment().strip().replaceAll("\\s+", " ");
        if (!comment.isEmpty()) parts.add(tr("dialog.add.comment", comment));
        return String.join(" · ", parts);
    }

    /** What a magnet says before its metadata: only its trackers. */
    static String magnetSummary(AddRequest.Source source) {
        if (!(source instanceof AddRequest.Magnet magnet)) return "";
        int trackers = MagnetNames.trackers(magnet.uri());
        return tr("dialog.add.magnetSummary", tr("dialog.add.magnetTrackers", trackers));
    }

    /** "Fetching file list · 3 peers · 12s"; a minute and more reads "1m 05s". */
    static String fetchingLine(int peers, long seconds) {
        long s = Math.max(0, seconds);
        String elapsed = s < 60
                ? tr("dialog.add.elapsed.seconds", Long.toString(s))
                : tr("dialog.add.elapsed.minutes", Long.toString(s / 60), String.format(Locale.ROOT, "%02d", s % 60));
        return tr("dialog.add.fetching", tr("dialog.add.peers", Math.max(0, peers)), elapsed);
    }

    /**
     * A chord as the buttons show it, with the key spelled out as the plain Enter beside it is:
     * JavaFX writes Enter as an arrow ("Shift+↵", "⌘↵" on macOS).
     */
    static String keyLabel(String displayText) {
        return displayText.replace("↵", tr("dialog.common.key.enter")).replace("⏎", tr("dialog.common.key.enter"));
    }

    /** The request an input is added with when the dialog does not open: the settings' defaults. */
    static AddRequest withoutAsking(AddRequest.Source source, Defaults defaults) {
        return new AddRequest(
                source,
                defaults.savePath(),
                defaults.startPaused(),
                defaults.sequential(),
                false,
                AddRequest.StopCondition.NONE,
                Map.of(),
                TorrentLabels.NONE,
                defaults.storage(defaults.savePath()));
    }

    /**
     * The folder "add with these settings" keeps as the download folder: the one chosen, unless it
     * is the default already or came with the category (a category's folder is the category's,
     * not every torrent's).
     */
    static Optional<Path> folderToRemember(Path chosen, Path defaultFolder, Optional<Path> categoryFolder) {
        Path c = chosen.toAbsolutePath().normalize();
        if (c.equals(defaultFolder.toAbsolutePath().normalize())) return Optional.empty();
        if (categoryFolder.map(f -> f.toAbsolutePath().normalize().equals(c)).orElse(false)) return Optional.empty();
        return Optional.of(chosen);
    }

    static String nameOf(AddRequest.Source source, TorrentPreview preview) {
        if (preview != null) return preview.name();
        if (source instanceof AddRequest.Magnet magnet) {
            String dn = MagnetNames.displayName(magnet.uri());
            return dn.isEmpty() ? tr("dialog.add.nameUnknown") : dn;
        }
        return "";
    }

    // ---- small pieces ----------------------------------------------------------------------------

    private static Button keyed(String text, String key) {
        Button button = new Button(text);
        button.setMinWidth(Region.USE_PREF_SIZE);
        Label hint = new Label(key);
        hint.getStyleClass().add("key-hint");
        button.setGraphic(hint);
        button.setContentDisplay(ContentDisplay.RIGHT);
        button.setGraphicTextGap(6);
        return button;
    }

    private static Region bar(double fraction) {
        Region bar = new Region();
        bar.getStyleClass().add("add-placeholder-bar");
        bar.setMinHeight(12);
        bar.setPrefHeight(12);
        bar.setMaxWidth(Region.USE_PREF_SIZE);
        bar.setPrefWidth(380 * fraction);
        return bar;
    }

    private static VBox spaced(javafx.scene.Node node, double top) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(top, 0, 0, 0));
        return box;
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> show) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return show.apply(value);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        return label;
    }
}
