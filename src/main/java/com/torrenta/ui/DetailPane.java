package com.torrenta.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.ComboBoxTreeTableCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.StringConverter;

import com.torrenta.content.FileKind;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.session.SpeedHistory;

import static com.torrenta.i18n.Messages.tr;

/**
 * The detail pane for the one selected torrent: a header (state, name, the line under it, the
 * actions with their keys), then Overview, Files, Peers, Trackers, Log and Speed. Fed a
 * {@link TorrentDetail} and the row's {@link Torrent} once a second by the window; shows nothing
 * with no selection or more than one.
 *
 * <p>The Overview is the redesign's inspector: the {@link Diagnosis} card when the torrent needs a
 * person, the pieces map ({@link PieceStrip}), half an hour of rates ({@link Sparkline}), then the
 * facts. It lays itself out in two columns when the pane is wide (docked under the list) and one
 * when it is narrow (docked beside it); the header does the same with its buttons.
 */
final class DetailPane extends VBox {

    static final List<String> TABS = List.of("overview", "files", "peers", "trackers", "log", "speed");

    /** The tabs a download from a link has nothing for: it has no swarm and no trackers. */
    static final java.util.Set<String> TORRENT_ONLY = java.util.Set.of("peers", "trackers");

    /** Wider than this, the Overview and the header use two columns. */
    static final double WIDE = 760;

    /** The Overview's graph: half an hour, as the mockup has it. */
    static final int OVERVIEW_WINDOW = 1800;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TabPane tabs = new TabPane();
    private final Tab filesTab;
    private final Tab peersTab;
    private final Tab trackersTab;
    private final Label filesCount = new Label();
    private final Label peersCount = new Label();
    private final Label trackersCount = new Label();

    // ---- header
    private final VBox header = new VBox(8);
    private final HBox headerTop = new HBox(10);
    private final Label stateGlyph = new Label();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    /** The buttons: a wrapping row under the name when narrow, a row of their own when wide. */
    private final FlowPane actions = new FlowPane(8, 8);

    private final HBox actionsRow = new HBox(8);
    private final Button pauseButton = new Button();
    private final Button reannounceButton = new Button(tr("detail.action.reannounce"));
    private final Button openButton = new Button(tr("detail.action.openFolder"));
    private final Button moreButton = new Button("···");
    /** Pause's glyph: two bars while it pauses, the play triangle while it resumes. */
    private final StackPane pauseGlyph = new StackPane();

    private Boolean pauseShowsResume;
    private RowState headerState;
    private boolean wide;

    // ---- overview
    private final GridPane overview = new GridPane();
    private final VBox card = new VBox(8);
    private final Label cardTitle = new Label();
    private final TextFlow cardBody = new TextFlow();
    private final VBox remedies = new VBox(2);
    private Diagnosis shownDiagnosis;
    /** The capture hook asked for a remedy before a card offered it: pressed when one does. */
    private Diagnosis.Remedy remedyPending;

    private boolean remedyPendingAny;
    private final Label piecesValue = new Label();
    private final PieceStrip pieceStrip = new PieceStrip();
    /** The pieces a player's read-ahead covers in the torrent shown; null while none plays it. */
    private com.torrenta.engine.PieceMath.Span streamWindow;

    private final VBox piecesBox = new VBox(8);
    private final Sparkline overviewSpark = new Sparkline(Sparkline.Series.BOTH, 200, 56);
    private final Label speedCaption = new Label();
    private final Label overviewDown = new Label();
    private final Label overviewUp = new Label();
    private final VBox speedBox = new VBox(8);
    private final GridPane facts = new GridPane();
    private final Label peersValue = value();
    private final Label downloadedValue = value();
    private final Label uploadedValue = value();
    private final Label savePathValue = value();
    private final Label hashValue = value();
    private final Label availabilityValue = value();
    private final Label pieceSizeValue = value();
    private final Label wastedValue = value();
    private final Label activeValue = value();
    private final Label seedingValue = value();
    private final Label announceValue = value();
    private final Label trackerValue = value();
    /** A download's link, in the place a torrent's info-hash has. */
    private final Label sourceValue = value();
    /** A download's command line for fetching it by hand ({@link TorrentDetail#command}). */
    private final Label commandValue = value();
    /** The facts' rows only a torrent has, and the one only a download from a link has. */
    private final List<Node> torrentFacts = new java.util.ArrayList<>();

    private final List<Node> directFacts = new java.util.ArrayList<>();
    private final Label privateValue = value();

    // ---- the other tabs
    private final FileRows fileRows = new FileRows();
    private final TreeTableView<FileRows.Row> files = new TreeTableView<>();
    private final ObservableList<TorrentDetail.Peer> peerItems = FXCollections.observableArrayList();
    private final ObservableList<TorrentDetail.Tracker> trackerItems = FXCollections.observableArrayList();
    private final TableView<TorrentDetail.Tracker> trackers = new TableView<>(trackerItems);
    private final ListView<EventLog.Line> logList = new ListView<>();
    private FilteredList<EventLog.Line> logItems;
    /** The selected torrent's graph; fed every tick, painted only while its tab shows. */
    private final SpeedGraph speed = new SpeedGraph(600, false);

    private SpeedHistory speedHistory;
    private long speedNow;
    private final Label empty = new Label(tr("detail.empty"));
    private boolean[] lastPieces = new boolean[0];
    private String shownId;

    /**
     * The tab the person chose, kept while a download from a link shows the Overview in place of
     * its Peers or Trackers, so the next torrent opens on it again.
     */
    private String wantedTab = "overview";

    /** Set while {@link #show} moves off a disabled tab, so the move is not taken as a choice. */
    private boolean steering;

    private Function<String, Optional<Command>> commands = id -> Optional.empty();
    private BiConsumer<Integer, com.torrenta.engine.Priority> onFilePriority = (i, p) -> {};
    private Consumer<FileRows.Row> onOpenFile = r -> {};
    private Consumer<FileRows.Row> onOpenFolder = r -> {};
    /** Stream in Player and Copy Stream URL, for a file row. */
    private Consumer<FileRows.Row> onStream = r -> {};

    private Consumer<FileRows.Row> onCopyStream = r -> {};
    private BiConsumer<FileRows.Row, String> onRename = (r, name) -> {};
    /** What the capture hook types into the next file Rename dialog before pressing OK. */
    private String renameForCapture;

    private Consumer<List<String>> onAddTrackers = urls -> {};
    private Consumer<List<String>> onAddWebSeeds = urls -> {};
    private Consumer<String> onRemoveWebSeed = url -> {};
    private final ObservableList<String> webSeedItems = FXCollections.observableArrayList();
    private final TableView<String> webSeeds = new TableView<>(webSeedItems);
    /** Add and Remove for the web seeds: hidden for an engine that only lists them. */
    private final HBox webSeedBar = new HBox(8);
    /** What the capture hook types into the next Add Web Seeds dialog before pressing OK. */
    private String webSeedsForCapture;

