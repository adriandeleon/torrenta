package com.torrenta.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.ComboBoxTreeTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

import com.torrenta.engine.TorrentDetail;

/**
 * The detail pane under the list: General, Files, Peers, Trackers, Pieces for the one selected
 * torrent. Fed a {@link TorrentDetail} once a second by the window; shows nothing with no
 * selection or more than one.
 */
final class DetailPane extends VBox {

    static final List<String> TABS = List.of("general", "files", "peers", "trackers", "pieces", "speed");

    private final TabPane tabs = new TabPane();
    private final GridPane general = new GridPane();
    private final Label[] generalValues = new Label[13];
    private final FileRows fileRows = new FileRows();
    private final TreeTableView<FileRows.Row> files = new TreeTableView<>();
    private final ObservableList<TorrentDetail.Peer> peerItems = FXCollections.observableArrayList();
    private final ObservableList<TorrentDetail.Tracker> trackerItems = FXCollections.observableArrayList();
    private final TableView<TorrentDetail.Tracker> trackers = new TableView<>(trackerItems);
    private final Canvas pieces = new Canvas();
    /** The selected torrent's graph; fed every tick, painted only while its tab shows. */
    private final SpeedGraph speed = new SpeedGraph(600, false);

    private SpeedHistory speedHistory;
    private long speedNow;
    private final Label empty = new Label("Select one torrent to see its details");
    private boolean[] lastPieces = new boolean[0];
    private String shownId;

    private BiConsumer<Integer, com.torrenta.engine.Priority> onFilePriority = (i, p) -> {};
    private Consumer<FileRows.Row> onOpenFile = r -> {};
    private Consumer<List<String>> onAddTrackers = urls -> {};
    private Consumer<String> onRemoveTracker = url -> {};
    private Runnable onReannounce = () -> {};
    private Consumer<String> onBanPeer = address -> {};

