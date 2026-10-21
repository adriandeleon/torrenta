package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableBooleanValue;
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
import javafx.stage.StageStyle;

import com.torrenta.AppInfo;
import com.torrenta.automation.Automation;
import com.torrenta.automation.Notifiers;
import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.Inputs;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.WatchFolders;
import com.torrenta.net.TorrentFetch;
import com.torrenta.os.Badge;
import com.torrenta.os.BadgeState;
import com.torrenta.os.Badges;
import com.torrenta.os.Handlers;
import com.torrenta.os.MacHandlers;
import com.torrenta.rss.Article;
import com.torrenta.rss.Feed;
import com.torrenta.rss.FeedFetcher;
import com.torrenta.rss.RssService;
import com.torrenta.rss.RssStore;
import com.torrenta.rss.Rule;
import com.torrenta.search.DeclarativeProvider;
import com.torrenta.search.SearchProvider;
import com.torrenta.search.SearchResult;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.search.TorznabProvider;
import com.torrenta.store.DefinitionStore;
import com.torrenta.store.LabelStore;
import com.torrenta.store.ProfileStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;
import com.torrenta.tray.TorrentaTray;
import com.torrenta.tray.Trays;
import com.torrenta.update.ReleaseInfo;
import com.torrenta.update.UpdateCheck;
import com.torrenta.update.UpdateService;