    private Consumer<String> onRemoveTracker = url -> {};
    private Runnable onReannounce = () -> {};
    private Consumer<String> onBanPeer = address -> {};
    private Consumer<String> onCopyPeer = address -> {};
    private Consumer<String> onCopyCommand = command -> {};
    private MenuItem banItem;
    private TableView<TorrentDetail.Peer> peerTable;
    private ContextMenu peerMenu;
    /** The peer the open context menu is about, read when it shows: the list is replaced every tick. */
    private String peerTarget;
    /** The files are on this machine (not a remote daemon's): Open File and the folder actions show. */
    private boolean localFiles = true;
    /** Whether a torrent waits for its files to be chosen: a mark in the settings, the window's. */
    private java.util.function.Predicate<String> filesToChoose = id -> false;

    void setFilesToChoose(java.util.function.Predicate<String> marked) {
        filesToChoose = marked;
    }
    /** The engine can serve a file while it downloads ({@code Capability.STREAMING}). */
    private boolean streaming = true;

    DetailPane() {
        getStyleClass().add("detail-pane");
        buildHeader();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Chrome is not focus-traversable: with no torrents the list is hidden and the scene would
        // otherwise hand the first keystrokes to this tab strip.
        tabs.setFocusTraversable(false);
        filesTab = tab(tr("detail.tab.files"), Icons.files(), filesCount, buildFiles());
        peersTab = tab(tr("detail.tab.peers"), Icons.peers(), peersCount, buildPeers());
        trackersTab = tab(tr("detail.tab.trackers"), Icons.broadcast(), trackersCount, buildTrackers());
        tabs.getTabs()
                .addAll(
                        tab(tr("detail.tab.overview"), Icons.info(), null, buildOverview()),
                        filesTab,
                        peersTab,
                        trackersTab,
                        tab(tr("detail.tab.log"), Icons.history(), null, buildLog()),
                        tab(tr("detail.tab.speed"), Icons.gauge(), null, speed));
        tabs.getSelectionModel().selectedIndexProperty().addListener(o -> {
            if (!steering) wantedTab = activeTab();
            if (speedShowing()) speed.show(speedHistory, speedNow);
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);
        empty.getStyleClass().add("empty-hint");
        empty.setPadding(new Insets(12));
        getChildren().addAll(header, tabs);
        widthProperty().addListener(o -> relayout());
        relayout();
        clear();
    }

    /**
     * The window's commands by id, for the header's buttons, the diagnosis's remedies and the
     * chords they show: one definition, so the pane cannot disagree with the menus.
     */
    void setCommands(Function<String, Optional<Command>> lookup) {
        commands = lookup;
        pauseButton.setGraphic(actionGraphic(pauseButton, pauseGlyph, "pause-resume"));
        reannounceButton.setGraphic(actionGraphic(reannounceButton, Icons.broadcast(), "reannounce"));
        openButton.setGraphic(actionGraphic(openButton, Icons.open(), "open-folder"));
    }

    /** The ··· button's menu: the Torrent menu's items, as the list's context menu has them. */
    void setMoreMenu(ContextMenu menu) {
        moreButton.setOnAction(e -> menu.show(moreButton, javafx.geometry.Side.BOTTOM, 0, 4));
    }

    /** The window's event log, of which the Log tab shows the selected torrent's lines. */
    void setEvents(ObservableList<EventLog.Line> events) {
        logItems = new FilteredList<>(events, e -> false);
        logList.setItems(logItems);
    }

    void setOnFilePriority(BiConsumer<Integer, com.torrenta.engine.Priority> handler) {
        onFilePriority = handler;
    }

    /** The read-ahead's pieces, marked on the strip from the next show; null for none. */
    void showStreamWindow(com.torrenta.engine.PieceMath.Span window) {
        streamWindow = window;
    }

    void setOnOpenFile(Consumer<FileRows.Row> handler) {
        onOpenFile = handler;
    }

    void setOnStream(Consumer<FileRows.Row> handler) {
        onStream = handler;
    }

    void setOnCopyStream(Consumer<FileRows.Row> handler) {
        onCopyStream = handler;
    }

    /** A folder row, or a file row whose containing folder is meant; see {@link FileRows#folderOf}. */
    void setOnOpenFolder(Consumer<FileRows.Row> handler) {
        onOpenFolder = handler;
    }

    /** A file or folder row and its new name (the last segment only); the engine says if it will not do. */
    void setOnRename(BiConsumer<FileRows.Row, String> handler) {
        onRename = handler;
    }

    void setOnAddTrackers(Consumer<List<String>> handler) {
        onAddTrackers = handler;
    }

    void setOnAddWebSeeds(Consumer<List<String>> handler) {
        onAddWebSeeds = handler;
    }

    void setOnRemoveWebSeed(Consumer<String> handler) {
        onRemoveWebSeed = handler;
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

    void setOnCopyPeer(Consumer<String> handler) {
        onCopyPeer = handler;
    }

    /** The Overview's Copy beside a download's command line. */
    void setOnCopyCommand(Consumer<String> handler) {
        onCopyCommand = handler;
    }

    /**
     * Hides what the engine cannot do here: Open file when the files are not on this machine, Ban
     * Peer when the engine takes no IP filter from us.
     */
    void applyCapabilities(com.torrenta.engine.Engine.Capabilities caps) {
        localFiles = caps.has(com.torrenta.engine.Engine.Capability.LOCAL_FILES);
        streaming = caps.has(com.torrenta.engine.Engine.Capability.STREAMING);
        openButton.setVisible(localFiles);
        openButton.setManaged(localFiles);
        if (banItem != null) banItem.setVisible(caps.has(com.torrenta.engine.Engine.Capability.IP_FILTER));
        boolean editSeeds = caps.has(com.torrenta.engine.Engine.Capability.WEB_SEEDS);
        webSeedBar.setVisible(editSeeds);
        webSeedBar.setManaged(editSeeds);
    }

    /** For the capture hook: the Peers tab's context menu, about the first peer when there is one. */
    void showPeerMenuForCapture() {
        selectTab("peers");
        if (!peerItems.isEmpty()) peerTable.getSelectionModel().clearAndSelect(0);
        peerMenu.show(peerTable, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /** For the capture hook: the menu's lines, {@code [icon]} after an item with a glyph. */
    java.util.List<String> peerMenuLines() {
        return peerMenu.getItems().stream()
                .filter(MenuItem::isVisible)
                .map(i -> i.getText() + (i.getGraphic() != null ? " [icon]" : "") + (i.isDisable() ? " (off)" : ""))
                .toList();
    }

    /** For the capture hook: presses Ban Peer on the first peer; false when there is none. */
    boolean banFirstPeerForCapture() {
        if (peerItems.isEmpty() || !banItem.isVisible()) return false;
        onBanPeer.accept(peerItems.get(0).address());
        return true;
    }

    /** The Speed tab's window, shared with the Statistics window through the settings. */
    void setSpeedWindow(int seconds) {
        speed.setWindow(seconds);
    }

    void setOnSpeedWindowChanged(java.util.function.IntConsumer handler) {
        speed.setOnWindowChanged(handler);
    }

    /**
     * FX thread. The selected torrent's history as of {@code nowSeconds}, with each snapshot: the
     * Overview's half hour is drawn every tick (a few shapes), the Speed tab's graph only while it
     * is the one showing, and when it becomes so.
     */
    void showSpeed(SpeedHistory history, long nowSeconds) {
        speedHistory = history;
        speedNow = nowSeconds;
        if (history == null) {
            overviewSpark.clear();
        } else {
            List<SpeedHistory.Sample> samples = history.blended(OVERVIEW_WINDOW, nowSeconds);
            int window = Sparkline.window(samples, nowSeconds, OVERVIEW_WINDOW);
            overviewSpark.show(samples, window, nowSeconds);
            speedCaption.setText(tr("detail.overview.speed", window / 60));
        }
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

    /** The tab to remember: the person's choice, not the Overview a link download stood in with. */
    String rememberedTab() {
        return wantedTab;
    }

    /** The tab to show for {@code wanted}: the Overview where a download from a link has nothing. */
    static String tabFor(String wanted, boolean direct) {
        return direct && TORRENT_ONLY.contains(wanted) ? "overview" : wanted;
    }

    /**
     * Selects a tab by name. General and Pieces were folded into the Overview; a remembered one
     * of those opens it.
     */
    void selectTab(String name) {
        String n = "general".equals(name) || "pieces".equals(name) ? "overview" : name;
        int i = TABS.indexOf(n);
        if (i >= 0) tabs.getSelectionModel().select(i);
    }

    /** What the capture hook prints. */
    String summary() {
        int fileCount = fileRows.rootItem() == null
                ? 0
                : fileRows.rootItem().getChildren().size();
        return activeTab() + " files=" + fileCount + " peers=" + peerItems.size() + " trackers=" + trackerItems.size()
                + " pieces=" + count(lastPieces) + "/" + lastPieces.length + " webseeds=" + webSeedItems
                + " peersTrackers=" + (peersTab.isDisable() ? "off" : "on");
    }

    /** The Overview as words, for the capture hook: header, card, map, graph, facts. */
    List<String> overviewLines() {
        if (shownId == null) return List.of("empty");
        return List.of(
                "header=" + stateGlyph.getText() + " " + title.getText() + " | " + subtitle.getText()
                        + " | actions=[" + pauseButton.getText() + ", "
                        + (reannounceButton.isManaged() ? reannounceButton.getText() + ", " : "")
                        + openButton.getText() + ", " + moreButton.getText() + "] wide=" + wide,
                "diagnosis="
                        + (shownDiagnosis == null
                                ? "none"
                                : shownDiagnosis.title().label() + ": " + shownDiagnosis.text() + " remedies="
                                        + shownDiagnosis.remedies()),
                "pieces=" + piecesValue.getText() + " · " + pieceStrip.summary(),
                "graph=" + overviewDown.getText() + " " + overviewUp.getText(),
                "facts=" + peersValue.getText() + " | " + downloadedValue.getText() + " | " + uploadedValue.getText()
                        + " | " + hashValue.getText(),
                "command=" + (commandValue.isManaged() ? commandValue.getText() : "none"),
                "log=" + (logItems == null ? 0 : logItems.size()));
    }

    /**
     * FX thread. A fresh snapshot of the selected torrent, with its row's torrent and stall clock
     * (null when the row has not arrived yet: the header and the facts then wait a tick).
     */
    void show(TorrentDetail detail, Torrent torrent, long quietSeconds, long nowSeconds) {
        TorrentDetail.General g = detail.general();
        boolean another = !g.id().equals(shownId);
        shownId = g.id();
        header.setVisible(true);
        header.setManaged(true);
        tabs.setVisible(true);
        tabs.setManaged(true);
        if (another && logItems != null) {
            String id = g.id();
            logItems.setPredicate(e -> id.equals(e.event().torrentId()));
            if (!logItems.isEmpty()) logList.scrollTo(logItems.size() - 1);
        }

        if (torrent != null) showHeader(torrent, detail.files().size(), quietSeconds, nowSeconds);
        showDiagnosis(
                torrent == null
                        ? Optional.empty()
                        : Diagnosis.of(
                                torrent,
                                RowState.of(torrent, quietSeconds),
                                detail,
                                quietSeconds,
                                filesToChoose.test(torrent.id())));

        piecesValue.setText(g.pieceCount() == 0 ? "—" : OverviewText.pieces(g.piecesHave(), g.pieceCount()));
        pieceStrip.show(detail.pieces(), detail.availability(), PieceStrip.skipped(detail), streamWindow);
        if (torrent != null) {
            overviewDown.setText("↓ " + Bytes.rate(torrent.downloadRate()));
            overviewUp.setText("↑ " + Bytes.rate(torrent.uploadRate()));
            peersValue.setText(OverviewText.peers(torrent));
            downloadedValue.setText(OverviewText.downloaded(torrent));
            uploadedValue.setText(OverviewText.uploaded(torrent));
        }
        // A download from a link has no swarm, pieces or announce: those rows go, its link shows.
        boolean direct = com.torrenta.engine.direct.DirectDownload.isId(g.id());
        peersTab.setDisable(direct);
        trackersTab.setDisable(direct);
        String tab = tabFor(wantedTab, direct);
        if (!tab.equals(activeTab())) {
            steering = true;
            selectTab(tab);
            steering = false;
        }
        for (Node n : torrentFacts) shown(n, !direct);
        for (Node n : directFacts) shown(n, direct);
        shown(reannounceButton, !direct);
        sourceValue.setText(direct ? g.currentTracker() : "");
        commandValue.setText(detail.command());
        savePathValue.setText(g.savePath());
        hashValue.setText(OverviewText.shortHash(g.id()));
        availabilityValue.setText(
                g.availability() < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.2f", g.availability()));
        pieceSizeValue.setText(
                g.pieceCount() == 0
                        ? "—"
                        : tr("detail.overview.pieceLayout", g.pieceCount(), Bytes.human(g.pieceLength())));
        wastedValue.setText(Bytes.human(g.wasted()));
        activeValue.setText(Format.duration(g.activeSeconds()));
        seedingValue.setText(Format.duration(g.seedingSeconds()));
        announceValue.setText(g.nextAnnounceSeconds() <= 0 ? "—" : Format.duration(g.nextAnnounceSeconds()));
        trackerValue.setText(Format.host(g.currentTracker()));
        privateValue.setText(g.isPrivate() ? tr("common.yes") : tr("common.no"));

        if (another) fileRows.clear();
        if (fileRows.apply(detail.files())) {
            files.setRoot(fileRows.rootItem());
        }
        peerItems.setAll(detail.peers());
        if (another || trackerItems.size() != detail.trackers().size()) trackerItems.setAll(detail.trackers());
        else
            for (int i = 0; i < trackerItems.size(); i++)
                trackerItems.set(i, detail.trackers().get(i));
        if (another || !webSeedItems.equals(detail.webSeeds())) webSeedItems.setAll(detail.webSeeds());
        lastPieces = detail.pieces();
        filesCount.setText(count(detail.files().size()));
        peersCount.setText(count(detail.peers().size()));
        trackersCount.setText(count(detail.trackers().size()));
    }

    /** FX thread. No single selection: the pane says so and holds no torrent. */
    void clear() {
        shownId = null;
        header.setVisible(false);
        header.setManaged(false);
        showDiagnosis(Optional.empty());
        fileRows.clear();
        files.setRoot(null);
        peerItems.clear();
        trackerItems.clear();
        webSeedItems.clear();
        lastPieces = new boolean[0];
        pieceStrip.show(lastPieces, new int[0], new boolean[0], streamWindow);
        if (logItems != null) logItems.setPredicate(e -> false);
        speedHistory = null;
        speed.clear();
        overviewSpark.clear();
        for (Label l : List.of(filesCount, peersCount, trackersCount)) l.setText("");
        for (Label l : List.of(
                piecesValue,
                overviewDown,
                overviewUp,
                peersValue,
                downloadedValue,
                uploadedValue,
                savePathValue,
                hashValue,
                availabilityValue,
                pieceSizeValue,
                wastedValue,
                activeValue,
                seedingValue,
                announceValue,
                trackerValue,
                privateValue)) l.setText("—");
    }

    // ---- header ---------------------------------------------------------------------------------

    private void buildHeader() {
        header.getStyleClass().add("detail-header");
        stateGlyph.getStyleClass().add("state-glyph");
        title.getStyleClass().add("detail-title");
        title.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        subtitle.getStyleClass().add("detail-subtitle");
        VBox names = new VBox(2, title, subtitle);
        names.setMinWidth(0);
        HBox.setHgrow(names, Priority.ALWAYS);
        headerTop.setAlignment(Pos.CENTER_LEFT);
        headerTop.getChildren().addAll(stateGlyph, names);
        for (Button b : List.of(pauseButton, reannounceButton, openButton, moreButton)) {
            b.getStyleClass().add("detail-action");
            b.setFocusTraversable(false);
        }
        pauseButton.getStyleClass().add("action-pause");
        reannounceButton.getStyleClass().add("action-reannounce");
        openButton.getStyleClass().add("action-open");
        moreButton.getStyleClass().add("detail-more");
        moreButton.setTooltip(new javafx.scene.control.Tooltip(tr("detail.action.more")));
        pauseButton.setOnAction(e -> runCommand("pause-resume"));
        reannounceButton.setOnAction(e -> runCommand("reannounce"));
        openButton.setOnAction(e -> runCommand("open-folder"));
        actions.setAlignment(Pos.CENTER_LEFT);
        actionsRow.setAlignment(Pos.CENTER_RIGHT);
        actionsRow.setMinWidth(Region.USE_PREF_SIZE);
        header.getChildren().add(headerTop);
    }

    private void showHeader(Torrent t, int fileCount, long quietSeconds, long nowSeconds) {
        RowState state = RowState.of(t, quietSeconds);
        stateGlyph.setText(state.glyph());
        if (state != headerState) {
            if (headerState != null)
                header.pseudoClassStateChanged(PseudoClass.getPseudoClass(headerState.pseudoClass()), false);
            header.pseudoClassStateChanged(PseudoClass.getPseudoClass(state.pseudoClass()), true);
            headerState = state;
        }
        title.setText(t.name());
        subtitle.setText(OverviewText.subtitle(t, fileCount, nowSeconds));
        pauseButton.setText(tr(t.paused() ? "detail.action.resume" : "detail.action.pause"));
        if (!Boolean.valueOf(t.paused()).equals(pauseShowsResume)) {
            pauseShowsResume = t.paused();
            pauseGlyph.getChildren().setAll((t.paused() ? Icons.run() : Icons.pause()).at(Icons.MENU_SIZE));
            pauseButton.pseudoClassStateChanged(RESUME, t.paused());
        }
    }

    private static final PseudoClass RESUME = PseudoClass.getPseudoClass("resume");

    /**
     * A header button's face: its glyph in the action's colour, its name (the button's own text,
     * so {@code getText} and the accessible name stay true), and the command's chord.
     */
    private Node actionGraphic(Button button, Node glyph, String id) {
        if (glyph instanceof Icons.Glyph g) g.at(Icons.MENU_SIZE);
        Label name = new Label();
        name.textProperty().bind(button.textProperty());
        name.getStyleClass().add("action-name");
        HBox face = new HBox(7, glyph, name);
        face.setAlignment(Pos.CENTER_LEFT);
        Node key = keyHint(id);
        if (key != null) {
            HBox.setMargin(key, new Insets(0, 0, 0, 3));
            face.getChildren().add(key);
        }
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        return face;
    }

    /** A command's chord in the muted key face, or nothing for one with none. */
    private Node keyHint(String id) {
        String hint = commands.apply(id).map(Command::hint).orElse("");
        if (hint.isEmpty()) return null;
        Label key = new Label(hint);
        key.getStyleClass().add("action-key");
        return key;
    }

    private void runCommand(String id) {
        commands.apply(id).ifPresent(c -> {
            if (c.enabled().get()) c.run().run();
        });
    }

    // ---- overview -------------------------------------------------------------------------------

    private Node buildOverview() {
        card.getStyleClass().add("diagnosis-card");
        cardTitle.getStyleClass().add("diagnosis-title");
        cardBody.getStyleClass().add("diagnosis-body");
        card.getChildren().addAll(cardTitle, cardBody, remedies);

        Label piecesLabel = new Label(tr("detail.overview.pieces"));
        piecesLabel.getStyleClass().add("overview-caption");
        piecesValue.getStyleClass().add("overview-caption-value");
        piecesBox.getChildren().addAll(captionRow(piecesLabel, piecesValue), pieceStrip);

        speedCaption.setText(tr("detail.overview.speed", OVERVIEW_WINDOW / 60));
        speedCaption.getStyleClass().add("overview-caption");
        overviewDown.getStyleClass().add("overview-rate-down");
        overviewUp.getStyleClass().add("overview-rate-up");
        Label dot = new Label(OverviewText.SEP.strip());
        dot.getStyleClass().add("overview-caption");
        HBox rates = new HBox(6, overviewDown, dot, overviewUp);
        rates.setAlignment(Pos.CENTER_RIGHT);
        speedBox.getChildren().addAll(captionRow(speedCaption, rates), overviewSpark);
        overviewSpark.setMaxWidth(Double.MAX_VALUE);

        buildFacts();

        overview.getStyleClass().add("overview");
        overview.setHgap(28);
        overview.setVgap(18);
        overview.setPadding(new Insets(14, 16, 16, 16));
        ScrollPane scroll = new ScrollPane(overview);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("overview-scroll");
        return scroll;
    }

    private static HBox captionRow(Label caption, Node value) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, caption, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void buildFacts() {
        facts.getStyleClass().add("overview-facts");
        facts.setHgap(18);
        facts.setVgap(7);
        ColumnConstraints names = new ColumnConstraints();
        names.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setMinWidth(60);
        facts.getColumnConstraints().addAll(names, values);
        savePathValue.getStyleClass().add("mono");
        savePathValue.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        hashValue.getStyleClass().add("mono");
        Hyperlink copy = new Hyperlink(tr("detail.overview.copy"));
        copy.getStyleClass().add("overview-copy");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> runCommand("copy-hash"));
        HBox hash = new HBox(10, hashValue, copy);
        hash.setAlignment(Pos.CENTER_LEFT);
        // The five a glance wants first, as the mockup has them; the General tab's others after a gap.
        sourceValue.getStyleClass().add("mono");
        sourceValue.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        int row = 0;
        torrentFacts.addAll(fact(row++, tr("detail.overview.peers"), peersValue));
        fact(row++, tr("detail.general.downloaded"), downloadedValue);
        torrentFacts.addAll(fact(row++, tr("detail.general.uploaded"), uploadedValue));
        fact(row++, tr("detail.general.savePath"), savePathValue);
        directFacts.addAll(fact(row++, tr("detail.general.source"), sourceValue));
        List<Node> command = fact(row++, tr("detail.general.command"), commandBox());
        GridPane.setValignment(command.get(0), javafx.geometry.VPos.TOP);
        directFacts.addAll(command);
        torrentFacts.addAll(fact(row++, tr("detail.general.infoHash"), hash));
        Region gap = new Region();
        gap.setMinHeight(6);
        facts.add(gap, 0, row++);
        torrentFacts.addAll(fact(row++, tr("detail.general.pieces"), pieceSizeValue));
        torrentFacts.addAll(fact(row++, tr("detail.general.availability"), availabilityValue));
        torrentFacts.addAll(fact(row++, tr("detail.general.wasted"), wastedValue));
        fact(row++, tr("detail.general.active"), activeValue);
        torrentFacts.addAll(fact(row++, tr("detail.general.seeding"), seedingValue));
        torrentFacts.addAll(fact(row++, tr("detail.general.nextAnnounce"), announceValue));
        torrentFacts.addAll(fact(row++, tr("detail.general.tracker"), trackerValue));
        torrentFacts.addAll(fact(row, tr("detail.general.private"), privateValue));
        for (Node n : directFacts) shown(n, false);
    }

    /** The command line, wrapped, with a Copy under it: the line is long and made to be pasted. */
    private Node commandBox() {
        commandValue.getStyleClass().add("mono");
        commandValue.setWrapText(true);
        commandValue.setMinHeight(Region.USE_PREF_SIZE);
        Hyperlink copy = new Hyperlink(tr("detail.overview.copy"));
        copy.getStyleClass().add("overview-copy");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> onCopyCommand.accept(commandValue.getText()));
        VBox box = new VBox(2, commandValue, copy);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** The row's name and value, for showing and hiding them together. */
    private List<Node> fact(int row, String name, Node value) {
        Label label = new Label(name);
        label.getStyleClass().add("fact-name");
        facts.add(label, 0, row);
        facts.add(value, 1, row);
        return List.of(label, value);
    }

    private static void shown(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    private static Label value() {
        Label l = new Label("—");
        l.getStyleClass().add("detail-value");
        return l;
    }

    /**
     * Wide: the header's buttons to the right of the name, and the Overview in two columns (card
     * and facts | map and graph). Narrow: everything in one column, the buttons under the name.
     */
    private void relayout() {
        boolean next = getWidth() >= WIDE;
        if (next == wide && !overview.getChildren().isEmpty()) return;
        wide = next;
        // Wide, one row beside the name that keeps its width and lets the name ellipsise; narrow, a
        // wrapping row under it.
        List<Button> buttons = List.of(pauseButton, reannounceButton, openButton, moreButton);
        header.getChildren().remove(actions);
        headerTop.getChildren().remove(actionsRow);
        actions.getChildren().clear();
        actionsRow.getChildren().clear();
        if (wide) {
            actionsRow.getChildren().addAll(buttons);
            headerTop.getChildren().add(actionsRow);
        } else {
            actions.getChildren().addAll(buttons);
            header.getChildren().add(actions);
        }

        overview.getChildren().clear();
        overview.getColumnConstraints().clear();
        ColumnConstraints left = new ColumnConstraints();
        left.setHgrow(Priority.ALWAYS);
        left.setPercentWidth(wide ? 55 : 100);
        overview.getColumnConstraints().add(left);
        if (wide) {
            ColumnConstraints right = new ColumnConstraints();
            right.setHgrow(Priority.ALWAYS);
            right.setPercentWidth(45);
            overview.getColumnConstraints().add(right);
            VBox l = new VBox(18, card, facts);
            VBox r = new VBox(18, piecesBox, speedBox);
            overview.add(l, 0, 0);
            overview.add(r, 1, 0);
        } else {
            overview.add(new VBox(18, card, piecesBox, speedBox, facts), 0, 0);
        }
    }

    private void showDiagnosis(Optional<Diagnosis> next) {
        Diagnosis d = next.orElse(null);
        card.setVisible(d != null);
        card.setManaged(d != null);
        if (java.util.Objects.equals(d, shownDiagnosis)) return;
        shownDiagnosis = d;
        card.getStyleClass()
                .removeAll(
                        "diagnosis-stalled",
                        "diagnosis-error",
                        "diagnosis-queued",
                        "diagnosis-encrypted",
                        "diagnosis-choose");
        if (d == null) return;
        card.getStyleClass().add("diagnosis-" + d.title().name().toLowerCase(java.util.Locale.ROOT));
        pressPending(d);
        cardTitle.setText(d.title().label());
        cardBody.getChildren().clear();
        String[] parts = d.body().split(Diagnosis.MARK, -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                Text file = new Text(d.file());
                file.getStyleClass().addAll("diagnosis-text", "diagnosis-file");
                cardBody.getChildren().add(file);
            }
            Text text = new Text(parts[i]);
            text.getStyleClass().add("diagnosis-text");
            cardBody.getChildren().add(text);
        }
        remedies.getChildren().clear();
        remedies.setVisible(!d.remedies().isEmpty());
        remedies.setManaged(!d.remedies().isEmpty());
        for (Diagnosis.Remedy remedy : d.remedies()) {
            // A daemon's folder is on its machine: nothing here can open it.
            if (remedy == Diagnosis.Remedy.OPEN_FOLDER && !localFiles) continue;
            remedies.getChildren()
                    .add(remedyRow(d, remedy, remedies.getChildren().isEmpty()));
        }
    }

    /** One remedy: its words on the left, its chord on the right, the whole row a button. */
    private Node remedyRow(Diagnosis d, Diagnosis.Remedy remedy, boolean primary) {
        Label label = new Label(remedy.label());
        label.getStyleClass().add("remedy-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, label, spacer);
        remedy.command()
                .flatMap(commands)
                .map(Command::hint)
                .filter(h -> !h.isEmpty())
                .ifPresent(h -> {
                    Label key = new Label(h);
                    key.getStyleClass().add("remedy-key");
                    row.getChildren().add(key);
                });
        row.getStyleClass().add("remedy");
        if (primary) row.getStyleClass().add("primary");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> apply(d, remedy));
        return row;
    }

    private void apply(Diagnosis d, Diagnosis.Remedy remedy) {
        switch (remedy) {
            case SKIP_FILE -> {
                if (d.fileIndex() >= 0) onFilePriority.accept(d.fileIndex(), com.torrenta.engine.Priority.IGNORE);
            }
            case ADD_TRACKERS -> askTrackers();
            default -> remedy.command().ifPresent(this::runCommand);
        }
    }

    /**
     * For the capture hook: presses a remedy on the card, as a click would: {@code wanted}, or the
     * first when null. The hook acts before a stall has had its minute, so with no card offering it
     * yet, it is pressed when one first does.
     */
    void pressRemedyForCapture(Diagnosis.Remedy wanted) {
        remedyPending = wanted;
        remedyPendingAny = wanted == null;
        if (shownDiagnosis != null) pressPending(shownDiagnosis);
    }

    private void pressPending(Diagnosis d) {
        if (!remedyPendingAny && remedyPending == null) return;
        Diagnosis.Remedy r = remedyPendingAny
                ? d.remedies().stream().findFirst().orElse(null)
                : d.remedies().contains(remedyPending) ? remedyPending : null;
        if (r == null) return;
        remedyPending = null;
        remedyPendingAny = false;
        System.out.println("capture: remedy-pressed=" + r + " (" + d.text() + ")");
        javafx.application.Platform.runLater(() -> apply(d, r));
    }

    // ---- log ------------------------------------------------------------------------------------

    private Node buildLog() {
        logList.getStyleClass().add("detail-log");
        logList.setFocusTraversable(false);
        logList.setPlaceholder(new Label(tr("detail.log.empty")));
        logList.setCellFactory(v -> new ListCell<>() {
            private final Label time = new Label();
            private final Label message = new Label();
            private final HBox box = new HBox(12, time, message);

            {
                time.getStyleClass().addAll("log-time", "mono");
                message.getStyleClass().add("log-message");
                message.setMinWidth(0);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(EventLog.Line e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("log-warning", "log-error");
                if (empty || e == null) {
                    setGraphic(null);
                    return;
                }
                time.setText(LOG_TIME.format(e.at().atZone(ZoneId.systemDefault())));
                message.setText(e.text());
                if (e.level() == EngineEvent.Level.ERROR) getStyleClass().add("log-error");
                else if (e.level() == EngineEvent.Level.WARNING) getStyleClass().add("log-warning");
                setGraphic(box);
            }
        });
        return logList;
    }

    // ---- tabs -----------------------------------------------------------------------------------

    /**
     * A tab with its glyph before the title, at the menus' size, and a muted count after it when
     * {@code count} is given (Files, Peers, Trackers).
     */
    private static Tab tab(String title, Icons.Glyph glyph, Label count, Node content) {
        Tab tab = new Tab(title, content);
        if (count == null) {
            tab.setGraphic(glyph.at(Icons.MENU_SIZE));
            return tab;
        }
        count.getStyleClass().add("tab-count");
        HBox graphic = new HBox(6, glyph.at(Icons.MENU_SIZE), new Label(title), count);
        graphic.getStyleClass().add("tab-title");
        graphic.setAlignment(Pos.CENTER_LEFT);
        tab.setText(null);
        tab.setGraphic(graphic);
        return tab;
    }

    private static String count(int n) {
        return n == 0 ? "" : String.valueOf(n);
    }

    private static int count(boolean[] bits) {
        int c = 0;
        for (boolean b : bits) if (b) c++;
        return c;
    }

    /**
     * Beside the list or under it, as a pseudo-class for app.css. The layout itself follows the
     * pane's width (see {@link #relayout}), which is what decides whether two columns fit.
     */
    void setBeside(boolean beside) {
        pseudoClassStateChanged(PseudoClass.getPseudoClass("right"), beside);
    }

    private javafx.scene.Node buildFiles() {
        files.setShowRoot(false);
        files.setEditable(true);
        files.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        files.setPlaceholder(new Label(tr("detail.files.empty")));
        TreeTableColumn<FileRows.Row, String> name = treeColumn(tr("common.name"), 320, FileRows.Row::name);
        name.setCellFactory(c -> new NameCell());
        TreeTableColumn<FileRows.Row, String> size = new TreeTableColumn<>(tr("common.size"));
        size.setPrefWidth(90);
        size.setCellValueFactory(p -> p.getValue().getValue().sizeProperty().map(n -> Bytes.human(n.longValue())));
        TreeTableColumn<FileRows.Row, String> progress = new TreeTableColumn<>(tr("detail.files.column.progress"));
        progress.setPrefWidth(90);
        progress.setCellValueFactory(p -> {
            FileRows.Row row = p.getValue().getValue();
            return row.doneProperty().map(d -> Format.percent(row.progress()));
        });
        TreeTableColumn<FileRows.Row, com.torrenta.engine.Priority> priority =
                new TreeTableColumn<>(tr("detail.files.column.priority"));
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

        files.setContextMenu(buildFilesMenu());
        // No row chosen: no menu, rather than an empty one. Rename works on every engine, so there
        // is always something for a row, even with the files on another machine.
        files.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            if (selectedFileRow().isEmpty()) e.consume();
        });
        // F2 here, not as an accelerator: a context menu's would be the whole window's, and F2 on
        // the list renames the torrent.
        files.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.F2 && !e.isShortcutDown() && !e.isAltDown()) {
                selectedFileRow().ifPresent(this::askRename);
                e.consume();
            }
        });
        return files;
    }

    /**
     * Open File and Open Containing Folder for a file, Open Folder for a folder: the menu decides
     * as it opens, from the selected row, and shows nothing it cannot do.
     */
    private ContextMenu buildFilesMenu() {
        MenuItem open = new MenuItem(tr("detail.files.openFile"));
        open.setGraphic(Icons.files().at(Icons.MENU_SIZE));
        open.setOnAction(e -> selectedFileRow().filter(FileRows.Row::isFile).ifPresent(onOpenFile));
        MenuItem folder = new MenuItem(tr("detail.files.openFolder"));
        folder.setGraphic(Icons.open().at(Icons.MENU_SIZE));
        folder.setOnAction(e -> selectedFileRow().ifPresent(onOpenFolder));
        MenuItem rename = new MenuItem(tr("detail.files.rename"));
        rename.setGraphic(Icons.rename().at(Icons.MENU_SIZE));
        rename.setOnAction(e -> selectedFileRow().ifPresent(this::askRename));
        // A file played while it downloads: in a player, or its URL for one of the person's choosing.
        MenuItem stream = new MenuItem(tr("detail.files.stream"));
        stream.setGraphic(Icons.forKind(com.torrenta.content.FileKind.VIDEO).at(Icons.MENU_SIZE));
        stream.setOnAction(e -> selectedFileRow().filter(FileRows.Row::isFile).ifPresent(onStream));
        MenuItem copyStream = new MenuItem(tr("detail.files.copyStream"));
        copyStream.setGraphic(Icons.copy().at(Icons.MENU_SIZE));
        copyStream.setOnAction(
                e -> selectedFileRow().filter(FileRows.Row::isFile).ifPresent(onCopyStream));
        javafx.scene.control.SeparatorMenuItem gap = new javafx.scene.control.SeparatorMenuItem();
        javafx.scene.control.SeparatorMenuItem streamGap = new javafx.scene.control.SeparatorMenuItem();
        ContextMenu menu = new ContextMenu(open, folder, gap, stream, copyStream, streamGap, rename);
        menu.setOnShowing(e -> {
            boolean file = selectedFileRow().map(FileRows.Row::isFile).orElse(false);
            open.setVisible(file && localFiles);
            folder.setVisible(localFiles);
            gap.setVisible(localFiles);
            stream.setVisible(file && streaming);
            copyStream.setVisible(file && streaming);
            streamGap.setVisible(file && streaming);
            folder.setText(tr(file ? "detail.files.openContainingFolder" : "detail.files.openFolder"));
        });
        return menu;
    }

    /**
     * The new name of a file or folder, its own name to start from with the part before the
     * extension selected, as a file manager does. On the next pulse, since a nested event loop
     * cannot start inside an animation or layout pass.
     */
    private void askRename(FileRows.Row row) {
        Platform.runLater(() -> {
            TextInputDialog prompt = new TextInputDialog(row.name());
            prompt.initOwner(getScene() == null ? null : getScene().getWindow());
            Fonts.installUiFont(prompt.getDialogPane());
            prompt.setTitle(tr(row.isFile() ? "detail.files.renameFile" : "detail.files.renameFolder"));
            prompt.setHeaderText(row.path());
            StockButtons.translate(prompt);
            prompt.getEditor().setPrefColumnCount(40);
            int dot = row.isFile() ? row.name().lastIndexOf('.') : -1;
            prompt.setOnShown(e ->
                    prompt.getEditor().selectRange(0, dot > 0 ? dot : row.name().length()));
            String typed = renameForCapture;
            renameForCapture = null;
            if (typed != null) {
                Platform.runLater(() -> {
                    prompt.getEditor().setText(typed);
                    StockButtons.okButton(prompt).fire();
                });
            }
            prompt.showAndWait()
                    .map(String::strip)
                    .filter(name -> !name.equals(row.name()))
                    .ifPresent(name -> onRename.accept(row, name));
        });
    }

    /** For the capture hook: the {@code n}th visible row of the Files tab renamed to {@code name}. */
    void renameFileForCapture(int n, String name) {
        selectTab("files");
        if (files.getRoot() == null) {
            files.rootProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(
                        javafx.beans.value.ObservableValue<? extends TreeItem<FileRows.Row>> o,
                        TreeItem<FileRows.Row> was,
                        TreeItem<FileRows.Row> now) {
                    if (now == null) return;
                    files.rootProperty().removeListener(this);
                    renameFileForCapture(n, name);
                }
            });
            return;
        }
        files.getSelectionModel().clearAndSelect(n);
        renameForCapture = name;
        selectedFileRow().ifPresent(this::askRename);
    }

    private Optional<FileRows.Row> selectedFileRow() {
        var item = files.getSelectionModel().getSelectedItem();
        return item == null ? Optional.empty() : Optional.ofNullable(item.getValue());
    }

    /**
     * For the capture hook: selects the {@code n}th visible row of the Files tab and opens its
     * menu. The hook's actions run before the first detail poll, so with no rows yet it waits for
     * the tree to be rooted and acts then.
     */
    /**
     * Capture only: Stream in Player on visible row {@code n}, once the rows are in; then back to
     * the tab that showed before, so a capture can look at the Overview's strip meanwhile.
     */
    void streamFileForCapture(int n) {
        streamFileForCapture(n, activeTab());
    }

    private void streamFileForCapture(int n, String back) {
        selectTab("files");
        if (files.getRoot() == null) {
            files.rootProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(
                        javafx.beans.value.ObservableValue<? extends TreeItem<FileRows.Row>> o,
                        TreeItem<FileRows.Row> was,
                        TreeItem<FileRows.Row> now) {
                    if (now == null) return;
                    files.rootProperty().removeListener(this);
                    streamFileForCapture(n, back);
                }
            });
            return;
        }
        files.getSelectionModel().clearAndSelect(n);
        selectedFileRow().filter(FileRows.Row::isFile).ifPresent(onStream);
        selectTab(back);
    }

    void showFilesMenuForCapture(int n) {
        selectTab("files");
        if (files.getRoot() == null) {
            files.rootProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(
                        javafx.beans.value.ObservableValue<? extends TreeItem<FileRows.Row>> o,
                        TreeItem<FileRows.Row> was,
                        TreeItem<FileRows.Row> now) {
                    if (now == null) return;
                    files.rootProperty().removeListener(this);
                    showFilesMenuForCapture(n);
                }
            });
            return;
        }
        files.getSelectionModel().clearAndSelect(n);
        files.getContextMenu().show(files, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /**
     * The Name column's cell: a small folder or sheet before the name, one node of each kept per
     * cell so a reused cell swaps rather than rebuilds. Long names cut in the middle so the
     * extension stays, as the Add dialog's tree cuts them. A file that came from disk at the add
     * (linked, cloned or copied from another torrent) has a link glyph after its own, and says
     * how and from where in its tooltip.
     */
    private static final class NameCell extends TreeTableCell<FileRows.Row, String> {
        /** One glyph per kind this cell has shown, so a reused cell swaps rather than rebuilds. */
        private final java.util.EnumMap<FileKind, Icons.Glyph> glyphs = new java.util.EnumMap<>(FileKind.class);

        private final Icons.Glyph foundGlyph = Icons.link().at(Icons.MENU_SIZE);
        private final javafx.scene.layout.HBox withFound = new javafx.scene.layout.HBox(3);
        private final javafx.scene.control.Tooltip foundTip = new javafx.scene.control.Tooltip();

        NameCell() {
            setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
            setGraphicTextGap(6);
            foundGlyph.getStyleClass().add("file-found");
            withFound.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            FileRows.Row row =
                    empty || getTableRow() == null ? null : getTableRow().getItem();
            if (row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item);
            Icons.Glyph kind = glyphs.computeIfAbsent(FileKind.of(row.name(), !row.isFile()), k -> {
                Icons.Glyph glyph = Icons.forKind(k);
                glyph.getStyleClass().add("file-glyph");
                if (k == FileKind.FOLDER) glyph.getStyleClass().add("file-glyph-folder");
                return glyph;
            });
            if (row.found() == null) {
                withFound.getChildren().clear();
                setGraphic(kind);
                setTooltip(null);
            } else {
                withFound.getChildren().setAll(kind, foundGlyph);
                setGraphic(withFound);
                foundTip.setText(row.found().sentence());
                setTooltip(foundTip);
            }
        }
    }

    /** Capture only: the files tree's rows as shown, indented by depth, each with its kind. */
    List<String> fileLinesForCapture() {
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < files.getExpandedItemCount(); i++) {
            var item = files.getTreeItem(i);
            FileRows.Row row = item.getValue();
            lines.add("  ".repeat(Math.max(0, files.getTreeItemLevel(item) - 1)) + row.name() + " ["
                    + FileKind.of(row.name(), !row.isFile()).name().toLowerCase(java.util.Locale.ROOT) + "]"
                    + (row.found() == null ? "" : " found=" + row.found().sentence()));
        }
        return lines;
    }

    private javafx.scene.Node buildPeers() {
        TableView<TorrentDetail.Peer> peers = new TableView<>(peerItems);
        peers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        peers.setPlaceholder(new Label(tr("detail.peers.empty")));
        peers.getColumns()
                .addAll(List.of(
                        column(tr("detail.peers.column.address"), 170, TorrentDetail.Peer::address),
                        column(tr("detail.peers.column.client"), 170, TorrentDetail.Peer::client),
                        column(tr("detail.peers.column.flags"), 70, TorrentDetail.Peer::flags),
                        column(tr("detail.peers.column.progress"), 80, p -> Format.percent(p.progress())),
                        column(tr("detail.peers.column.down"), 90, p -> Bytes.rate(p.downRate())),
                        column(tr("detail.peers.column.up"), 90, p -> Bytes.rate(p.upRate())),
                        column(tr("detail.peers.column.downloaded"), 100, p -> Bytes.human(p.downloaded())),
                        column(tr("detail.peers.column.uploaded"), 100, p -> Bytes.human(p.uploaded()))));
        Headers.uppercase(peers);

        banItem = new MenuItem(tr("detail.peers.ban"));
        banItem.setGraphic(Icons.ban().at(Icons.MENU_SIZE));
        banItem.setOnAction(e -> {
            if (peerTarget != null) onBanPeer.accept(peerTarget);
        });
        MenuItem copy = new MenuItem(tr("detail.peers.copyAddress"));
        copy.setGraphic(Icons.copy().at(Icons.MENU_SIZE));
        copy.setOnAction(e -> {
            if (peerTarget != null) onCopyPeer.accept(peerTarget);
        });
        peerMenu = new ContextMenu(banItem, copy);
        // A right-click selects the row before the menu shows; the address is taken then, because
        // the next tick replaces the list (and with it the selection) while the menu is open.
        peerMenu.setOnShowing(e -> {
            TorrentDetail.Peer peer = peers.getSelectionModel().getSelectedItem();
            peerTarget = peer == null ? null : peer.address();
            banItem.setDisable(peerTarget == null);
            copy.setDisable(peerTarget == null);
        });
        peers.setContextMenu(peerMenu);
        peerTable = peers;
        return peers;
    }

    private javafx.scene.Node buildTrackers() {
        trackers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        trackers.setPlaceholder(new Label(tr("detail.trackers.empty")));
        trackers.getColumns()
                .addAll(List.of(
                        column(tr("detail.trackers.column.url"), 360, TorrentDetail.Tracker::url),
                        column(tr("detail.trackers.column.tier"), 50, t -> String.valueOf(t.tier())),
                        column(tr("detail.trackers.column.status"), 100, t -> trackerStatus(t.status())),
                        column(tr("detail.trackers.column.message"), 240, TorrentDetail.Tracker::message)));
        Button add = new Button(tr("detail.trackers.add"));
        add.setOnAction(e -> askTrackers());
        Button remove = new Button(tr("common.remove"));
        remove.disableProperty()
                .bind(trackers.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> onRemoveTracker.accept(
                trackers.getSelectionModel().getSelectedItem().url()));
        Button reannounce = new Button(tr("detail.trackers.reannounce"));
        reannounce.setOnAction(e -> onReannounce.run());
        HBox bar = new HBox(8, add, remove, reannounce);
        bar.setPadding(new Insets(6));

        // The web seeds under the trackers: HTTP sources are the other place pieces come from.
        webSeeds.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        webSeeds.setPlaceholder(new Label(tr("detail.webSeeds.empty")));
        webSeeds.getColumns().add(column(tr("detail.webSeeds.title"), 360, url -> url));
        webSeeds.setPrefHeight(100);
        Button addSeeds = new Button(tr("detail.webSeeds.add"));
        addSeeds.setOnAction(e -> askWebSeeds());
        Button removeSeed = new Button(tr("common.remove"));
        removeSeed
                .disableProperty()
                .bind(webSeeds.getSelectionModel().selectedItemProperty().isNull());
        removeSeed.setOnAction(
                e -> onRemoveWebSeed.accept(webSeeds.getSelectionModel().getSelectedItem()));
        webSeedBar.getChildren().setAll(addSeeds, removeSeed);
        webSeedBar.setPadding(new Insets(6));

        VBox box = new VBox(bar, trackers, webSeedBar, webSeeds);
        // The two share the height: in a short pane the web seeds must not be pushed off the end.
        VBox.setVgrow(trackers, Priority.ALWAYS);
        VBox.setVgrow(webSeeds, Priority.ALWAYS);
        trackers.setMinHeight(60);
        webSeeds.setMinHeight(60);
        return box;
    }

    private void askWebSeeds() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.initOwner(getScene() == null ? null : getScene().getWindow());
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle(tr("detail.webSeeds.addDialog.title"));
        prompt.setHeaderText(tr("detail.webSeeds.addDialog.header"));
        prompt.setContentText(tr("detail.trackers.addDialog.prompt"));
        StockButtons.translate(prompt);
        prompt.getEditor().setPrefColumnCount(48);
        String typed = webSeedsForCapture;
        webSeedsForCapture = null;
        if (typed != null) {
            Platform.runLater(() -> {
                prompt.getEditor().setText(typed);
                StockButtons.okButton(prompt).fire();
            });
        }
        Optional<String> text = prompt.showAndWait();
        text.map(Actions::trackerUrls).filter(urls -> !urls.isEmpty()).ifPresent(onAddWebSeeds);
    }

    /** For the capture hook: the web seeds listed, and whether Add and Remove are offered. */
    String webSeedLineForCapture() {
        return webSeedItems + " editable=" + webSeedBar.isVisible();
    }

    /** For the capture hook: the Trackers tab's Add Web Seeds dialog, {@code urls} typed and confirmed. */
    void addWebSeedsForCapture(String urls) {
        selectTab("trackers");
        webSeedsForCapture = urls;
        // On the next pulse: a nested event loop cannot start inside an animation or layout pass.
        Platform.runLater(this::askWebSeeds);
    }

    /**
     * For the capture hook: the web seed at {@code row} removed, as Remove under the list does;
     * once the list has that many rows.
     */
    void removeWebSeedForCapture(int row) {
        selectTab("trackers");
        if (row < webSeedItems.size()) {
            onRemoveWebSeed.accept(webSeedItems.get(row));
            return;
        }
        // The list arrives with the first detail poll, after the hook's actions have run.
        webSeedItems.addListener(new javafx.collections.ListChangeListener<String>() {
            @Override
            public void onChanged(Change<? extends String> c) {
                if (row >= webSeedItems.size()) return;
                webSeedItems.removeListener(this);
                onRemoveWebSeed.accept(webSeedItems.get(row));
            }
        });
    }

    private void askTrackers() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.initOwner(getScene() == null ? null : getScene().getWindow());
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle(tr("detail.trackers.addDialog.title"));
        prompt.setHeaderText(tr("detail.trackers.addDialog.header"));
        prompt.setContentText(tr("detail.trackers.addDialog.prompt"));
        StockButtons.translate(prompt);
        prompt.getEditor().setPrefColumnCount(48);
        Optional<String> text = prompt.showAndWait();
        text.map(Actions::trackerUrls).filter(urls -> !urls.isEmpty()).ifPresent(onAddTrackers);
    }

    /** A tracker's status word: the engines report working, updating, not contacted or error. */
    static String trackerStatus(String status) {
        return switch (status) {
            case "working" -> tr("detail.trackers.status.working");
            case "updating" -> tr("detail.trackers.status.updating");
            case "not contacted" -> tr("detail.trackers.status.notContacted");
            case "error" -> tr("detail.trackers.status.error");
            default -> status;
        };
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
