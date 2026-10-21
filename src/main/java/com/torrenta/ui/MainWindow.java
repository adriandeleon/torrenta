package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
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
import com.torrenta.automation.Automation;
import com.torrenta.automation.Notifiers;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Inputs;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.WatchFolders;
import com.torrenta.net.TorrentFetch;
import com.torrenta.search.SearchResult;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.store.LabelStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;
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
    private ToolBar toolbar;
    private final StackPane host = new StackPane();
    private final CommandPalette palette;
    private final EventLog log = new EventLog();
    private final LogWindow logWindow;
    private final Label errorBadge = new Label();
    private TableView<TorrentRow> table;
    private List<Command> commands = List.of();
    private AddDialog currentDialog;
    private LimitsDialog limitsDialog;
    private TagsDialog tagsDialog;
    /** A choice or text prompt of the category commands, while one is up. */
    private javafx.scene.control.Dialog<?> promptDialog;

    private final LabelStore labels;
    private final WatchFolderStore watchStore;
    private final SearchProviderStore searchStore;
    private WatchFolders watcher;
    /** Runs the person's commands and shows notifications; made with the engine in {@link #listenTo}. */
    private volatile Automation automation;

    private Sidebar sidebar;
    private SidebarSelection sidebarSelection = SidebarSelection.ALL;
    private String lastClipboardOffer = "";
    private final DetailPane detail = new DetailPane();
    private SearchPane searchPane;
    /** The transfers (sidebar, filter, list, detail) and the Search tab share the centre. */
    private javafx.scene.Node transfersView;

    private final UpdateService updates = new UpdateService();
    private final SettingsWindow settingsWindow;
    private final IpFilterController ipFilter;
    private final MenuItem downloadUpdateItem = new MenuItem();
    private final MenuItem skipUpdateItem = new MenuItem();
    private final Label updateBadge = new Label();
    private final Label altSpeedToggle = new Label();
    private ReleaseInfo availableUpdate;
    private SettingsSync sync;
    private final Label syncBadge = new Label("Sync ⚠");
    private Alert aboutDialog;
    private SplitPane split;
    private volatile String detailId;
    private volatile boolean detailWanted;
    /** The global share limit as the poll thread applies it; refreshed with the engine settings. */
    private volatile ShareLimit globalShareLimit = ShareLimit.NONE;
    /** Per-torrent share limits as of the last poll, for the status sentences ("ratio 1.2 of 2"). */
    private volatile java.util.Map<String, ShareLimit> shareOverrides = java.util.Map.of();

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
        this.labels = new LabelStore(configDir);
        labels.load();
        this.watchStore = new WatchFolderStore(configDir);
        watchStore.load();
        this.searchStore = new SearchProviderStore(configDir);
        searchStore.load();
        stage.setTitle(AppInfo.NAME);

        BorderPane root = new BorderPane();
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());
        commands = buildCommands();
        // The toolbar first: the View menu's check item binds to its visibility.
        ToolBar bar = buildToolbar();
        root.setTop(new VBox(buildMenuBar(), bar));
        table.setContextMenu(buildContextMenu());
        palette =
                new CommandPalette(host, this::focusList, shiftChord(KeyCode.P).getDisplayText());
        logWindow = new LogWindow(stage, log);
        settingsWindow = new SettingsWindow(settings);
        settingsWindow.setOnEngineSettingsChanged(this::applyEngineSettings);
        settingsWindow.setLabels(labels, new LabelsHost());
        settingsWindow.setOnIpFilterChanged(this::refreshIpFilter, this::ipFilterStatusText);
        settingsWindow.setOnAppearanceChanged(this::applyStateColors);
        applyStateColors();
        settingsWindow.setWatchFolders(watchStore, new WatchHost());
        settingsWindow.setSearchProviders(searchStore, searchPane::reloadProviders);
        settingsWindow.setOnTestNotification(() -> {
            Automation a = automation;
            if (a != null) a.testNotification();
        });
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
            stopWatching();
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

    /** What to photograph: the open Add dialog if there is one, else a window of ours, else the window. */
    public javafx.scene.Scene captureScene() {
        if (currentDialog != null && currentDialog.isShowing()) return currentDialog.scene();
        if (limitsDialog != null && limitsDialog.isShowing()) return limitsDialog.scene();
        if (tagsDialog != null && tagsDialog.isShowing()) return tagsDialog.scene();
        if (promptDialog != null && promptDialog.isShowing())
            return promptDialog.getDialogPane().getScene();
        if (aboutDialog != null && aboutDialog.isShowing())
            return aboutDialog.getDialogPane().getScene();
        if (settingsWindow.dialogScene() != null) return settingsWindow.dialogScene();
        if (settingsWindow.isShowing()) return settingsWindow.scene();
        if (logWindow.isShowing()) return logWindow.scene();
        return stage.getScene();
    }

    /** Dismisses an open Add dialog, so the capture hook can exit outside its nested event loop. */
    public void closeDialogsForCapture() {
        if (currentDialog != null && currentDialog.isShowing()) currentDialog.cancel();
        if (limitsDialog != null && limitsDialog.isShowing()) limitsDialog.cancel();
        if (tagsDialog != null && tagsDialog.isShowing()) tagsDialog.cancel();
        if (promptDialog != null && promptDialog.isShowing()) promptDialog.close();
        if (aboutDialog != null && aboutDialog.isShowing()) aboutDialog.close();
        settingsWindow.closeDialogsForCapture();
    }

    /** The log's lines, oldest first, for the capture hook: what a screenshot cannot show. */
    public List<String> eventLines() {
        return log.events().stream()
                .map(e -> LogWindow.levelLabel(e.level()) + " " + e.message())
                .toList();
    }

    /**
     * Drives the window from the capture hook, by name: {@code close-window} (the quit path a
     * person takes, where the hook itself exits through Platform.exit), {@code select-all}, {@code palette},
     * {@code palette:<query>}, {@code sidebar:<filter>} (as {@link SidebarFilter#parse} reads it),
     * {@code sidebar-keys:<KEY+KEY+…>} (focus the sidebar and press them),
     * {@code dialog-category:<name>} on an open Add dialog, or a command id. Prints what a
     * screenshot cannot show.
     */
    public void runForCapture(String action) {
        if (action.equals("close-window")) {
            stage.close();
        } else if (action.equals("select-all")) {
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
        } else if (action.equals("settings-watch-add")) {
            settingsWindow.show(stage);
            settingsWindow.openWatchDialogForCapture();
        } else if (action.startsWith("settings-search-add")) {
            // settings-search-add, settings-search-add:<url>, settings-search-test:<url> (Test pressed)
            settingsWindow.show(stage);
            int colon = action.indexOf(':');
            settingsWindow.openSearchDialogForCapture(colon < 0 ? "" : action.substring(colon + 1), false);
        } else if (action.startsWith("settings-search-dialog-test:")) {
            settingsWindow.show(stage);
            settingsWindow.openSearchDialogForCapture(action.substring("settings-search-dialog-test:".length()), true);
        } else if (action.equals("settings-search-test")) {
            settingsWindow.show(stage);
            settingsWindow.testSearchProviderForCapture();
        } else if (action.equals("search")) {
            showSearch();
        } else if (action.startsWith("search:")) {
            showSearch();
            searchPane.setQuery(action.substring("search:".length()));
            searchPane.runSearch();
        } else if (action.startsWith("search-keys:")) {
            // Keys pressed in the results table (DOWN+ENTER): the keyboard path to the Add dialog.
            showSearch();
            searchPane.pressForCapture(java.util.Arrays.stream(
                            action.substring("search-keys:".length()).split("\\+"))
                    .map(key -> KeyCode.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT)))
                    .toList());
        } else if (action.equals("dialog-add")) {
            if (currentDialog != null && currentDialog.isShowing()) currentDialog.confirm();
        } else if (action.startsWith("dialog-press:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.pressForCapture(action.substring("dialog-press:".length()));
        } else if (action.startsWith("dialog-key:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.keyForCapture(KeyCode.valueOf(action.substring("dialog-key:".length())));
        } else if (action.equals("choose-files")) {
            selectedTorrents().stream().findFirst().ifPresent(t -> chooseFiles(t.id()));
        } else if (action.startsWith("dialog-category:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.selectCategoryForCapture(action.substring("dialog-category:".length()));
        } else if (action.startsWith("sidebar:")) {
            chooseFilter(SidebarFilter.parse(action.substring("sidebar:".length())));
        } else if (action.startsWith("sidebar-keys:")) {
            // Focus the sidebar and press these keys in turn (DOWN+DOWN+SPACE; the comma is the hook's own separator):
            // the keyboard path.
            focusSidebar();
            for (String key : action.substring("sidebar-keys:".length()).split("\\+"))
                sidebar.pressForCapture(
                        javafx.scene.input.KeyCode.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT)));
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
        if (currentDialog != null && currentDialog.isShowing()) {
            System.out.println("capture: dialog=" + currentDialog.title());
            System.out.println("capture: add-dialog=" + currentDialog.summaryForCapture());
            System.out.println("capture: dialog-hint=" + currentDialog.hint());
        }
        if (limitsDialog != null && limitsDialog.isShowing())
            System.out.println("capture: dialog=" + limitsDialog.title());
        if (tagsDialog != null && tagsDialog.isShowing()) System.out.println("capture: dialog=" + tagsDialog.title());
        if (promptDialog != null && promptDialog.isShowing())
            System.out.println("capture: dialog=" + promptDialog.getTitle());
        if (sidebar.isVisible()) System.out.println("capture: sidebar=" + sidebar.summary());
        System.out.println("capture: filter=" + sidebarSelection.describe() + " shown=" + filtered.size());
        System.out.println("capture: focus-sidebar=" + sidebar.hasFocus() + " cursor=" + sidebar.cursorForCapture());
        for (TorrentRow row : table.getItems()) {
            Torrent t = row.torrent();
            StatusLine line = StatusLine.of(t, row.quietSeconds(), shareLimitFor(t));
            System.out.println("capture: row=" + line.state().glyph() + " " + t.name() + " | " + line.text());
        }
        if (detailWanted) System.out.println("capture: detail=" + detail.summary());
        if (settingsWindow.isShowing())
            System.out.println("capture: settings=" + settingsWindow.visibleRowsForCapture());
        if (!settingsWindow.dialogTitle().isEmpty())
            System.out.println("capture: dialog=" + settingsWindow.dialogTitle());
        if (!settingsWindow.searchStatus().isEmpty())
            System.out.println("capture: search=" + settingsWindow.searchStatus());
        if (watcher != null) System.out.println("capture: watch=" + watchStatusText());
        System.out.println("capture: search-tab=" + (searchShowing() ? "showing" : "hidden"));
        if (searchShowing()) {
            System.out.println("capture: search-query=" + searchPane.queryText());
            System.out.println("capture: search-chips=" + searchPane.chipsForCapture());
            System.out.println("capture: search-status=" + searchPane.statusText()
                    + (searchPane.searching() ? " (searching)" : ""));
            for (String row : searchPane.rowsForCapture()) System.out.println("capture: search-row=" + row);
        }
    }

    /**
     * Any thread, before {@code engine.start()}: the start itself raises events (engine started,
     * listening on a port) that a listener attached afterwards would never hear. Events arrive on
     * the engine's alert thread; the log and the status bar are FX-owned, hence the hop.
     */
    public void listenTo(Engine engine) {
        automation = new Automation(engine, settings::automation, Notifiers.forPlatform(), this::reportFromWorker);
        engine.addListener(event -> {
            // The automation decides on the alert thread and does its work on threads of its own.
            automation.handle(event);
            Platform.runLater(() -> {
                log.add(event);
                setStatus(event.message());
            });
        });
    }

    /**
     * Any thread: an event from a worker of the window's own (the automation) into the log. A
     * command's output lines go to the log only; the status bar keeps the line that says it ran.
     */
    private void reportFromWorker(EngineEvent event) {
        Platform.runLater(() -> {
            log.add(event);
            if (!event.message().startsWith(Automation.OUTPUT_PREFIX)) setStatus(event.message());
        });
    }

    /** FX thread. The engine has started; flush what was queued and begin polling. */
    public void engineReady(Engine engine) {
        this.engine = engine;
        this.engineDescription = engine.description();
        globalShareLimit = settings.shareLimit();
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
        // The schedule is checked every minute; a change of state applies the other pair.
        poller.scheduleAtFixedRate(this::refreshLimits, 0, 60, TimeUnit.SECONDS);
        watcher = new WatchFolders(engine, new WatcherHost());
        restartWatching();
    }

    // ---- watch folders --------------------------------------------------------------------------

    /** What the watcher needs; called on its thread, so everything FX-owned hops. */
    private final class WatcherHost implements WatchFolders.Host {
        @Override
        public Path defaultDownloadDir() {
            return settings.downloadDir();
        }

        @Override
        public Optional<com.torrenta.engine.Category> category(String name) {
            return labels.category(name);
        }

        @Override
        public AddRequest.Storage storage(Path destination) {
            return settings.storage(destination);
        }

        @Override
        public void report(EngineEvent event) {
            Platform.runLater(() -> {
                log.add(event);
                setStatus(event.message());
            });
        }

        @Override
        public void statusChanged() {
            Platform.runLater(settingsWindow::refreshWatchStatus);
        }
    }

    /** What the Settings page needs. */
    private final class WatchHost implements SettingsWindow.WatchHost {
        @Override
        public void watchFoldersChanged() {
            restartWatching();
        }

        @Override
        public String watchStatus() {
            return watchStatusText();
        }
    }

    private String watchStatusText() {
        WatchFolders w = watcher;
        return WatchFolders.statusText(
                watchStore.entries().size(), w == null ? List.of() : w.missing(), w != null && w.isRunning());
    }

    /** Any thread. (Re)starts the watcher on the stored list; a no-op until the engine is up. */
    private void restartWatching() {
        WatchFolders w = watcher;
        if (w == null) return;
        List<com.torrenta.engine.WatchFolder> entries = watchStore.entries();
        // start() waits for a poll in progress, which may be inside the engine: not on the FX thread.
        Thread.ofVirtual().name("watch-folders-start").start(() -> {
            w.start(entries);
            Platform.runLater(settingsWindow::refreshWatchStatus);
        });
    }

    private void stopWatching() {
        WatchFolders w = watcher;
        if (w != null) Thread.ofVirtual().name("watch-folders-stop").start(w::stop);
    }

    // ---- speed ----------------------------------------------------------------------------------

    private void refreshLimits() {
        Engine e = engine;
        if (e == null) return;
        try {
            boolean alternative = e.refreshLimits(java.time.LocalDateTime.now());
            Platform.runLater(() -> {
                altSpeedToggle.setText("Alt speed: " + (alternative ? "on" : "off"));
                altSpeedToggle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), alternative);
            });
        } catch (EngineException ex) {
            Platform.runLater(() -> setStatus(ex.getMessage()));
        }
    }

    private void toggleAlternativeSpeed() {
        boolean on = !settings.engineSettings().speed().alternativeOn();
        settings.set(Settings.SPEED_ALT_ON, String.valueOf(on));
        settings.saveQuietly();
        setStatus(on ? "Alternative speed limits on" : "Alternative speed limits off");
        applyEngineSettings();
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
            applyStateColors();
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
        globalShareLimit = current.queue().share();
        Thread.ofVirtual().name("engine-settings").start(() -> {
            try {
                e.applySettings(current);
                refreshLimits();
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

    /** Help → About: one panel at a time, brought back if it is already open. */
    private void showAbout() {
        if (aboutDialog != null && aboutDialog.isShowing()) {
            aboutDialog.getDialogPane().getScene().getWindow().requestFocus();
            return;
        }
        aboutDialog = AboutDialog.create(stage, settings.file(), opener, availableUpdate);
        aboutDialog.show();
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
        addInput(input, null);
    }

    /** {@code via} is for the dialog's header; null reads it off the input itself. */
    private void addInput(String input, AddDialog.Via via) {
        if (engine == null) {
            pendingInputs.add(input);
            return;
        }
        String text = input.trim();
        Engine e = engine;
        boolean withoutAsking = settings.addWithoutAsking();
        AddDialog.Defaults defaults = AddDialog.Defaults.from(settings);
        Thread.ofVirtual().name("engine-inspect").start(() -> {
            try {
                Inputs.Kind kind = Inputs.classify(text);
                AddDialog.Via shownVia = via != null
                        ? via
                        : switch (kind) {
                            case MAGNET -> AddDialog.Via.MAGNET;
                            case FILE -> AddDialog.Via.FILE;
                            case URL -> AddDialog.Via.URL;
                        };
                AddRequest.Source source =
                        switch (kind) {
                            case MAGNET -> new AddRequest.Magnet(text);
                            case FILE -> {
                                Path file = Path.of(text);
                                if (!Files.isRegularFile(file)) throw new EngineException("No such file: " + text);
                                yield new AddRequest.TorrentFile(Files.readAllBytes(file), text);
                            }
                            case URL -> new AddRequest.TorrentFile(new TorrentFetch().fetch(URI.create(text)), text);
                        };
                addSource(e, source, shownVia, withoutAsking, defaults);
            } catch (EngineException | IOException | RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> setStatus(message));
            }
        });
    }

    /**
     * Worker. A source that is ready: added at once when the setting says so, else inspected and
     * handed to the Add dialog on the FX thread.
     */
    private void addSource(
            Engine e, AddRequest.Source source, AddDialog.Via via, boolean withoutAsking, AddDialog.Defaults defaults)
            throws EngineException {
        if (withoutAsking) {
            Torrent added = e.add(AddDialog.withoutAsking(source, defaults));
            Platform.runLater(() -> setStatus("Added " + added.name()));
            return;
        }
        TorrentPreview preview = source instanceof AddRequest.TorrentFile file ? e.inspect(file.bytes()) : null;
        Platform.runLater(() -> openDialog(source, preview, via));
    }

    /**
     * FX thread. A search result into the Add dialog: a magnet as it is, a {@code .torrent} link
     * fetched first, the status bar saying so meanwhile.
     */
    private void addFromSearch(SearchResult result) {
        Engine e = engine;
        if (e == null) {
            setStatus("The engine is still starting");
            return;
        }
        String fetching = SearchAdd.fetchingText(result);
        if (!fetching.isEmpty()) setStatus(fetching);
        boolean withoutAsking = settings.addWithoutAsking();
        AddDialog.Defaults defaults = AddDialog.Defaults.from(settings);
        Thread.ofVirtual().name("search-add").start(() -> {
            try {
                AddRequest.Source source = SearchAdd.sourceOf(result, new TorrentFetch()::fetch);
                if (!fetching.isEmpty()) Platform.runLater(() -> setStatus("Fetched " + result.name()));
                addSource(e, source, AddDialog.Via.SEARCH, withoutAsking, defaults);
            } catch (EngineException | IOException | RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> setStatus(message));
            }
        });
    }

    /**
     * A magnet's metadata being fetched for the open dialog. The fetch starts on a worker and may
     * land after the dialog has closed, so whichever comes second cancels it; both happen off the
     * FX thread, since cancelling is an engine call.
     */
    private static final class FetchSlot {
        private Engine.MetadataFetch fetch;
        private boolean closed;

        /** False, and the fetch cancelled, if the dialog closed first. */
        synchronized boolean set(Engine.MetadataFetch f) {
            if (closed) {
                f.cancel();
                return false;
            }
            fetch = f;
            return true;
        }

        synchronized Engine.MetadataFetch get() {
            return closed ? null : fetch;
        }

        /** Must come before the same magnet is added: the fetch holds its place in the session. */
        synchronized void close() {
            closed = true;
            if (fetch != null) fetch.cancel();
        }
    }

    /** The open dialog's fetch, for the poll thread to report peers to; null when there is none. */
    private volatile FetchWatch fetchWatch;

    private record FetchWatch(FetchSlot slot, AddDialog dialog) {}

    /** FX thread. Dialogs that arrived while one was open, each opened when the one before closes. */
    private final List<Runnable> queuedDialogs = new ArrayList<>();

    /** FX thread. Shows the dialog and, if confirmed, adds on a worker. */
    private void openDialog(AddRequest.Source source, TorrentPreview preview, AddDialog.Via via) {
        if (currentDialog != null) {
            // One dialog at a time: several files chosen at once open one after the other.
            queuedDialogs.add(() -> openDialog(source, preview, via));
            return;
        }
        AddDialog dialog =
                new AddDialog(stage, source, preview, via, AddDialog.Defaults.from(settings), labels.categories());
        FetchSlot slot = new FetchSlot();
        if (preview == null && source instanceof AddRequest.Magnet magnet) startFetch(magnet, slot, dialog);
        currentDialog = dialog;
        Optional<AddDialog.Choice> choice = dialog.showAndWait();
        currentDialog = null;
        fetchWatch = null;
        focusList();
        if (!queuedDialogs.isEmpty()) Platform.runLater(queuedDialogs.remove(0));
        Engine e = engine;
        if (choice.isEmpty() || e == null) {
            Thread.ofVirtual().name("engine-fetch-cancel").start(slot::close);
            return;
        }
        AddDialog.Choice c = choice.get();
        AddRequest request = c.request();
        rememberLabels(request.labels());
        if (c.withoutAsking()) rememberAddDefaults(request);
        Thread.ofVirtual().name("engine-add").start(() -> {
            slot.close();
            String outcome;
            try {
                Torrent added = e.add(request);
                outcome = "Added " + added.name();
                if (c.firstAndLastFirst()) {
                    TorrentLimits limits = e.limits(added.id());
                    e.setLimits(
                            added.id(),
                            new TorrentLimits(
                                    limits.downloadLimit(),
                                    limits.uploadLimit(),
                                    limits.maxConnections(),
                                    limits.maxUploads(),
                                    true));
                }
                if (c.filesLater()) {
                    outcome += " · its files can be chosen when the file list arrives";
                    String id = added.id();
                    Platform.runLater(() -> {
                        settings.setFilesToPick(id, true);
                        settings.saveQuietly();
                    });
                }
            } catch (EngineException ex) {
                outcome = ex.getMessage();
            }
            String text = outcome;
            Platform.runLater(() -> setStatus(text));
        });
    }

    /** Worker: asks the engine for the magnet's metadata and hands the dialog the files. */
    private void startFetch(AddRequest.Magnet magnet, FetchSlot slot, AddDialog dialog) {
        Engine e = engine;
        Thread.ofVirtual().name("engine-fetch").start(() -> {
            Engine.MetadataFetch fetch;
            try {
                fetch = e.fetchMetadata(magnet.uri());
            } catch (EngineException ex) {
                Platform.runLater(() -> dialog.fetchFailed(ex.getMessage()));
                return;
            }
            if (!slot.set(fetch)) return;
            fetchWatch = new FetchWatch(slot, dialog);
            fetch.torrent().whenComplete((bytes, failure) -> {
                if (failure != null) {
                    if (!(failure instanceof java.util.concurrent.CancellationException)) {
                        Platform.runLater(() -> dialog.fetchFailed(failure.getMessage()));
                    }
                    return;
                }
                // The alert thread: inspecting is a native call, so it moves to a worker.
                Thread.ofVirtual().name("engine-inspect").start(() -> {
                    try {
                        TorrentPreview preview = e.inspect(bytes);
                        AddRequest.TorrentFile file = new AddRequest.TorrentFile(bytes, magnet.uri());
                        Platform.runLater(() -> dialog.showFiles(file, preview));
                    } catch (EngineException ex) {
                        Platform.runLater(() -> dialog.fetchFailed(ex.getMessage()));
                    }
                });
            });
        });
    }

    /** "Next time, add with these settings without asking": the dialog's choices become the defaults. */
    private void rememberAddDefaults(AddRequest request) {
        settings.setAddWithoutAsking(true);
        settings.setAddStartPaused(request.startPaused());
        settings.setAddSequential(request.sequential());
        Optional<Path> categoryFolder =
                labels.category(request.labels().category()).flatMap(Category::path);
        AddDialog.folderToRemember(request.savePath(), settings.downloadDir(), categoryFolder)
                .ifPresent(settings::downloadDir);
        settings.saveQuietly();
        log.add(EngineEvent.info(
                null, null, "Torrents are added without asking from now on; Settings → Adding turns it off"));
    }

    /**
     * Opens the dialog's file tree for a torrent already in the list, to choose its files and start
     * it: the hook for a torrent added with "Add now, pick files when ready", which is marked with
     * {@link Settings#filesToPick} until this has been done. Any thread.
     */
    public void chooseFiles(String id) {
        Engine e = engine;
        if (e == null) return;
        Thread.ofVirtual().name("engine-detail").start(() -> {
            try {
                TorrentDetail d = e.detail(id);
                if (d.files().isEmpty()) {
                    Platform.runLater(() -> setStatus("The file list has not arrived yet"));
                    return;
                }
                Torrent t = e.torrents().stream()
                        .filter(x -> x.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new EngineException("No such torrent: " + id));
                List<TorrentPreview.FileEntry> files = d.files().stream()
                        .map(f -> new TorrentPreview.FileEntry(f.index(), f.path(), f.size()))
                        .toList();
                TorrentPreview preview = new TorrentPreview(
                        id,
                        t.name(),
                        files.stream().mapToLong(TorrentPreview.FileEntry::size).sum(),
                        files,
                        d.general().isPrivate(),
                        d.trackers().stream().map(TorrentDetail.Tracker::url).toList(),
                        "",
                        "",
                        0,
                        false,
                        false);
                Map<Integer, com.torrenta.engine.Priority> own = new java.util.HashMap<>();
                d.files().forEach(f -> own.put(f.index(), f.priority()));
                Platform.runLater(() -> openChooser(id, preview, own));
            } catch (EngineException ex) {
                Platform.runLater(() -> setStatus(ex.getMessage()));
            }
        });
    }

    /** FX thread. The chooser for {@link #chooseFiles}; applies the priorities and starts it. */
    private void openChooser(String id, TorrentPreview preview, Map<Integer, com.torrenta.engine.Priority> own) {
        if (currentDialog != null) {
            queuedDialogs.add(() -> openChooser(id, preview, own));
            return;
        }
        AddDialog dialog = AddDialog.chooseFiles(stage, preview, own);
        currentDialog = dialog;
        Optional<AddDialog.Choice> choice = dialog.showAndWait();
        currentDialog = null;
        focusList();
        if (!queuedDialogs.isEmpty()) Platform.runLater(queuedDialogs.remove(0));
        Engine e = engine;
        if (choice.isEmpty() || e == null) return;
        AddRequest request = choice.get().request();
        settings.setFilesToPick(id, false);
        settings.saveQuietly();
        Thread.ofVirtual().name("engine-choose").start(() -> {
            String outcome;
            try {
                for (TorrentPreview.FileEntry f : preview.files()) {
                    com.torrenta.engine.Priority p =
                            request.filePriorities().getOrDefault(f.index(), com.torrenta.engine.Priority.NORMAL);
                    if (p != own.get(f.index())) e.setFilePriority(id, f.index(), p);
                }
                if (!request.startPaused()) e.resume(id);
                outcome = (request.startPaused() ? "Files chosen for " : "Started ") + preview.name();
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
        clipboardCandidate()
                .ifPresentOrElse(
                        text -> addInput(text, AddDialog.Via.CLIPBOARD),
                        () -> setStatus("Nothing to add on the clipboard"));
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
        FetchWatch watch = fetchWatch;
        Engine.MetadataFetch fetch = watch == null ? null : watch.slot().get();
        if (fetch != null) {
            int peers = fetch.peers();
            Platform.runLater(() -> watch.dialog().showFetching(peers));
        }
        List<Torrent> snapshot = engine.torrents();
        Engine.Rates current = engine.rates();
        enforceShareLimits(snapshot);
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
            rows.apply(snapshot, System.currentTimeMillis() / 1000);
            refreshSidebar(snapshot);
            rates.setText(ratesText(current));
            if (detailWanted) {
                if (shown != null && shown.general().id().equals(detailId)) detail.show(shown);
                else if (detailId == null) detail.clear();
            }
        });
    }

    /**
     * Poll thread. libtorrent ranks seeds by the share limits but pauses nothing by them, so the
     * rule is applied here, once a second: a finished torrent past its own or the global limit is
     * paused or removed, and the log says so. A paused one no longer qualifies, so it is done once;
     * resuming it by hand seeds it until the next tick, raising the limit seeds it for good.
     */
    private void enforceShareLimits(List<Torrent> snapshot) {
        ShareLimit global = globalShareLimit;
        java.util.Map<String, ShareLimit> overrides = settings.shareOverrides();
        shareOverrides = overrides;
        if (!global.limited() && overrides.isEmpty()) return;
        for (Torrent t : snapshot) {
            Optional<ShareLimit.Action> due = ShareLimit.decide(t, global, overrides.get(t.id()));
            if (due.isEmpty()) continue;
            String text;
            try {
                switch (due.get()) {
                    case PAUSE -> {
                        engine.pause(t.id());
                        text = "Share limit reached: paused " + t.name();
                    }
                    case REMOVE -> {
                        engine.remove(t.id(), false);
                        text = "Share limit reached: removed " + t.name();
                    }
                    default -> throw new IllegalStateException(due.get().name());
                }
            } catch (EngineException e) {
                text = "Share limit on " + t.name() + ": " + e.getMessage();
            }
            String message = text;
            boolean removed = due.get() == ShareLimit.Action.REMOVE;
            EngineEvent event =
                    EngineEvent.of(EngineEvent.Kind.SEEDING_DONE, EngineEvent.Level.INFO, t.id(), t.name(), message);
            // The snapshot goes along: a removed torrent cannot be looked up for the placeholders.
            Automation a = automation;
            if (a != null) a.handle(event, t);
            Platform.runLater(() -> {
                log.add(event);
                setStatus(message);
                if (removed) forgetOverride(t.id());
            });
        }
    }

    /** FX thread: a removed torrent takes its own share limit with it. */
    private void forgetOverride(String id) {
        if (settings.shareOverride(id).isEmpty()) return;
        settings.setShareOverride(id, null);
        settings.saveQuietly();
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

    /**
     * Any thread, idempotent. Before the engine stops: a poll of a stopped engine sees no
     * torrents, and the sidebar would take that for the choices' entries having gone.
     */
    public void stopPolling() {
        if (poller == null) return;
        poller.shutdownNow();
        // A poll in flight finishes before the engine is stopped behind it; the engine's own lock
        // guards that too, this just keeps the quit orderly.
        try {
            poller.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        onTorrents(verb, selectedTorrents(), action);
    }

    private void onTorrents(String verb, List<Torrent> targets, TorrentAction action) {
        List<Torrent> selected = targets;
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
        onSelection("Removed", (e, t) -> {
            e.remove(t.id(), deleteFiles);
            Platform.runLater(() -> forgetOverride(t.id()));
        });
    }

    /**
     * Torrent → Limits…: the first selected torrent's limits are read on a worker, the dialog edits
     * them, and Apply writes them to every selected torrent. The share override is the window's,
     * kept in the settings file beside the torrent's id.
     */
    private void editLimits() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty() || engine == null || (limitsDialog != null && limitsDialog.isShowing())) return;
        Engine e = engine;
        Torrent first = selected.get(0);
        Thread.ofVirtual().name("engine-limits").start(() -> {
            TorrentLimits current;
            try {
                current = e.limits(first.id());
            } catch (EngineException ex) {
                Platform.runLater(() -> setStatus(ex.getMessage()));
                return;
            }
            Platform.runLater(() -> {
                String subject = selected.size() == 1 ? first.name() : selected.size() + " torrents";
                LimitsDialog dialog = new LimitsDialog(
                        stage,
                        subject,
                        current,
                        settings.shareOverride(first.id()).orElse(null),
                        settings.shareLimit(),
                        first.size() > 0);
                limitsDialog = dialog;
                Optional<LimitsDialog.Result> result = dialog.showAndWait();
                limitsDialog = null;
                if (result.isEmpty()) return;
                for (Torrent t : selected)
                    settings.setShareOverride(t.id(), result.get().shareOverride());
                settings.saveQuietly();
                onSelection(
                        "Limits set on",
                        (eng, t) -> eng.setLimits(t.id(), result.get().limits()));
            });
        });
    }

    // ---- categories and tags --------------------------------------------------------------------

    /** FX thread. A category or tags typed somewhere new get into the store, so the sidebar lists them. */
    private void rememberLabels(TorrentLabels l) {
        try {
            if (!l.category().isEmpty() && labels.category(l.category()).isEmpty())
                labels.putCategory(new Category(l.category()));
            labels.addTags(l.tags());
        } catch (IOException e) {
            setStatus("Could not write " + labels.dir() + ": " + e.getMessage());
        }
        refreshSidebar(rows.list().stream().map(TorrentRow::torrent).toList());
    }

    /** Files the selection under the category (empty for none), on a worker. */
    private void setCategory(String name) {
        rememberLabels(new TorrentLabels(name, List.of()));
        String verb = name.isEmpty() ? "Category cleared on" : "Filed under " + name + ":";
        onSelection(verb, (e, t) -> e.setCategory(t.id(), name));
    }

    /**
     * Torrent → Set Category… from the palette: a choice of the categories, None, or a new one.
     * Opened on the next pulse, as every modal here is: a nested event loop cannot start inside
     * an animation or layout pass, and the capture hook runs commands from one.
     */
    private void chooseCategory() {
        if (selectedTorrents().isEmpty()) return;
        Platform.runLater(this::showCategoryChoice);
    }

    private void showCategoryChoice() {
        if (selectedTorrents().isEmpty() || (promptDialog != null && promptDialog.isShowing())) return;
        List<String> choices = new ArrayList<>();
        choices.add(NONE_CHOICE);
        for (Category c : labels.categories()) choices.add(c.name());
        choices.add(NEW_CHOICE);
        String current = selectedTorrents().get(0).category();
        javafx.scene.control.ChoiceDialog<String> prompt =
                new javafx.scene.control.ChoiceDialog<>(current.isEmpty() ? NONE_CHOICE : current, choices);
        prompt.initOwner(stage);
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle("Set Category");
        prompt.setHeaderText("File the selection under");
        prompt.setContentText("Category:");
        promptDialog = prompt;
        Optional<String> answer = prompt.showAndWait();
        promptDialog = null;
        if (answer.isEmpty()) return;
        if (answer.get().equals(NEW_CHOICE)) newCategory();
        else setCategory(answer.get().equals(NONE_CHOICE) ? "" : answer.get());
    }

    private static final String NONE_CHOICE = "None";
    private static final String NEW_CHOICE = "New category…";

    /** Asks for a name, makes the category, files the selection under it. */
    private void newCategory() {
        Platform.runLater(this::showNewCategoryPrompt);
    }

    private void showNewCategoryPrompt() {
        if (promptDialog != null && promptDialog.isShowing()) return;
        javafx.scene.control.TextInputDialog prompt = new javafx.scene.control.TextInputDialog();
        prompt.initOwner(stage);
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle("New Category");
        prompt.setHeaderText("A name for the category; its save path can be set in Settings");
        prompt.setContentText("Name:");
        promptDialog = prompt;
        Optional<String> name = prompt.showAndWait().map(String::trim).filter(n -> !n.isEmpty());
        promptDialog = null;
        name.ifPresent(this::setCategory);
    }

    /** Torrent → Edit Tags…: the dialog over the selection's tags, applied to each torrent's own. */
    private void editTags() {
        if (selectedTorrents().isEmpty()) return;
        Platform.runLater(this::showTagsDialog);
    }

    private void showTagsDialog() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty() || (tagsDialog != null && tagsDialog.isShowing())) return;
        String subject = selected.size() == 1 ? selected.get(0).name() : selected.size() + " torrents";
        TagsDialog dialog = new TagsDialog(
                stage,
                subject,
                labels.tags(),
                selected.stream().map(Torrent::tags).toList());
        tagsDialog = dialog;
        Optional<TagsDialog.Result> result = dialog.showAndWait();
        tagsDialog = null;
        table.requestFocus();
        if (result.isEmpty()) return;
        rememberLabels(new TorrentLabels("", result.get().add()));
        onTorrents("Tags set on", selected, (e, t) -> {
            List<String> tags = new ArrayList<>(e.tags(t.id()));
            tags.removeAll(result.get().remove());
            tags.addAll(result.get().add());
            e.setTags(t.id(), tags);
        });
    }

    /** The torrents carrying a category or a tag, as the rows have them now. */
    private List<Torrent> carrying(SidebarFilter filter) {
        return rows.list().stream()
                .map(TorrentRow::torrent)
                .filter(filter::matches)
                .toList();
    }

    /** What the Settings page tells the window; the engine is told on a worker. */
    private final class LabelsHost implements SettingsWindow.LabelsHost {
        @Override
        public void labelsChanged() {
            refreshSidebar(rows.list().stream().map(TorrentRow::torrent).toList());
        }

        @Override
        public void categoryDeleted(String name) {
            onTorrents(
                    "Category cleared on", carrying(SidebarFilter.category(name)), (e, t) -> e.setCategory(t.id(), ""));
            labelsChanged();
        }

        @Override
        public void tagDeleted(String name) {
            onTorrents("Tag removed from", carrying(SidebarFilter.tag(name)), (e, t) -> {
                List<String> tags = new ArrayList<>(e.tags(t.id()));
                tags.remove(name);
                e.setTags(t.id(), tags);
            });
            labelsChanged();
        }
    }

    // ---- sidebar --------------------------------------------------------------------------------

    /** FX thread. The sidebar's entries and counts from the store and this snapshot. */
    private void refreshSidebar(List<Torrent> snapshot) {
        sidebar.update(labels.categories().stream().map(Category::name).toList(), labels.tags(), snapshot);
    }

    /** An entry chosen in the sidebar by hand (or a section's All, when its choice vanished). */
    private void onSidebarChoice(SidebarEntries.Entry entry) {
        setSidebarSelection(sidebarSelection.choose(entry.filter, entry.axis));
    }

    /** Chooses a filter from code: a palette command, the capture hook. */
    private void chooseFilter(SidebarFilter filter) {
        setSidebarSelection(sidebarSelection.with(filter));
    }

    /** The selection in force: the sidebar draws it, the list obeys it, the settings keep it. */
    private void setSidebarSelection(SidebarSelection selection) {
        sidebarSelection = selection;
        sidebar.select(selection);
        applyFilters();
        selection.format().forEach((axis, text) -> settings.setSidebarChoice(axis.key(), text));
        settings.saveQuietly();
    }

    private void onSidebarCollapse(java.util.Set<SidebarFilter.Axis> collapsed) {
        settings.setSidebarCollapsed(
                collapsed.stream().map(SidebarFilter.Axis::key).toList());
        settings.saveQuietly();
    }

    private void applyFilters() {
        filtered.setPredicate(rowFilter(filter.getText(), sidebarSelection));
    }

    private void setSidebarVisible(boolean visible) {
        sidebar.setVisible(visible);
    }

    private void focusSidebar() {
        if (!sidebar.isVisible()) setSidebarVisible(true);
        sidebar.focus();
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
        List<Command> all = new ArrayList<>(List.of(
                new Command("add-file", "Add Torrent File…", chord(KeyCode.O), null, always, this::addTorrentFile),
                new Command("add-magnet", "Add Magnet Link…", chord(KeyCode.M), null, always, this::addMagnetLink),
                // The chord is a scene accelerator, not a menu one: Ctrl+V in a text field must paste.
                new Command(
                        "add-clipboard",
                        "Add from Clipboard",
                        null,
                        chord(KeyCode.V).getDisplayText(),
                        always,
                        this::addFromClipboard),
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
                new Command("limits", "Limits…", chord(KeyCode.L), null, selection, this::editLimits),
                new Command(
                        "set-category", "Set Category…", shiftChord(KeyCode.G), null, selection, this::chooseCategory),
                new Command("edit-tags", "Edit Tags…", chord(KeyCode.T), null, selection, this::editTags),
                new Command("manage-categories", "Manage Categories and Tags…", null, null, always, () -> {
                    settingsWindow.show(stage);
                    settingsWindow.selectPageForCapture("LABELS");
                }),
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
                new Command("about", "About " + AppInfo.NAME, null, null, always, this::showAbout),
                new Command(
                        "settings", "Settings…", chord(KeyCode.COMMA), null, always, () -> settingsWindow.show(stage)),
                new Command("search", "Search…", chord(KeyCode.K), null, always, this::showSearch),
                new Command("transfers", "Show Transfers", null, "Esc", always, this::showTransfers),
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
                        () -> table.getSelectionModel().selectAll()),
                new Command(
                        "event-log",
                        "Event Log",
                        null,
                        shiftChord(KeyCode.L).getDisplayText(),
                        always,
                        () -> logWindow.show()),
                new Command(
                        "toggle-toolbar",
                        "Toggle Toolbar",
                        null,
                        null,
                        always,
                        () -> toolbar.setVisible(!toolbar.isVisible())),
                new Command(
                        "toggle-sidebar",
                        "Toggle Sidebar",
                        shiftChord(KeyCode.B),
                        null,
                        always,
                        () -> setSidebarVisible(!sidebar.isVisible())),
                new Command("focus-sidebar", "Focus Sidebar", chord(KeyCode.B), null, always, this::focusSidebar)));
        // One "Show: …" per status, and Show: All, so a filter is picked without the mouse.
        all.add(new Command("show-all", "Show: All", null, null, always, () -> chooseFilter(SidebarFilter.ALL)));
        for (SidebarStatus status : SidebarStatus.values()) {
            all.add(new Command(
                    "show-" + status.key(),
                    "Show: " + status.title(),
                    null,
                    null,
                    always,
                    () -> chooseFilter(SidebarFilter.status(status))));
        }
        return List.copyOf(all);
    }

    /**
     * The icon toolbar: the common actions, each a command the menus and the palette already have,
     * with Editora's glyphs. Hidden and unmanaged together, so View → Toolbar gives the row back to
     * the list; the choice is kept.
     */
    private ToolBar buildToolbar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button paletteButton = Icons.toolbarButton(
                Icons.palette(),
                "Command Palette (" + shiftChord(KeyCode.P).getDisplayText() + ")",
                () -> palette.show(commands));
        toolbar = new ToolBar(
                toolButton("add-file", Icons.open()),
                toolButton("add-magnet", Icons.magnet()),
                new Separator(),
                toolButton("pause-resume", Icons.pause()),
                toolButton("force-resume", Icons.run()),
                toolButton("recheck", Icons.refresh()),
                toolButton("remove", Icons.trash()),
                new Separator(),
                toolButton("filter", Icons.find()),
                toolButton("search", Icons.search()),
                toolButton("event-log", Icons.history()),
                toolButton("settings", Icons.settings()),
                spacer,
                paletteButton);
        toolbar.getStyleClass().add("main-toolbar");
        toolbar.setVisible(settings.toolbar());
        toolbar.managedProperty().bind(toolbar.visibleProperty());
        toolbar.visibleProperty().addListener((o, was, now) -> {
            settings.setToolbar(now);
            settings.saveQuietly();
        });
        return toolbar;
    }

    /** A toolbar button for a command: its label and chord as the name, its enabled state followed. */
    private Button toolButton(String id, javafx.scene.Node icon) {
        Command c = command(id);
        String name = c.label().replace("…", "");
        if (!c.hint().isEmpty()) name += " (" + c.hint() + ")";
        Button b = Icons.toolbarButton(icon, name, c.run());
        b.disableProperty().bind(Bindings.not(c.enabled()));
        return b;
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
        MenuItem addTorrent = command("add-file").menuItem();
        MenuItem addMagnet = command("add-magnet").menuItem();
        MenuItem addClipboard = command("add-clipboard").menuItem();

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
                        command("search").menuItem(),
                        command("settings").menuItem(),
                        new SeparatorMenuItem(),
                        quit);

        Menu torrent = new Menu("Torrent");
        torrent.getItems().addAll(torrentItems());

        MenuItem paletteItem = new MenuItem("Command Palette…");
        paletteItem.setAccelerator(shiftChord(KeyCode.P));
        paletteItem.setOnAction(e -> palette.show(commands));
        CheckMenuItem toolbarItem = new CheckMenuItem("Toolbar");
        toolbarItem.selectedProperty().bindBidirectional(toolbar.visibleProperty());
        CheckMenuItem sidebarItem = new CheckMenuItem("Sidebar");
        sidebarItem.setAccelerator(shiftChord(KeyCode.B));
        sidebarItem.selectedProperty().bindBidirectional(sidebar.visibleProperty());
        Menu view = new Menu("View");
        view.getItems()
                .addAll(
                        toolbarItem,
                        sidebarItem,
                        new SeparatorMenuItem(),
                        command("filter").menuItem(),
                        command("transfers").menuItem(),
                        command("focus-sidebar").menuItem(),
                        command("select-all").menuItem(),
                        command("event-log").menuItem(),
                        paletteItem);

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
                        command("about").menuItem());

        MenuBar bar = new MenuBar(file, torrent, view, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    /** The Torrent menu and the context menu show the same items in the same order. */
    private List<MenuItem> torrentItems() {
        List<MenuItem> items = new ArrayList<>();
        for (String id : List.of("pause-resume", "force-resume", "recheck", "reannounce", "sequential", "limits")) {
            items.add(command(id).menuItem());
        }
        items.add(new SeparatorMenuItem());
        items.add(categoryMenu());
        items.add(command("edit-tags").menuItem());
        items.add(new SeparatorMenuItem());
        for (String id : List.of("copy-name", "copy-hash", "copy-magnet", "open-folder")) {
            items.add(command(id).menuItem());
        }
        items.add(new SeparatorMenuItem());
        for (String id : List.of("remove", "remove-data")) items.add(command(id).menuItem());
        return items;
    }

    /**
     * Set Category as a submenu: the categories that exist, with the selection's current one
     * checked, then None and New Category…. Rebuilt each time it opens, since the list changes.
     */
    private Menu categoryMenu() {
        Menu menu = new Menu("Set Category");
        menu.disableProperty().bind(Bindings.not(command("set-category").enabled()));
        menu.setOnShowing(e -> {
            List<Torrent> selected = selectedTorrents();
            List<MenuItem> items = new ArrayList<>();
            for (Category c : labels.categories()) {
                CheckMenuItem item = new CheckMenuItem(c.name());
                item.setSelected(!selected.isEmpty()
                        && selected.stream().allMatch(t -> t.category().equals(c.name())));
                item.setOnAction(a -> setCategory(c.name()));
                items.add(item);
            }
            if (!items.isEmpty()) items.add(new SeparatorMenuItem());
            MenuItem none = new MenuItem(NONE_CHOICE);
            none.setOnAction(a -> setCategory(""));
            MenuItem fresh = new MenuItem(NEW_CHOICE);
            fresh.setOnAction(a -> newCategory());
            items.add(none);
            items.add(fresh);
            menu.getItems().setAll(items);
        });
        // Never empty, or the submenu arrow has nothing to open and some platforms drop it.
        menu.getItems().add(new MenuItem(NONE_CHOICE));
        return menu;
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
            if (now && !filter.isFocused() && !searchShowing()) table.requestFocus();
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
        VBox listAndDetail = new VBox(buildFilterBar(), split);
        // The sidebar runs the full height on the left of the filter bar, the list and the detail
        // pane; hidden and unmanaged together, so View → Sidebar gives the width back to the list.
        sidebar = new Sidebar(this::onSidebarChoice, this::onSidebarCollapse, () -> table.requestFocus());
        sidebar.managedProperty().bind(sidebar.visibleProperty());
        sidebar.setVisible(settings.sidebar());
        sidebar.setCollapsed(settings.sidebarCollapsed().stream()
                .map(key -> SidebarFilter.Axis.valueOf(key.toUpperCase(java.util.Locale.ROOT)))
                .collect(java.util.stream.Collectors.toCollection(
                        () -> java.util.EnumSet.noneOf(SidebarFilter.Axis.class))));
        sidebar.visibleProperty().addListener((o, was, now) -> {
            settings.setSidebar(now);
            settings.saveQuietly();
        });
        refreshSidebar(List.of());
        sidebarSelection = SidebarSelection.parse(
                settings.sidebarChoice("status"),
                settings.sidebarChoice("category"),
                settings.sidebarChoice("tag"),
                settings.sidebarChoice("tracker"));
        sidebar.select(sidebarSelection);
        applyFilters();
        BorderPane centreWithSidebar = new BorderPane(listAndDetail);
        centreWithSidebar.setLeft(sidebar);
        transfersView = centreWithSidebar;
        // The Search tab shares the centre with the transfers: one is shown and managed, the
        // other neither, so the transfers look exactly as they did while Search is away.
        searchPane = new SearchPane(new SearchHost(), settings);
        StackPane centre2 = new StackPane(centreWithSidebar, searchPane);
        VBox.setVgrow(centre2, Priority.ALWAYS);
        return new VBox(centre2);
    }

    // ---- the Search tab -------------------------------------------------------------------------

    /** What the Search tab asks of the window. */
    private final class SearchHost implements SearchPane.Host {
        @Override
        public List<TorznabEndpoint> enabledProviders() {
            return searchStore.enabled();
        }

        @Override
        public void openProviderSettings() {
            settingsWindow.showSearchProviders(stage);
        }

        @Override
        public void add(SearchResult result) {
            addFromSearch(result);
        }

        @Override
        public void openDetail(String url) {
            if (!url.isEmpty()) opener.accept(url);
        }

        @Override
        public void copy(String text) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("Copied " + (text.startsWith("magnet:") ? "the magnet link" : "the link"));
        }

        @Override
        public void leave() {
            showTransfers();
        }
    }

    private boolean searchShowing() {
        return searchPane != null && searchPane.isVisible();
    }

    /** Ctrl+K: the Search tab in place of the transfers, the query field focused. */
    private void showSearch() {
        if (!searchShowing()) {
            transfersView.setVisible(false);
            transfersView.setManaged(false);
            searchPane.setVisible(true);
            searchPane.setManaged(true);
        }
        searchPane.focusQuery();
    }

    /** Escape in the query: the transfers back, as they were. */
    private void showTransfers() {
        if (searchShowing()) {
            searchPane.setVisible(false);
            searchPane.setManaged(false);
            transfersView.setVisible(true);
            transfersView.setManaged(true);
        }
        table.requestFocus();
    }

    /** The list that is showing takes the focus back: after a dialog, after the palette. */
    private void focusList() {
        if (searchShowing()) searchPane.focusResults();
        else table.requestFocus();
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
        filter.setPromptText("Filter (Ctrl+F) — try state:seeding");
        // Not traversable: the scene hands initial focus to the first traversable node, and while
        // the table is hidden (no torrents yet) that would be this box, so every keystroke on a
        // fresh launch would land in the filter. Ctrl+F reaches it; Tab does not need to.
        filter.setFocusTraversable(false);
        filter.textProperty().addListener((o, was, text) -> applyFilters());
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

    /**
     * Every filter at once: the box's query and the sidebar's choices (status, category, tag,
     * tracker). Package-private for tests.
     */
    static java.util.function.Predicate<TorrentRow> rowFilter(String text, SidebarSelection sidebar) {
        return rowFilter(text, sidebar, java.time.Instant.now().getEpochSecond());
    }

    /** {@link #rowFilter(String, SidebarSelection)} with the clock that {@code added<7d} counts from. */
    static java.util.function.Predicate<TorrentRow> rowFilter(String text, SidebarSelection sidebar, long now) {
        FilterQuery query = FilterQuery.parse(text, now);
        SidebarSelection s = sidebar == null ? SidebarSelection.ALL : sidebar;
        return row -> query.matches(row.torrent()) && s.matches(row.torrent());
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
        table.setRowFactory(t -> new StateRow());

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
                        statusColumn(),
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
                        column("category", "Category", 120, false, Torrent::category, s -> s),
                        column("tags", "Tags", 160, false, t -> Format.tags(t.tags()), s -> s),
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

    /** The share limit in force for a torrent: its own, else the global one. */
    private ShareLimit shareLimitFor(Torrent t) {
        ShareLimit own = shareOverrides.get(t.id());
        return own != null ? own : globalShareLimit;
    }

    /**
     * The Status column: the state's glyph, then the sentence, its lead in the state's colour. The
     * id stays {@code state}, so a remembered layout keeps its place and width for it. It sorts by
     * state, in {@link RowState}'s order: errors and stalls first.
     */
    private TableColumn<TorrentRow, StatusLine> statusColumn() {
        TableColumn<TorrentRow, StatusLine> column = new TableColumn<>("Status");
        column.setId("state");
        column.setPrefWidth(300);
        column.setCellValueFactory(cell -> {
            TorrentRow row = cell.getValue();
            return row.torrentProperty().map(t -> StatusLine.of(t, row.quietSeconds(), shareLimitFor(t)));
        });
        column.setCellFactory(c -> new StatusCell());
        return column;
    }

    /** Glyph, lead and rest as three labels, coloured by the row's state pseudo-class in app.css. */
    private static final class StatusCell extends TableCell<TorrentRow, StatusLine> {
        private final Label glyph = new Label();
        private final Label lead = new Label();
        private final Label rest = new Label();
        private final HBox box = new HBox(glyph, lead, rest);

        StatusCell() {
            glyph.getStyleClass().add("state-glyph");
            lead.getStyleClass().add("state-lead");
            rest.getStyleClass().add("state-rest");
            glyph.setMinWidth(Region.USE_PREF_SIZE);
            lead.setMinWidth(Region.USE_PREF_SIZE);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setSpacing(0);
            setText(null);
        }

        @Override
        protected void updateItem(StatusLine item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            glyph.setText(item.state().glyph());
            lead.setText(item.lead());
            rest.setText(item.rest());
            setGraphic(box);
        }
    }

    /**
     * A table row that carries its torrent's {@link RowState} as a pseudo-class ({@code st-seeding}
     * …) and {@code inactive} for the dimmed states, so app.css colours the glyph, the lead and the
     * progress bar from one place. It listens to the row's torrent, since an in-place update does
     * not call {@code updateItem}.
     */
    private static final class StateRow extends javafx.scene.control.TableRow<TorrentRow> {
        private static final javafx.css.PseudoClass INACTIVE = javafx.css.PseudoClass.getPseudoClass("inactive");
        // Invalidation, not change: two equal snapshots fire no change, yet the stall clock moved.
        private final javafx.beans.InvalidationListener onTorrent = o -> restyle();
        private TorrentRow watched;
        private RowState shown;

        @Override
        protected void updateItem(TorrentRow item, boolean empty) {
            super.updateItem(item, empty);
            if (watched != null) watched.torrentProperty().removeListener(onTorrent);
            watched = empty ? null : item;
            if (watched != null) watched.torrentProperty().addListener(onTorrent);
            restyle();
        }

        private void restyle() {
            RowState next = watched == null ? null : RowState.of(watched.torrent(), watched.quietSeconds());
            if (next == shown) return;
            if (shown != null)
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass(shown.pseudoClass()), false);
            if (next != null) pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass(next.pseudoClass()), true);
            pseudoClassStateChanged(INACTIVE, next != null && next.inactive());
            shown = next;
        }
    }

    /**
     * State colours on or off (Settings → General → Appearance): a style class on the scene root
     * that every state rule in app.css is scoped under. Off, rows keep their glyphs and sentences
     * in the ordinary text colour, and the bars in the accent.
     */
    private void applyStateColors() {
        var classes = host.getStyleClass();
        boolean on = settings.stateColors();
        if (on && !classes.contains("state-colors")) classes.add("state-colors");
        if (!on) classes.remove("state-colors");
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
        // The alternative-speed toggle: a click flips the manual switch; the schedule shows too.
        altSpeedToggle.getStyleClass().add("alt-speed");
        altSpeedToggle.setText("Alt speed: off");
        altSpeedToggle.setOnMouseClicked(e -> toggleAlternativeSpeed());
        HBox bar = new HBox(12, status, spacer, altSpeedToggle, updateBadge, syncBadge, errorBadge, rates);
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
