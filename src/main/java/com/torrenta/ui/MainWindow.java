package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.torrenta.AppInfo;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Torrent;
import com.torrenta.store.Settings;

/**
 * The one window: a menu bar, a filter box over the torrent list, a status bar.
 *
 * <p>The engine arrives after the window is up ({@link #engineReady}), because loading it takes
 * longer than a first frame should. Until then inputs queue. Once it is there a worker polls it
 * once a second and hands the FX thread a fresh list, which {@link TorrentRows} reconciles into the
 * rows the table already shows; nothing on the FX thread ever calls into the engine.
 */
public final class MainWindow {

    private static final long POLL_MS = 1000;

    private final Stage stage;
    private final Settings settings;
    private final Label status = new Label();
    private final Label rates = new Label();
    private final TorrentRows rows = new TorrentRows();
    private final FilteredList<TorrentRow> filtered = new FilteredList<>(rows.list());
    private final TextField filter = new TextField();
    private TableView<TorrentRow> table;

    private Engine engine;
    private String engineDescription = "";
    private final List<String> pendingInputs = new ArrayList<>();
    private ScheduledExecutorService poller;

    public MainWindow(Stage stage, Settings settings) {
        this.stage = stage;
        this.settings = settings;
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
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+F"), this::focusFilter);
        stage.setScene(scene);
        stage.setOnHidden(e -> stopPolling());
        setStatus("Starting the engine…");
        rates.setText(ratesText(Engine.Rates.ZERO));
    }

    public void show() {
        stage.show();
        table.requestFocus();
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
            rows.apply(snapshot);
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

        MenuItem findItem = new MenuItem("Filter Torrents");
        // The accelerator is the scene's, so it works while the menu is closed; this is the label.
        findItem.setOnAction(e -> focusFilter());
        Menu view = new Menu("View");
        view.getItems().add(findItem);

        MenuItem about = new MenuItem("About " + AppInfo.NAME);
        about.setOnAction(e -> setStatus(AppInfo.NAME + " " + AppInfo.VERSION + " — " + AppInfo.COPYRIGHT));
        Menu help = new Menu("Help");
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, view, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private VBox buildCenter() {
        table = buildTable();
        VBox empty = buildEmptyState();
        // The empty state is the whole centre until the first torrent, and the table after it;
        // a filter that matches nothing shows the table's own placeholder instead.
        empty.visibleProperty().bind(Bindings.isEmpty(rows.list()));
        empty.managedProperty().bind(empty.visibleProperty());
        table.visibleProperty().bind(Bindings.isNotEmpty(rows.list()));
        table.managedProperty().bind(table.visibleProperty());
        // The first torrent makes the table appear; it takes the focus then, unless the person is
        // typing a filter at that moment.
        table.visibleProperty().addListener((o, was, now) -> {
            if (now && !filter.isFocused()) table.requestFocus();
        });
        StackPane centre = new StackPane(table, empty);
        VBox.setVgrow(centre, Priority.ALWAYS);
        return new VBox(buildFilterBar(), centre);
    }

    private HBox buildFilterBar() {
        filter.setPromptText("Filter by name (Ctrl+F)");
        // Not traversable: the scene hands initial focus to the first traversable node, and while
        // the table is hidden (no torrents yet) that would be this box, so every keystroke on a
        // fresh launch would land in the filter. Ctrl+F reaches it; Tab does not need to.
        filter.setFocusTraversable(false);
        filter.textProperty().addListener((o, was, text) -> filtered.setPredicate(nameFilter(text)));
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                filter.clear();
                table.requestFocus();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                table.requestFocus();
                e.consume();
            }
        });
        HBox.setHgrow(filter, Priority.ALWAYS);
        HBox bar = new HBox(filter);
        bar.setPadding(new Insets(6, 8, 6, 8));
        bar.getStyleClass().add("filter-bar");
        return bar;
    }

    /** Case-insensitive substring on the name; blank matches everything. Package-private for tests. */
    static java.util.function.Predicate<TorrentRow> nameFilter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return row -> true;
        return row -> row.torrent().name().toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private void focusFilter() {
        filter.requestFocus();
        filter.selectAll();
    }

    private TableView<TorrentRow> buildTable() {
        // Sorted over filtered over the live rows: the table's comparator drives the sort, and the
        // rows list's extractor makes a changed rate re-sort its row without a sort() call.
        SortedList<TorrentRow> sorted = new SortedList<>(filtered);
        TableView<TorrentRow> table = new TableView<>(sorted);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        // Widths are remembered, so they are not stretched to fit; the last column does not flex.
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setTableMenuButtonVisible(true);
        table.setPlaceholder(new Label("No torrents match the filter"));
        table.getStyleClass().add("torrent-table");

        TableColumn<TorrentRow, String> name = column("name", "Name", 360, true, Torrent::name, s -> s);
        name.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<TorrentRow, Double> progress =
                column("progress", "Progress", 110, true, Torrent::progress, Format::percent);
        progress.setCellFactory(ProgressBarTableCell.forTableColumn());
        table.getColumns()
                .addAll(List.of(
                        name,
                        column("size", "Size", 90, true, Torrent::size, v -> v == 0 ? "—" : Bytes.human(v)),
                        column("done", "Done", 90, false, Torrent::done, Bytes::human),
                        progress,
                        column("state", "State", 130, true, t -> t.state().label(), s -> s),
                        column("seeds", "Seeds", 70, true, Torrent::seeds, String::valueOf),
                        column("peers", "Peers", 70, true, Torrent::peers, String::valueOf),
                        column("down", "Down", 90, true, Torrent::downloadRate, Bytes::rate),
                        column("up", "Up", 90, true, Torrent::uploadRate, Bytes::rate),
                        column("eta", "ETA", 80, true, Format::etaSeconds, Format::eta),
                        column(
                                "ratio",
                                "Ratio",
                                70,
                                true,
                                t -> Format.ratioValue(t.uploaded(), t.downloaded()),
                                Format::ratio),
                        column("added", "Added", 130, false, Torrent::added, Format::dateTime),
                        column("completed", "Completed", 130, false, Torrent::completed, Format::dateTime),
                        column("tracker", "Tracker", 160, false, Torrent::tracker, Format::host),
                        column("path", "Save path", 240, false, Torrent::savePath, s -> s)));

        ColumnMemory.apply(table, ColumnLayout.parse(settings.get(Settings.TABLE_COLUMNS, "")));
        ColumnMemory.watch(table, () -> {
            settings.set(Settings.TABLE_COLUMNS, ColumnLayout.format(ColumnMemory.capture(table)));
            settings.saveQuietly();
        });
        return table;
    }

    /**
     * A column whose cell value is a typed, comparable field of the row's current snapshot — so the
     * sort compares numbers, not their text — rendered through {@code text}. The value observes the
     * row's torrent property, so a new snapshot refreshes the cell in place.
     */
    private static <T extends Comparable<? super T>> TableColumn<TorrentRow, T> column(
            String id,
            String title,
            double width,
            boolean visible,
            Function<Torrent, T> value,
            Function<T, String> text) {
        TableColumn<TorrentRow, T> column = new TableColumn<>(title);
        column.setId(id);
        column.setPrefWidth(width);
        column.setVisible(visible);
        column.setCellValueFactory(cell -> cell.getValue().torrentProperty().map(value));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        });
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
}