import static com.torrenta.i18n.Messages.tr;

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
    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    private final Stage stage;
    private final Settings settings;
    private final Consumer<String> opener;
    private final Label status = new Label();
    private final Label rates = new Label();
    private final TorrentRows rows = new TorrentRows();
    private final FilteredList<TorrentRow> filtered = new FilteredList<>(rows.list());
    private final TextField filter = new TextField();
    /** The omnibox as a value: chips and text; the field and the chip row draw it. */
    private Omnibox omnibox = Omnibox.EMPTY;

    private final HBox chipRow = new HBox(4);
    /** Set while the field's text is being written from the value, so the listener stays quiet. */
    private boolean settingFilter;

    private ToolBar toolbar;
    private final StackPane host = new StackPane();
    private final CommandPalette palette;
    /** The chord that opens the palette, as {@code ui.paletteKey} says; see {@link #applyPaletteKey}. */
    private KeyCombination paletteChord;

    private Button paletteButton;
    private MenuItem paletteItem;
    private final EventLog log = new EventLog();
    private final LogWindow logWindow;
    private final StatisticsWindow statsWindow;
    /** Whether the poll should read the engine's counters: only while the Statistics window shows. */
    private volatile boolean statsWanted;
    /** The session rates, one sample per poll, for the Statistics window's graph. */
    private final SpeedHistory sessionHistory = new SpeedHistory();
    /** The rates of every torrent that has been selected, by id, for the Speed tab; gone with the torrent. */
    private final java.util.concurrent.ConcurrentMap<String, SpeedHistory> torrentHistories =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** What the status bar has said; the popup over it shows the history. */
    private final MessageLog messages = new MessageLog();

    private HBox statusBar;
    private final MessageLogPopup messagePopup;
    /** Built on first use: most sessions never open it. */
    private DebugLogWindow debugLogWindow;

    private final Label errorBadge = new Label();
    private TableView<TorrentRow> table;
    /** Two-line or one-line rows; each remembers its own columns. */
    private Density density = Density.DEFAULT;
    /** The columns as built, before any remembered layout: compact's default. */
    private String builtInLayout = "";

    private final javafx.scene.control.ToggleGroup densityGroup = new javafx.scene.control.ToggleGroup();
    private final java.util.Map<Density, javafx.scene.control.RadioMenuItem> densityItems =
            new java.util.EnumMap<>(Density.class);
    private List<Command> commands = List.of();
    private AddDialog currentDialog;
    private CreateDialog createDialog;
    private LimitsDialog limitsDialog;
    private TagsDialog tagsDialog;
    private LocationDialog locationDialog;
    /** A folder the capture hook wants typed into the next Set Location dialog, or null. */
    private String locationForCapture;
    /** A choice or text prompt of the category commands, while one is up. */
    private javafx.scene.control.Dialog<?> promptDialog;

    private final LabelStore labels;
    private final WatchFolderStore watchStore;
    private final SearchProviderStore searchStore;
    private final DefinitionStore definitionStore;
    /** One connection pool for every search provider. */
    private final java.net.http.HttpClient searchClient = TorznabProvider.defaultClient();
    /** The definition problems already said in the log, so a broken file is reported once. */
    private final Set<String> reportedDefinitionProblems = new java.util.HashSet<>();

    private WatchFolders watcher;
    /** Runs the person's commands and shows notifications; made with the engine in {@link #listenTo}. */
    private volatile Automation automation;

    private Sidebar sidebar;
    private SidebarSelection sidebarSelection = SidebarSelection.ALL;
    private String lastClipboardOffer = "";
    private final DetailPane detail = new DetailPane();
    private SearchPane searchPane;
    private final RssStore rssStore;
    /** The reader: its own schedule, a virtual thread per fetch, listeners on the FX thread. */
    private final RssService rss;

    private RssPane rssPane;
    /** The transfers (sidebar, filter, list, detail) and the Search tab share the centre. */
    private javafx.scene.Node transfersView;

    private final UpdateService updates = new UpdateService();
    private final SettingsWindow settingsWindow;
    private final IpFilterController ipFilter;
    private final MenuItem downloadUpdateItem = new MenuItem();
    private MenuBar menuBar;
    private final MenuItem skipUpdateItem = new MenuItem();
    private final Label updateBadge = new Label();
    private final Label altSpeedToggle = new Label();
    private ReleaseInfo availableUpdate;
    private SettingsSync sync;
    private final Label syncBadge = new Label(tr("status.syncProblem"));
    private Alert aboutDialog;
    private SplitPane split;
    private volatile String detailId;
    private volatile boolean detailWanted;
    /** The global share limit as the poll thread applies it; refreshed with the engine settings. */
    private volatile ShareLimit globalShareLimit = ShareLimit.NONE;
    /** Per-torrent share limits as of the last poll, for the status sentences ("ratio 1.2 of 2"). */
    private volatile java.util.Map<String, ShareLimit> shareOverrides = java.util.Map.of();

    /**
     * Set on the FX thread, read by the poll thread and workers: volatile, and copied to a local
     * before use, since a profile switch sets it to null while a poll may still be running.
     */
    private volatile Engine engine;

    private String engineDescription = "";
    /** What the engine can do; everything until one has started. Read by workers, set on the FX thread. */
    private volatile Engine.Capabilities capabilities = Engine.Capabilities.ALL;
    /**
     * One switch per capability. A command behind a missing one is disabled as well as hidden, so
     * its chord and its palette entry are dead too, not only its menu item.
     */
    private final java.util.Map<Engine.Capability, javafx.beans.property.BooleanProperty> capabilityOn =
            new java.util.EnumMap<>(Engine.Capability.class);
    /** Every menu item made for a command, by id, so a missing capability can hide them all. */
    private final java.util.Map<String, List<MenuItem>> commandItems = new java.util.HashMap<>();

    private final java.util.Map<String, Button> commandButtons = new java.util.HashMap<>();
    /** The profile the engine is built from; the local one until App says otherwise. */
    private EngineProfile profile = EngineProfile.LOCAL;

    private EngineFactory engineFactory;
    private final ProfileStore profiles;
    /** The engine could not start: the empty state says why and offers Retry and another profile. */
    private final javafx.beans.property.BooleanProperty engineDown = new javafx.beans.property.SimpleBooleanProperty();
    /** Bumped on the FX thread whenever a "pick files when ready" mark is set or cleared. */
    private final javafx.beans.property.IntegerProperty pickFilesMarks =
            new javafx.beans.property.SimpleIntegerProperty();

    private VBox engineProblem;
    private Label engineProblemText;
    /** What the daemon's own blocklist fetch last said, for the IP filter page of a remote engine. */
    private volatile String blocklistStatus = "";

    private final List<String> pendingInputs = new ArrayList<>();
    /** Opened when the engine is up: a rule's add from a feed fetched before then waits on it. */
    private final java.util.concurrent.CountDownLatch engineUp = new java.util.concurrent.CountDownLatch(1);

    private ScheduledExecutorService poller;

    /** The tray icon once one has come up, else null; set on the FX thread, read by the poll. */
    private volatile TorrentaTray tray;
    /** Why there is no tray, once an attempt has said; empty before one and while there is one. */
    private volatile String trayReason = "";
    /** What the tray was last given, so an equal snapshot costs no round trip; poll thread. */
    private volatile TrayStatus trayStatus = TrayStatus.starting();
    /** The window is hidden behind the tray icon, not closed: {@code onHidden} tears nothing down. */
    private boolean hiddenToTray;
    /** Quit was asked for: a close request is not a close-to-tray, and teardown runs once. */
    private boolean quitting;

    /** The dock or launcher badge once one has come up, else null; set on the FX thread, read by the poll. */
    private volatile Badge badge;
    /** Why there is no badge, once an attempt has said; empty before one and while there is one. */
    private volatile String badgeReason = "";
    /** What the badge was last given, so an equal snapshot costs nothing; poll thread. */
    private volatile BadgeState badgeState = BadgeState.idle();
    /** The single-instance lock's description, for the capture hook; set by App. */
    private volatile String instanceDescription = "none";
    /** What the last handler registration said, for the capture hook. */
    private volatile String handlerOutcome = "";
    /** Which desktop open handlers are installed (macOS), or why none. */
    private volatile String openHandlers = "";

    private boolean shutDown;

    /**
     * @param opener opens a URI with the desktop (a folder, later a web page); the application's
     *     host services, which only the {@code Application} can hand out
     */
    public MainWindow(Stage stage, Settings settings, Path configDir, Consumer<String> opener) {
        this.stage = stage;
        this.settings = settings;
        this.opener = opener;
        this.ipFilter = new IpFilterController(settings, configDir);
        for (Engine.Capability c : Engine.Capability.values()) {
            capabilityOn.put(c, new javafx.beans.property.SimpleBooleanProperty(true));
        }
        this.profiles = new ProfileStore(configDir);
        profiles.load();
        this.labels = new LabelStore(configDir);
        labels.load();
        this.watchStore = new WatchFolderStore(configDir);
        watchStore.load();
        this.searchStore = new SearchProviderStore(configDir);
        searchStore.load();
        this.definitionStore = new DefinitionStore(configDir);
        installExampleDefinitions();
        definitionStore.load();
        this.rssStore = new RssStore(configDir);
        rssStore.load();
        this.rss = new RssService(
                rssStore,
                new FeedFetcher(),
                settings::rssRefreshMinutes,
                r -> Thread.ofVirtual().name("rss-fetch").start(r),
                Platform::runLater);
        stage.setTitle(AppInfo.NAME);
        // Before anything else touches the stage: initStyle throws once a stage has been shown, and
        // the setting is read here, once, which is why it applies on restart.
        if (ExtendedWindow.enabled(settings.extendedWindow(), System.getProperty("os.name"))) {
            try {
                stage.initStyle(StageStyle.EXTENDED);
            } catch (RuntimeException e) {
                // A window is not something a chrome preference gets to prevent: fall back to a
                // decorated one if the platform rejects extended decorations despite the OS gate.
                LOG.log(Level.WARNING, "Extended window unavailable; using a decorated window", e);
            }
        }

        BorderPane root = new BorderPane();
        root.setCenter(buildCenter());
        statusBar = buildStatusBar();
        root.setBottom(statusBar);
        commands = buildCommands();
        paletteChord =
                PaletteKey.byId(settings.paletteKey(), PaletteKey.DEFAULT).chord();
        // The toolbar first: the View menu's check item binds to its visibility.
        ToolBar bar = buildToolbar();
        // An extended window has no system title bar, so the menu bar takes its place; asking the
        // stage rather than re-reading the setting means the two can never disagree once built.
        MenuBar menuBar = buildMenuBar();
        root.setTop(new VBox(
                stage.getStyle() == StageStyle.EXTENDED ? ExtendedWindow.header(menuBar, stage) : menuBar, bar));
        table.setContextMenu(buildContextMenu());
        palette = new CommandPalette(host, this::focusList, paletteChord.getDisplayText());
        logWindow = new LogWindow(stage, log);
        statsWindow = new StatisticsWindow(stage, settings.statsWindowSeconds(), this::rememberStatsWindow);
        statsWindow.showingProperty().addListener((o, was, now) -> statsWanted = now);
        detail.setSpeedWindow(settings.statsWindowSeconds());
        detail.setOnSpeedWindowChanged(this::rememberStatsWindow);
        messagePopup = new MessageLogPopup(host, statusBar, messages, this::focusList);
        settingsWindow = new SettingsWindow(settings);
        settingsWindow.setOnShowDebugLog(this::showDebugLog);
        settingsWindow.setOnEngineSettingsChanged(this::applyEngineSettings);
        settingsWindow.setLabels(labels, new LabelsHost());
        settingsWindow.setOnIpFilterChanged(this::refreshIpFilter, this::ipFilterStatusText);
        settingsWindow.setOnAppearanceChanged(this::applyAppearance);
        settingsWindow.setOnTrayChanged(this::applyTraySetting);
        settingsWindow.setOnHandlerChanged(() -> applyHandlerSetting(true));
        settingsWindow.setOnBadgeChanged(this::applyBadgeSetting);
        settingsWindow.setProfiles(profiles, new ProfilesHost());
        applyAppearance();
        settingsWindow.setWatchFolders(watchStore, new WatchHost());
        settingsWindow.setSearchProviders(searchStore, searchPane::reloadProviders);
        settingsWindow.setSearchDefinitions(
                definitionStore,
                () -> opener.accept(definitionStore.dir().toUri().toString()));
        settingsWindow.setRss(rss);
        rss.setDownloader(this::downloadForRule, settings::rssAutoDownload, this::reportRule);
        rss.start();
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
        reportDefinitionProblems();
        host.getChildren().add(root);

        Scene scene = new Scene(host, 960, 600);
        scene.getStylesheets()
                .add(MainWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.getAccelerators().put(chord(KeyCode.F), this::focusFilter);
        scene.getAccelerators().put(chord(KeyCode.K), this::focusFilter);
        scene.getAccelerators().put(paletteChord, () -> palette.show(CapabilityRules.commands(commands, capabilities)));
        scene.getAccelerators().put(shiftChord(KeyCode.L), logWindow::show);
        scene.getAccelerators().put(shiftChord(KeyCode.T), statsWindow::show);
        scene.getAccelerators().put(shiftChord(KeyCode.M), messagePopup::toggle);
        scene.getAccelerators().put(chord(KeyCode.V), this::addFromClipboard);
        // Set Category is a submenu, and a submenu carries no accelerator: the chord is the scene's.
        scene.getAccelerators().put(shiftChord(KeyCode.G), this::chooseCategoryByChord);
        scene.setOnDragOver(this::onDragOver);
        scene.setOnDragDropped(this::onDragDropped);
        // A magnet copied elsewhere is the commonest way a torrent arrives; say so when the window
        // comes back to the front, once per link, and leave the adding to a keystroke.
        stage.focusedProperty().addListener((o, was, now) -> {
            if (now) offerClipboard();
        });
        stage.setScene(scene);
        // The live setting, not one captured at startup: the checkbox can change while the window
        // is open. Conditional on a tray having actually appeared, not on the preference alone:
        // hiding a window that leaves no icon behind is how a program becomes unreachable.
        stage.setOnCloseRequest(e -> {
            if (!quitting && tray != null && settings.closeToTray()) {
                e.consume();
                hideToTray();
            }
        });
        stage.setOnHidden(e -> {
            if (hiddenToTray) return;
            shutdown();
        });
        setStatus(tr("status.starting"));
        rates.setText(ratesText(Engine.Rates.ZERO));
    }

    public void show() {
        stage.show();
        table.requestFocus();
        installTray();
        installBadge();
        installOpenHandlers();
        applyHandlerSetting(false);
    }

    // ---- the desktop: badge, handlers, a second instance ----------------------------------------

    /**
     * Brings the badge up off the FX thread (D-Bus or AWT may block). Nothing happens when the
     * setting is off or one is up; a second attempt that finds one up disposes its own.
     */
    private void installBadge() {
        if (!settings.osBadge() || badge != null) return;
        Thread.ofPlatform().daemon().name("badge-init").start(() -> {
            String os = System.getProperty("os.name", "");
            Badges.Attempt attempt = Badges.create(os, Handlers.desktopIdHere());
            Platform.runLater(() -> {
                if (attempt.badge().isEmpty()) {
                    badgeReason = attempt.reason();
                    LOG.info("No badge: " + attempt.reason());
                    return;
                }
                Badge created = attempt.badge().get();
                if (badge != null || !settings.osBadge()) {
                    created.dispose();
                    return;
                }
                badge = created;
                badgeReason = "";
                created.update(badgeState);
                LOG.info("Badge up (" + created.backend() + ")");
            });
        });
    }

    /** FX thread, from the Settings page: the switch was flipped. */
    private void applyBadgeSetting() {
        if (settings.osBadge()) {
            installBadge();
            return;
        }
        Badge current = badge;
        if (current == null) return;
        badge = null;
        current.dispose();
    }

    /** Poll thread: the badge, only when it changed since the last push. */
    private void pushBadge(BadgeState next) {
        if (next.equals(badgeState)) return;
        badgeState = next;
        Badge current = badge;
        if (current != null) current.update(next);
    }

    /** Any thread, idempotent; from App.stop. */
    public void disposeBadge() {
        Badge current = badge;
        badge = null;
        if (current != null) current.dispose();
    }

    /**
     * macOS hands a running application its files and links through AWT's Desktop handlers; on
     * the other platforms they arrive as a second instance's command line instead. Off the FX
     * thread: initialising AWT there, on macOS, is the AppKit contention the headless guard is for.
     */
    private void installOpenHandlers() {
        if (Handlers.platform(System.getProperty("os.name", "")) != Handlers.Platform.MAC) {
            openHandlers = "none (not macOS)";
            return;
        }
        Thread.ofPlatform().daemon().name("open-handlers").start(() -> {
            String installed = MacHandlers.install(
                    inputs -> Platform.runLater(() -> openFromOutside(inputs, tr("window.origin.desktop"))));
            openHandlers = installed;
            LOG.info("Desktop open handlers: " + installed);
        });
    }

    /**
     * (Re)registers as the handler when the setting is on, on a worker. At start it is quiet
     * unless a command failed (a development run has nothing to register and says so only in the
     * log); from the Settings page the status bar says what happened either way.
     */
    private void applyHandlerSetting(boolean fromSettings) {
        boolean on = settings.osHandler();
        if (!on && !fromSettings) return;
        Thread.ofVirtual().name("handler-registration").start(() -> {
            Handlers.Outcome outcome = Handlers.applyHere(on);
            handlerOutcome = outcome.text();
            if (!fromSettings && (outcome.ok() || outcome.skipped())) {
                LOG.info("Handler registration: " + outcome.text());
                return;
            }
            Platform.runLater(
                    () -> setStatus(outcome.text(), outcome.ok() ? EngineEvent.Level.INFO : EngineEvent.Level.WARNING));
        });
    }

    /**
     * FX thread. What a second instance or the desktop handed over: the window comes to the front
     * first (shown from the tray, un-minimised), then each input takes the usual add path, as a
     * dropped file would, so the Adding settings apply.
     */
    public void openFromOutside(List<String> inputs, String origin) {
        if (hiddenToTray) showFromTray();
        else {
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        }
        String said = inputs.isEmpty()
                ? tr("status.broughtToFront", origin)
                : tr("status.received", inputs.size(), origin, String.join(", ", inputs));
        log.add(EngineEvent.info(null, null, said));
        setStatus(said);
        inputs.forEach(this::addInput);
    }

    /** Set by App once the lock is held, for the capture hook. */
    public void setInstanceDescription(String text) {
        instanceDescription = text;
    }

    public String instanceDescription() {
        return instanceDescription;
    }

    /** The badge backend that came up, or the reason none did, or "none"; for the capture hook. */
    public String badgeDescription() {
        Badge current = badge;
        if (current != null) return current.backend() + (badgeState.active() ? " " + badgeState.text() : "");
        return badgeReason.isEmpty() ? "none" : badgeReason;
    }

    public String handlerDescription() {
        return handlerOutcome;
    }

    public String openHandlersDescription() {
        return openHandlers;
    }

    // ---- tray -----------------------------------------------------------------------------------

    /**
     * Brings the tray up off the FX thread, because both backends can block on D-Bus or AWT
     * initialisation and neither is worth a stalled first frame. Nothing happens when the setting
     * is off or an icon is already up; a second attempt that finds one up disposes its own.
     */
    private void installTray() {
        if (!settings.tray() || tray != null) return;
        Thread.ofPlatform().daemon().name("tray-init").start(() -> {
            Trays.Attempt attempt =
                    Trays.create(() -> Platform.runLater(this::showFromTray), () -> Platform.runLater(this::quit));
            Platform.runLater(() -> {
                if (attempt.tray().isEmpty()) {
                    trayReason = attempt.reason();
                    // Silence here is the bug a person actually hits: the setting is on, the box
                    // says so, and closing still quits, because no tray ever appeared.
                    setStatus(tr("status.noTray", attempt.reason(), AppInfo.NAME));
                    return;
                }
                TorrentaTray created = attempt.tray().get();
                if (tray != null || !settings.tray()) {
                    created.dispose();
                    return;
                }
                tray = created;
                trayReason = "";
                // Without this, hiding the last window ends the JavaFX runtime, which calls
                // App.stop, which ends the process: closing to the tray would quit no matter what
                // the close handler did. Set only once a tray really exists, for the reason above.
                Platform.setImplicitExit(false);
                created.update(trayStatus.display(), trayStatus.line());
                LOG.info("Tray icon up (" + created.backend() + ")");
            });
        });
    }

    /** FX thread, from the Settings page: the switch was flipped, bring the icon up or take it down. */
    private void applyTraySetting() {
        if (settings.tray()) {
            installTray();
            return;
        }
        TorrentaTray current = tray;
        if (current == null) return;
        tray = null;
        current.dispose();
        Platform.setImplicitExit(true);
        // A window hidden behind an icon that has just gone would be unreachable.
        if (hiddenToTray) showFromTray();
    }

    /** Poll thread: the icon and its line, only when they changed since the last push. */
    private void pushTray(TrayStatus next) {
        if (next.equals(trayStatus)) return;
        trayStatus = next;
        TorrentaTray current = tray;
        if (current != null) current.update(next.display(), next.line());
    }

    private void hideToTray() {
        rememberDivider();
        hiddenToTray = true;
        stage.hide();
    }

    private void showFromTray() {
        hiddenToTray = false;
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        table.requestFocus();
    }

    /**
     * Ends the program: from the File menu, the tray menu, or a close with no tray to close to.
     * Platform.exit is needed outright once implicit exit is off; before that it is harmless.
     */
    private void quit() {
        quitting = true;
        hiddenToTray = false;
        if (stage.isShowing()) stage.close(); // onHidden tears down
        else shutdown();
        Platform.exit();
    }

    /** Once: what the window owns, before the engine is stopped behind it. */
    private void shutdown() {
        if (shutDown) return;
        shutDown = true;
        rememberDivider();
        stopPolling();
        stopWatching();
        rss.stop();
        updates.shutdown();
        sync.shutdown();
    }

    /** Any thread, idempotent; from App.stop, after the engine. */
    public void disposeTray() {
        TorrentaTray current = tray;
        tray = null;
        if (current != null) current.dispose();
    }

    /** The backend that came up, or the reason none did, or "none"; read by the development capture. */
    public String trayDescription() {
        TorrentaTray current = tray;
        if (current != null) return current.backend();
        return trayReason.isEmpty() ? "none" : trayReason;
    }

    /** The icon's line as last pushed; read by the development capture. */
    public String trayLine() {
        return trayStatus.line();
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
        if (createDialog != null && createDialog.isShowing()) return createDialog.scene();
        if (limitsDialog != null && limitsDialog.isShowing()) return limitsDialog.scene();
        if (tagsDialog != null && tagsDialog.isShowing()) return tagsDialog.scene();
        if (locationDialog != null && locationDialog.isShowing()) return locationDialog.scene();
        if (promptDialog != null && promptDialog.isShowing())
            return promptDialog.getDialogPane().getScene();
        if (aboutDialog != null && aboutDialog.isShowing())
            return aboutDialog.getDialogPane().getScene();
        if (rssPane != null && rssPane.dialogScene() != null) return rssPane.dialogScene();
        if (settingsWindow.dialogScene() != null) return settingsWindow.dialogScene();
        if (settingsWindow.isShowing()) return settingsWindow.scene();
        if (statsWindow.isShowing()) return statsWindow.scene();
        if (logWindow.isShowing()) return logWindow.scene();
        javafx.stage.PopupWindow popup = shownPopup();
        if (popup != null) return popup.getScene();
        if (debugLogWindow != null && debugLogWindow.isShowing()) return debugLogWindow.scene();
        return stage.getScene();
    }

    /**
     * The menu or context menu open now, or null: a popup is its own window, so its own scene. The
     * last one showing, since a submenu is a second context menu opened after its parent.
     */
    private static javafx.stage.PopupWindow shownPopup() {
        ContextMenu last = null;
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
            if (w instanceof ContextMenu c && c.isShowing()) last = c;
        }
        return last;
    }

    /**
     * The open menu's items for the capture hook, top to bottom: the text, {@code [icon]} when
     * one is set, {@code ---} for a separator, {@code >} before a submenu.
     */
    public List<String> menuLines() {
        if (!(shownPopup() instanceof ContextMenu menu)) return List.of();
        List<String> lines = new ArrayList<>();
        for (MenuItem item : menu.getItems()) {
            if (item instanceof SeparatorMenuItem) {
                lines.add("---");
                continue;
            }
            if (!item.isVisible()) continue;
            String line = (item instanceof Menu ? "> " : "") + item.getText();
            if (item.getGraphic() != null) line += " [icon]";
            lines.add(line);
            // A submenu's items, indented, so a shot of the parent still says what it holds.
            if (item instanceof Menu sub) {
                for (MenuItem child : sub.getItems()) {
                    if (child instanceof SeparatorMenuItem || !child.isVisible()) continue;
                    lines.add("    " + child.getText() + (child.getGraphic() != null ? " [icon]" : ""));
                }
            }
        }
        return lines;
    }

    /** Dismisses an open Add dialog, so the capture hook can exit outside its nested event loop. */
    public void closeDialogsForCapture() {
        if (currentDialog != null && currentDialog.isShowing()) currentDialog.cancel();
        if (createDialog != null && createDialog.isShowing()) createDialog.close();
        if (limitsDialog != null && limitsDialog.isShowing()) limitsDialog.cancel();
        if (tagsDialog != null && tagsDialog.isShowing()) tagsDialog.cancel();
        if (locationDialog != null && locationDialog.isShowing()) locationDialog.cancel();
        if (promptDialog != null && promptDialog.isShowing()) promptDialog.close();
        if (aboutDialog != null && aboutDialog.isShowing()) aboutDialog.close();
        if (rssPane != null) rssPane.closeDialog();
        settingsWindow.closeDialogsForCapture();
    }

    /**
     * Printed after the shot, when a dialog opened on a pulse after the action has had its say:
     * the RSS rule dialog's title, its Matches list and its problem line.
     */
    public List<String> lateLinesForCapture() {
        List<String> out = new ArrayList<>();
        if (createDialog != null && createDialog.isShowing()) {
            out.add("dialog=" + createDialog.title());
            out.addAll(createDialog.linesForCapture());
        }
        if (locationDialog != null && locationDialog.isShowing()) {
            out.add("dialog=" + locationDialog.title());
            out.add("location-hint=" + locationDialog.hint());
        }
        // After the shot: the window and the tab have had their ticks by then.
        if (statsWindow.isShowing()) {
            out.add("stats=" + statsWindow.summaryForCapture());
            out.add("stats-graph=" + statsWindow.graph().summaryForCapture());
        }
        if (detailWanted && detail.activeTab().equals("speed")) out.add("speed-tab=" + detail.speedSummaryForCapture());
        if (!settingsWindow.profileDialogStatusForCapture().isEmpty()) {
            out.add("dialog=" + settingsWindow.dialogTitle());
            out.add("profile-test=" + settingsWindow.profileDialogStatusForCapture());
        }
        if (!settingsWindow.ruleMatchesForCapture().isEmpty()) {
            out.add("dialog=" + settingsWindow.dialogTitle());
            out.add("rule-matches=" + settingsWindow.ruleMatchesForCapture());
            out.add("rule-problem=" + settingsWindow.ruleProblemForCapture());
        }
        return out;
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
        } else if (action.equals("close-request")) {
            // What the title bar's button sends: the close-to-tray rule runs, stage.close() skips it.
            stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        } else if (action.equals("select-all")) {
            table.getSelectionModel().selectAll();
        } else if (action.equals("event-log")) {
            logWindow.show();
        } else if (action.equals("stats")) {
            statsWindow.show();
        } else if (action.equals("message-log")) {
            messagePopup.show();
        } else if (action.startsWith("message-log:")) {
            messagePopup.show();
            messagePopup.setFilterForCapture(action.substring("message-log:".length()));
        } else if (action.equals("debug-log")) {
            showDebugLog();
        } else if (action.equals("settings")) {
            settingsWindow.show(stage);
        } else if (action.startsWith("settings-page:")) {
            settingsWindow.show(stage);
            settingsWindow.selectPageForCapture(action.substring("settings-page:".length()));
        } else if (action.startsWith("settings:")) {
            settingsWindow.show(stage);
            settingsWindow.searchForCapture(action.substring("settings:".length()));
        } else if (action.startsWith("settings-profile-add")) {
            // settings-profile-add, settings-profile-add:<url>; the Add Profile dialog, URL filled in
            settingsWindow.show(stage);
            int colon = action.indexOf(':');
            settingsWindow.openProfileDialogForCapture(colon < 0 ? "" : action.substring(colon + 1), false);
        } else if (action.startsWith("settings-profile-dialog-test:")) {
            settingsWindow.show(stage);
            settingsWindow.openProfileDialogForCapture(
                    action.substring("settings-profile-dialog-test:".length()), true);
        } else if (action.startsWith("switch-profile:")) {
            // The profile by id, switched in place, as the Settings page's Switch now does.
            profiles.byId(action.substring("switch-profile:".length())).ifPresent(this::switchProfile);
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
        } else if (action.equals("settings-definitions-reload")) {
            settingsWindow.show(stage);
            settingsWindow.reloadDefinitionsForCapture();
        } else if (action.equals("rss")) {
            showRss();
        } else if (action.startsWith("rss:")) {
            // rss:<url>: the feed added through the service, as the dialog would, and refreshed.
            showRss();
            try {
                rss.addFeed(Feed.of(action.substring("rss:".length()), "", 0));
            } catch (IOException e) {
                System.err.println("capture: could not add the feed: " + e.getMessage());
            }
        } else if (action.startsWith("rss-dialog")) {
            // rss-dialog, rss-dialog:<url>: the Add Feed dialog from the tab, the URL typed in.
            showRss();
            int colon = action.indexOf(':');
            rssPane.openDialogForCapture(colon < 0 ? "" : action.substring(colon + 1));
        } else if (action.startsWith("rss-feed:")) {
            showRss();
            rssPane.selectFeedForCapture(action.substring("rss-feed:".length()));
        } else if (action.startsWith("rss-keys:")) {
            // Keys pressed where the focus is (DOWN+ENTER in the articles), once no feed is refreshing.
            showRss();
            rssPane.pressForCapture(java.util.Arrays.stream(
                            action.substring("rss-keys:".length()).split("\\+"))
                    .map(key -> KeyCode.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT)))
                    .toList());
        } else if (action.startsWith("settings-rss-rule-add")) {
            // settings-rss-rule-add, settings-rss-rule-add:<must contain>: the Add RSS Rule dialog from Settings → RSS.
            settingsWindow.show(stage);
            int colon = action.indexOf(':');
            settingsWindow.openRuleDialogForCapture(colon < 0 ? "" : action.substring(colon + 1));
        } else if (action.equals("rss-rule-from-title")) {
            // The context menu's Create rule from title on the first article, once the feeds are fetched.
            showRss();
            rssPane.actOnArticleForCapture(false);
        } else if (action.equals("rss-download")) {
            showRss();
            rssPane.actOnArticleForCapture(true);
        } else if (action.startsWith("settings-rss-add")) {
            settingsWindow.show(stage);
            int colon = action.indexOf(':');
            settingsWindow.openRssDialogForCapture(colon < 0 ? "" : action.substring(colon + 1));
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
        } else if (action.startsWith("create")) {
            // create, create:<path> (the dialog with the source filled in), create-run:<path> (and
            // created into a temporary file with seeding on, the Save As chooser skipped).
            int colon = action.indexOf(':');
            createTorrent();
            if (createDialog != null && colon > 0) {
                Path path = Path.of(action.substring(colon + 1));
                createDialog.setSource(path);
                if (action.startsWith("create-run:")) {
                    try {
                        Path target =
                                Files.createTempDirectory("torrenta-create").resolve(CreateText.fileName(path));
                        createDialog.createForCapture(target);
                    } catch (IOException e) {
                        System.err.println("capture: no temporary folder: " + e.getMessage());
                    }
                }
            }
        } else if (action.equals("dialog-add")) {
            if (currentDialog != null && currentDialog.isShowing()) currentDialog.confirm();
        } else if (action.startsWith("dialog-press:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.pressForCapture(action.substring("dialog-press:".length()));
        } else if (action.startsWith("dialog-key:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.keyForCapture(KeyCode.valueOf(action.substring("dialog-key:".length())));
        } else if (action.startsWith("set-location:")) {
            // The Set Location dialog with this folder typed in, so its free-space line can be read;
            // the dialog opens on a later pulse, so the folder waits for it.
            locationForCapture = action.substring("set-location:".length());
            setLocation();
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
        } else if (action.startsWith("omnibox:")) {
            // The action list is split on commas and trimmed, so a trailing space (what commits
            // a chip) could not be passed; a tilde stands for one.
            filter.requestFocus();
            filter.setText(action.substring("omnibox:".length()).replace('~', ' '));
        } else if (action.startsWith("omnibox-key:")) {
            filter.requestFocus();
            KeyCode code = KeyCode.valueOf(action.substring("omnibox-key:".length()));
            filter.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        } else if (action.startsWith("table-key:")) {
            table.requestFocus();
            KeyCode code = KeyCode.valueOf(action.substring("table-key:".length()));
            table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        } else if (action.equals("select-first")) {
            table.getSelectionModel().clearAndSelect(0);
        } else if (action.startsWith("detail:")) {
            setDetailVisible(true);
            detail.selectTab(action.substring("detail:".length()));
        } else if (action.startsWith("menu:")) {
            // Open a menu of the bar by its title (menu:File); the PNG is then of the popup.
            // menu:Torrent>Queue opens that submenu too; the PNG is then of the submenu.
            String[] titles = action.substring("menu:".length()).split(">");
            Menu menu = menuBar.getMenus().stream()
                    .filter(m -> m.getText().equalsIgnoreCase(titles[0]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no menu " + titles[0]));
            menu.show();
            for (int i = 1; i < titles.length; i++) {
                // A submenu opens from the keyboard, as a hand would: Down to its row, then Right.
                // Menu.show() on a submenu sets a property its skin does not watch.
                String title = titles[i];
                int row = 0;
                for (MenuItem item : menu.getItems()) {
                    if (item instanceof SeparatorMenuItem || !item.isVisible()) continue;
                    row++;
                    if (item instanceof Menu && item.getText().equalsIgnoreCase(title)) break;
                }
                javafx.stage.PopupWindow popup = shownPopup();
                if (popup == null) throw new IllegalArgumentException("no popup under " + title);
                javafx.scene.Node target = popup.getScene().getFocusOwner() != null
                        ? popup.getScene().getFocusOwner()
                        : popup.getScene().getRoot();
                for (int k = 0; k < row; k++) {
                    target.fireEvent(
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                }
                target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, false));
                menu = menu.getItems().stream()
                        .filter(item -> item instanceof Menu && item.getText().equalsIgnoreCase(title))
                        .map(Menu.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("no submenu " + title));
            }
        } else if (action.equals("context-menu")) {
            // The list's context menu, as a right-click on it would open it.
            table.getContextMenu().show(table, javafx.geometry.Side.BOTTOM, 0, 0);
        } else if (action.equals("palette")) {
            palette.show(CapabilityRules.commands(commands, capabilities));
        } else if (action.startsWith("palette:")) {
            palette.show(CapabilityRules.commands(commands, capabilities));
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
        if (messagePopup.isShowing()) System.out.println("capture: message-log=" + messagePopup.visibleLines());
        if (debugLogWindow != null && debugLogWindow.isShowing()) System.out.println("capture: debug-log=showing");
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
        System.out.println("capture: omnibox=" + omnibox.chips() + " text=\"" + filter.getText() + "\" mode="
                + omnibox.mode() + " query=\"" + omnibox.query() + "\"");
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
        if (settingsWindow.isShowing())
            for (String row : settingsWindow.definitionsForCapture()) System.out.println("capture: definition=" + row);
        if (watcher != null) System.out.println("capture: watch=" + watchStatusText());
        System.out.println("capture: search-tab=" + (searchShowing() ? "showing" : "hidden"));
        if (searchShowing()) {
            System.out.println("capture: search-query=" + searchPane.queryText());
            System.out.println("capture: search-chips=" + searchPane.chipsForCapture());
            System.out.println("capture: search-status=" + searchPane.statusText()
                    + (searchPane.searching() ? " (searching)" : ""));
            for (String row : searchPane.rowsForCapture()) System.out.println("capture: search-row=" + row);
        }
        System.out.println("capture: rss-tab=" + (rssShowing() ? "showing" : "hidden"));
        if (rssShowing()) {
            System.out.println("capture: rss-status=" + rssPane.statusText());
            for (String f : rssPane.feedsForCapture()) System.out.println("capture: rss-feed=" + f);
            for (String row : rssPane.rowsForCapture()) System.out.println("capture: rss-row=" + row);
            System.out.println("capture: rss-detail=" + rssPane.detailForCapture());
            if (!rssPane.dialogTitle().isEmpty())
                System.out.println("capture: dialog=" + rssPane.dialogTitle() + " | " + rssPane.dialogStatus());
        }
        if (!settingsWindow.rssStatus().isEmpty())
            System.out.println("capture: rss-settings=" + settingsWindow.rssStatus());
        if (settingsWindow.isShowing())
            for (String row : settingsWindow.rulesForCapture()) System.out.println("capture: rss-rule=" + row);
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
                setStatus(event.message(), event.level());
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
            if (!event.message().startsWith(Automation.OUTPUT_PREFIX)) setStatus(event.message(), event.level());
        });
    }

    /** FX thread. The engine has started; flush what was queued and begin polling. */
    public void engineReady(Engine engine) {
        this.engine = engine;
        this.engineDescription = engine.description();
        applyCapabilities(engine.capabilities());
        showEngineProblem(null);
        applyTitle();
        engineUp.countDown();
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
        // An engine that fetches its own blocklist (a daemon) is told the URL instead.
        if (capabilities.has(Engine.Capability.IP_FILTER)) {
            ipFilter.setEngine(engine);
            Thread.ofVirtual().name("ip-filter").start(() -> {
                ipFilter.applyStored(this::reportIpFilter);
                ipFilter.refresh(false, this::reportIpFilter);
            });
            poller.scheduleAtFixedRate(() -> ipFilter.refresh(false, this::reportIpFilter), 1, 1, TimeUnit.HOURS);
        } else if (capabilities.has(Engine.Capability.BLOCKLIST_URL) && settings.ipFilterEnabled()) {
            // Only when a blocklist is wanted: the daemon's own state is not worth a status line otherwise.
            refreshBlocklist();
        }
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
                setStatus(event.message(), event.level());
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
                altSpeedToggle.setText(alternative ? tr("status.altSpeed.on") : tr("status.altSpeed.off"));
                altSpeedToggle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), alternative);
            });
        } catch (EngineException ex) {
            Platform.runLater(() -> setStatus(ex.getMessage(), EngineEvent.Level.ERROR));
        }
    }

    private void toggleAlternativeSpeed() {
        boolean on = !settings.engineSettings().speed().alternativeOn();
        settings.set(Settings.SPEED_ALT_ON, String.valueOf(on));
        settings.saveQuietly();
        setStatus(on ? tr("status.altSpeedLimitsOn") : tr("status.altSpeedLimitsOff"));
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
            applyAppearance();
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
        if (capabilities.has(Engine.Capability.IP_FILTER)) {
            Thread.ofVirtual().name("ip-filter").start(() -> ipFilter.refresh(force, this::reportIpFilter));
        } else if (capabilities.has(Engine.Capability.BLOCKLIST_URL)) {
            refreshBlocklist();
        }
    }

    /**
     * Any thread. An engine that fetches its own blocklist is told the URL and the switch from the
     * IP filter page, and asked to fetch now; what it says lands in the page's status line.
     */
    private void refreshBlocklist() {
        Engine e = engine;
        if (e == null) return;
        String url = settings.ipFilterUrl();
        boolean on = settings.ipFilterEnabled();
        Thread.ofVirtual().name("blocklist").start(() -> {
            String text;
            try {
                long ranges = e.setBlocklist(url, on);
                text = !on || url.isBlank()
                        ? tr("status.blocklist.off")
                        : tr("status.blocklist.applied", ranges < 0 ? 0 : ranges, ranges < 0 ? 0 : 1);
            } catch (EngineException ex) {
                text = tr("status.blocklist.failed", ex.getMessage());
            }
            blocklistStatus = text;
            reportIpFilter(text);
        });
    }

    private void reportIpFilter(String text) {
        Platform.runLater(() -> {
            setStatus(text);
            settingsWindow.refreshIpFilterStatus();
        });
    }

    private String ipFilterStatusText() {
        if (capabilities.lacks(Engine.Capability.IP_FILTER)) return blocklistStatus;
        IpFilterController.Status s = ipFilter.status();
        long blocked = engine == null ? 0 : engine.blockedPeers();
        if (s.loadedAt() == 0) return s.note().isEmpty() ? tr("status.ipFilter.nothingLoaded") : s.note();
        return tr(
                "status.ipFilter.loaded",
                s.ranges(),
                Format.dateTime(s.loadedAt() / 1000),
                s.skipped(),
                s.note().isEmpty() ? 0 : 1,
                s.note(),
                blocked);
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
        setStatus(tr("status.banned", ip));
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
                Platform.runLater(() -> setStatus(ex.getMessage(), EngineEvent.Level.ERROR));
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
        setStatus(tr("status.checkingUpdates"));
        settings.setLastUpdateCheck(System.currentTimeMillis());
        settings.saveQuietly();
        updates.check(AppInfo.VERSION, outcome -> {
            onUpdateOutcome(outcome, true);
            if (outcome.error() != null) setStatus(tr("status.updateFailed", outcome.error()), EngineEvent.Level.ERROR);
            else if (outcome.available())
                setStatus(tr(
                        "status.updateAvailable", AppInfo.NAME, outcome.latest().version()));
            else setStatus(tr("status.upToDate", AppInfo.NAME, AppInfo.VERSION));
        });
    }

    /** FX thread. */
    private void onUpdateOutcome(UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.available()) return;
        if (!manual && outcome.latest().version().equals(settings.dismissedUpdate())) return;
        availableUpdate = outcome.latest();
        downloadUpdateItem.setText(tr("menu.help.downloadUpdate", AppInfo.NAME, availableUpdate.version()));
        skipUpdateItem.setText(tr("menu.help.skipVersion", availableUpdate.version()));
        downloadUpdateItem.setVisible(true);
        skipUpdateItem.setVisible(true);
        updateBadge.setText(tr("status.updateBadge", availableUpdate.version()));
        updateBadge.setVisible(true);
        updateBadge.setManaged(true);
        if (!manual) setStatus(tr("status.updateAvailable", AppInfo.NAME, availableUpdate.version()));
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
        setStatus(tr("status.updateSkipped", AppInfo.NAME, availableUpdate.version()));
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
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        setStatus(message, EngineEvent.Level.ERROR);
        showEngineProblem(message);
    }

    /** FX thread. The empty state says why the engine is not there, or nothing when it is. */
    private void showEngineProblem(String message) {
        boolean down = message != null;
        engineDown.set(down);
        if (engineProblem == null) return;
        engineProblemText.setText(down ? message : "");
        engineProblem.setVisible(down);
        engineProblem.setManaged(down);
    }

    // ---- the engine's life ----------------------------------------------------------------------

    /** Builds the engine for a profile; App's, since the stores and the settings are its to hand over. */
    public interface EngineFactory {
        Engine create(EngineProfile profile);
    }

    public void setEngineFactory(EngineFactory factory) {
        this.engineFactory = factory;
    }

    /** The profile in use; the local engine's until {@link #startEngine} says otherwise. */
    public EngineProfile profile() {
        return profile;
    }

    /**
     * FX thread. Builds the engine for {@code next} and starts it on a worker (loading a native
     * library or reaching a daemon takes longer than a frame should wait); the status bar says so
     * meanwhile and the title names a remote profile. {@link #engineReady} or {@link #engineFailed}
     * follows on the FX thread.
     */
    public void startEngine(EngineProfile next) {
        if (engineFactory == null) throw new IllegalStateException("no engine factory");
        profile = next;
        applyTitle();
        showEngineProblem(null);
        setStatus(
                next.isLocal() ? tr("status.starting") : tr("status.connecting", next.displayName(), next.hostLine()));
        Engine fresh;
        try {
            fresh = engineFactory.create(next);
        } catch (RuntimeException e) {
            engineFailed(new EngineException(e.getMessage() == null ? e.toString() : e.getMessage(), e));
            return;
        }
        listenTo(fresh);
        Thread starter = new Thread(
                () -> {
                    try {
                        fresh.start();
                        Platform.runLater(() -> engineReady(fresh));
                    } catch (EngineException e) {
                        Platform.runLater(() -> engineFailed(e));
                    } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "The engine failed to start", e);
                        Platform.runLater(() -> engineFailed(
                                new EngineException(e.getMessage() == null ? e.toString() : e.getMessage(), e)));
                    }
                },
                "engine-start");
        starter.setDaemon(true);
        starter.start();
    }

    /** Any thread, from App.stop after the poll: stops the engine in use, if one came up. */
    public void stopEngine() {
        Engine e = engine;
        engine = null;
        if (e != null) e.stop();
    }

    /**
     * FX thread. Another profile was chosen: remembered for the next start, and the engine is
     * replaced in place when it is not the one running. The list empties while the new one comes
     * up; the poll, the watcher and the old engine stop first, on their own threads.
     */
    private void switchProfile(EngineProfile next) {
        settings.setEngineProfile(next.id());
        settings.saveQuietly();
        settingsWindow.refreshProfiles();
        if (next.id().equals(profile.id()) && engine != null) {
            setStatus(tr("status.profileInUse", next.displayName()));
            return;
        }
        Engine old = engine;
        engine = null;
        engineDescription = "";
        stopPolling();
        poller = null;
        stopWatching();
        watcher = null;
        fetchWatch = null;
        long now = System.currentTimeMillis() / 1000;
        rows.apply(List.of(), now);
        refreshSidebar(List.of());
        detail.clear();
        rates.setText(ratesText(Engine.Rates.ZERO));
        if (old != null) Thread.ofVirtual().name("engine-stop").start(old::stop);
        startEngine(next);
    }

    /** FX thread. A list of the profiles to pick from; the choice switches at once. */
    private void chooseProfile() {
        record Choice(EngineProfile profile) {
            @Override
            public String toString() {
                return profile.displayName();
            }
        }
        List<Choice> choices = profiles.all().stream().map(Choice::new).toList();
        Choice current = choices.stream()
                .filter(c -> c.profile().id().equals(profile.id()))
                .findFirst()
                .orElse(choices.get(0));
        javafx.scene.control.ChoiceDialog<Choice> dialog = new javafx.scene.control.ChoiceDialog<>(current, choices);
        dialog.initOwner(stage);
        Fonts.installUiFont(dialog.getDialogPane());
        dialog.setTitle(tr("dialog.switchProfile.title"));
        dialog.setHeaderText(tr("dialog.switchProfile.header"));
        dialog.setContentText(tr("dialog.switchProfile.content"));
        promptDialog = dialog;
        Optional<Choice> picked = dialog.showAndWait();
        promptDialog = null;
        picked.ifPresent(c -> switchProfile(c.profile()));
    }

    /** The window title: the app's name, and the profile's when the engine is a remote daemon. */
    private void applyTitle() {
        stage.setTitle(
                profile.isLocal() ? AppInfo.NAME : tr("window.title.remote", AppInfo.NAME, profile.displayName()));
    }

    /** What the Settings → Engine page needs of the window; FX thread. */
    private final class ProfilesHost implements SettingsWindow.ProfilesHost {
        @Override
        public void switchTo(EngineProfile next) {
            switchProfile(next);
        }

        @Override
        public EngineProfile inUse() {
            return profile;
        }

        @Override
        public String engineLine() {
            return engineDescription;
        }
    }

    /**
     * A {@code .torrent} path, a URL to one, or a {@code magnet:} link — from the command line, a
     * drop, the clipboard or a dialog — opens the Add dialog for it. Reading, fetching and decoding
     * happen on a worker; the dialog opens on the FX thread when there is something to show.
     */
    public void addInput(String input) {
        addInput(input, null);
    }

    /**
     * The Add dialog's defaults: the settings', with the engine's own folder in place of this
     * machine's when the files are not here (a daemon's download folder is a path on its machine).
     * Any thread.
     */
    private AddDialog.Defaults addDefaults() {
        AddDialog.Defaults d = AddDialog.Defaults.from(settings);
        Engine e = engine;
        if (e != null && capabilities.lacks(Engine.Capability.LOCAL_FILES)) {
            String remote = e.downloadDir();
            if (!remote.isEmpty()) return d.withSavePath(Path.of(remote));
        }
        return d;
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
        AddDialog.Defaults defaults = addDefaults();
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
                                if (!Files.isRegularFile(file))
                                    throw new EngineException(tr("status.noSuchFile", text));
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
            Platform.runLater(() -> setStatus(tr("status.added", added.name())));
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
            setStatus(tr("status.engineStarting"));
            return;
        }
        String fetching = SearchAdd.fetchingText(result);
        if (!fetching.isEmpty()) setStatus(fetching);
        boolean withoutAsking = settings.addWithoutAsking();
        AddDialog.Defaults defaults = addDefaults();
        Thread.ofVirtual().name("search-add").start(() -> {
            try {
                AddRequest.Source source = SearchAdd.sourceOf(result, new TorrentFetch()::fetch);
                if (!fetching.isEmpty()) Platform.runLater(() -> setStatus(tr("status.fetched", result.name())));
                addSource(e, source, AddDialog.Via.SEARCH, withoutAsking, defaults);
            } catch (EngineException | IOException | RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> setStatus(message));
            }
        });
    }

    /**
     * FX thread. An article into the Add dialog: its magnet as it is, its {@code .torrent} link
     * fetched first, the status bar saying so meanwhile.
     */
    private void addFromArticle(Article article) {
        Engine e = engine;
        if (e == null) {
            setStatus(tr("status.engineStarting"));
            return;
        }
        String fetching = RssAdd.fetchingText(article);
        if (!fetching.isEmpty()) setStatus(fetching);
        boolean withoutAsking = settings.addWithoutAsking();
        AddDialog.Defaults defaults = addDefaults();
        Thread.ofVirtual().name("rss-add").start(() -> {
            try {
                AddRequest.Source source = RssAdd.sourceOf(article, new TorrentFetch()::fetch);
                if (!fetching.isEmpty())
                    Platform.runLater(() -> setStatus(tr("status.fetched", article.displayTitle())));
                addSource(e, source, AddDialog.Via.RSS, withoutAsking, defaults);
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

        /** Cancelled: the dialog closed without adding. */
        synchronized void close() {
            Engine.MetadataFetch f = take();
            if (f != null) f.cancel();
        }

        /**
         * Added: the fetch, for {@link Engine#add(AddRequest, Engine.MetadataFetch)} to end and
         * take the peers from; null if there was none. A fetch that starts later is cancelled.
         */
        synchronized Engine.MetadataFetch take() {
            closed = true;
            return fetch;
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
        AddDialog dialog = new AddDialog(stage, source, preview, via, addDefaults(), labels.categories());
        dialog.applyCapabilities(capabilities);
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
            Engine.MetadataFetch fetch = slot.take();
            String outcome;
            try {
                Torrent added = fetch == null ? e.add(request) : e.add(request, fetch);
                outcome = tr("status.added", added.name());
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
                    outcome = tr("status.addedFilesLater", added.name());
                    String id = added.id();
                    Platform.runLater(() -> {
                        settings.setFilesToPick(id, true);
                        settings.saveQuietly();
                        pickFilesMarks.set(pickFilesMarks.get() + 1);
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
        if (capabilities.lacks(Engine.Capability.METADATA_FETCH)) {
            // A daemon lists a magnet's files once it has them; the dialog says so and adds blind.
            dialog.noFetch(tr("dialog.add.filesAfterAdd"));
            return;
        }
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
        log.add(EngineEvent.info(null, null, tr("log.addWithoutAsking")));
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
                    Platform.runLater(() -> setStatus(tr("status.noFileList")));
                    return;
                }
                Torrent t = e.torrents().stream()
                        .filter(x -> x.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new EngineException(tr("status.noSuchTorrent", id)));
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
                Platform.runLater(() -> setStatus(ex.getMessage(), EngineEvent.Level.ERROR));
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
        pickFilesMarks.set(pickFilesMarks.get() + 1);
        Thread.ofVirtual().name("engine-choose").start(() -> {
            String outcome;
            try {
                for (TorrentPreview.FileEntry f : preview.files()) {
                    com.torrenta.engine.Priority p =
                            request.filePriorities().getOrDefault(f.index(), com.torrenta.engine.Priority.NORMAL);
                    if (p != own.get(f.index())) e.setFilePriority(id, f.index(), p);
                }
                if (!request.startPaused()) e.resume(id);
                outcome = request.startPaused()
                        ? tr("status.filesChosen", preview.name())
                        : tr("status.started", preview.name());
            } catch (EngineException ex) {
                outcome = ex.getMessage();
            }
            String text = outcome;
            Platform.runLater(() -> setStatus(text));
        });
    }

    /** Opens the Create Torrent dialog, or brings the open one to the front; one at a time. */
    private void createTorrent() {
        Engine e = engine;
        if (e == null) {
            setStatus(tr("status.engineStarting"));
            return;
        }
        if (createDialog == null || !createDialog.isShowing()) createDialog = new CreateDialog(stage, e, settings);
        createDialog.show();
    }

    private void addTorrentFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("window.addFile.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("window.addFile.filter"), "*.torrent"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null) files.forEach(f -> addInput(f.getAbsolutePath()));
    }

    private void addMagnetLink() {
        javafx.scene.control.TextInputDialog prompt =
                new javafx.scene.control.TextInputDialog(clipboardCandidate().orElse(""));
        prompt.initOwner(stage);
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle(tr("window.addMagnet.title"));
        prompt.setHeaderText(tr("window.addMagnet.header"));
        prompt.setContentText(tr("window.addMagnet.link"));
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
            setStatus(
                    Inputs.classify(text) == Inputs.Kind.MAGNET
                            ? tr("status.clipboard.magnet")
                            : tr("status.clipboard.torrent"));
        });
    }

    private void addFromClipboard() {
        if (currentDialog != null) return;
        clipboardCandidate()
                .ifPresentOrElse(
                        text -> addInput(text, AddDialog.Via.CLIPBOARD), () -> setStatus(tr("status.clipboardEmpty")));
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
        Engine engine = this.engine;
        if (engine == null) return;
        FetchWatch watch = fetchWatch;
        Engine.MetadataFetch fetch = watch == null ? null : watch.slot().get();
        if (fetch != null) {
            int peers = fetch.peers();
            Platform.runLater(() -> watch.dialog().showFetching(peers));
        }
        List<Torrent> snapshot = engine.torrents();
        Engine.Rates current = engine.rates();
        long nowSeconds = System.currentTimeMillis() / 1000;
        sessionHistory.add(nowSeconds, current.download(), current.upload());
        if (!torrentHistories.isEmpty()) {
            Set<String> present = new java.util.HashSet<>();
            for (Torrent t : snapshot) {
                present.add(t.id());
                SpeedHistory h = torrentHistories.get(t.id());
                if (h != null) h.add(nowSeconds, t.downloadRate(), t.uploadRate());
            }
            torrentHistories.keySet().retainAll(present);
        }
        // The counters are a copy of the last stats alert, cheap; still, only while someone looks.
        com.torrenta.engine.SessionStats stats = statsWanted ? engine.stats() : null;
        enforceShareLimits(engine, snapshot);
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
        pushTray(TrayStatus.of(snapshot, current));
        pushBadge(BadgeState.of(snapshot, current.download()));
        Platform.runLater(() -> {
            rows.apply(snapshot, nowSeconds);
            refreshSidebar(snapshot);
            rates.setText(ratesText(current));
            if (detailWanted) {
                if (shown != null && shown.general().id().equals(detailId)) {
                    detail.show(shown);
                    detail.showSpeed(torrentHistories.get(detailId), nowSeconds);
                } else if (detailId == null) detail.clear();
            }
            if (stats != null) statsWindow.update(stats, sessionHistory, nowSeconds);
        });
    }

    /**
     * Poll thread. libtorrent ranks seeds by the share limits but pauses nothing by them, so the
     * rule is applied here, once a second: a finished torrent past its own or the global limit is
     * paused or removed, and the log says so. A paused one no longer qualifies, so it is done once;
     * resuming it by hand seeds it until the next tick, raising the limit seeds it for good.
     */
    private void enforceShareLimits(Engine engine, List<Torrent> snapshot) {
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
                        text = tr("log.shareLimit.paused", t.name());
                    }
                    case REMOVE -> {
                        engine.remove(t.id(), false);
                        text = tr("log.shareLimit.removed", t.name());
                    }
                    default -> throw new IllegalStateException(due.get().name());
                }
            } catch (EngineException e) {
                text = tr("log.shareLimit.failed", t.name(), e.getMessage());
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

    /** FX thread. A graph's window chip was chosen: remembered, and the other graph follows. */
    private void rememberStatsWindow(int seconds) {
        settings.setStatsWindowSeconds(seconds);
        settings.saveQuietly();
        statsWindow.graph().setWindow(seconds);
        detail.setSpeedWindow(seconds);
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
        return DetailSplit.position(settings.get(Settings.DETAIL_DIVIDER, null));
    }

    /**
     * The divider and the detail pane's tab. The divider only beside a list: while the empty
     * state stands in for it, the position is the layout's, not the person's (see DetailSplit).
     */
    private void rememberDivider() {
        if (split.getDividers().isEmpty() || !detailWanted) return;
        if (!rows.list().isEmpty()) {
            settings.set(
                    Settings.DETAIL_DIVIDER,
                    String.format(java.util.Locale.ROOT, "%.3f", split.getDividerPositions()[0]));
        }
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
        if (Actions.shouldResume(selectedTorrents()))
            onSelection(tr("actions.verb.resumed"), (e, t) -> e.resume(t.id()));
        else onSelection(tr("actions.verb.paused"), (e, t) -> e.pause(t.id()));
    }

    private void toggleSequential() {
        boolean on = Actions.shouldEnableSequential(selectedTorrents());
        onSelection(
                on ? tr("actions.verb.sequentialOn") : tr("actions.verb.sequentialOff"),
                (e, t) -> e.setSequential(t.id(), on));
    }

    private void remove(boolean deleteFiles) {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, Actions.removeQuestion(selected.size(), deleteFiles));
        confirm.setHeaderText(deleteFiles ? tr("window.remove.header.delete") : tr("common.remove"));
        confirm.initOwner(stage);
        Fonts.installUiFont(confirm.getDialogPane());
        ButtonType yes = new ButtonType(
                deleteFiles ? tr("window.remove.button.delete") : tr("common.remove"), ButtonType.OK.getButtonData());
        confirm.getButtonTypes().setAll(yes, ButtonType.CANCEL);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != yes) return;
        onSelection(tr("actions.verb.removed"), (e, t) -> {
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
                Platform.runLater(() -> setStatus(ex.getMessage(), EngineEvent.Level.ERROR));
                return;
            }
            Platform.runLater(() -> {
                String subject = selected.size() == 1 ? first.name() : tr("window.torrentsCount", selected.size());
                LimitsDialog dialog = new LimitsDialog(
                        stage,
                        subject,
                        current,
                        settings.shareOverride(first.id()).orElse(null),
                        settings.shareLimit(),
                        first.size() > 0);
                dialog.applyCapabilities(capabilities);
                limitsDialog = dialog;
                Optional<LimitsDialog.Result> result = dialog.showAndWait();
                limitsDialog = null;
                if (result.isEmpty()) return;
                for (Torrent t : selected)
                    settings.setShareOverride(t.id(), result.get().shareOverride());
                settings.saveQuietly();
                onSelection(
                        tr("actions.verb.limitsSet"),
                        (eng, t) -> eng.setLimits(t.id(), result.get().limits()));
            });
        });
    }

    /** Torrent → Queue → …: every selected torrent, in the order that keeps their order ({@link QueueOrder}). */
    private void queueMove(Engine.QueueMove move) {
        String verb =
                switch (move) {
                    case TOP -> tr("actions.verb.queueTop");
                    case UP -> tr("actions.verb.queueUp");
                    case DOWN -> tr("actions.verb.queueDown");
                    case BOTTOM -> tr("actions.verb.queueBottom");
                };
        onTorrents(verb, QueueOrder.plan(selectedTorrents(), move), (e, t) -> e.queueMove(t.id(), move));
    }

    /** Torrent → Set Location…: one folder for the whole selection; the engine moves on its own thread. */
    private void setLocation() {
        if (selectedTorrents().isEmpty()) return;
        Platform.runLater(this::showLocationDialog);
    }

    private void showLocationDialog() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty() || (locationDialog != null && locationDialog.isShowing())) return;
        Torrent first = selected.get(0);
        String subject = selected.size() == 1 ? first.name() : tr("window.torrentsCount", selected.size());
        // The free-space line weighs the whole selection; unknown while a magnet has no metadata.
        long wanted = selected.stream().anyMatch(t -> t.size() <= 0)
                ? -1
                : selected.stream().mapToLong(Torrent::size).sum();
        LocationDialog dialog = new LocationDialog(stage, subject, first.savePath(), wanted);
        dialog.applyCapabilities(capabilities);
        if (locationForCapture != null) {
            dialog.setPathForCapture(locationForCapture);
            locationForCapture = null;
        }
        locationDialog = dialog;
        Optional<LocationDialog.Result> result = dialog.showAndWait();
        locationDialog = null;
        table.requestFocus();
        if (result.isEmpty()) return;
        String path = result.get().path();
        boolean moveFiles = result.get().moveFiles();
        onTorrents(tr("actions.verb.moving", path), selected, (e, t) -> e.moveStorage(t.id(), path, moveFiles));
    }

    // ---- categories and tags --------------------------------------------------------------------

    /** FX thread. A category or tags typed somewhere new get into the store, so the sidebar lists them. */
    private void rememberLabels(TorrentLabels l) {
        try {
            if (!l.category().isEmpty() && labels.category(l.category()).isEmpty())
                labels.putCategory(new Category(l.category()));
            labels.addTags(l.tags());
        } catch (IOException e) {
            setStatus(tr("status.labelsWriteFailed", labels.dir(), e.getMessage()));
        }
        refreshSidebar(rows.list().stream().map(TorrentRow::torrent).toList());
    }

    /** Files the selection under the category (empty for none), on a worker. */
    private void setCategory(String name) {
        rememberLabels(new TorrentLabels(name, List.of()));
        String verb = name.isEmpty() ? tr("actions.verb.categoryCleared") : tr("actions.verb.filedUnder", name);
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

    /** Ctrl+Shift+G: the chooser, when the command is live and no Add dialog is up. */
    private void chooseCategoryByChord() {
        if (currentDialog != null || !command("set-category").enabled().get()) return;
        chooseCategory();
    }

    private void showCategoryChoice() {
        if (selectedTorrents().isEmpty() || (promptDialog != null && promptDialog.isShowing())) return;
        String noneChoice = noneChoice();
        String newChoice = newChoice();
        List<String> choices = new ArrayList<>();
        choices.add(noneChoice);
        for (Category c : labels.categories()) choices.add(c.name());
        choices.add(newChoice);
        String current = selectedTorrents().get(0).category();
        javafx.scene.control.ChoiceDialog<String> prompt =
                new javafx.scene.control.ChoiceDialog<>(current.isEmpty() ? noneChoice : current, choices);
        prompt.initOwner(stage);
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle(tr("window.category.title"));
        prompt.setHeaderText(tr("window.category.header"));
        prompt.setContentText(tr("window.category.label"));
        promptDialog = prompt;
        Optional<String> answer = prompt.showAndWait();
        promptDialog = null;
        if (answer.isEmpty()) return;
        if (answer.get().equals(newChoice)) newCategory();
        else setCategory(answer.get().equals(noneChoice) ? "" : answer.get());
    }

    /** The "no category" entry of the choice and the submenu; read at use, since the language is chosen at start. */
    private static String noneChoice() {
        return tr("common.none");
    }

    /** The "make a new category" entry of the choice and the submenu. */
    private static String newChoice() {
        return tr("window.category.new");
    }

    /** Asks for a name, makes the category, files the selection under it. */
    private void newCategory() {
        Platform.runLater(this::showNewCategoryPrompt);
    }

    private void showNewCategoryPrompt() {
        if (promptDialog != null && promptDialog.isShowing()) return;
        javafx.scene.control.TextInputDialog prompt = new javafx.scene.control.TextInputDialog();
        prompt.initOwner(stage);
        Fonts.installUiFont(prompt.getDialogPane());
        prompt.setTitle(tr("window.newCategory.title"));
        prompt.setHeaderText(tr("window.newCategory.header"));
        prompt.setContentText(tr("window.newCategory.name"));
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
        String subject = selected.size() == 1 ? selected.get(0).name() : tr("window.torrentsCount", selected.size());
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
        onTorrents(tr("actions.verb.tagsSet"), selected, (e, t) -> {
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
                    tr("actions.verb.categoryCleared"),
                    carrying(SidebarFilter.category(name)),
                    (e, t) -> e.setCategory(t.id(), ""));
            labelsChanged();
        }

        @Override
        public void tagDeleted(String name) {
            onTorrents(tr("actions.verb.tagRemoved"), carrying(SidebarFilter.tag(name)), (e, t) -> {
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
        filtered.setPredicate(rowFilter(omnibox.query(), sidebarSelection));
    }

    private void setSidebarVisible(boolean visible) {
        sidebar.setVisible(visible);
    }

    private void focusSidebar() {
        if (!sidebar.isVisible()) setSidebarVisible(true);
        sidebar.focus();
    }

    /** {@code key} names the status sentence ({@code status.copied.*}), given the selection's size. */
    private void copyToClipboard(String key, Function<Torrent, String> text) {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join("\n", selected.stream().map(text).toList()));
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(tr(key, selected.size()));
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
                setStatus(tr("status.copied.magnet", links.size()));
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

    /** The Queue submenu's commands, top to bottom. */
    private static final List<String> QUEUE_COMMANDS = List.of("queue-top", "queue-up", "queue-down", "queue-bottom");

    private static KeyCombination chord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    private static KeyCombination shiftChord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private List<Command> buildCommands() {
        BooleanBinding selection = Bindings.isNotEmpty(table.getSelectionModel().getSelectedItems());
        BooleanBinding always = Bindings.createBooleanBinding(() -> true);
        // A torrent added with "Add now, pick files when ready" until its files have been chosen;
        // the settings are not observable, so a change of mark bumps pickFilesMarks.
        BooleanBinding filesToChoose = Bindings.createBooleanBinding(
                () -> Actions.canChooseFiles(
                        table.getSelectionModel().getSelectedItems().stream()
                                .map(TorrentRow::id)
                                .toList(),
                        settings::filesToPick),
                table.getSelectionModel().getSelectedItems(),
                pickFilesMarks);
        List<Command> all = new ArrayList<>(List.of(
                new Command(
                        "create",
                        tr("command.create"),
                        chord(KeyCode.N),
                        null,
                        gate("create", always),
                        this::createTorrent),
                new Command("add-file", tr("command.addFile"), chord(KeyCode.O), null, always, this::addTorrentFile),
                new Command("add-magnet", tr("command.addMagnet"), chord(KeyCode.M), null, always, this::addMagnetLink),
                // The chord is a scene accelerator, not a menu one: Ctrl+V in a text field must paste.
                new Command(
                        "add-clipboard",
                        tr("command.addClipboard"),
                        null,
                        chord(KeyCode.V).getDisplayText(),
                        always,
                        this::addFromClipboard),
                new Command("pause-resume", tr("command.pauseResume"), null, "Space", selection, this::togglePause),
                new Command(
                        "force-resume",
                        tr("command.forceResume"),
                        shiftChord(KeyCode.U),
                        null,
                        selection,
                        () -> onSelection(tr("actions.verb.forced"), (e, t) -> e.forceResume(t.id()))),
                new Command(
                        "recheck",
                        tr("command.recheck"),
                        shiftChord(KeyCode.R),
                        null,
                        selection,
                        () -> onSelection(tr("actions.verb.rechecking"), (e, t) -> e.recheck(t.id()))),
                new Command(
                        "reannounce",
                        tr("command.reannounce"),
                        shiftChord(KeyCode.A),
                        null,
                        selection,
                        () -> onSelection(tr("actions.verb.reannounced"), (e, t) -> e.reannounce(t.id()))),
                new Command(
                        "sequential",
                        tr("command.sequential"),
                        shiftChord(KeyCode.S),
                        null,
                        gate("sequential", selection),
                        this::toggleSequential),
                new Command("limits", tr("command.limits"), chord(KeyCode.L), null, selection, this::editLimits),
                // The four chords are also taken on the table itself (onTableChord): its own
                // Ctrl+Shift+arrow bindings would consume them before the accelerators saw them.
                new Command(
                        "queue-top",
                        tr("command.queueTop"),
                        shiftChord(KeyCode.HOME),
                        null,
                        gate("queue-top", selection),
                        () -> queueMove(Engine.QueueMove.TOP)),
                new Command(
                        "queue-up",
                        tr("command.queueUp"),
                        shiftChord(KeyCode.UP),
                        null,
                        gate("queue-up", selection),
                        () -> queueMove(Engine.QueueMove.UP)),
                new Command(
                        "queue-down",
                        tr("command.queueDown"),
                        shiftChord(KeyCode.DOWN),
                        null,
                        gate("queue-down", selection),
                        () -> queueMove(Engine.QueueMove.DOWN)),
                new Command(
                        "queue-bottom",
                        tr("command.queueBottom"),
                        shiftChord(KeyCode.END),
                        null,
                        gate("queue-bottom", selection),
                        () -> queueMove(Engine.QueueMove.BOTTOM)),
                new Command(
                        "set-location",
                        tr("command.setLocation"),
                        shiftChord(KeyCode.D),
                        null,
                        gate("set-location", selection),
                        this::setLocation),
                new Command(
                        "choose-files",
                        tr("command.chooseFiles"),
                        null,
                        null,
                        filesToChoose,
                        () -> selectedTorrents().stream().findFirst().ifPresent(t -> chooseFiles(t.id()))),
                new Command(
                        "set-category",
                        tr("command.setCategory"),
                        shiftChord(KeyCode.G),
                        null,
                        selection,
                        this::chooseCategory),
                new Command("edit-tags", tr("command.editTags"), chord(KeyCode.T), null, selection, this::editTags),
                new Command("manage-categories", tr("command.manageCategories"), null, null, always, () -> {
                    settingsWindow.show(stage);
                    settingsWindow.selectPageForCapture("LABELS");
                }),
                new Command(
                        "copy-name",
                        tr("command.copyName"),
                        chord(KeyCode.C),
                        null,
                        selection,
                        () -> copyToClipboard("status.copied.name", Torrent::name)),
                new Command(
                        "copy-hash",
                        tr("command.copyHash"),
                        shiftChord(KeyCode.H),
                        null,
                        selection,
                        () -> copyToClipboard("status.copied.infoHash", Torrent::id)),
                new Command(
                        "copy-magnet",
                        tr("command.copyMagnet"),
                        shiftChord(KeyCode.C),
                        null,
                        selection,
                        this::copyMagnetLinks),
                new Command(
                        "open-folder",
                        tr("command.openFolder"),
                        shiftChord(KeyCode.O),
                        null,
                        gate("open-folder", selection),
                        this::openFolder),
                new Command("remove", tr("command.remove"), null, "Delete", selection, () -> remove(false)),
                new Command(
                        "remove-data", tr("command.removeData"), null, "Shift+Delete", selection, () -> remove(true)),
                new Command("check-updates", tr("command.checkUpdates"), null, null, always, this::checkForUpdatesNow),
                new Command("about", tr("command.about", AppInfo.NAME), null, null, always, this::showAbout),
                new Command(
                        "settings",
                        tr("command.settings"),
                        chord(KeyCode.COMMA),
                        null,
                        always,
                        () -> settingsWindow.show(stage)),
                new Command("switch-profile", tr("command.switchProfile"), null, null, always, this::chooseProfile),
                new Command(
                        "retry-engine", tr("command.retryEngine"), null, null, engineDown, () -> startEngine(profile)),
                new Command("search", tr("command.search"), shiftChord(KeyCode.F), null, always, this::showSearch),
                new Command("rss", tr("command.rss"), chord(KeyCode.R), null, always, this::showRss),
                new Command("rss-run-rules", tr("command.rssRunRules"), null, null, always, rss::runRulesNow),
                new Command("transfers", tr("command.transfers"), null, "Esc", always, this::showTransfers),
                new Command(
                        "filter",
                        tr("command.filter"),
                        null,
                        chord(KeyCode.K).getDisplayText(),
                        always,
                        this::focusFilter),
                new Command(
                        "toggle-density",
                        tr("command.toggleDensity"),
                        null,
                        null,
                        always,
                        () -> chooseDensity(density.other())),
                new Command(
                        "select-all",
                        tr("command.selectAll"),
                        null,
                        chord(KeyCode.A).getDisplayText(),
                        always,
                        () -> table.getSelectionModel().selectAll()),
                new Command(
                        "event-log",
                        tr("command.eventLog"),
                        null,
                        shiftChord(KeyCode.L).getDisplayText(),
                        always,
                        () -> logWindow.show()),
                new Command(
                        "statistics",
                        tr("command.statistics"),
                        null,
                        shiftChord(KeyCode.T).getDisplayText(),
                        always,
                        () -> statsWindow.show()),
                new Command(
                        "message-log",
                        tr("command.messageLog"),
                        null,
                        shiftChord(KeyCode.M).getDisplayText(),
                        always,
                        () -> messagePopup.toggle()),
                new Command("debug-log", tr("command.debugLog"), null, null, always, this::showDebugLog),
                new Command(
                        "toggle-toolbar",
                        tr("command.toggleToolbar"),
                        null,
                        null,
                        always,
                        () -> toolbar.setVisible(!toolbar.isVisible())),
                new Command(
                        "toggle-sidebar",
                        tr("command.toggleSidebar"),
                        shiftChord(KeyCode.B),
                        null,
                        always,
                        () -> setSidebarVisible(!sidebar.isVisible())),
                new Command(
                        "focus-sidebar",
                        tr("command.focusSidebar"),
                        chord(KeyCode.B),
                        null,
                        always,
                        this::focusSidebar)));
        // One "Show: …" per status, and Show: All, so a filter is picked without the mouse.
        all.add(new Command(
                "show-all", tr("command.showAll"), null, null, always, () -> chooseFilter(SidebarFilter.ALL)));
        for (SidebarStatus status : SidebarStatus.values()) {
            all.add(new Command(
                    "show-" + status.key(),
                    tr("command.show", status.title()),
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
        paletteButton = Icons.toolbarButton(
                Icons.palette(),
                paletteButtonName(),
                () -> palette.show(CapabilityRules.commands(commands, capabilities)));
        // Settings alone at the right end, as Editora keeps it; the palette with the other views.
        toolbar = new ToolBar(
                toolButton("create", Icons.newFile()),
                toolButton("add-file", Icons.open()),
                toolButton("add-magnet", Icons.magnet()),
                new Separator(),
                toolButton("pause-resume", Icons.pause()),
                toolButton("force-resume", Icons.forceRun()),
                toolButton("recheck", Icons.refresh()),
                toolButton("remove", Icons.trash()),
                new Separator(),
                toolButton("filter", Icons.find()),
                toolButton("search", Icons.search()),
                toolButton("rss", Icons.rss()),
                toolButton("event-log", Icons.history()),
                paletteButton,
                spacer,
                toolButton("settings", Icons.settings()));
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
        if (!c.hint().isEmpty()) name = tr("toolbar.withChord", name, c.hint());
        Button b = Icons.toolbarButton(icon, name, c.run());
        b.disableProperty().bind(Bindings.not(c.enabled()));
        commandButtons.put(id, b);
        return b;
    }

    private Command command(String id) {
        return commands.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * A menu item for a command, with the command's glyph beside it (see {@link Icons#forCommand}),
     * remembered so a missing capability can hide every copy of it.
     */
    private MenuItem menuItem(String id) {
        MenuItem item = command(id).menuItem();
        item.setGraphic(menuIcon(Icons.forCommand(id)));
        commandItems.computeIfAbsent(id, k -> new ArrayList<>()).add(item);
        return item;
    }

    /** A glyph at the menus' size, or null for none. */
    private static javafx.scene.Node menuIcon(Icons.Glyph glyph) {
        return glyph == null ? null : glyph.at(Icons.MENU_SIZE);
    }

    /**
     * A command's enabled state behind its capability, when it needs one: off for an engine that
     * lacks it, so the menu item, the toolbar button, the chord and the palette entry are all dead.
     */
    private ObservableBooleanValue gate(String id, ObservableBooleanValue enabled) {
        return CapabilityRules.forCommand(id)
                .<ObservableBooleanValue>map(c -> Bindings.and(enabled, capabilityOn.get(c)))
                .orElse(enabled);
    }

    /**
     * FX thread. Hides what the engine cannot do: commands (menus, toolbar, chords, palette), the
     * dialogs' choices, the Settings rows and pages. Hidden, never greyed, never imitated.
     */
    private void applyCapabilities(Engine.Capabilities caps) {
        capabilities = caps;
        capabilityOn.forEach((c, on) -> on.set(caps.has(c)));
        commandItems.forEach((id, items) -> {
            boolean shown = CapabilityRules.commandShown(id, caps);
            for (MenuItem item : items) item.setVisible(shown);
        });
        commandButtons.forEach((id, button) -> {
            boolean shown = CapabilityRules.commandShown(id, caps);
            button.setVisible(shown);
            button.setManaged(shown);
        });
        detail.applyCapabilities(caps);
        settingsWindow.applyCapabilities(caps);
    }

    /** The capabilities the engine lacks, one line, for the capture hook; empty for libtorrent. */
    public String capabilitiesLine() {
        return CapabilityRules.missingLine(capabilities);
    }

    /** The profile in use, without its password; for the capture hook. */
    public String profileDescription() {
        return profile.toString();
    }

    /**
     * The queue chords, taken before the table's own behaviour sees them: TableView binds
     * Ctrl+Shift+Up/Down/Home/End to discontinuous selection and consumes them, so the menu
     * accelerators would never fire while the list has the focus, which is nearly always.
     */
    private void onTableChord(KeyEvent e) {
        for (String id : QUEUE_COMMANDS) {
            Command c = command(id);
            if (c.accelerator() != null && c.accelerator().match(e)) {
                if (c.enabled().get()) c.run().run();
                e.consume();
                return;
            }
        }
    }

    /** Space and Delete: on the table only, never as scene accelerators a text field could fire. */
    private void onTableKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            if (escapeOnList()) e.consume();
        } else if (e.getCode() == KeyCode.SPACE) {
            command("pause-resume").run().run();
            e.consume();
        } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            command(e.isShiftDown() ? "remove-data" : "remove").run().run();
            e.consume();
        }
    }

    private MenuBar buildMenuBar() {
        MenuItem addTorrent = menuItem("add-file");
        MenuItem addMagnet = menuItem("add-magnet");
        MenuItem addClipboard = menuItem("add-clipboard");

        MenuItem quit = new MenuItem(tr("menu.file.quit"));
        quit.setGraphic(menuIcon(Icons.power()));
        quit.setAccelerator(chord(KeyCode.Q));
        quit.setOnAction(e -> quit());

        Menu file = new Menu(tr("menu.file"));
        file.getItems()
                .addAll(
                        menuItem("create"),
                        new SeparatorMenuItem(),
                        addTorrent,
                        addMagnet,
                        addClipboard,
                        new SeparatorMenuItem(),
                        menuItem("search"),
                        menuItem("rss"),
                        menuItem("rss-run-rules"),
                        menuItem("settings"),
                        menuItem("switch-profile"),
                        new SeparatorMenuItem(),
                        quit);

        Menu torrent = new Menu(tr("menu.torrent"));
        torrent.getItems().addAll(torrentItems());

        paletteItem = new MenuItem(tr("menu.view.commandPalette"));
        paletteItem.setGraphic(menuIcon(Icons.palette()));
        paletteItem.setAccelerator(paletteChord);
        paletteItem.setOnAction(e -> palette.show(CapabilityRules.commands(commands, capabilities)));
        CheckMenuItem toolbarItem = new CheckMenuItem(tr("menu.view.toolbar"));
        toolbarItem.setGraphic(menuIcon(Icons.toolbar()));
        toolbarItem.selectedProperty().bindBidirectional(toolbar.visibleProperty());
        CheckMenuItem sidebarItem = new CheckMenuItem(tr("menu.view.sidebar"));
        sidebarItem.setGraphic(menuIcon(Icons.sidebar()));
        sidebarItem.setAccelerator(shiftChord(KeyCode.B));
        sidebarItem.selectedProperty().bindBidirectional(sidebar.visibleProperty());
        for (Density d : Density.values()) {
            javafx.scene.control.RadioMenuItem item =
                    new javafx.scene.control.RadioMenuItem(tr("menu.view.densityRows", d.toString()));
            item.setGraphic(menuIcon(d == Density.COMFORTABLE ? Icons.rowsComfortable() : Icons.rowsCompact()));
            item.setToggleGroup(densityGroup);
            item.setSelected(d == density);
            item.setOnAction(e -> chooseDensity(d));
            densityItems.put(d, item);
        }
        Menu view = new Menu(tr("menu.view"));
        view.getItems()
                .addAll(
                        toolbarItem,
                        sidebarItem,
                        new SeparatorMenuItem(),
                        densityItems.get(Density.COMFORTABLE),
                        densityItems.get(Density.COMPACT),
                        new SeparatorMenuItem(),
                        menuItem("filter"),
                        menuItem("transfers"),
                        menuItem("focus-sidebar"),
                        menuItem("select-all"),
                        menuItem("event-log"),
                        menuItem("statistics"),
                        menuItem("message-log"),
                        menuItem("debug-log"),
                        paletteItem);

        // The two update items exist from the start and show only once a release is known, so the
        // menu's shape does not change under a hand that has learned it.
        downloadUpdateItem.setVisible(false);
        downloadUpdateItem.setGraphic(menuIcon(Icons.download()));
        downloadUpdateItem.setOnAction(e -> downloadUpdate());
        skipUpdateItem.setVisible(false);
        skipUpdateItem.setGraphic(menuIcon(Icons.skip()));
        skipUpdateItem.setOnAction(e -> skipUpdate());
        Menu help = new Menu(tr("menu.help"));
        help.getItems()
                .addAll(
                        menuItem("check-updates"),
                        downloadUpdateItem,
                        skipUpdateItem,
                        new SeparatorMenuItem(),
                        menuItem("about"));

        MenuBar bar = new MenuBar(file, torrent, view, help);
        bar.setUseSystemMenuBar(true);
        menuBar = bar;
        return bar;
    }

    /** The Torrent menu and the context menu show the same items in the same order. */
    private List<MenuItem> torrentItems() {
        List<MenuItem> items = new ArrayList<>();
        for (String id : List.of("pause-resume", "force-resume", "recheck", "reannounce", "sequential", "limits")) {
            items.add(menuItem(id));
        }
        items.add(queueMenu());
        items.add(menuItem("set-location"));
        items.add(menuItem("choose-files"));
        items.add(new SeparatorMenuItem());
        items.add(categoryMenu());
        items.add(menuItem("edit-tags"));
        items.add(new SeparatorMenuItem());
        for (String id : List.of("copy-name", "copy-hash", "copy-magnet", "open-folder")) {
            items.add(menuItem(id));
        }
        items.add(new SeparatorMenuItem());
        for (String id : List.of("remove", "remove-data")) items.add(menuItem(id));
        return items;
    }

    /** Queue as a submenu: the four moves, hidden with the whole submenu for an engine without a queue. */
    private Menu queueMenu() {
        Menu menu = new Menu(tr("menu.torrent.queue"));
        menu.setGraphic(menuIcon(Icons.forCommand("queue")));
        for (String id : QUEUE_COMMANDS) menu.getItems().add(menuItem(id));
        menu.disableProperty().bind(Bindings.not(command("queue-top").enabled()));
        menu.visibleProperty().bind(menu.getItems().get(0).visibleProperty());
        return menu;
    }

    /**
     * Set Category as a submenu: the categories that exist, with the selection's current one
     * checked, then None and New Category…. Rebuilt each time it opens, since the list changes.
     */
    private Menu categoryMenu() {
        Menu menu = new Menu(tr("menu.torrent.setCategory"));
        menu.setGraphic(menuIcon(Icons.forCommand("set-category")));
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
            MenuItem none = new MenuItem(noneChoice());
            none.setOnAction(a -> setCategory(""));
            MenuItem fresh = new MenuItem(newChoice());
            fresh.setOnAction(a -> newCategory());
            items.add(none);
            items.add(fresh);
            menu.getItems().setAll(items);
        });
        // Never empty, or the submenu arrow has nothing to open and some platforms drop it.
        menu.getItems().add(new MenuItem(noneChoice()));
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
        // The empty state never sizes the split: its height would push the divider down (see
        // DetailSplit). The list appearing puts the saved position back.
        empty.setMinHeight(0);
        empty.visibleProperty().addListener((o, was, now) -> {
            if (!now && detailWanted && !split.getDividers().isEmpty())
                split.setDividerPositions(dividerFromSettings());
        });
        table.visibleProperty().bind(Bindings.isNotEmpty(rows.list()));
        table.managedProperty().bind(table.visibleProperty());
        // The first torrent makes the table appear; it takes the focus then, unless the person is
        // typing a filter at that moment.
        table.visibleProperty().addListener((o, was, now) -> {
            if (now && !filter.isFocused() && !searchShowing() && !rssShowing()) table.requestFocus();
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
                    // The Speed tab's history starts with the first selection and is fed from the next poll.
                    if (detailId != null) torrentHistories.computeIfAbsent(detailId, k -> new SpeedHistory());
                    if (detailId == null && detailWanted) detail.clear();
                });
        detail.selectTab(settings.get(Settings.DETAIL_TAB, "general"));
        detail.setOnFilePriority((index, priority) -> {
            String id = detailId;
            if (id != null) onTorrent("status.prioritySet", id, (e, t) -> e.setFilePriority(t, index, priority));
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
            if (id != null) onTorrent("status.trackersAdded", id, (e, t) -> e.addTrackers(t, urls));
        });
        detail.setOnRemoveTracker(url -> {
            String id = detailId;
            if (id != null) onTorrent("status.trackerRemoved", id, (e, t) -> e.removeTracker(t, url));
        });
        detail.setOnReannounce(() -> {
            String id = detailId;
            if (id != null) onTorrent("status.reannounced", id, (e, t) -> e.reannounce(t));
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
        rssPane = new RssPane(new RssHost(), rss, settings);
        StackPane centre2 = new StackPane(centreWithSidebar, searchPane, rssPane);
        VBox.setVgrow(centre2, Priority.ALWAYS);
        return new VBox(centre2);
    }

    // ---- the Search tab -------------------------------------------------------------------------

    /** What the Search tab asks of the window. */
    private final class SearchHost implements SearchPane.Host {
        @Override
        public List<SearchProvider> enabledProviders() {
            List<SearchProvider> out = new ArrayList<>();
            for (TorznabEndpoint e : searchStore.enabled()) out.add(new TorznabProvider(e, searchClient));
            Set<String> off = settings.disabledSearchDefinitions();
            for (var d : definitionStore.definitions()) {
                if (!off.contains(d.id())) out.add(new DeclarativeProvider(d, searchClient));
            }
            return out;
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
            setStatus(text.startsWith("magnet:") ? tr("status.copied.theMagnet") : tr("status.copied.theLink"));
        }

        @Override
        public void leave() {
            showTransfers();
        }
    }

    private boolean searchShowing() {
        return searchPane != null && searchPane.isVisible();
    }

    // ---- the RSS tab ----------------------------------------------------------------------------

    /**
     * The fetch thread. A rule's add: the article's magnet, or its {@code .torrent} fetched, added
     * with the rule's save path, category and paused choice and the rest of the defaults; the
     * engine's refusal is the reason thrown.
     */
    private void downloadForRule(Rule rule, Article article) throws IOException {
        // The feeds due at start are fetched before the engine is up; the add waits for it, bounded.
        try {
            engineUp.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        Engine e = engine;
        if (e == null) throw new IOException(tr("status.engineNotRunning"));
        AddRequest.Source source = RssAdd.sourceOf(article, new TorrentFetch()::fetch);
        Optional<Path> categoryFolder = labels.category(rule.category()).flatMap(Category::path);
        try {
            e.add(RuleAdd.request(rule, source, addDefaults(), categoryFolder));
        } catch (EngineException ex) {
            throw new IOException(ex.getMessage() == null ? ex.toString() : ex.getMessage(), ex);
        }
    }

    /** The fetch thread: a rule's add, or its failure, into the log and the status bar. */
    private void reportRule(String message, boolean failed) {
        reportFromWorker(EngineEvent.session(failed ? EngineEvent.Level.WARNING : EngineEvent.Level.INFO, message));
    }

    /**
     * FX thread. The article's torrent added at once with the defaults, no dialog, and the article
     * marked downloaded: the context menu's Download.
     */
    private void downloadArticle(Article article) {
        Engine e = engine;
        if (e == null) {
            setStatus(tr("status.engineStarting"));
            return;
        }
        String fetching = RssAdd.fetchingText(article);
        if (!fetching.isEmpty()) setStatus(fetching);
        AddDialog.Defaults defaults = addDefaults();
        Thread.ofVirtual().name("rss-download").start(() -> {
            try {
                AddRequest.Source source = RssAdd.sourceOf(article, new TorrentFetch()::fetch);
                Torrent added = e.add(AddDialog.withoutAsking(source, defaults));
                rss.markDownloaded(article.feedId(), article.identity());
                Platform.runLater(() -> setStatus(tr("status.added", added.name())));
            } catch (EngineException | IOException | RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> setStatus(message));
            }
        });
    }

    /** What the RSS tab asks of the window. */
    private final class RssHost implements RssPane.Host {
        @Override
        public void add(Article article) {
            addFromArticle(article);
        }

        @Override
        public void download(Article article) {
            downloadArticle(article);
        }

        @Override
        public void createRule(Article article) {
            settingsWindow.show(stage);
            settingsWindow.createRuleFromTitle(article.displayTitle());
        }

        @Override
        public void openLink(String url) {
            if (!url.isEmpty()) opener.accept(url);
        }

        @Override
        public void copy(String text) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            setStatus(text.startsWith("magnet:") ? tr("status.copied.theMagnet") : tr("status.copied.theLink"));
        }

        @Override
        public void say(String text) {
            setStatus(text);
        }

        @Override
        public void leave() {
            showTransfers();
        }
    }

    private boolean rssShowing() {
        return rssPane != null && rssPane.isVisible();
    }

    /** Ctrl+R: the RSS tab in place of the transfers, the articles focused. */
    private void showRss() {
        showView(rssPane);
        rssPane.focusDefault();
    }

    /** One of the three centre views shown and managed, the other two neither. */
    private void showView(javafx.scene.Node view) {
        for (javafx.scene.Node n : List.of(transfersView, searchPane, rssPane)) {
            boolean on = n == view;
            n.setVisible(on);
            n.setManaged(on);
        }
    }

    /**
     * The shipped definition files into the folder, the ones not there yet; a failure is a log
     * line, not a failed start.
     */
    private void installExampleDefinitions() {
        try {
            definitionStore.installExamples();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not copy the example search definitions into " + definitionStore.dir(), e);
        }
    }

    /** FX thread: every definition file that did not load, said once in the log. */
    private void reportDefinitionProblems() {
        for (DefinitionStore.Entry entry : definitionStore.problems()) {
            if (reportedDefinitionProblems.add(entry.problem()))
                log.add(EngineEvent.session(EngineEvent.Level.WARNING, tr("log.definitionSkipped", entry.problem())));
        }
    }

    /** Ctrl+Shift+F: the Search tab in place of the transfers, the query field focused. */
    private void showSearch() {
        // The folder is re-read when the tab opens, so a file dropped in is a chip at once.
        if (definitionStore.refresh()) {
            reportDefinitionProblems();
            searchPane.reloadProviders();
        }
        showView(searchPane);
        searchPane.focusQuery();
    }

    /** Escape in the query: the transfers back, as they were. */
    private void showTransfers() {
        showView(transfersView);
        table.requestFocus();
    }

    /** The list that is showing takes the focus back: after a dialog, after the palette. */
    private void focusList() {
        if (searchShowing()) searchPane.focusResults();
        else if (rssShowing()) rssPane.focusArticles();
        else table.requestFocus();
    }

    /** An engine action on one torrent by id. */
    private interface IdAction {
        void apply(Engine engine, String id) throws EngineException;
    }

    /** {@code key} names the status sentence for success; the torrent's name is its one placeholder. */
    private void onTorrent(String key, String id, IdAction action) {
        if (engine == null) return;
        Engine e = engine;
        TorrentRow row = rows.get(id);
        String name = row == null ? id : row.torrent().name();
        Thread.ofVirtual().name("engine-detail-action").start(() -> {
            String text;
            try {
                action.apply(e, id);
                text = tr(key, name);
            } catch (EngineException ex) {
                text = ex.getMessage();
            }
            String outcome = text;
            Platform.runLater(() -> setStatus(outcome));
        });
    }

    /**
     * The omnibox: one box that filters (words and {@code key:value} chips), runs a command after
     * a {@code >}, and adds what is pasted into it. {@link Omnibox} holds the rules; this draws it.
     */
    private HBox buildFilterBar() {
        filter.setPromptText(tr("window.filter.prompt"));
        // Not traversable: the scene hands initial focus to the first traversable node, and while
        // the table is hidden (no torrents yet) that would be this box, so every keystroke on a
        // fresh launch would land in the filter. Ctrl+K reaches it; Tab does not need to.
        filter.setFocusTraversable(false);
        filter.textProperty().addListener((o, was, text) -> onOmniboxTyped(was, text));
        filter.setOnKeyPressed(this::onOmniboxKey);
        chipRow.setAlignment(Pos.CENTER_LEFT);
        chipRow.setVisible(false);
        chipRow.managedProperty().bind(chipRow.visibleProperty());
        HBox.setHgrow(filter, Priority.ALWAYS);
        // The box the redesign draws: a lens, the field with no chrome of its own, and the chord as
        // a key badge, read from the same chord the command and the menu use.
        Label badge = new Label(chord(KeyCode.K).getDisplayText());
        badge.getStyleClass().add("key-badge");
        HBox box = new HBox(Icons.find(), chipRow, filter, badge);
        box.getStyleClass().add("filter-box");
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox bar = new HBox(box);
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

    /** Every keystroke in the field goes through the value; the field is then redrawn from it. */
    private void onOmniboxTyped(String was, String text) {
        if (settingFilter) return;
        Omnibox next = omnibox.typed(text, java.time.Instant.now().getEpochSecond());
        if (next.mode() == Omnibox.Mode.COMMAND) {
            // The prefix typed: the palette is the command box, and takes what followed it.
            setOmnibox(next.withText(""));
            palette.show(CapabilityRules.commands(commands, capabilities), next.commandQuery());
            return;
        }
        if (next.mode() == Omnibox.Mode.ADD && Omnibox.pasted(was, text)) {
            setOmnibox(next.withText(""));
            addInput(text.trim());
            return;
        }
        setOmnibox(next);
    }

    private void onOmniboxKey(KeyEvent e) {
        switch (e.getCode()) {
            case BACK_SPACE -> {
                if (filter.getText().isEmpty() && !omnibox.chips().isEmpty()) {
                    setOmnibox(omnibox.backspaceOnEmpty());
                    e.consume();
                }
            }
            case ESCAPE -> {
                // One layer: a box with something in it empties; an empty one gives the list back.
                if (!omnibox.isEmpty()) setOmnibox(Omnibox.EMPTY);
                else table.requestFocus();
                e.consume();
            }
            case ENTER -> {
                if (omnibox.mode() == Omnibox.Mode.ADD) {
                    String input = filter.getText().trim();
                    setOmnibox(omnibox.withText(""));
                    addInput(input);
                } else {
                    // The matches are what the box was for: Enter hands them over, all selected,
                    // so the next key (Space, Delete, Ctrl+T …) acts on every one of them.
                    if (!omnibox.isEmpty()) table.getSelectionModel().selectAll();
                    table.requestFocus();
                }
                e.consume();
            }
            default -> {}
        }
    }

    /** The value changed: the field, the chips and the list follow it. */
    private void setOmnibox(Omnibox next) {
        omnibox = next;
        if (!filter.getText().equals(next.text())) {
            settingFilter = true;
            try {
                filter.setText(next.text());
                filter.positionCaret(next.text().length());
            } finally {
                settingFilter = false;
            }
        }
        List<FilterQuery.Chip> chips = next.chipViews(java.time.Instant.now().getEpochSecond());
        chipRow.getChildren().clear();
        for (int i = 0; i < chips.size(); i++) {
            FilterQuery.Chip chip = chips.get(i);
            Label label = new Label(chip.label());
            label.getStyleClass().add("filter-chip");
            if (!chip.valid()) label.getStyleClass().add("invalid");
            label.setTooltip(new javafx.scene.control.Tooltip(
                    chip.valid() ? chip.term() : tr("omnibox.chip.notUnderstood", chip.term())));
            int index = i;
            label.setOnMouseClicked(e -> setOmnibox(omnibox.removeChip(index)));
            chipRow.getChildren().add(label);
        }
        chipRow.setVisible(!chips.isEmpty());
        applyFilters();
    }

    /** Escape on the list peels one layer: the filter first, then the selection. */
    private boolean escapeOnList() {
        return switch (Actions.escape(
                !omnibox.isEmpty(), !table.getSelectionModel().isEmpty())) {
            case CLEAR_FILTER -> {
                setOmnibox(Omnibox.EMPTY);
                yield true;
            }
            case CLEAR_SELECTION -> {
                table.getSelectionModel().clearSelection();
                yield true;
            }
            case NOTHING -> false;
        };
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
        table.setPlaceholder(new Label(tr("window.table.noMatch")));
        table.getStyleClass().add("torrent-table");
        table.setOnKeyPressed(this::onTableKey);
        table.addEventFilter(KeyEvent.KEY_PRESSED, this::onTableChord);
        table.setRowFactory(t -> new StateRow());

        TableColumn<TorrentRow, RowView> name = nameColumn();
        TableColumn<TorrentRow, Double> progress =
                column("progress", tr("column.progress"), 110, true, Torrent::progress, Format::percent);
        progress.setCellFactory(ProgressBarTableCell.forTableColumn());
        table.getColumns()
                .addAll(List.of(
                        name,
                        column("size", tr("common.size"), 90, true, Torrent::size, v -> v == 0 ? "—" : Bytes.human(v)),
                        column("done", tr("column.done"), 90, false, Torrent::done, Bytes::human),
                        progress,
                        statusColumn(),
                        column("seeds", tr("column.seeds"), 70, true, Torrent::seeds, String::valueOf),
                        column("peers", tr("column.peers"), 70, true, Torrent::peers, String::valueOf),
                        column("down", tr("column.down"), 90, true, Torrent::downloadRate, Format::rate),
                        column("up", tr("column.up"), 90, true, Torrent::uploadRate, Format::rate),
                        column("eta", tr("column.eta"), 80, true, Format::etaSeconds, Format::eta),
                        column(
                                "ratio",
                                tr("column.ratio"),
                                70,
                                true,
                                t -> Format.ratioValue(t.uploaded(), t.downloaded()),
                                Format::ratio),
                        column("added", tr("column.added"), 130, false, Torrent::added, Format::dateTime),
                        column("completed", tr("column.completed"), 130, false, Torrent::completed, Format::dateTime),
                        column("tracker", tr("column.tracker"), 160, false, Torrent::tracker, Format::host),
                        column("category", tr("column.category"), 120, false, Torrent::category, s -> s),
                        column("tags", tr("column.tags"), 160, false, t -> Format.tags(t.tags()), s -> s),
                        column("path", tr("column.path"), 240, false, Torrent::savePath, s -> s),
                        column("queue", tr("column.queue"), 60, false, Torrent::queuePosition, Format::queuePosition)));

        Headers.uppercase(table);
        builtInLayout = ColumnLayout.format(ColumnMemory.capture(table));
        density = Density.byId(settings.density(), Density.DEFAULT);
        ColumnMemory.apply(table, ColumnLayout.parse(layoutFor(density)));
        styleForDensity(table);
        ColumnMemory.watch(table, () -> {
            settings.set(layoutKey(density), ColumnLayout.format(ColumnMemory.capture(table)));
            settings.saveQuietly();
        });
        return table;
    }

    private static String layoutKey(Density d) {
        return d == Density.COMFORTABLE ? Settings.TABLE_COLUMNS_COMFORTABLE : Settings.TABLE_COLUMNS;
    }

    /** The remembered layout for a density, else its default (compact's is the columns as built). */
    private String layoutFor(Density d) {
        String saved = settings.get(layoutKey(d), "");
        if (!saved.isBlank()) return saved;
        return d == Density.COMPACT ? builtInLayout : d.defaultLayout();
    }

    private void styleForDensity(TableView<TorrentRow> t) {
        t.setFixedCellSize(density.cellSize() > 0 ? density.cellSize() : Region.USE_COMPUTED_SIZE);
        t.getStyleClass().removeAll("comfortable", "compact");
        t.getStyleClass().add(density.id());
    }

    /**
     * Switches the rows' density: the columns as they are now are kept for the density being left,
     * the other density's own come back, and the cells redraw. The menu follows.
     */
    private void applyDensity(Density next) {
        densityItems
                .getOrDefault(next, new javafx.scene.control.RadioMenuItem())
                .setSelected(true);
        if (next == density) return;
        settings.set(layoutKey(density), ColumnLayout.format(ColumnMemory.capture(table)));
        density = next;
        styleForDensity(table);
        ColumnMemory.apply(table, ColumnLayout.parse(layoutFor(next)));
        // A refresh() reuses the cells, and a progress cell built at the other height keeps its
        // bar where that height put it; a new factory makes new cells.
        for (TableColumn<TorrentRow, ?> column : table.getColumns()) renewCells(column);
        table.refresh();
        settings.saveQuietly();
    }

    private static <T> void renewCells(TableColumn<TorrentRow, T> column) {
        var factory = column.getCellFactory();
        column.setCellFactory(c -> null);
        column.setCellFactory(factory);
    }

    /** From the menu or the palette: remembered, then applied. */
    private void chooseDensity(Density next) {
        settings.setDensity(next.id());
        settings.saveQuietly();
        applyDensity(next);
    }

    /** Everything Settings → Appearance drives in this window. */
    private void applyAppearance() {
        applyStateColors();
        applyDensity(Density.byId(settings.density(), Density.DEFAULT));
        applyWindowStyle();
        applyPaletteKey();
    }

    /**
     * The window buttons' scheme (a dark theme wants light glyphs, whatever the desktop says), and
     * the one appearance setting that cannot change live: the window's style is fixed once shown,
     * so a setting that disagrees with the window is said in the status bar instead.
     */
    private void applyWindowStyle() {
        ExtendedWindow.matchButtonsToTheme(
                stage, Theme.byId(settings.theme(), Theme.DEFAULT).isDark());
        boolean wanted = ExtendedWindow.enabled(settings.extendedWindow(), System.getProperty("os.name"));
        if (ExtendedWindow.restartNeeded(wanted, stage.getStyle() == StageStyle.EXTENDED)) {
            setStatus(tr("status.restartWindowStyle"));
        }
    }

    private String paletteButtonName() {
        return tr("toolbar.palette.tooltip", paletteChord.getDisplayText());
    }

    /**
     * The palette's chord, re-read from the settings: the accelerator moves, and the menu item, the
     * toolbar tooltip and the palette's chip say the new one. Before the scene exists (the
     * constructor's first pass) there is no accelerator to move; the scene is built with the chord.
     */
    private void applyPaletteKey() {
        KeyCombination wanted =
                PaletteKey.byId(settings.paletteKey(), PaletteKey.DEFAULT).chord();
        if (wanted.equals(paletteChord)) return;
        Scene scene = host.getScene();
        if (scene != null) {
            scene.getAccelerators().remove(paletteChord);
            scene.getAccelerators().put(wanted, () -> palette.show(CapabilityRules.commands(commands, capabilities)));
        }
        paletteChord = wanted;
        paletteItem.setAccelerator(wanted);
        Icons.name(paletteButton, paletteButtonName());
        palette.setOpenerChord(wanted.getDisplayText());
    }

    /**
     * The Name column's value: the name, plus what a comfortable row draws under it. A value, not
     * the snapshot, so the cell redraws exactly when what it shows changes (the stall clock moves a
     * sentence on while the snapshot stays equal). Sorts by name.
     */
    record RowView(String name, StatusLine line, double progress) {}

    private TableColumn<TorrentRow, RowView> nameColumn() {
        TableColumn<TorrentRow, RowView> column = new TableColumn<>(tr("common.name"));
        column.setId("name");
        column.setPrefWidth(360);
        column.setCellValueFactory(cell -> {
            TorrentRow row = cell.getValue();
            return row.torrentProperty()
                    .map(t -> new RowView(
                            t.name(), StatusLine.of(t, row.quietSeconds(), shareLimitFor(t)), t.progress()));
        });
        column.setComparator(java.util.Comparator.comparing(RowView::name, String.CASE_INSENSITIVE_ORDER));
        column.setCellFactory(c -> new NameCell());
        return column;
    }

    /**
     * Compact: the name. Comfortable: the state's glyph beside two lines, the name over a thin bar
     * and the status sentence; the bar and the glyph take the row's state colour from app.css.
     */
    private final class NameCell extends TableCell<TorrentRow, RowView> {
        private final Label glyph = new Label();
        private final Label name = new Label();
        private final javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar();
        private final Label lead = new Label();
        private final Label rest = new Label();
        private final HBox two;

        NameCell() {
            glyph.getStyleClass().addAll("state-glyph", "row-glyph");
            name.getStyleClass().add("row-name");
            bar.getStyleClass().add("row-bar");
            lead.getStyleClass().add("state-lead");
            rest.getStyleClass().add("state-rest");
            lead.setMinWidth(Region.USE_PREF_SIZE);
            HBox sub = new HBox(lead, rest);
            sub.setAlignment(Pos.CENTER_LEFT);
            HBox under = new HBox(10, bar, sub);
            under.setAlignment(Pos.CENTER_LEFT);
            VBox lines = new VBox(3, name, under);
            lines.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(lines, Priority.ALWAYS);
            lines.setMinWidth(0);
            two = new HBox(8, glyph, lines);
            two.setAlignment(Pos.CENTER_LEFT);
            two.getStyleClass().add("row-two-lines");
        }

        @Override
        protected void updateItem(RowView item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (density == Density.COMPACT) {
                setGraphic(null);
                setText(item.name());
            } else {
                setText(null);
                glyph.setText(item.line().state().glyph());
                name.setText(item.name());
                bar.setProgress(item.progress());
                lead.setText(item.line().lead());
                rest.setText(item.line().rest());
                setGraphic(two);
            }
        }
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
        TableColumn<TorrentRow, StatusLine> column = new TableColumn<>(tr("column.state"));
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
        Label title = new Label(tr("window.empty.title"));
        title.getStyleClass().add("empty-title");
        Label hint = new Label(tr("window.empty.hint"));
        hint.getStyleClass().add("empty-hint");
        // A translation runs longer than the English line; a paragraph, never an ellipsis.
        hint.setWrapText(true);
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        hint.setMaxWidth(560);
        // When the engine could not start: the reason, and the two ways out. Hidden otherwise, so
        // the empty state keeps its shape; the palette offers both as commands as well.
        engineProblemText = new Label();
        engineProblemText.getStyleClass().addAll("empty-hint", "engine-problem");
        engineProblemText.setWrapText(true);
        engineProblemText.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        engineProblemText.setMaxWidth(560);
        Button retry = new Button(tr("window.engine.retry"));
        retry.setFocusTraversable(false);
        retry.setOnAction(e -> startEngine(profile));
        Button other = new Button(tr("window.engine.switch"));
        other.setFocusTraversable(false);
        other.setOnAction(e -> chooseProfile());
        HBox ways = new HBox(8, retry, other);
        ways.setAlignment(Pos.CENTER);
        engineProblem = new VBox(10, engineProblemText, ways);
        engineProblem.setAlignment(Pos.CENTER);
        engineProblem.setPadding(new Insets(12, 0, 0, 0));
        engineProblem.setVisible(false);
        engineProblem.setManaged(false);
        VBox box = new VBox(8, title, hint, engineProblem);
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
                        () -> tr("status.errors", log.errorsProperty().get()), log.errorsProperty()));
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
        altSpeedToggle.setText(tr("status.altSpeed.off"));
        altSpeedToggle.setOnMouseClicked(e -> toggleAlternativeSpeed());
        // The status text is the door to its own history: a click shows what scrolled past.
        status.getStyleClass().add("status-message");
        status.setOnMouseClicked(e -> messagePopup.toggle());
        HBox bar = new HBox(12, status, spacer, altSpeedToggle, updateBadge, syncBadge, errorBadge, rates);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static String ratesText(Engine.Rates r) {
        return tr("status.rates", Bytes.rate(r.download()), Bytes.rate(r.upload()));
    }

    private void setStatus(String text) {
        setStatus(text, EngineEvent.Level.INFO);
    }

    /**
     * The status bar says {@code text}, coloured by {@code level}, and the message log keeps it.
     * One line replaces the next, so an error that lands while nobody is looking is still in the
     * log, in red, when the status bar is clicked.
     */
    private void setStatus(String text, EngineEvent.Level level) {
        status.setText(text);
        status.getStyleClass().removeAll("status-warning", "status-error");
        if (level == EngineEvent.Level.ERROR) status.getStyleClass().add("status-error");
        else if (level == EngineEvent.Level.WARNING) status.getStyleClass().add("status-warning");
        messages.add(text, level, java.time.Instant.now());
    }

    /** The debug log's window, built the first time it is asked for. */
    private void showDebugLog() {
        if (debugLogWindow == null) debugLogWindow = new DebugLogWindow(stage);
        debugLogWindow.show();
    }
}
