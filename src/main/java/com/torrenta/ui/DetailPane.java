package com.torrenta.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

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
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.StringConverter;

import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;

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
    private RowState headerState;
    private boolean wide;

    // ---- overview
    private final GridPane overview = new GridPane();
    private final VBox card = new VBox(8);
    private final Label cardTitle = new Label();
    private final TextFlow cardBody = new TextFlow();
    private final VBox remedies = new VBox(2);
    private Diagnosis shownDiagnosis;
    private final Label piecesValue = new Label();
    private final PieceStrip pieceStrip = new PieceStrip();
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
    private final Label privateValue = value();

    // ---- the other tabs
    private final FileRows fileRows = new FileRows();
    private final TreeTableView<FileRows.Row> files = new TreeTableView<>();
    private final ObservableList<TorrentDetail.Peer> peerItems = FXCollections.observableArrayList();
    private final ObservableList<TorrentDetail.Tracker> trackerItems = FXCollections.observableArrayList();
    private final TableView<TorrentDetail.Tracker> trackers = new TableView<>(trackerItems);
    private final ListView<EngineEvent> logList = new ListView<>();
    private FilteredList<EngineEvent> logItems;
    /** The selected torrent's graph; fed every tick, painted only while its tab shows. */
    private final SpeedGraph speed = new SpeedGraph(600, false);

    private SpeedHistory speedHistory;
    private long speedNow;
    private final Label empty = new Label(tr("detail.empty"));
    private boolean[] lastPieces = new boolean[0];
    private String shownId;

    private Function<String, Optional<Command>> commands = id -> Optional.empty();
    private BiConsumer<Integer, com.torrenta.engine.Priority> onFilePriority = (i, p) -> {};
    private Consumer<FileRows.Row> onOpenFile = r -> {};
    private Consumer<FileRows.Row> onOpenFolder = r -> {};
    private Consumer<List<String>> onAddTrackers = urls -> {};
    private Consumer<String> onRemoveTracker = url -> {};
    private Runnable onReannounce = () -> {};
    private Consumer<String> onBanPeer = address -> {};

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
        pauseButton.setGraphic(keyHint("pause-resume"));
        reannounceButton.setGraphic(keyHint("reannounce"));
        openButton.setGraphic(keyHint("open-folder"));
    }

    /** The ··· button's menu: the Torrent menu's items, as the list's context menu has them. */
    void setMoreMenu(ContextMenu menu) {
        moreButton.setOnAction(e -> menu.show(moreButton, javafx.geometry.Side.BOTTOM, 0, 4));
    }

    /** The window's event log, of which the Log tab shows the selected torrent's lines. */
    void setEvents(ObservableList<EngineEvent> events) {
        logItems = new FilteredList<>(events, e -> false);
        logList.setItems(logItems);
    }

    void setOnFilePriority(BiConsumer<Integer, com.torrenta.engine.Priority> handler) {
        onFilePriority = handler;
    }

    void setOnOpenFile(Consumer<FileRows.Row> handler) {
        onOpenFile = handler;
    }

    /** A folder row, or a file row whose containing folder is meant; see {@link FileRows#folderOf}. */
    void setOnOpenFolder(Consumer<FileRows.Row> handler) {
        onOpenFolder = handler;
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
                + " pieces=" + count(lastPieces) + "/" + lastPieces.length;
    }

    /** The Overview as words, for the capture hook: header, card, map, graph, facts. */
    List<String> overviewLines() {
        if (shownId == null) return List.of("empty");
        return List.of(
                "header=" + stateGlyph.getText() + " " + title.getText() + " | " + subtitle.getText()
                        + " | actions=[" + pauseButton.getText() + ", " + reannounceButton.getText() + ", "
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
            logItems.setPredicate(e -> id.equals(e.torrentId()));
            if (!logItems.isEmpty()) logList.scrollTo(logItems.size() - 1);
        }

        if (torrent != null) showHeader(torrent, detail.files().size(), quietSeconds, nowSeconds);
        showDiagnosis(
                torrent == null
                        ? Optional.empty()
                        : Diagnosis.of(torrent, RowState.of(torrent, quietSeconds), detail, quietSeconds));

        piecesValue.setText(g.pieceCount() == 0 ? "—" : OverviewText.pieces(g.piecesHave(), g.pieceCount()));
        pieceStrip.show(detail.pieces(), detail.availability());
        if (torrent != null) {
            overviewDown.setText("↓ " + Bytes.rate(torrent.downloadRate()));
            overviewUp.setText("↑ " + Bytes.rate(torrent.uploadRate()));
            peersValue.setText(OverviewText.peers(torrent));
            downloadedValue.setText(OverviewText.downloaded(torrent));
            uploadedValue.setText(OverviewText.uploaded(torrent));
        }
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
        lastPieces = new boolean[0];
        pieceStrip.show(lastPieces, new int[0]);
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
            b.setContentDisplay(ContentDisplay.RIGHT);
            b.setGraphicTextGap(10);
        }
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
        int row = 0;
        fact(row++, tr("detail.overview.peers"), peersValue);
        fact(row++, tr("detail.general.downloaded"), downloadedValue);
        fact(row++, tr("detail.general.uploaded"), uploadedValue);
        fact(row++, tr("detail.general.savePath"), savePathValue);
        fact(row++, tr("detail.general.infoHash"), hash);
        Region gap = new Region();
        gap.setMinHeight(6);
        facts.add(gap, 0, row++);
        fact(row++, tr("detail.general.pieces"), pieceSizeValue);
        fact(row++, tr("detail.general.availability"), availabilityValue);
        fact(row++, tr("detail.general.wasted"), wastedValue);
        fact(row++, tr("detail.general.active"), activeValue);
        fact(row++, tr("detail.general.seeding"), seedingValue);
        fact(row++, tr("detail.general.nextAnnounce"), announceValue);
        fact(row++, tr("detail.general.tracker"), trackerValue);
        fact(row, tr("detail.general.private"), privateValue);
    }

    private void fact(int row, String name, Node value) {
        Label label = new Label(name);
        label.getStyleClass().add("fact-name");
        facts.add(label, 0, row);
        facts.add(value, 1, row);
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
        card.getStyleClass().removeAll("diagnosis-stalled", "diagnosis-error", "diagnosis-queued");
        if (d == null) return;
        card.getStyleClass().add("diagnosis-" + d.title().name().toLowerCase(java.util.Locale.ROOT));
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
        for (int i = 0; i < d.remedies().size(); i++) {
            remedies.getChildren().add(remedyRow(d, d.remedies().get(i), i == 0));
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

    /** For the capture hook: presses the card's first remedy, as a click would. */
    void pressFirstRemedyForCapture() {
        if (shownDiagnosis != null && !shownDiagnosis.remedies().isEmpty())
            apply(shownDiagnosis, shownDiagnosis.remedies().getFirst());
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
            protected void updateItem(EngineEvent e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("log-warning", "log-error");
                if (empty || e == null) {
                    setGraphic(null);
                    return;
                }
                time.setText(LOG_TIME.format(e.at().atZone(ZoneId.systemDefault())));
                message.setText(e.message());
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
        ContextMenu menu = new ContextMenu(open, folder);
        menu.setOnShowing(e -> {
            boolean file = selectedFileRow().map(FileRows.Row::isFile).orElse(false);
            open.setVisible(file);
            folder.setText(tr(file ? "detail.files.openContainingFolder" : "detail.files.openFolder"));
        });
        return menu;
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
     * extension stays, as the Add dialog's tree cuts them.
     */
    private static final class NameCell extends TreeTableCell<FileRows.Row, String> {
        private final Icons.Glyph folder = Icons.folderSmall();
        private final Icons.Glyph file = Icons.fileSmall();

        NameCell() {
            folder.getStyleClass().add("file-glyph");
            file.getStyleClass().add("file-glyph");
            setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
            setGraphicTextGap(6);
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
            setGraphic(row.isFile() ? file : folder);
        }
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
        return peers;
    }

    private javafx.scene.Node buildTrackers() {
        trackers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        trackers.setPlaceholder(new Label(tr("detail.trackers.empty")));
        trackers.getColumns()
                .addAll(List.of(
                        column(tr("detail.trackers.column.url"), 360, TorrentDetail.Tracker::url),
                        column(tr("detail.trackers.column.tier"), 50, t -> String.valueOf(t.tier())),
                        column(tr("detail.trackers.column.status"), 100, TorrentDetail.Tracker::status),
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
        VBox box = new VBox(bar, trackers);
        VBox.setVgrow(trackers, Priority.ALWAYS);
        return box;
    }

    private void askTrackers() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.initOwner(getScene() == null ? null : getScene().getWindow());
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle(tr("detail.trackers.addDialog.title"));
        prompt.setHeaderText(tr("detail.trackers.addDialog.header"));
        prompt.setContentText(tr("detail.trackers.addDialog.prompt"));
        prompt.getEditor().setPrefColumnCount(48);
        Optional<String> text = prompt.showAndWait();
        text.ifPresent(t -> {
            List<String> urls = List.of(t.trim().split("\\\\s+"));
            if (!urls.isEmpty() && !urls.get(0).isEmpty()) onAddTrackers.accept(urls);
        });
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