    DetailPane() {
        getStyleClass().add("detail-pane");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Chrome is not focus-traversable: with no torrents the list is hidden and the scene would
        // otherwise hand the first keystrokes to this tab strip.
        tabs.setFocusTraversable(false);
        tabs.getTabs()
                .addAll(
                        tab("General", Icons.info(), buildGeneral()),
                        tab("Files", Icons.files(), buildFiles()),
                        tab("Peers", Icons.peers(), buildPeers()),
                        tab("Trackers", Icons.broadcast(), buildTrackers()),
                        tab("Pieces", Icons.pieces(), buildPieces()),
                        tab("Speed", Icons.graph(), speed));
        tabs.getSelectionModel().selectedIndexProperty().addListener(o -> {
            if (speedShowing()) speed.show(speedHistory, speedNow);
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);
        empty.getStyleClass().add("empty-hint");
        empty.setPadding(new Insets(12));
        getChildren().addAll(tabs);
        clear();
    }

    void setOnFilePriority(BiConsumer<Integer, com.torrenta.engine.Priority> handler) {
        onFilePriority = handler;
    }

    void setOnOpenFile(Consumer<FileRows.Row> handler) {
        onOpenFile = handler;
    }

    void setOnAddTrackers(Consumer<List<String>> handler) {
        onAddTrackers = handler;
    }

    void setOnRemoveTracker(Consumer<String> handler) {
        onRemoveTracker = handler;
    }

    void setOnReannounce(Runnable handler) {
        onReannounce = handler;
    }

    void setOnBanPeer(Consumer<String> handler) {
        onBanPeer = handler;
    }

    /** The Speed tab's window, shared with the Statistics window through the settings. */
    void setSpeedWindow(int seconds) {
        speed.setWindow(seconds);
    }

    void setOnSpeedWindowChanged(java.util.function.IntConsumer handler) {
        speed.setOnWindowChanged(handler);
    }

    /**
     * FX thread. The selected torrent's history as of {@code nowSeconds}, with each snapshot; the
     * graph is painted only while the Speed tab is the one showing, and when it becomes so.
     */
    void showSpeed(SpeedHistory history, long nowSeconds) {
        speedHistory = history;
        speedNow = nowSeconds;
        if (speedShowing()) speed.show(history, nowSeconds);
    }

    private boolean speedShowing() {
        return "speed".equals(activeTab());
    }

    /** The graph's state, for the capture hook. */
    String speedSummaryForCapture() {
        return speed.summaryForCapture();
    }

    /** The active tab's name, one of {@link #TABS}; remembered by the window. */
    String activeTab() {
        return TABS.get(tabs.getSelectionModel().getSelectedIndex());
    }

    void selectTab(String name) {
        int i = TABS.indexOf(name);
        if (i >= 0) tabs.getSelectionModel().select(i);
    }

    /** What the capture hook prints. */
    String summary() {
        int fileCount = fileRows.rootItem() == null
                ? 0
                : fileRows.rootItem().getChildren().size();
        return activeTab() + " files=" + fileCount + " peers=" + peerItems.size() + " trackers=" + trackerItems.size()
                + " pieces=" + count(lastPieces) + "/" + lastPieces.length;
    }

    /** FX thread. A fresh snapshot of the selected torrent. */
    void show(TorrentDetail detail) {
        TorrentDetail.General g = detail.general();
        boolean another = !g.id().equals(shownId);
        shownId = g.id();
        tabs.setVisible(true);
        tabs.setManaged(true);

        generalValues[0].setText(g.id());
        generalValues[1].setText(g.savePath());
        generalValues[2].setText(
                g.pieceCount() == 0
                        ? "—"
                        : g.piecesHave() + " of " + g.pieceCount() + " × " + Bytes.human(g.pieceLength()));
        generalValues[3].setText(
                g.availability() < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.2f", g.availability()));
        generalValues[4].setText(Bytes.human(g.downloaded()));
        generalValues[5].setText(Bytes.human(g.uploaded()));
        generalValues[6].setText(Bytes.human(g.wasted()));
        generalValues[7].setText(Format.duration(g.activeSeconds()));
        generalValues[8].setText(Format.duration(g.seedingSeconds()));
        generalValues[9].setText(g.nextAnnounceSeconds() <= 0 ? "—" : Format.duration(g.nextAnnounceSeconds()));
        generalValues[10].setText(String.valueOf(g.connections()));
        generalValues[11].setText(Format.host(g.currentTracker()));
        generalValues[12].setText(g.isPrivate() ? "Yes" : "No");

        if (another) fileRows.clear();
        if (fileRows.apply(detail.files())) {
            files.setRoot(fileRows.rootItem());
        }
        peerItems.setAll(detail.peers());
        if (another || trackerItems.size() != detail.trackers().size()) trackerItems.setAll(detail.trackers());
        else
            for (int i = 0; i < trackerItems.size(); i++)
                trackerItems.set(i, detail.trackers().get(i));
        lastPieces = detail.pieces();
        drawPieces();
    }

    /** FX thread. No single selection: the pane says so and holds no torrent. */
    void clear() {
        shownId = null;
        fileRows.clear();
        files.setRoot(null);
        peerItems.clear();
        trackerItems.clear();
        lastPieces = new boolean[0];
        drawPieces();
        speedHistory = null;
        speed.clear();
        for (Label value : generalValues) value.setText("—");
    }

    /** A tab with its glyph before the title, at the menus' size. */
    private static Tab tab(String title, Icons.Glyph glyph, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setGraphic(glyph.at(Icons.MENU_SIZE));
        return tab;
    }

    private javafx.scene.Node buildGeneral() {
        general.setHgap(12);
        general.setVgap(6);
        general.setPadding(new Insets(10));
        // Labels keep their width; values take the rest, and a long path ellipsises rather than
        // squeezing the labels beside it.
        for (int c = 0; c < 4; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            if (c % 2 == 0) {
                cc.setMinWidth(Region.USE_PREF_SIZE);
            } else {
                cc.setHgrow(Priority.ALWAYS);
                cc.setMinWidth(60);
            }
            general.getColumnConstraints().add(cc);
        }
        String[] names = {
            "Info-hash",
            "Save path",
            "Pieces",
            "Availability",
            "Downloaded",
            "Uploaded",
            "Wasted",
            "Active",
            "Seeding",
            "Next announce",
            "Connections",
            "Tracker",
            "Private"
        };
        // Two columns of pairs, so the tab is a glance rather than a scroll.
        for (int i = 0; i < names.length; i++) {
            Label name = new Label(names[i]);
            name.getStyleClass().add("dialog-label");
            Label value = new Label("—");
            value.getStyleClass().add("detail-value");
            // The hash and the path: the working face, as Editora sets them.
            if (i < 2) value.getStyleClass().add("mono");
            generalValues[i] = value;
            int column = i < 7 ? 0 : 2;
            int row = i < 7 ? i : i - 7;
            general.add(name, column, row);
            general.add(value, column + 1, row);
        }
        ScrollPane scroll = new ScrollPane(general);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private javafx.scene.Node buildFiles() {
        files.setShowRoot(false);
        files.setEditable(true);
        files.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        files.setPlaceholder(new Label("No files until the metadata arrives"));
        TreeTableColumn<FileRows.Row, String> name = treeColumn("Name", 320, FileRows.Row::name);
        TreeTableColumn<FileRows.Row, String> size = new TreeTableColumn<>("Size");
        size.setPrefWidth(90);
        size.setCellValueFactory(p -> p.getValue().getValue().sizeProperty().map(n -> Bytes.human(n.longValue())));
        TreeTableColumn<FileRows.Row, String> progress = new TreeTableColumn<>("Progress");
        progress.setPrefWidth(90);
        progress.setCellValueFactory(p -> {
            FileRows.Row row = p.getValue().getValue();
            return row.doneProperty().map(d -> Format.percent(row.progress()));
        });
        TreeTableColumn<FileRows.Row, com.torrenta.engine.Priority> priority = new TreeTableColumn<>("Priority");
        priority.setPrefWidth(100);
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
                com.torrenta.engine.Priority.values()));
        priority.setOnEditCommit(e -> {
            FileRows.Row row = e.getRowValue().getValue();
            if (row.isFile() && e.getNewValue() != null) {
                row.priorityProperty().set(e.getNewValue());
                onFilePriority.accept(row.index(), e.getNewValue());
            }
        });
        files.getColumns().addAll(List.of(name, size, progress, priority));
        Headers.uppercase(files);

        MenuItem open = new MenuItem("Open File");
        open.setGraphic(Icons.open().at(Icons.MENU_SIZE));
        open.setOnAction(e -> {
            var item = files.getSelectionModel().getSelectedItem();
            if (item != null && item.getValue().isFile()) onOpenFile.accept(item.getValue());
        });
        files.setContextMenu(new ContextMenu(open));
        return files;
    }

    private javafx.scene.Node buildPeers() {
        TableView<TorrentDetail.Peer> peers = new TableView<>(peerItems);
        peers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        peers.setPlaceholder(new Label("No peers connected"));
        peers.getColumns()
                .addAll(List.of(
                        column("Address", 170, TorrentDetail.Peer::address),
                        column("Client", 170, TorrentDetail.Peer::client),
                        column("Flags", 70, TorrentDetail.Peer::flags),
                        column("Progress", 80, p -> Format.percent(p.progress())),
                        column("Down", 90, p -> Bytes.rate(p.downRate())),
                        column("Up", 90, p -> Bytes.rate(p.upRate())),
                        column("Downloaded", 100, p -> Bytes.human(p.downloaded())),
                        column("Uploaded", 100, p -> Bytes.human(p.uploaded()))));
        Headers.uppercase(peers);
        return peers;
    }

    private javafx.scene.Node buildTrackers() {
        trackers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        trackers.setPlaceholder(new Label("No trackers; DHT and PEX still find peers"));
        trackers.getColumns()
                .addAll(List.of(
                        column("URL", 360, TorrentDetail.Tracker::url),
                        column("Tier", 50, t -> String.valueOf(t.tier())),
                        column("Status", 100, TorrentDetail.Tracker::status),
                        column("Message", 240, TorrentDetail.Tracker::message)));
        Button add = new Button("Add Trackers…");
        add.setOnAction(e -> askTrackers());
        Button remove = new Button("Remove");
        remove.disableProperty()
                .bind(trackers.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> onRemoveTracker.accept(
                trackers.getSelectionModel().getSelectedItem().url()));
        Button reannounce = new Button("Reannounce");
        reannounce.setOnAction(e -> onReannounce.run());
        HBox bar = new HBox(8, add, remove, reannounce);
        bar.setPadding(new Insets(6));
        VBox box = new VBox(bar, trackers);
        VBox.setVgrow(trackers, Priority.ALWAYS);
        return box;
    }

    private void askTrackers() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.initOwner(getScene() == null ? null : getScene().getWindow());
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle("Add Trackers");
        prompt.setHeaderText("Tracker URLs, separated by spaces");
        prompt.setContentText("URLs:");
        prompt.getEditor().setPrefColumnCount(48);
        Optional<String> text = prompt.showAndWait();
        text.ifPresent(t -> {
            List<String> urls = List.of(t.trim().split("\\\\s+"));
            if (!urls.isEmpty() && !urls.get(0).isEmpty()) onAddTrackers.accept(urls);
        });
    }

