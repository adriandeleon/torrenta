package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.torrenta.AppInfo;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Inputs;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.net.TorrentFetch;
import com.torrenta.store.Settings;
import com.torrenta.update.ReleaseInfo;
import com.torrenta.update.UpdateCheck;
import com.torrenta.update.UpdateService;

/**
 * The one window: a menu bar, a filter box over the torrent list, a status bar, and the command
 * palette over all of it.
 *
 * <p>The engine arrives after the window is up ({@link #engineReady}), because loading it takes
 * longer than a first frame should. Until then inputs queue. Once it is there a worker polls it
 * once a second and hands the FX thread a fresh list, which {@link TorrentRows} reconciles into the
 * rows the table already shows; nothing on the FX thread ever calls into the engine. Every action
 * on a selection runs on a virtual thread and reports to the status bar.
 */
public final class MainWindow {

    private static final long POLL_MS = 1000;

    private final Stage stage;
    private final Settings settings;
    private final Consumer<String> opener;
    private final Label status = new Label();
    private final Label rates = new Label();
    private final TorrentRows rows = new TorrentRows();
    private final FilteredList<TorrentRow> filtered = new FilteredList<>(rows.list());
    private final TextField filter = new TextField();
    private final StackPane host = new StackPane();
    private final CommandPalette palette;
    private final EventLog log = new EventLog();
    private final LogWindow logWindow;
    private final Label errorBadge = new Label();
    private TableView<TorrentRow> table;
    private List<Command> commands = List.of();
    private AddDialog currentDialog;
    private String lastClipboardOffer = "";
    private final DetailPane detail = new DetailPane();
    private final UpdateService updates = new UpdateService();
    private final SettingsWindow settingsWindow;
    private final IpFilterController ipFilter;
    private final MenuItem downloadUpdateItem = new MenuItem();
    private final MenuItem skipUpdateItem = new MenuItem();
    private final Label updateBadge = new Label();
    private ReleaseInfo availableUpdate;
    private SettingsSync sync;
    private final Label syncBadge = new Label("Sync ⚠");
    private SplitPane split;
    private volatile String detailId;
    private volatile boolean detailWanted;

    private Engine engine;
    private String engineDescription = "";
    private final List<String> pendingInputs = new ArrayList<>();
    private ScheduledExecutorService poller;

    /**
     * @param opener opens a URI with the desktop (a folder, later a web page); the application's
     *     host services, which only the {@code Application} can hand out
     */
    public MainWindow(Stage stage, Settings settings, Path configDir, Consumer<String> opener) {
        this.stage = stage;
        this.settings = settings;
        this.opener = opener;
        this.ipFilter = new IpFilterController(settings, configDir);
        stage.setTitle(AppInfo.NAME);

        BorderPane root = new BorderPane();
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());
        commands = buildCommands();
        root.setTop(buildMenuBar());
        table.setContextMenu(buildContextMenu());
        palette = new CommandPalette(host, () -> table.requestFocus());
        logWindow = new LogWindow(stage, log);
        settingsWindow = new SettingsWindow(settings);
        settingsWindow.setOnEngineSettingsChanged(this::applyEngineSettings);
        settingsWindow.setOnIpFilterChanged(this::refreshIpFilter, this::ipFilterStatusText);
        sync = new SettingsSync(settings, configDir, new SyncHost());
        settingsWindow.setSync(sync);
        sync.addListener(() -> {
            boolean problem = sync.state().phase() == SettingsSync.Phase.PROBLEM;
            syncBadge.setVisible(problem);
            syncBadge.setManaged(problem);
        });
        // Every save (a Settings change, a column drag) is a reason to sync soon; the service
        // ignores its own. Saves happen on the FX thread, but the listener does not assume it.
        settings.setOnSaved(() -> Platform.runLater(sync::markDirty));
        detail.setOnBanPeer(this::banPeer);
        log.installLogBridge(Platform::runLater);
        host.getChildren().add(root);

