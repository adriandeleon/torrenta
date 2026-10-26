package com.torrenta.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.torrenta.engine.CreateRequest;
import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.PieceSize;
import com.torrenta.session.Session;
import com.torrenta.store.Settings;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Create Torrent dialog: a file or folder, the format, the piece size, the flags, the
 * trackers and web seeds, then Create…, which asks where to save and hashes on a worker.
 *
 * <p>Shaped as the Add dialog (its own stage, the app's stylesheet and font, Enter creates,
 * Escape closes) but modeless: it stays open after the torrent is made so the info-hash and the
 * magnet link can be copied while the new torrent seeds in the list. The source is sized on a
 * worker as soon as it is chosen, so the header shows the piece count for the piece size chosen,
 * live. While hashing the form is disabled, a bar shows the pieces done, and Cancel ends it: the
 * worker stops at the next piece, and a generation counter drops whatever a stale run still
 * reports. The file is written beside its final name and moved into place, so an interrupted
 * write leaves no half torrent.
 *
 * <p>With a {@link Publisher} (an engine with {@code Capability.MUTABLE}) the torrent can be
 * published as updatable (BEP 46): a key made under a name, the torrent seeded and put in the
 * DHT, and the link that follows it shown with the hashes. Opened {@link #forNewVersion} on a
 * published torrent, the same key publishes what is created as its next version.
 */
final class CreateDialog {

    /** What publishing needs of the session, run off the toolkit thread. */
    interface Publisher {
        /** Why a new key cannot have this name, or empty. */
        String problem(String name);

        Path keysDir();

        Session.Published publish(String name, CreatedTorrent made) throws EngineException, IOException;

        Session.Published newVersion(String name, CreatedTorrent made) throws EngineException, IOException;
    }

    private final Stage stage = new Stage();
    private final Engine engine;
    private final Settings settings;

    private final Label title = new Label();
    private final Label summary = new Label();
    private final TextField source = new TextField();
    private final ChoiceBox<CreateRequest.Format> format = new ChoiceBox<>();
    /** 0 is automatic, the rest the ladder. */
    private final ChoiceBox<Integer> pieceSize = new ChoiceBox<>();

    private final CheckBox isPrivate = new CheckBox(tr("dialog.create.private"));
    private final CheckBox seed = new CheckBox(tr("dialog.create.seed"));
    private final CheckBox align = new CheckBox(tr("dialog.create.align"));
    private final TextArea trackers = new TextArea();
    private final TextArea webSeeds = new TextArea();
    private final TextField comment = new TextField();
    private final TextField sourceTag = new TextField();
    private final CheckBox publish = new CheckBox(tr("dialog.create.publish"));
    private final TextField publishName = new TextField();
    private final Label publishNote = new Label();
    private final Publisher publisher;
    /** The publication this creates the next version of, or null for a torrent of its own. */
    private String newVersionOf;

    private final VBox form;

    private final ProgressBar bar = new ProgressBar();
    private final Label progressLine = new Label();
    private final VBox progressBox;
    private final TextField v1 = value();
    private final TextField v2 = value();
    private final TextField magnet = value();
    private final TextField updatable = value();
    private final Label updatableLabel = muted(tr("dialog.create.updatableLink"));
    private final Button updatableCopy = copyButton(updatable);
    private final Label outcome = new Label();
    private final GridPane resultBox = new GridPane();
    private final Label problem = new Label();

    private final Button close = keyed(tr("common.close"), tr("dialog.common.key.esc"));
    private final Button create = keyed(tr("dialog.create.create"), tr("dialog.common.key.enter"));
    private final Button cancelHashing = new Button(tr("common.cancel"));

    private final BooleanProperty hashing = new SimpleBooleanProperty();
    /** The sizes of the source's files, for the piece count; empty until sized. */
    private List<Long> sizes = List.of();
    /** -1 while sizing. */
    private int fileCount = -1;

    private volatile int sizeGeneration;
    /** Bumped by Cancel and by a new run: a worker whose generation is stale is ignored. */
    private volatile int hashGeneration;

    private CreatedTorrent made;
    /** What publishing said, for the capture: empty before, else a line. */
    private String publishedLine = "";

    private Path savedTo;
    private boolean seedAsked;

    CreateDialog(Window owner, Engine engine, Settings settings, Publisher publisher) {
        this.engine = engine;
        this.settings = settings;
        this.publisher = publisher;
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle(tr("dialog.create.title"));

        Label kicker = new Label(tr("dialog.create.kicker"));
        kicker.getStyleClass().add("add-kicker");
        title.getStyleClass().add("add-title");
        summary.getStyleClass().add("add-summary");
        VBox header = new VBox(2, kicker, title, summary);

        // ---- the source ----
        source.setPromptText(tr("dialog.create.source.prompt"));
        source.textProperty().addListener((o, was, now) -> sourceChanged());
        Button file = new Button(tr("dialog.create.file"));
        file.setOnAction(e -> chooseFile());
        Button folder = new Button(tr("dialog.create.folder"));
        folder.setOnAction(e -> chooseFolder());
        HBox.setHgrow(source, Priority.ALWAYS);
        HBox sourceRow = new HBox(6, source, file, folder);
        Label drop = new Label(tr("dialog.create.drop"));
        drop.getStyleClass().add("dialog-hint");

        // ---- format, piece size, flags ----
        format.getItems().addAll(CreateRequest.Format.values());
        format.setValue(CreateRequest.Format.HYBRID);
        format.setConverter(converter(f -> f == null ? "" : f.label()));
        format.setMaxWidth(Double.MAX_VALUE);
        format.valueProperty().addListener((o, was, now) -> formatChanged());
        pieceSize.getItems().add(0);
        pieceSize.getItems().addAll(PieceSize.choices());
        pieceSize.setValue(0);
        pieceSize.setMaxWidth(Double.MAX_VALUE);
        pieceSize.valueProperty().addListener((o, was, now) -> updateSummary());
        GridPane options = twoColumns();
        options.addRow(0, muted(tr("dialog.create.format")), muted(tr("dialog.create.pieceSize")));
        options.addRow(1, format, pieceSize);

        align.selectedProperty().addListener((o, was, now) -> updateSummary());
        formatChanged();
        HBox flags = new HBox(18, isPrivate, seed, align);
        flags.setAlignment(Pos.CENTER_LEFT);

        // ---- publishing as updatable ----
        publishName.setPromptText(tr("dialog.create.publish.prompt"));
        publishName.setPrefColumnCount(18);
        publishName.disableProperty().bind(publish.selectedProperty().not());
        publishNote.getStyleClass().add("dialog-hint");
        publishNote.setWrapText(true);
        publish.selectedProperty().addListener((o, was, now) -> publishChanged());
        HBox publishRow = new HBox(10, publish, publishName);
        publishRow.setAlignment(Pos.CENTER_LEFT);
        VBox publishBox = new VBox(4, publishRow, publishNote);
        publishBox.setVisible(publisher != null);
        publishBox.setManaged(publisher != null);
        publishChanged();

        // ---- trackers and web seeds ----
        trackers.setPromptText(tr("dialog.create.trackers.prompt"));
        trackers.setPrefRowCount(5);
        trackers.setText(settings.createDefaultTrackers());
        webSeeds.setPromptText(tr("dialog.create.webSeeds.prompt"));
        webSeeds.setPrefRowCount(5);
        VBox trackerBox = new VBox(4, muted(tr("dialog.create.trackers")), trackers);
        VBox seedBox = new VBox(4, muted(tr("dialog.create.webSeeds")), webSeeds);
        VBox.setVgrow(trackers, Priority.ALWAYS);
        VBox.setVgrow(webSeeds, Priority.ALWAYS);
        HBox.setHgrow(trackerBox, Priority.ALWAYS);
        HBox.setHgrow(seedBox, Priority.ALWAYS);
        trackerBox.setMinWidth(0);
        seedBox.setMinWidth(0);
        HBox lists = new HBox(10, trackerBox, seedBox);
        VBox.setVgrow(lists, Priority.ALWAYS);

        comment.setPromptText(tr("dialog.create.comment.prompt"));
        sourceTag.setPromptText(tr("dialog.create.sourceTag.prompt"));
        GridPane texts = twoColumns();
        texts.addRow(0, muted(tr("dialog.create.comment")), muted(tr("dialog.create.sourceTag")));
        texts.addRow(1, comment, sourceTag);

        form = new VBox(
                10,
                new VBox(4, muted(tr("dialog.create.fileOrFolder")), sourceRow, drop),
                options,
                flags,
                publishBox,
                lists,
                texts);
        VBox.setVgrow(form, Priority.ALWAYS);

        // ---- progress ----
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setProgress(-1);
        progressLine.getStyleClass().add("add-fetching");
        progressBox = new VBox(4, bar, progressLine);
        progressBox.visibleProperty().bind(hashing);
        progressBox.managedProperty().bind(hashing);

        // ---- the result ----
        resultBox.setHgap(8);
        resultBox.setVgap(4);
        ColumnConstraints label = new ColumnConstraints();
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        resultBox.getColumnConstraints().addAll(label, field, new ColumnConstraints());
        resultBox.addRow(0, muted(tr("dialog.create.infoHashV1")), v1, copyButton(v1));
        resultBox.addRow(1, muted(tr("dialog.create.infoHashV2")), v2, copyButton(v2));
        resultBox.addRow(2, muted(tr("dialog.create.magnet")), magnet, copyButton(magnet));
        resultBox.addRow(3, updatableLabel, updatable, updatableCopy);
        for (javafx.scene.Node n : List.of(updatableLabel, updatable, updatableCopy)) {
            n.visibleProperty().bind(updatable.textProperty().isNotEmpty());
            n.managedProperty().bind(n.visibleProperty());
        }
        outcome.getStyleClass().add("dialog-hint");
        outcome.setWrapText(true);
        resultBox.add(outcome, 0, 4, 3, 1);
        resultBox.setVisible(false);
        resultBox.managedProperty().bind(resultBox.visibleProperty());

        // ---- footer ----
        problem.getStyleClass().add("add-fetch-failed");
        problem.setWrapText(true);
        problem.setMinWidth(0);
        HBox.setHgrow(problem, Priority.ALWAYS);
        close.setOnAction(e -> close());
        close.setCancelButton(true);
        create.setDefaultButton(true);
        create.setOnAction(e -> create());
        create.disableProperty().bind(hashing);
        cancelHashing.setOnAction(e -> cancel());
        cancelHashing.visibleProperty().bind(hashing);
        cancelHashing.managedProperty().bind(hashing);
        HBox footer = new HBox(8, problem, cancelHashing, close, create);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("add-footer");

        form.disableProperty().bind(hashing);

        VBox content = new VBox(14, header, form, progressBox, resultBox, footer);
        content.setPadding(new Insets(18, 20, 16, 20));
        content.getStyleClass().add("add-dialog");

        Scene scene = new Scene(content, 760, 640);
        scene.getStylesheets()
                .add(CreateDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        scene.getStylesheets()
                .add(CreateDialog.class
                        .getResource("/com/torrenta/styles/add-dialog.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
        scene.setOnDragOver(this::onDragOver);
        scene.setOnDragDropped(this::onDragDropped);
        stage.setScene(scene);
        stage.setOnHidden(e -> {
            hashGeneration++;
            sizeGeneration++;
        });
        sourceChanged();
    }

    void show() {
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        stage.show();
        source.requestFocus();
        source.end();
    }

    /**
     * The next version of {@code p}: its content as the source, publishing on and fixed to its
     * key. A run already showing its result starts over.
     */
    void forNewVersion(com.torrenta.engine.Publication p, Path content) {
        if (hashing.get()) return;
        newVersionOf = p.name();
        publishName.setText(p.name());
        publish.setSelected(true);
        publish.setDisable(true);
        publishName.setEditable(false);
        resultBox.setVisible(false);
        publishChanged();
        setSource(content);
    }

    /** Fills the source in, as a drop or the capture hook does; the rest stays as it is. */
    void setSource(Path path) {
        source.setText(path.toAbsolutePath().normalize().toString());
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

    /** Closes; a run in progress is cancelled with it. */
    void close() {
        stage.hide();
    }

    // ---- the source ------------------------------------------------------------------------------

    private void sourceChanged() {
        String text = source.getText().trim();
        title.setText(
                text.isEmpty()
                        ? tr("dialog.create.chooseSource")
                        : Path.of(text).getFileName() == null
                                ? text
                                : Path.of(text).getFileName().toString());
        problem.setText("");
        int generation = ++sizeGeneration;
        fileCount = -1;
        sizes = List.of();
        updateSummary();
        if (!CreateText.problem(text).isEmpty()) {
            fileCount = 0;
            updateSummary();
            return;
        }
        Path path = Path.of(text);
        Thread.ofVirtual().name("create-size").start(() -> {
            List<Long> found = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(path)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        found.add(Files.size(p));
                    } catch (IOException e) {
                        // unreadable: left out of the estimate; the engine says so when it hashes
                    }
                });
            } catch (IOException | RuntimeException e) {
                // the same
            }
            List<Long> result = List.copyOf(found);
            Platform.runLater(() -> {
                if (generation != sizeGeneration) return;
                sizes = result;
                fileCount = result.size();
                updateSummary();
            });
        });
    }

    /** Publishing needs a seed: someone has to have the torrent the link names. */
    private void publishChanged() {
        boolean on = publish.isSelected();
        if (on) seed.setSelected(true);
        seed.setDisable(on);
        if (publisher == null) return;
        publishNote.setText(
                newVersionOf != null
                        ? tr("dialog.create.publish.newVersion", newVersionOf)
                        : tr("dialog.create.publish.note", publisher.keysDir().toString()));
        publishNote.setVisible(on);
        publishNote.setManaged(on);
    }

    private void formatChanged() {
        boolean implied = format.getValue().alignsFiles();
        if (implied) align.setSelected(true);
        align.setDisable(implied);
        updateSummary();
    }

    private long totalSize() {
        long total = 0;
        for (long size : sizes) total += size;
        return total;
    }

    private int resolvedPieceSize() {
        Integer chosen = pieceSize.getValue();
        return chosen == null || chosen == 0 ? PieceSize.suggest(totalSize()) : chosen;
    }

    private void updateSummary() {
        if (source.getText().isBlank()) {
            summary.setText(tr("dialog.create.summaryHint"));
            return;
        }
        long total = totalSize();
        int size = resolvedPieceSize();
        boolean aligned = format.getValue().alignsFiles() || align.isSelected();
        summary.setText(CreateText.summary(fileCount, total, size, PieceSize.count(sizes, size, aligned)));
        // The automatic choice's label names the size it resolves to, so it follows the total.
        pieceSize.setConverter(converter(choice -> choice == null ? "" : CreateText.pieceSizeLabel(choice, total)));
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.create.fileChooser"));
        startIn(chooser);
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) setSource(chosen.toPath());
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.create.folderChooser"));
        File current = currentFolder();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) setSource(chosen.toPath());
    }

    private void startIn(FileChooser chooser) {
        File current = currentFolder();
        if (current != null) chooser.setInitialDirectory(current);
    }

    /** The nearest folder of what is typed, else the download folder. */
    private File currentFolder() {
        String text = source.getText().trim();
        File current = text.isEmpty() ? settings.downloadDir().toFile() : new File(text);
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        return current;
    }

    private void onDragOver(DragEvent e) {
        if (e.getDragboard().hasFiles() && !hashing.get()) e.acceptTransferModes(TransferMode.COPY);
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        Dragboard board = e.getDragboard();
        boolean taken = false;
        if (board.hasFiles() && !board.getFiles().isEmpty() && !hashing.get()) {
            setSource(board.getFiles().get(0).toPath());
            taken = true;
        }
        e.setDropCompleted(taken);
        e.consume();
    }

    // ---- creating --------------------------------------------------------------------------------

    /** What the form says now. */
    CreateRequest request(Path path) {
        return new CreateRequest(
                path,
                format.getValue(),
                resolvedPieceSize(),
                isPrivate.isSelected(),
                CreateText.tiers(trackers.getText()),
                CreateText.lines(webSeeds.getText()),
                comment.getText().trim(),
                sourceTag.getText().trim(),
                align.isSelected(),
                seed.isSelected());
    }

    private void create() {
        if (hashing.get()) return;
        String why = CreateText.problem(source.getText());
        if (!why.isEmpty()) {
            problem.setText(why);
            return;
        }
        if (publish.isSelected() && publisher != null && newVersionOf == null) {
            String name = publisher.problem(publishName.getText());
            if (!name.isEmpty()) {
                problem.setText(name);
                return;
            }
        }
        Path path = Path.of(source.getText().trim());
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.create.saveAs"));
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter(tr("dialog.create.torrentFiles"), "*.torrent"));
        chooser.setInitialDirectory(
                CreateText.saveDir(settings.createLastDir(), path).toFile());
        chooser.setInitialFileName(CreateText.fileName(path));
        File chosen = chooser.showSaveDialog(stage);
        if (chosen == null) return;
        Path target = chosen.toPath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".torrent")) {
            target = target.resolveSibling(target.getFileName() + ".torrent");
        }
        if (target.getParent() != null) settings.setCreateLastDir(target.getParent());
        settings.setCreateDefaultTrackers(trackers.getText());
        settings.saveQuietly();
        start(path, target);
    }

    /** Hashes {@code path} into {@code target} on a worker; the form is held until it is done. */
    void start(Path path, Path target) {
        CreateRequest request = request(path);
        int generation = ++hashGeneration;
        seedAsked = request.startSeeding();
        boolean publishing = publish.isSelected() && publisher != null;
        String name = publishName.getText().trim();
        String nextOf = newVersionOf;
        made = null;
        savedTo = null;
        publishedLine = "";
        updatable.setText("");
        resultBox.setVisible(false);
        problem.setText("");
        bar.setProgress(-1);
        progressLine.setText(CreateText.hashing(0, 0));
        hashing.set(true);
        Thread.ofVirtual().name("engine-create").start(() -> {
            CreatedTorrent result;
            try {
                result = engine.create(request, (done, total) -> {
                    if (generation != hashGeneration) return false;
                    Platform.runLater(() -> {
                        if (generation != hashGeneration) return;
                        bar.setProgress(total <= 0 ? -1 : (double) done / total);
                        progressLine.setText(CreateText.hashing(done, total));
                    });
                    return true;
                });
                write(target, result.bytes());
                if (publishing) {
                    Platform.runLater(() -> {
                        if (generation != hashGeneration) return;
                        bar.setProgress(-1);
                        progressLine.setText(tr("dialog.create.publishing"));
                    });
                }
            } catch (EngineException | IOException | RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                Platform.runLater(() -> {
                    if (generation != hashGeneration) return;
                    hashing.set(false);
                    problem.setText(message);
                });
                return;
            }
            // The torrent is made and saved whatever publishing says: the key is kept either way.
            Session.Published published = null;
            String publishProblem = "";
            if (publishing) {
                try {
                    published = nextOf != null ? publisher.newVersion(nextOf, result) : publisher.publish(name, result);
                } catch (EngineException | IOException | RuntimeException e) {
                    publishProblem = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
            Session.Published done = published;
            String why = publishProblem;
            Platform.runLater(() -> {
                if (generation != hashGeneration) return;
                hashing.set(false);
                show(result, target);
                if (publishing) showPublished(done, why);
            });
        });
    }

    private void showPublished(Session.Published done, String why) {
        String line;
        if (done == null) {
            line = tr("dialog.create.notPublished", why);
        } else {
            updatable.setText(done.publication().uri());
            line = done.reached()
                    ? CreateText.published(done.publication().seq(), done.nodes())
                    : tr("dialog.create.notPublishedYet");
        }
        publishedLine = line;
        outcome.setText(outcome.getText() + " " + line);
    }

    private void show(CreatedTorrent result, Path target) {
        made = result;
        savedTo = target;
        v1.setText(result.v1().isEmpty() ? "—" : result.v1());
        v2.setText(result.v2().isEmpty() ? "—" : result.v2());
        magnet.setText(result.magnet());
        outcome.setText(CreateText.outcome(target, result, seedAsked));
        resultBox.setVisible(true);
    }

    private void cancel() {
        if (!hashing.get()) return;
        hashGeneration++;
        hashing.set(false);
        problem.setText(tr("dialog.create.cancelled"));
    }

    /** Beside its final name, then moved into place: no half-written torrent survives a crash. */
    static void write(Path target, byte[] bytes) throws IOException {
        Path dir = target.toAbsolutePath().normalize().getParent();
        if (dir == null) throw new IOException("Nowhere to write " + target);
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".torrenta-", ".part");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ---- the capture hook ------------------------------------------------------------------------

    /** Publishing on under {@code name}, as a person would tick it and type. */
    void publishForCapture(String name) {
        publish.setSelected(true);
        publishName.setText(name);
    }

    /** Creates into {@code target} with seeding on, the Save As chooser skipped. */
    void createForCapture(Path target) {
        seed.setSelected(true);
        String why = CreateText.problem(source.getText());
        if (!why.isEmpty()) {
            problem.setText(why);
            return;
        }
        if (publish.isSelected() && publisher != null && newVersionOf == null) {
            String name = publisher.problem(publishName.getText());
            if (!name.isEmpty()) {
                problem.setText(name);
                return;
            }
        }
        start(Path.of(source.getText().trim()), target);
    }

    /** What a screenshot cannot show: the state, the header, and the hashes once made. */
    List<String> linesForCapture() {
        List<String> out = new ArrayList<>();
        String state = hashing.get() ? "hashing" : made != null ? "created" : "idle";
        out.add("create=" + state + " | " + title.getText() + " | " + summary.getText() + " | "
                + (made != null ? outcome.getText() : problem.getText()));
        if (made != null) {
            out.add("create-id=" + made.id());
            out.add("create-v1=" + made.v1());
            out.add("create-v2=" + made.v2());
            out.add("create-magnet=" + made.magnet());
            out.add("create-file=" + savedTo);
        }
        if (publish.isSelected()) {
            out.add("create-publish=" + (newVersionOf != null ? "next of " + newVersionOf : publishName.getText())
                    + " | " + updatable.getText() + " | " + publishedLine);
        }
        return out;
    }

    // ---- small pieces ----------------------------------------------------------------------------

    private static GridPane twoColumns() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(4);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        grid.getColumnConstraints().addAll(half, half);
        return grid;
    }

    private static TextField value() {
        TextField field = new TextField();
        field.setEditable(false);
        field.getStyleClass().add("dialog-value");
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    private static Button copyButton(TextField of) {
        Button button = new Button(tr("common.copy"));
        button.setOnAction(e -> copy(of.getText()));
        button.disableProperty()
                .bind(of.textProperty().isEmpty().or(of.textProperty().isEqualTo("—")));
        return button;
    }

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