    private javafx.scene.Node buildPieces() {
        Pane host = new Pane(pieces);
        host.getStyleClass().add("pieces-map");
        pieces.widthProperty().bind(host.widthProperty());
        pieces.heightProperty().bind(host.heightProperty());
        pieces.widthProperty().addListener(o -> drawPieces());
        pieces.heightProperty().addListener(o -> drawPieces());
        return host;
    }

    /**
     * A grid of cells, one per piece, as many per row as fit at a readable size; every piece is
     * drawn, so a long torrent gets smaller cells rather than a sample.
     */
    private void drawPieces() {
        GraphicsContext g = pieces.getGraphicsContext2D();
        double w = pieces.getWidth();
        double h = pieces.getHeight();
        g.clearRect(0, 0, w, h);
        int n = lastPieces.length;
        if (n == 0 || w < 8 || h < 8) return;
        double cell = Math.max(2, Math.min(20, Math.sqrt((w - 8) * (h - 8) / n)));
        int perRow = Math.max(1, (int) ((w - 8) / cell));
        for (int i = 0; i < n; i++) {
            double x = 4 + (i % perRow) * cell;
            double y = 4 + (i / perRow) * cell;
            if (y > h) break;
            g.setFill(lastPieces[i] ? HAVE : MISSING);
            g.fillRect(x, y, Math.max(1, cell - 1), Math.max(1, cell - 1));
        }
    }

    /** Caret teal and Ink's subtle surface: the canvas cannot read the theme's CSS variables. */
    private static final Color HAVE = Color.web("#22B8A6");

    private static final Color MISSING = Color.web("#2a2f3f");

    private static int count(boolean[] bits) {
        int c = 0;
        for (boolean b : bits) if (b) c++;
        return c;
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> text) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(text.apply(cell.getValue())));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });
        return column;
    }

    private static TreeTableColumn<FileRows.Row, String> treeColumn(
            String title, double width, Function<FileRows.Row, String> text) {
        TreeTableColumn<FileRows.Row, String> column = new TreeTableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
                p -> new ReadOnlyObjectWrapper<>(text.apply(p.getValue().getValue())));
        column.setCellFactory(c -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });
        return column;
    }

    /** Unused: kept the pane's API symmetrical for the window. */
    Region placeholder() {
        return empty;
    }
}