        Scene scene = new Scene(host, 960, 600);
        scene.getStylesheets()
                .add(MainWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.getAccelerators().put(chord(KeyCode.F), this::focusFilter);
        scene.getAccelerators().put(shiftChord(KeyCode.P), () -> palette.show(commands));
        scene.getAccelerators().put(shiftChord(KeyCode.L), logWindow::show);
        scene.getAccelerators().put(chord(KeyCode.V), this::addFromClipboard);
        scene.setOnDragOver(this::onDragOver);
        scene.setOnDragDropped(this::onDragDropped);
        // A magnet copied elsewhere is the commonest way a torrent arrives; say so when the window
        // comes back to the front, once per link, and leave the adding to a keystroke.
        stage.focusedProperty().addListener((o, was, now) -> {
            if (now) offerClipboard();
        });
        stage.setScene(scene);
        stage.setOnHidden(e -> {
            rememberDivider();
            stopPolling();
            updates.shutdown();
            sync.shutdown();
        });
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

    /** What to photograph: the open Add dialog if there is one, else the window. */
    public javafx.scene.Scene captureScene() {
        if (currentDialog != null && currentDialog.isShowing()) return currentDialog.scene();
        if (settingsWindow.isShowing()) return settingsWindow.scene();
        return stage.getScene();
    }

    /** Dismisses an open Add dialog, so the capture hook can exit outside its nested event loop. */
    public void closeDialogsForCapture() {
        if (currentDialog != null && currentDialog.isShowing()) currentDialog.cancel();
    }

    /** The log's lines, oldest first, for the capture hook: what a screenshot cannot show. */
    public List<String> eventLines() {
        return log.events().stream()
                .map(e -> LogWindow.levelLabel(e.level()) + " " + e.message())
                .toList();
    }

    /**
     * Drives the window from the capture hook, by name: {@code select-all}, {@code palette},
     * {@code palette:<query>}, or a command id. Prints what a screenshot cannot show.
     */
    public void runForCapture(String action) {
        if (action.equals("select-all")) {
            table.getSelectionModel().selectAll();
        } else if (action.equals("event-log")) {
            logWindow.show();
        } else if (action.equals("settings")) {
            settingsWindow.show(stage);
        } else if (action.startsWith("settings-page:")) {
            settingsWindow.show(stage);
            settingsWindow.selectPageForCapture(action.substring("settings-page:".length()));
        } else if (action.startsWith("settings:")) {
            settingsWindow.show(stage);
            settingsWindow.searchForCapture(action.substring("settings:".length()));
        } else if (action.equals("dialog-add")) {
            if (currentDialog != null && currentDialog.isShowing()) currentDialog.confirm();
        } else if (action.equals("select-first")) {
            table.getSelectionModel().clearAndSelect(0);
        } else if (action.startsWith("detail:")) {
            setDetailVisible(true);
            detail.selectTab(action.substring("detail:".length()));
        } else if (action.equals("palette")) {
            palette.show(commands);
        } else if (action.startsWith("palette:")) {
            palette.show(commands);
            palette.setQueryForCapture(action.substring("palette:".length()));
        } else {
            commands.stream()
                    .filter(c -> c.id().equals(action))
                    .findFirst()
                    .ifPresentOrElse(c -> c.run().run(), () -> System.err.println("capture: no command " + action));
        }
        System.out.println("capture: selected="
                + table.getSelectionModel().getSelectedItems().size());
        if (palette.isShowing()) System.out.println("capture: palette=" + palette.visibleCommands());
        if (logWindow.isShowing()) System.out.println("capture: log-window=showing");
        if (currentDialog != null && currentDialog.isShowing())
            System.out.println("capture: dialog=" + currentDialog.title());
        if (detailWanted) System.out.println("capture: detail=" + detail.summary());
        if (settingsWindow.isShowing())
            System.out.println("capture: settings=" + settingsWindow.visibleRowsForCapture());
    }

    /**
     * Any thread, before {@code engine.start()}: the start itself raises events (engine started,
     * listening on a port) that a listener attached afterwards would never hear. Events arrive on
     * the engine's alert thread; the log and the status bar are FX-owned, hence the hop.
     */
    public void listenTo(Engine engine) {
        engine.addListener(event -> Platform.runLater(() -> {
            log.add(event);
            setStatus(event.message());
        }));
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
        maybeCheckForUpdates();
        // The filter from disk first, so no peer slips in while a download runs; the schedule next.
        ipFilter.setEngine(engine);
        Thread.ofVirtual().name("ip-filter").start(() -> {
            ipFilter.applyStored(this::reportIpFilter);
            ipFilter.refresh(false, this::reportIpFilter);
        });
        poller.scheduleAtFixedRate(() -> ipFilter.refresh(false, this::reportIpFilter), 1, 1, TimeUnit.HOURS);
    }

    // ---- settings sync --------------------------------------------------------------------------

    /** What a sync round needs from the window. */
    private final class SyncHost implements SettingsSync.Host {
        @Override
        public void reportStatus(String text) {
            setStatus(text);
        }

        @Override
        public void settingsApplied() {
            // The file changed under every reader: re-apply what the settings drive.
            javafx.application.Application.setUserAgentStylesheet(
                    Theme.byId(settings.theme(), Theme.DEFAULT).stylesheet());
            applyEngineSettings();
            refreshIpFilter(false);
            if (settingsWindow.isShowing()) settingsWindow.reloadFromSettings();
        }

        @Override
        public boolean windowFocused() {
            return stage.isFocused();
        }
    }

    // ---- IP filter ------------------------------------------------------------------------------

    private void refreshIpFilter(boolean force) {
        Thread.ofVirtual().name("ip-filter").start(() -> ipFilter.refresh(force, this::reportIpFilter));
    }

    private void reportIpFilter(String text) {
        Platform.runLater(() -> {
            setStatus(text);
            settingsWindow.refreshIpFilterStatus();
        });
    }

    private String ipFilterStatusText() {
        IpFilterController.Status s = ipFilter.status();
        long blocked = engine == null ? 0 : engine.blockedPeers();
        if (s.loadedAt() == 0) return s.note().isEmpty() ? "Nothing loaded" : s.note();
        return s.ranges() + " ranges loaded at " + Format.dateTime(s.loadedAt() / 1000)
                + (s.skipped() > 0 ? ", " + s.skipped() + " lines skipped" : "")
                + (s.note().isEmpty() ? "" : " (" + s.note() + ")")
                + " · " + blocked + " peers refused this session";
    }

    /** From the Peers tab: the address (without its port) joins the manual bans, and the filter is on. */
    private void banPeer(String address) {
        String ip = address;
        if (ip.startsWith("[")) ip = ip.substring(1, ip.indexOf(']'));
        else if (ip.chars().filter(c -> c == ':').count() == 1) ip = ip.substring(0, ip.indexOf(':'));
        List<String> bans = new ArrayList<>(settings.ipFilterBans());
        if (!bans.contains(ip)) bans.add(ip);
        settings.setIpFilterBans(bans);
        settings.set(Settings.IPFILTER_ENABLED, "true");
        settings.saveQuietly();
        setStatus("Banned " + ip);
        refreshIpFilter(false);
    }

    /** A connection setting changed: the engine takes it live, on a worker. */
    private void applyEngineSettings() {
        if (engine == null) return;
        Engine e = engine;
        var current = settings.engineSettings();
        Thread.ofVirtual().name("engine-settings").start(() -> {
            try {
                e.applySettings(current);
            } catch (EngineException ex) {
                Platform.runLater(() -> setStatus(ex.getMessage()));
            }
        });
    }

    // ---- updates --------------------------------------------------------------------------------

    /** After startup, at most once a day, and only if the setting allows; says nothing on failure. */
    private void maybeCheckForUpdates() {
        if (!settings.updateCheck()) return;
        long now = System.currentTimeMillis();
        if (!UpdateCheck.isDue(settings.lastUpdateCheck(), now, UpdateCheck.DEFAULT_INTERVAL_MS)) return;
        settings.setLastUpdateCheck(now);
        settings.saveQuietly();
        updates.check(AppInfo.VERSION, outcome -> onUpdateOutcome(outcome, false));
    }

    /** The user asked: ignores the interval, the setting and the skipped version, and always reports. */
    private void checkForUpdatesNow() {
        setStatus("Checking for updates…");
        settings.setLastUpdateCheck(System.currentTimeMillis());
        settings.saveQuietly();
        updates.check(AppInfo.VERSION, outcome -> {
            onUpdateOutcome(outcome, true);
            if (outcome.error() != null) setStatus("Update check failed: " + outcome.error());
            else if (outcome.available())
                setStatus(AppInfo.NAME + " " + outcome.latest().version() + " is available — see the Help menu");
            else setStatus(AppInfo.NAME + " " + AppInfo.VERSION + " is up to date");
        });
    }

    /** FX thread. */
    private void onUpdateOutcome(UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.available()) return;
        if (!manual && outcome.latest().version().equals(settings.dismissedUpdate())) return;
        availableUpdate = outcome.latest();
        downloadUpdateItem.setText("Download " + AppInfo.NAME + " " + availableUpdate.version() + "…");
        skipUpdateItem.setText("Skip Version " + availableUpdate.version());
        downloadUpdateItem.setVisible(true);
        skipUpdateItem.setVisible(true);
        updateBadge.setText(availableUpdate.version() + " available");
        updateBadge.setVisible(true);
        updateBadge.setManaged(true);
        if (!manual) setStatus(AppInfo.NAME + " " + availableUpdate.version() + " is available — see the Help menu");
    }

