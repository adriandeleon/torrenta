package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.torrenta.AppInfo;
import com.torrenta.cli.CommandLine;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;

/**
 * The one window: a menu bar over the torrent list, with a status bar below.
 *
 * <p>The engine arrives after the window is up ({@link #engineReady}), because loading it takes
 * longer than a first frame should. Until then inputs queue. Once it is there a worker polls it
 * once a second and hands the FX thread a fresh list; nothing on the FX thread ever calls into the
 * engine.
 */
public final class MainWindow {

    private static final long POLL_MS = 1000;

    private final Stage stage;
    private final Label status = new Label();
    private final Label rates = new Label();
    private final ObservableList<Torrent> torrents = FXCollections.observableArrayList();

    private Engine engine;
    private String engineDescription = "";
    private final List<String> pendingInputs = new ArrayList<>();
    private ScheduledExecutorService poller;

    public MainWindow(Stage stage) {
        this.stage = stage;
        stage.setTitle(AppInfo.NAME);

        BorderPane root = new BorderPane();
        root.setTop(buildMenuBar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 960, 600);
        scene.getStylesheets()
                .add(MainWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> stopPolling());
        setStatus("Starting the engine…");
        rates.setText(ratesText(Engine.Rates.ZERO));
    }

    public void show() {
        stage.show();
    }

    /** The status bar's text; read by the development capture. */
    public String statusText() {
        return status.getText();
    }

    /** The engine's description once it is up, else empty; read by the development capture. */
    public String engineDescription() {
        return engineDescription;
    }

    /** FX thread. The engine has started; flush what was queued and begin polling. */
    public void engineReady(Engine engine) {
        this.engine = engine;
        this.engineDescription = engine.description();
        setStatus(engineDescription);
        List<String> queued = List.copyOf(pendingInputs);
        pendingInputs.clear();
        queued.forEach(this::addInput);

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** FX thread. The engine could not start; the window stays up so the message can be read. */
    public void engineFailed(EngineException failure) {
        setStatus(failure.getMessage());
    }

    /** A {@code .torrent} path or a {@code magnet:} link, from the command line or a dialog. */
    public void addInput(String input) {
        if (engine == null) {
            pendingInputs.add(input);
            return;
        }
        Thread.ofVirtual().name("engine-add").start(() -> {
            String outcome;
            try {
                Torrent added = engine.add(input);
                outcome = "Added " + added.name();
            } catch (EngineException e) {
                outcome = e.getMessage();
            }
            String text = outcome;
            Platform.runLater(() -> setStatus(text));
        });
    }

    private void poll() {
        List<Torrent> snapshot = engine.torrents();
        Engine.Rates current = engine.rates();
        Platform.runLater(() -> {
            // setAll rather than clear+addAll, so the table sees one change and keeps its scroll.
            torrents.setAll(snapshot);
            rates.setText(ratesText(current));
        });
    }

    private void stopPolling() {
        if (poller != null) poller.shutdownNow();
    }

    private MenuBar buildMenuBar() {
        MenuItem addTorrent = new MenuItem("Add Torrent File…");
        addTorrent.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        addTorrent.setOnAction(e -> setStatus("Add torrent file: no dialog yet; pass the path on the command line"));

        MenuItem addMagnet = new MenuItem("Add Magnet Link…");
        addMagnet.setAccelerator(KeyCombination.keyCombination("Shortcut+M"));
        addMagnet.setOnAction(e -> setStatus("Add magnet link: no dialog yet; pass the link on the command line"));

        MenuItem quit = new MenuItem("Quit");
        quit.setAccelerator(KeyCombination.keyCombination("Shortcut+Q"));
        quit.setOnAction(e -> stage.close());

        Menu file = new Menu("File");
        file.getItems().addAll(addTorrent, addMagnet, new SeparatorMenuItem(), quit);

        MenuItem about = new MenuItem("About " + AppInfo.NAME);
        about.setOnAction(e -> setStatus(AppInfo.NAME + " " + AppInfo.VERSION + " — " + AppInfo.COPYRIGHT));
        Menu help = new Menu("Help");
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private StackPane buildCenter() {
        TableView<Torrent> table = buildTable();
        VBox empty = buildEmptyState();
        // The empty state is the whole centre until the first torrent, and the table after it.
        empty.visibleProperty().bind(Bindings.isEmpty(torrents));
        empty.managedProperty().bind(empty.visibleProperty());
        table.visibleProperty().bind(Bindings.isNotEmpty(torrents));
        table.managedProperty().bind(table.visibleProperty());
        return new StackPane(table, empty);
    }

    private TableView<Torrent> buildTable() {
        TableView<Torrent> table = new TableView<>(torrents);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(""));
        table.getStyleClass().add("torrent-table");

        TableColumn<Torrent, String> name = column("Name", t -> t.name());
        name.setPrefWidth(360);
        TableColumn<Torrent, String> size = column("Size", t -> t.size() == 0 ? "—" : Bytes.human(t.size()));
        TableColumn<Torrent, String> progress =
                column("Progress", t -> String.format(Locale.ROOT, "%.1f %%", t.progress() * 100));
        TableColumn<Torrent, String> down = column("Down", t -> Bytes.rate(t.downloadRate()));
        TableColumn<Torrent, String> up = column("Up", t -> Bytes.rate(t.uploadRate()));
        TableColumn<Torrent, String> peers = column("Peers", t -> t.seeds() + " / " + t.peers());
        TableColumn<Torrent, String> state = column("State", t -> t.state().label());
        table.getColumns().addAll(List.of(name, size, progress, down, up, peers, state));
        return table;
    }

    private static TableColumn<Torrent, String> column(
            String title, java.util.function.Function<Torrent, String> value) {
        TableColumn<Torrent, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    private VBox buildEmptyState() {
        Label title = new Label("No torrents yet");
        title.getStyleClass().add("empty-title");
        Label hint = new Label("Press Ctrl+O to add a .torrent file, or Ctrl+M to paste a magnet link.");
        hint.getStyleClass().add("empty-hint");
        VBox box = new VBox(8, title, hint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        return box;
    }

    private HBox buildStatusBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, status, spacer, rates);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static String ratesText(Engine.Rates r) {
        return "↓ " + Bytes.rate(r.download()) + "   ↑ " + Bytes.rate(r.upload());
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    /** True when the input would be handled as a magnet rather than a file. */
    static boolean isMagnet(String input) {
        return CommandLine.isMagnet(input);
    }
}