    private void downloadUpdate() {
        if (availableUpdate == null) return;
        String url = availableUpdate.url().isEmpty() ? AppInfo.RELEASES_PAGE : availableUpdate.url();
        opener.accept(url);
    }

    private void skipUpdate() {
        if (availableUpdate == null) return;
        settings.setDismissedUpdate(availableUpdate.version());
        settings.saveQuietly();
        setStatus("Skipping " + AppInfo.NAME + " " + availableUpdate.version() + "; a manual check will still show it");
        availableUpdate = null;
        downloadUpdateItem.setVisible(false);
        skipUpdateItem.setVisible(false);
        updateBadge.setVisible(false);
        updateBadge.setManaged(false);
    }

    /** The newer version found, or empty; read by the capture hook. */
    public String availableUpdateVersion() {
        return availableUpdate == null ? "" : availableUpdate.version();
    }

    /** FX thread. The engine could not start; the window stays up so the message can be read. */
    public void engineFailed(EngineException failure) {
        setStatus(failure.getMessage());
    }

    /**
     * A {@code .torrent} path, a URL to one, or a {@code magnet:} link — from the command line, a
     * drop, the clipboard or a dialog — opens the Add dialog for it. Reading, fetching and decoding
     * happen on a worker; the dialog opens on the FX thread when there is something to show.
     */
    public void addInput(String input) {
        if (engine == null) {
            pendingInputs.add(input);
            return;
        }
        String text = input.trim();
        Engine e = engine;
        Thread.ofVirtual().name("engine-inspect").start(() -> {
            try {
                switch (Inputs.classify(text)) {
                    case MAGNET -> Platform.runLater(() -> openDialog(new AddRequest.Magnet(text), null, text));
                    case FILE -> {
                        Path file = Path.of(text);
                        if (!Files.isRegularFile(file)) throw new EngineException("No such file: " + text);
                        byte[] bytes = Files.readAllBytes(file);
                        TorrentPreview preview = e.inspect(bytes);
                        Platform.runLater(() -> openDialog(new AddRequest.TorrentFile(bytes, text), preview, text));
                    }
                    case URL -> {
                        byte[] bytes = new TorrentFetch().fetch(URI.create(text));
                        TorrentPreview preview = e.inspect(bytes);
                        Platform.runLater(() -> openDialog(new AddRequest.TorrentFile(bytes, text), preview, text));
                    }
                }
            } catch (EngineException | IOException | RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> setStatus(message));
            }
        });
    }

    /** FX thread. Shows the dialog and, if confirmed, adds on a worker. */
    private void openDialog(AddRequest.Source source, TorrentPreview preview, String origin) {
        AddDialog dialog = new AddDialog(
                stage,
                source,
                preview,
                origin,
                settings.downloadDir(),
                settings.addStartPaused(),
                settings.addSequential());
        currentDialog = dialog;
        Optional<AddRequest> request = dialog.showAndWait();
        currentDialog = null;
        table.requestFocus();
        if (request.isEmpty() || engine == null) return;
        Engine e = engine;
        Thread.ofVirtual().name("engine-add").start(() -> {
            String outcome;
            try {
                Torrent added = e.add(request.get());
                outcome = "Added " + added.name();
            } catch (EngineException ex) {
                outcome = ex.getMessage();
            }
            String text = outcome;
            Platform.runLater(() -> setStatus(text));
        });
    }

    private void addTorrentFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Torrent File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Torrent files", "*.torrent"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null) files.forEach(f -> addInput(f.getAbsolutePath()));
    }

    private void addMagnetLink() {
        javafx.scene.control.TextInputDialog prompt =
                new javafx.scene.control.TextInputDialog(clipboardCandidate().orElse(""));
        prompt.initOwner(stage);
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle("Add Magnet Link");
        prompt.setHeaderText("Magnet link, or a link to a .torrent");
        prompt.setContentText("Link:");
        prompt.getEditor().setPrefColumnCount(48);
        prompt.showAndWait().map(String::trim).filter(t -> !t.isEmpty()).ifPresent(this::addInput);
    }

    /** The clipboard's text if it is a magnet or a link to a .torrent. */
    private static Optional<String> clipboardCandidate() {
        try {
            String text = Clipboard.getSystemClipboard().getString();
            return Inputs.looksAddable(text) ? Optional.of(text.trim()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void offerClipboard() {
        clipboardCandidate().ifPresent(text -> {
            if (text.equals(lastClipboardOffer)) return;
            lastClipboardOffer = text;
            setStatus((Inputs.classify(text) == Inputs.Kind.MAGNET ? "Magnet link" : "Torrent link")
                    + " on the clipboard — press Ctrl+V to add it");
        });
    }

    private void addFromClipboard() {
        if (currentDialog != null) return;
        clipboardCandidate().ifPresentOrElse(this::addInput, () -> setStatus("Nothing to add on the clipboard"));
    }

    private void onDragOver(DragEvent e) {
        Dragboard board = e.getDragboard();
        if (board.hasFiles() || (board.hasString() && Inputs.looksAddable(board.getString()))) {
            e.acceptTransferModes(TransferMode.COPY);
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        Dragboard board = e.getDragboard();
        boolean taken = false;
        if (board.hasFiles()) {
            for (java.io.File file : board.getFiles()) {
                if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".torrent")) {
                    addInput(file.getAbsolutePath());
                    taken = true;
                }
            }
        } else if (board.hasString() && Inputs.looksAddable(board.getString())) {
            addInput(board.getString().trim());
            taken = true;
        }
        e.setDropCompleted(taken);
        e.consume();
    }

    private void poll() {
        List<Torrent> snapshot = engine.torrents();
        Engine.Rates current = engine.rates();
        // The detail pane costs a status with the pieces bitfield; only for one shown torrent.
        String id = detailWanted ? detailId : null;
        TorrentDetail detailSnapshot = null;
        if (id != null) {
            try {
                detailSnapshot = engine.detail(id);
            } catch (EngineException e) {
                detailSnapshot = null; // gone between the selection and the poll
            }
        }
        TorrentDetail shown = detailSnapshot;
        Platform.runLater(() -> {
            rows.apply(snapshot);
            rates.setText(ratesText(current));
            if (detailWanted) {
                if (shown != null && shown.general().id().equals(detailId)) detail.show(shown);
                else if (detailId == null) detail.clear();
            }
        });
    }

    private void setDetailVisible(boolean visible) {
        detailWanted = visible;
        detail.setVisible(visible);
        detail.setManaged(visible);
        if (visible) {
            if (!split.getItems().contains(detail)) split.getItems().add(detail);
            split.setDividerPositions(dividerFromSettings());
        } else {
            split.getItems().remove(detail);
        }
        settings.set(Settings.DETAIL_VISIBLE, String.valueOf(visible));
        settings.saveQuietly();
    }

    private double dividerFromSettings() {
        try {
            double d = Double.parseDouble(settings.get(Settings.DETAIL_DIVIDER, "0.62"));
            return d > 0.1 && d < 0.95 ? d : 0.62;
        } catch (NumberFormatException e) {
            return 0.62;
        }
    }

    private void rememberDivider() {
        if (split.getDividers().isEmpty() || !detailWanted) return;
        settings.set(
                Settings.DETAIL_DIVIDER, String.format(java.util.Locale.ROOT, "%.3f", split.getDividerPositions()[0]));
        settings.set(Settings.DETAIL_TAB, detail.activeTab());
        settings.saveQuietly();
    }

    private void stopPolling() {
        if (poller != null) poller.shutdownNow();
    }

    // ---- commands -------------------------------------------------------------------------------

    /** An engine action on one torrent. */
    private interface TorrentAction {
        void apply(Engine engine, Torrent torrent) throws EngineException;
    }

    private List<Torrent> selectedTorrents() {
        return table.getSelectionModel().getSelectedItems().stream()
                .map(TorrentRow::torrent)
                .toList();
    }

    /** Applies an action to every selected torrent on a worker; the status bar gets the tally. */
    private void onSelection(String verb, TorrentAction action) {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty() || engine == null) return;
        Engine e = engine;
        Thread.ofVirtual().name("engine-action").start(() -> {
            int done = 0;
            List<String> failures = new ArrayList<>();
            for (Torrent t : selected) {
                try {
                    action.apply(e, t);
                    done++;
                } catch (EngineException ex) {
                    failures.add(ex.getMessage());
                }
            }
            String text = Actions.outcome(verb, done, failures);
            Platform.runLater(() -> setStatus(text));
        });
    }

    private void togglePause() {
        if (Actions.shouldResume(selectedTorrents())) onSelection("Resumed", (e, t) -> e.resume(t.id()));
        else onSelection("Paused", (e, t) -> e.pause(t.id()));
    }

    private void toggleSequential() {
        boolean on = Actions.shouldEnableSequential(selectedTorrents());
        onSelection(on ? "Sequential on for" : "Sequential off for", (e, t) -> e.setSequential(t.id(), on));
    }

    private void remove(boolean deleteFiles) {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, Actions.removeQuestion(selected.size(), deleteFiles));
        confirm.setHeaderText(deleteFiles ? "Remove and delete files" : "Remove");
        confirm.initOwner(stage);
        Fonts.installUiFont(confirm.getDialogPane());
        ButtonType yes = new ButtonType(deleteFiles ? "Delete files" : "Remove", ButtonType.OK.getButtonData());
        confirm.getButtonTypes().setAll(yes, ButtonType.CANCEL);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != yes) return;
        onSelection("Removed", (e, t) -> e.remove(t.id(), deleteFiles));
    }

    private void copyToClipboard(String what, Function<Torrent, String> text) {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join("\n", selected.stream().map(text).toList()));
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("Copied " + what + (selected.size() == 1 ? "" : " of " + selected.size() + " torrents"));
    }

    private void copyMagnetLinks() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty() || engine == null) return;
        Engine e = engine;
        Thread.ofVirtual().name("engine-magnet").start(() -> {
            List<String> links = new ArrayList<>();
            for (Torrent t : selected) {
                try {
                    links.add(e.magnetLink(t.id()));
                } catch (EngineException ex) {
                    links.add("magnet:?xt=urn:btih:" + t.id());
                }
            }
            Platform.runLater(() -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(String.join("\n", links));
                Clipboard.getSystemClipboard().setContent(content);
                setStatus("Copied " + (links.size() == 1 ? "magnet link" : links.size() + " magnet links"));
            });
        });
    }

    private void openFolder() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        String path = selected.get(0).savePath();
        if (path == null || path.isBlank()) return;
        opener.accept(Path.of(path).toUri().toString());
    }

    private static KeyCombination chord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    private static KeyCombination shiftChord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private List<Command> buildCommands() {
        BooleanBinding selection = Bindings.isNotEmpty(table.getSelectionModel().getSelectedItems());
        BooleanBinding always = Bindings.createBooleanBinding(() -> true);
        return List.of(
                new Command("pause-resume", "Pause / Resume", null, "Space", selection, this::togglePause),
                new Command(
                        "force-resume",
                        "Force Resume",
                        shiftChord(KeyCode.U),
                        null,
                        selection,
                        () -> onSelection("Forced", (e, t) -> e.forceResume(t.id()))),
                new Command(
                        "recheck",
                        "Force Recheck",
                        shiftChord(KeyCode.R),
                        null,
                        selection,
                        () -> onSelection("Rechecking", (e, t) -> e.recheck(t.id()))),
                new Command(
                        "reannounce",
                        "Force Reannounce",
                        shiftChord(KeyCode.A),
                        null,
                        selection,
                        () -> onSelection("Reannounced", (e, t) -> e.reannounce(t.id()))),
                new Command(
                        "sequential",
                        "Sequential Download",
                        shiftChord(KeyCode.S),
                        null,
                        selection,
                        this::toggleSequential),
                new Command(
                        "copy-name",
                        "Copy Name",
                        chord(KeyCode.C),
                        null,
                        selection,
                        () -> copyToClipboard("name", Torrent::name)),
                new Command(
                        "copy-hash",
                        "Copy Info-Hash",
                        shiftChord(KeyCode.H),
                        null,
                        selection,
                        () -> copyToClipboard("info-hash", Torrent::id)),
                new Command(
                        "copy-magnet",
                        "Copy Magnet Link",
                        shiftChord(KeyCode.C),
                        null,
                        selection,
                        this::copyMagnetLinks),
                new Command(
                        "open-folder",
                        "Open Destination Folder",
                        shiftChord(KeyCode.O),
                        null,
                        selection,
                        this::openFolder),
                new Command("remove", "Remove…", null, "Delete", selection, () -> remove(false)),
                new Command("remove-data", "Remove with Data…", null, "Shift+Delete", selection, () -> remove(true)),
                new Command("check-updates", "Check for Updates", null, null, always, this::checkForUpdatesNow),
                new Command(
                        "settings", "Settings…", chord(KeyCode.COMMA), null, always, () -> settingsWindow.show(stage)),
                new Command(
                        "filter",
                        "Filter Torrents",
                        null,
                        chord(KeyCode.F).getDisplayText(),
                        always,
                        this::focusFilter),
                new Command(
                        "select-all",
                        "Select All",
                        null,
                        chord(KeyCode.A).getDisplayText(),
                        always,
                        () -> table.getSelectionModel().selectAll()));
    }

    private Command command(String id) {
        return commands.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Space and Delete: on the table only, never as scene accelerators a text field could fire. */
    private void onTableKey(KeyEvent e) {
        if (e.getCode() == KeyCode.SPACE) {
            command("pause-resume").run().run();
            e.consume();
        } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            command(e.isShiftDown() ? "remove-data" : "remove").run().run();
            e.consume();
        }
    }

    private MenuBar buildMenuBar() {
        MenuItem addTorrent = new MenuItem("Add Torrent File…");
        addTorrent.setAccelerator(chord(KeyCode.O));
        addTorrent.setOnAction(e -> addTorrentFile());

        MenuItem addMagnet = new MenuItem("Add Magnet Link…");
        addMagnet.setAccelerator(chord(KeyCode.M));
        addMagnet.setOnAction(e -> addMagnetLink());

        MenuItem addClipboard = new MenuItem("Add from Clipboard");
        addClipboard.setAccelerator(chord(KeyCode.V));
        addClipboard.setOnAction(e -> addFromClipboard());

        MenuItem quit = new MenuItem("Quit");
        quit.setAccelerator(chord(KeyCode.Q));
        quit.setOnAction(e -> stage.close());

        Menu file = new Menu("File");
        file.getItems()
                .addAll(
                        addTorrent,
                        addMagnet,
                        addClipboard,
                        new SeparatorMenuItem(),
                        command("settings").menuItem(),
                        new SeparatorMenuItem(),
                        quit);

        Menu torrent = new Menu("Torrent");
        torrent.getItems().addAll(torrentItems());

        MenuItem paletteItem = new MenuItem("Command Palette…");
        paletteItem.setAccelerator(shiftChord(KeyCode.P));
        paletteItem.setOnAction(e -> palette.show(commands));
        Menu view = new Menu("View");
        view.getItems()
                .addAll(command("filter").menuItem(), command("select-all").menuItem(), paletteItem);

        MenuItem about = new MenuItem("About " + AppInfo.NAME);
        about.setOnAction(e -> setStatus(AppInfo.NAME + " " + AppInfo.VERSION + " — " + AppInfo.COPYRIGHT));
        // The two update items exist from the start and show only once a release is known, so the
        // menu's shape does not change under a hand that has learned it.
        downloadUpdateItem.setVisible(false);
        downloadUpdateItem.setOnAction(e -> downloadUpdate());
        skipUpdateItem.setVisible(false);
        skipUpdateItem.setOnAction(e -> skipUpdate());
        Menu help = new Menu("Help");
        help.getItems()
                .addAll(
                        command("check-updates").menuItem(),
                        downloadUpdateItem,
                        skipUpdateItem,
                        new SeparatorMenuItem(),
                        about);

        MenuBar bar = new MenuBar(file, torrent, view, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    /** The Torrent menu and the context menu show the same items in the same order. */
    private List<MenuItem> torrentItems() {
        List<MenuItem> items = new ArrayList<>();
        for (String id : List.of("pause-resume", "force-resume", "recheck", "reannounce", "sequential")) {
            items.add(command(id).menuItem());
        }
        items.add(new SeparatorMenuItem());
        for (String id : List.of("copy-name", "copy-hash", "copy-magnet", "open-folder")) {
            items.add(command(id).menuItem());
        }
        items.add(new SeparatorMenuItem());
        for (String id : List.of("remove", "remove-data")) items.add(command(id).menuItem());
        return items;
    }

    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(torrentItems());
        return menu;
    }

    // ---- layout ---------------------------------------------------------------------------------

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
        split = new SplitPane(centre);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox.setVgrow(split, Priority.ALWAYS);

        // The pane follows the selection: one torrent shows it, anything else clears it.
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<TorrentRow>)
                c -> {
                    var selected = table.getSelectionModel().getSelectedItems();
                    detailId = selected.size() == 1 ? selected.get(0).id() : null;
                    if (detailId == null && detailWanted) detail.clear();
                });
        detail.selectTab(settings.get(Settings.DETAIL_TAB, "general"));
        detail.setOnFilePriority((index, priority) -> {
            String id = detailId;
            if (id != null) onTorrent("Priority set for", id, (e, t) -> e.setFilePriority(t, index, priority));
        });
        detail.setOnOpenFile(row -> {
            String id = detailId;
            Torrent t = id == null
                    ? null
                    : rows.get(id) == null ? null : rows.get(id).torrent();
            if (t != null)
                opener.accept(Path.of(t.savePath()).resolve(row.path()).toUri().toString());
        });
        detail.setOnAddTrackers(urls -> {
            String id = detailId;
            if (id != null) onTorrent("Trackers added to", id, (e, t) -> e.addTrackers(t, urls));
        });
        detail.setOnRemoveTracker(url -> {
            String id = detailId;
            if (id != null) onTorrent("Tracker removed from", id, (e, t) -> e.removeTracker(t, url));
        });
        detail.setOnReannounce(() -> {
            String id = detailId;
            if (id != null) onTorrent("Reannounced", id, (e, t) -> e.reannounce(t));
        });
        Platform.runLater(() -> {
            setDetailVisible(Boolean.parseBoolean(settings.get(Settings.DETAIL_VISIBLE, "true")));
            split.getDividers().forEach(d -> d.positionProperty().addListener(o -> rememberDivider()));
        });
        return new VBox(buildFilterBar(), split);
    }

    /** An engine action on one torrent by id. */
    private interface IdAction {
        void apply(Engine engine, String id) throws EngineException;
    }

    private void onTorrent(String verb, String id, IdAction action) {
        if (engine == null) return;
        Engine e = engine;
        TorrentRow row = rows.get(id);
        String name = row == null ? id : row.torrent().name();
        Thread.ofVirtual().name("engine-detail-action").start(() -> {
            String text;
            try {
                action.apply(e, id);
                text = verb + " " + name;
            } catch (EngineException ex) {
                text = ex.getMessage();
            }
            String outcome = text;
            Platform.runLater(() -> setStatus(outcome));
        });
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
        table.setOnKeyPressed(this::onTableKey);

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
                        column("state", "State", 130, true, Format::stateLabel, s -> s),
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
        Label hint = new Label("Press Ctrl+O to add a .torrent file, Ctrl+M for a magnet link, or drop either here.");
        hint.getStyleClass().add("empty-hint");
        VBox box = new VBox(8, title, hint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        return box;
    }

    private HBox buildStatusBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // "3 errors", shown only while there are some; a click opens the log. Cleared with it.
        errorBadge.getStyleClass().add("error-badge");
        errorBadge
                .textProperty()
                .bind(Bindings.createStringBinding(
                        () -> log.errorsProperty().get() == 1
                                ? "1 error"
                                : log.errorsProperty().get() + " errors",
                        log.errorsProperty()));
        errorBadge.visibleProperty().bind(log.errorsProperty().greaterThan(0));
        errorBadge.managedProperty().bind(errorBadge.visibleProperty());
        errorBadge.setOnMouseClicked(e -> logWindow.show());
        // "0.9.0 available", persistent where the status text is not; a click opens the download.
        updateBadge.getStyleClass().add("update-badge");
        updateBadge.setVisible(false);
        updateBadge.setManaged(false);
        updateBadge.setOnMouseClicked(e -> downloadUpdate());
        // "Sync ⚠" while the last sync failed; a click opens the Sync page. Cleared by a good one.
        syncBadge.getStyleClass().add("error-badge");
        syncBadge.setVisible(false);
        syncBadge.setManaged(false);
        syncBadge.setOnMouseClicked(e -> {
            settingsWindow.show(stage);
            settingsWindow.selectPageForCapture("Sync");
        });
        HBox bar = new HBox(12, status, spacer, updateBadge, syncBadge, errorBadge, rates);
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
