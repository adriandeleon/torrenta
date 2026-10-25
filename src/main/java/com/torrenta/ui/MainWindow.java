package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
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
import com.torrenta.engine.Connectivity;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.Inputs;
import com.torrenta.engine.LinkRequest;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.SwarmPreview;
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
import com.torrenta.sync.SyncLabels;
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
    /** The status bar's rates, each with its last minute drawn beside it. */
    private final Label downRate = new Label();

    private final Label upRate = new Label();
    private final Sparkline downSpark = new Sparkline(Sparkline.Series.DOWN, 64, 14);
    private final Sparkline upSpark = new Sparkline(Sparkline.Series.UP, 64, 14);
    // The status bar's network half (NetworkStatus): port with its dot, DHT, free space.
    private final Label portStatus = new Label();
    private final Region portDot = new Region();
    private final Tooltip portTip = new Tooltip();
    private final Label dhtStatus = new Label();
    private final Label freeStatus = new Label();
    private final Tooltip freeTip = new Tooltip();
    /** Poll thread only: running time on an unproven port, and the last free-space reading. */
    private final NetworkStatus.Clock portClock = new NetworkStatus.Clock();

    /** 0 until the first reading. */
    private long freeCheckedAt;

    private Optional<NetworkStatus.Free> free = Optional.empty();
    private final TorrentRows rows = new TorrentRows();
    private final FilteredList<TorrentRow> filtered = new FilteredList<>(rows.list());
    private final TextField filter = new TextField();
    /** The omnibox as a value: chips and text; the field and the chip row draw it. */
    private Omnibox omnibox = Omnibox.EMPTY;
    /** The omnibox holds a query that Save Filter as View can keep. */
    private final javafx.beans.property.BooleanProperty filterSaveable =
            new javafx.beans.property.SimpleBooleanProperty();
    /** The last poll's torrents, for a sidebar refresh that cannot wait for the next one. */
    private List<Torrent> lastSnapshot = List.of();
    /** A name the capture hook types into Save Filter as View's prompt; null for a person's. */
    private String saveViewForCapture;

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
    /** The window's bounds when last neither maximised nor minimised; what is saved. */
    private WindowGeometry.Bounds normalBounds;
    /**
     * The last {@link SpeedHistory#RECENT_SECONDS} of every torrent's rates, by id: a few KiB each,
     * so the Speed tab is not empty the first time a torrent is chosen. Gone with the torrent.
     */
    private final java.util.concurrent.ConcurrentMap<String, SpeedHistory> recentHistories =
            new java.util.concurrent.ConcurrentHashMap<>();
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
    /** The table and, over it until the first torrent, the welcome. */
    private StackPane centre;

    private Welcome welcome;
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

    private LinkDialog linkDialog;
    /** Links that arrived while the Add Link dialog was open, each asked about in turn. */
    private final java.util.ArrayDeque<String> linksWaiting = new java.util.ArrayDeque<>();
    /** A name the capture hook types into the Add Link dialog when it opens. */
    private String linkNameForCapture;
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
    /** The transfers (sidebar, filter, list, detail), Search and RSS share the centre. */
    private javafx.scene.Node transfersView;
    /** The centre's panes by view: the one for {@link #view} is shown and managed, the rest neither. */
    private final Map<View, javafx.scene.Node> panes = new EnumMap<>(View.class);
    /** The view showing; FX thread. */
    private View view = View.TRANSFERS;
    /** The row above the centre that says which view is showing. */
    private ViewStrip viewStrip;

    private final UpdateService updates = new UpdateService();
    private final SettingsWindow settingsWindow;
    private final IpFilterController ipFilter;
    private final MenuItem downloadUpdateItem = new MenuItem();
    private MenuBar menuBar;
    private final MenuItem skipUpdateItem = new MenuItem();
    private final Label updateBadge = new Label();
    private final Label altSpeedToggle = new Label();
    /** View → Alternative Speed Limits, checked while they are in force. */
    private CheckMenuItem altSpeedItem;
    /** Whether the alternative limits were in force at the last refresh, on the FX thread. */
    private boolean altSpeedOn;

    private ReleaseInfo availableUpdate;
    /** A check is running; and the last one's failure this session, null when it answered. */
    private boolean updateChecking;

    private String updateError;
    private SettingsSync sync;
    private final Label syncBadge = new Label(tr("status.syncProblem"));
    private Alert aboutDialog;
    /** On the sidebar when it sits on the right: the rule faces the list. */
    private static final javafx.css.PseudoClass BESIDE = javafx.css.PseudoClass.getPseudoClass("right");

    private SplitPane split;
    /** The sidebar's frame: it sits on this pane's left or right. */
    private BorderPane transfersFrame;
    /** Where the detail pane is now; {@link #setDetailPlace} moves it. */
    private DetailPlace detailPlace = DetailPlace.HIDDEN;

    private CheckMenuItem detailItem;
    private CheckMenuItem detailBottomItem;
    private CheckMenuItem sidebarRightItem;
    private volatile String detailId;
    private volatile boolean detailWanted;
    /** A {@link #refreshDetailNow} waiting on the poll thread; later requests ride on it. */
    private final java.util.concurrent.atomic.AtomicBoolean detailRefreshQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
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
    /** Torrents whose content-check mark is being handled, so a tick does not handle one twice. */
    private final Set<String> screening = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        installWelcome();
        // The strip is built with the centre, before the chords it shows exist.
        viewStrip.showChords(v -> Chords.view(v).getDisplayText());
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
        // The detail header's buttons and the diagnosis's remedies run the same commands.
        detail.setCommands(
                id -> commands.stream().filter(c -> c.id().equals(id)).findFirst());
        detail.setMoreMenu(buildContextMenu());
        detail.setEvents(log.events());
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
        sync = new SettingsSync(settings, labels, configDir, new SyncHost());
        settingsWindow.setSync(sync);
        sync.addListener(() -> {
            boolean problem = sync.state().phase() == SettingsSync.Phase.PROBLEM;
            syncBadge.setVisible(problem);
            syncBadge.setManaged(problem);
        });
        // Every save (a Settings change, a column drag) is a reason to sync soon; the service
        // ignores its own. Saves happen on the FX thread, but the listener does not assume it.
        settings.setOnSaved(() -> Platform.runLater(() -> {
            sync.markDirty();
            // The download folder may have moved in Settings; the welcome's footer names it.
            showDownloadFolder(welcome);
        }));
        // So is a category or tag added or deleted; the lists travel with the settings.
        labels.setOnChanged(() -> Platform.runLater(sync::markDirty));
        detail.setOnBanPeer(this::banPeer);
        detail.setOnCopyPeer(address -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(address);
            Clipboard.getSystemClipboard().setContent(content);
            setStatus(tr("status.copied.address", address));
        });
        log.installLogBridge(Platform::runLater);
        reportDefinitionProblems();
        host.getChildren().add(root);

        WindowGeometry.Bounds opening = openingBounds();
        Scene scene = new Scene(host, opening.width(), opening.height());
        scene.getStylesheets()
                .add(MainWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.getAccelerators().put(Chords.of("filter-find"), this::focusFilter);
        scene.getAccelerators().put(Chords.of("filter"), this::focusFilter);
        scene.getAccelerators().put(paletteChord, () -> palette.show(CapabilityRules.commands(commands, capabilities)));
        scene.getAccelerators().put(Chords.of("event-log"), logWindow::show);
        scene.getAccelerators().put(Chords.of("statistics"), statsWindow::show);
        scene.getAccelerators().put(Chords.of("message-log"), messagePopup::toggle);
        scene.getAccelerators().put(Chords.of("add-clipboard"), this::addFromClipboard);
        // Set Category is a submenu, and a submenu carries no accelerator: the chord is the scene's.
        scene.getAccelerators().put(Chords.of("set-category"), this::chooseCategoryByChord);
        // Ctrl+1, 2, 3: each view by its place on the strip, beside the views' own chords
        // (Esc, Ctrl+Shift+F, Ctrl+R), which the menus keep showing.
        for (View v : View.values()) scene.getAccelerators().put(Chords.view(v), () -> show(v));
        // Ctrl+Tab and Ctrl+Shift+Tab cycle the views. A filter, ahead of focus traversal, which
        // would otherwise take Ctrl+Tab out of the table; Control on every platform, since Cmd+Tab
        // is macOS's own application switcher and never reaches the window.
        // Shortcut+Alt+1…9 choose the saved views in order, unless a text field has the focus:
        // Ctrl+Alt is AltGr on many layouts, and what it types there is the field's.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) return;
            for (int n = 1; n <= 9; n++) {
                if (Chords.of("saved-view-" + n).match(e)) {
                    chooseSavedView(n);
                    e.consume();
                    return;
                }
            }
        });
        // Shortcut+Z takes back the newest undoable action, unless a text field has the focus: there
        // it is the field's own undo, which a scene accelerator would steal.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) return;
            if (Chords.of("undo").match(e)) {
                undoNewest();
                e.consume();
            }
        });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                show(e.isShiftDown() ? ViewSwitch.previous(view) : ViewSwitch.next(view));
                e.consume();
            }
        });
        scene.setOnDragOver(this::onDragOver);
        scene.setOnDragDropped(this::onDragDropped);
        scene.setOnDragExited(e -> welcome.setDragging(false));
        // A magnet copied elsewhere is the commonest way a torrent arrives; say so when the window
        // comes back to the front, once per link, and leave the adding to a keystroke.
        stage.focusedProperty().addListener((o, was, now) -> {
            if (now) {
                offerClipboard();
                refreshWelcomeClipboard();
            }
        });
        stage.setScene(scene);
        placeWindow(opening);
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
        showRates(Engine.Rates.ZERO, 0);
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

    // ---- the window's place ---------------------------------------------------------------------

    /**
     * Where the window opens: the saved bounds if a screen still shows them, else a first window
     * as wide as the sidebar and the default columns need, so the list opens without a
     * horizontal scrollbar.
     */
    private WindowGeometry.Bounds openingBounds() {
        List<WindowGeometry.Bounds> screens = javafx.stage.Screen.getScreens().stream()
                .map(sc -> bounds(sc.getVisualBounds()))
                .toList();
        WindowGeometry.Bounds primary = bounds(javafx.stage.Screen.getPrimary().getVisualBounds());
        double columns = table.getVisibleLeafColumns().stream()
                .mapToDouble(TableColumn::getPrefWidth)
                .sum();
        double side = settings.sidebar() ? sidebar.prefWidth(-1) : 0;
        return WindowGeometry.choose(
                WindowGeometry.parse(settings.windowBounds()),
                screens,
                primary,
                side + columns + WindowGeometry.LIST_CHROME,
                WindowGeometry.FIRST_RUN_HEIGHT);
    }

    private static WindowGeometry.Bounds bounds(javafx.geometry.Rectangle2D r) {
        return new WindowGeometry.Bounds(r.getMinX(), r.getMinY(), r.getWidth(), r.getHeight());
    }

    /**
     * Puts the stage where {@link #openingBounds} said, maximised if it was, and keeps the normal
     * bounds as they change. A maximise can move and resize the stage a pulse before it says it is
     * maximised, so a change is taken as the normal bounds only once it has held for a moment.
     */
    private void placeWindow(WindowGeometry.Bounds at) {
        stage.setX(at.x());
        stage.setY(at.y());
        stage.setWidth(at.width());
        stage.setHeight(at.height());
        normalBounds = at;
        if (settings.windowMaximized()) stage.setMaximized(true);
        javafx.animation.PauseTransition settle =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
        settle.setOnFinished(e -> {
            if (stage.isShowing() && !stage.isMaximized() && !stage.isIconified() && !stage.isFullScreen())
                normalBounds = stageBounds();
        });
        javafx.beans.InvalidationListener moved = o -> settle.playFromStart();
        stage.xProperty().addListener(moved);
        stage.yProperty().addListener(moved);
        stage.widthProperty().addListener(moved);
        stage.heightProperty().addListener(moved);
        stage.maximizedProperty().addListener(moved);
    }

    private WindowGeometry.Bounds stageBounds() {
        return new WindowGeometry.Bounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    /**
     * Saves where the window is, for the next start: on close, on a hide to the tray, and from
     * App.stop for any way out that skips both. The bounds as they are when the window is
     * plainly open, else the last normal ones.
     */
    public void rememberWindow() {
        boolean maximized = stage.isMaximized();
        WindowGeometry.Bounds normal = maximized || stage.isIconified() || stage.isFullScreen() || normalBounds == null
                ? normalBounds
                : stageBounds();
        if (normal == null || normal.width() < WindowGeometry.MIN_WIDTH || normal.height() < WindowGeometry.MIN_HEIGHT)
            return;
        settings.setWindow(WindowGeometry.format(normal), maximized);
        settings.saveQuietly();
    }

    /** The stage's bounds and state, and what is stored, for the capture hook. */
    private String windowLine() {
        return WindowGeometry.format(stageBounds()) + " maximized=" + stage.isMaximized() + " saved="
                + settings.windowBounds() + (settings.windowMaximized() ? " (maximized)" : "")
                + " hScroll=" + horizontalScrollbarShowing();
    }

    /** Whether the list's horizontal scrollbar shows: the first-run width is meant to avoid it. */
    private boolean horizontalScrollbarShowing() {
        for (javafx.scene.Node n : table.lookupAll(".scroll-bar")) {
            if (n instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                    && bar.isVisible()) return true;
        }
        return false;
    }

    private void hideToTray() {
        rememberDivider();
        rememberWindow();
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
        rememberWindow();
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

    /** The status bar's network entries as shown; read by the development capture. */
    /** The status bar's alternative-speed toggle and View's check item, for the capture hook. */
    public String altSpeedText() {
        return "\"" + altSpeedToggle.getText() + "\" checked=" + (altSpeedItem != null && altSpeedItem.isSelected());
    }

    public String networkStatusText() {
        String dot = portDot.getStyleClass().stream()
                .filter(c -> c.startsWith("port-") && !c.equals("port-dot"))
                .findFirst()
                .orElse("none");
        return "port=\"" + (portStatus.isVisible() ? portStatus.getText() : "") + "\" dot=" + dot + " dht=\""
                + (dhtStatus.isVisible() ? dhtStatus.getText() : "") + "\" free=\""
                + (freeStatus.isVisible() ? freeStatus.getText() : "") + "\" low="
                + freeStatus.getStyleClass().contains("free-low");
    }

    /**
     * Each shown status-bar entry as {@code name=width/needed} (an entry narrower than it needs is
     * elided), then the bar's right edge against the window's, for the capture hook.
     */
    private String statusBarFit() {
        StringBuilder out = new StringBuilder();
        java.util.Map<String, Region> named = new java.util.LinkedHashMap<>();
        named.put("port", portStatus);
        named.put("dht", dhtStatus);
        named.put("message", status);
        // Each rate is a box of the number and its sparkline.
        named.put("down", (Region) downRate.getParent());
        named.put("up", (Region) upRate.getParent());
        named.put("alt", altSpeedToggle);
        named.put("free", freeStatus);
        named.put("update", updateBadge);
        named.put("sync", syncBadge);
        named.put("errors", errorBadge);
        named.forEach((name, r) -> {
            if (r == null || !r.isVisible() || !r.isManaged()) return;
            out.append(name)
                    .append('=')
                    .append(Math.round(r.getWidth()))
                    .append('/')
                    .append(Math.round(r.prefWidth(-1)))
                    .append(' ');
        });
        double right = statusBar.localToScene(statusBar.getLayoutBounds()).getMaxX();
        return out.append("barRight=")
                .append(Math.round(right))
                .append(" window=")
                .append(Math.round(stage.getScene().getWidth()))
                .toString();
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
        if (linkDialog != null && linkDialog.isShowing()) return linkDialog.scene();
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
        linksWaiting.clear(); // or the next one opens as this one closes
        if (linkDialog != null && linkDialog.isShowing()) linkDialog.cancel();
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
        out.add("statusbar-fit=" + statusBarFit());
        out.add("window=" + windowLine());
        if (createDialog != null && createDialog.isShowing()) {
            out.add("dialog=" + createDialog.title());
            out.addAll(createDialog.linesForCapture());
        }
        if (locationDialog != null && locationDialog.isShowing()) {
            out.add("dialog=" + locationDialog.title());
            out.add("location-hint=" + locationDialog.hint());
        }
        if (linkDialog != null && linkDialog.isShowing()) {
            out.add("dialog=" + linkDialog.title());
            out.add("link-dialog=" + linkDialog.describe());
        }
        // After the shot: the window and the tab have had their ticks by then.
        if (statsWindow.isShowing()) {
            out.add("stats=" + statsWindow.summaryForCapture());
            out.add("stats-graph=" + statsWindow.graph().summaryForCapture());
        }
        if (detailWanted && detail.activeTab().equals("speed")) out.add("speed-tab=" + detail.speedSummaryForCapture());
        if (detailWanted && detail.activeTab().equals("trackers"))
            out.add("web-seeds=" + detail.webSeedLineForCapture());
        if (detailWanted && detail.activeTab().equals("files"))
            for (String line : detail.fileLinesForCapture()) out.add("file=" + line);
        if (detailWanted && detail.activeTab().equals("overview"))
            for (String line : detail.overviewLines()) out.add("overview-" + line);
        out.add("status-bar=" + statusBarForCapture());
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
                .map(e -> LogWindow.levelLabel(e.level()) + " " + e.text())
                .toList();
    }

    /**
     * Drives the window from the capture hook, by name: {@code close-window} (the quit path a
     * person takes, where the hook itself exits through Platform.exit), {@code select-all}, {@code palette},
     * {@code palette:<query>}, {@code sidebar:<filter>} (as {@link SidebarFilter#parse} reads it),
     * {@code sidebar-keys:<KEY+KEY+…>} (focus the sidebar and press them),
     * {@code sidebar-click:<section>} (a click on a heading folds it), {@code sidebar-drag:<px>}
     * (the edge facing the list dragged that far; a {@code sidebar-width=} line),
     * {@code dialog-category:<name>} on an open Add dialog, or a command id. Prints what a
     * screenshot cannot show.
     */
    public void runForCapture(String action) {
        if (action.equals("close-window")) {
            stage.close();
        } else if (action.startsWith("window:")) {
            // window:<width>x<height>+<x>+<y>, or window:maximize
            String spec = action.substring("window:".length());
            if (spec.equals("maximize")) stage.setMaximized(true);
            else {
                String[] p = spec.split("[x+]");
                stage.setMaximized(false);
                stage.setWidth(Double.parseDouble(p[0]));
                stage.setHeight(Double.parseDouble(p[1]));
                stage.setX(Double.parseDouble(p[2]));
                stage.setY(Double.parseDouble(p[3]));
            }
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
        } else if (action.startsWith("welcome-clipboard:")) {
            // The card as it would read with this on the clipboard, without touching the real one.
            welcome.showClipboard(action.substring("welcome-clipboard:".length()));
        } else if (action.equals("settings")) {
            settingsWindow.show(stage);
        } else if (action.startsWith("settings-page:")) {
            settingsWindow.show(stage);
            settingsWindow.selectPageForCapture(action.substring("settings-page:".length()));
        } else if (action.startsWith("settings:")) {
            settingsWindow.show(stage);
            settingsWindow.searchForCapture(action.substring("settings:".length()));
        } else if (action.startsWith("settings-key:")) {
            // A key in the open Settings window, where its focus is (settings-key:ESCAPE).
            settingsWindow.keyForCapture(KeyCode.valueOf(action.substring("settings-key:".length())));
        } else if (action.equals("settings-close")) {
            settingsWindow.hideForCapture();
        } else if (action.startsWith("settings-layout:")) {
            // settings-layout:<sidebar>/<detail>, e.g. right/hidden: the General page's two rows.
            String[] ids = action.substring("settings-layout:".length()).split("/", 2);
            settingsWindow.show(stage);
            settingsWindow.chooseLayoutForCapture(ids[0], ids.length > 1 ? ids[1] : "");
            settingsWindow.hideForCapture();
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
        } else if (action.startsWith("view:")) {
            ViewSwitch.parse(action.substring("view:".length()))
                    .ifPresentOrElse(this::show, () -> System.err.println("capture: no view " + action));
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
        } else if (action.equals("content-skip")) {
            if (currentDialog != null && currentDialog.isShowing()) currentDialog.skipFlaggedForCapture();
        } else if (action.startsWith("dialog-press:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.pressForCapture(action.substring("dialog-press:".length()));
        } else if (action.startsWith("dialog-key:")) {
            if (currentDialog != null && currentDialog.isShowing())
                currentDialog.keyForCapture(KeyCode.valueOf(action.substring("dialog-key:".length())));
        } else if (action.startsWith("link-name:")) {
            // The Add Link dialog's name, typed in; `~` for a space, the action list being trimmed.
            String typed = action.substring("link-name:".length()).replace('~', ' ');
            if (linkDialog != null && linkDialog.isShowing()) linkDialog.setNameForCapture(typed);
            else linkNameForCapture = typed;
        } else if (action.equals("link-download")) {
            if (linkDialog != null && linkDialog.isShowing()) linkDialog.confirm();
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
        } else if (action.startsWith("sidebar-click:")) {
            // A click on a section's heading (status, category, tag, tracker): the mouse's fold.
            sidebar.clickHeadingForCapture(action.substring("sidebar-click:".length()));
        } else if (action.startsWith("sidebar-drag:")) {
            // A drag of the edge that faces the list by this many px (negative is leftwards).
            sidebar.dragForCapture(Double.parseDouble(action.substring("sidebar-drag:".length())));
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
        } else if (action.startsWith("focus-key:")) {
            // focus-key:CTRL+SHIFT+TAB: a key pressed where the focus is, through the scene's
            // filters, which is where Ctrl+Tab is caught.
            List<String> parts = List.of(action.substring("focus-key:".length()).split("\\+"));
            KeyCode code = KeyCode.valueOf(parts.getLast());
            javafx.scene.Node owner = stage.getScene().getFocusOwner();
            javafx.event.Event.fireEvent(
                    owner == null ? stage.getScene().getRoot() : owner,
                    new KeyEvent(
                            KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            code,
                            parts.contains("SHIFT"),
                            parts.contains("CTRL"),
                            parts.contains("ALT"),
                            parts.contains("META")));
        } else if (action.startsWith("rename:")) {
            // rename:<name>: the selection's Rename dialog, filled and confirmed ("~" is a space).
            renameForCapture = action.substring("rename:".length()).replace('~', ' ');
            renameTorrent();
        } else if (action.startsWith("save-view:")) {
            // save-view:<name>: Save Filter as View on the omnibox's query, named ("~" is a space).
            saveViewForCapture = action.substring("save-view:".length()).replace('~', ' ');
            saveView();
        } else if (action.startsWith("pick-view:")) {
            // pick-view:<n>: the n-th saved view chosen, as Shortcut+Alt+n does from the list.
            chooseSavedView(
                    Integer.parseInt(action.substring("pick-view:".length()).trim()));
        } else if (action.equals("remove-confirmed")) {
            // Remove (the files kept) on the selection, its question answered yes.
            RemoveConfirm.answerForCapture = true;
            remove(false);
        } else if (action.startsWith("undo-after:")) {
            // Shortcut+Z, that many milliseconds later: after the worker an undoable action ran on.
            javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.millis(
                    Long.parseLong(action.substring("undo-after:".length()).trim())));
            later.setOnFinished(e -> {
                System.out.println("capture: undo=offered " + undoOffer.isVisible() + ", kept " + undoStack.size());
                undoNewest();
            });
            later.play();
        } else if (action.startsWith("export-to:")) {
            // The selection exported into a folder, as Export does past its (native) chooser.
            export(exportTargets(selectedTorrents(), Path.of(action.substring("export-to:".length()))));
        } else if (action.equals("select-first")) {
            table.getSelectionModel().clearAndSelect(0);
        } else if (action.startsWith("detail:")) {
            showDetail();
            detail.selectTab(action.substring("detail:".length()));
        } else if (action.equals("remedy") || action.startsWith("remedy:")) {
            // remedy or remedy:<NAME> (SKIP_FILE, REANNOUNCE …): pressed once a card offers it.
            showDetail();
            detail.pressRemedyForCapture(
                    action.equals("remedy") ? null : Diagnosis.Remedy.valueOf(action.substring("remedy:".length())));
        } else if (action.startsWith("web-seeds-add:")) {
            // web-seeds-add:<urls>: the Trackers tab's Add Web Seeds dialog, filled and confirmed
            // ("~" is a space, between URLs).
            showDetail();
            detail.addWebSeedsForCapture(
                    action.substring("web-seeds-add:".length()).replace('~', ' '));
        } else if (action.startsWith("web-seed-remove:")) {
            showDetail();
            detail.removeWebSeedForCapture(Integer.parseInt(action.substring("web-seed-remove:".length())));
        } else if (action.startsWith("files-rename:")) {
            // files-rename:<row>:<name>: that visible row of the Files tab renamed ("~" is a space).
            String[] parts = action.substring("files-rename:".length()).split(":", 2);
            showDetail();
            detail.renameFileForCapture(Integer.parseInt(parts[0]), parts[1].replace('~', ' '));
        } else if (action.startsWith("files-menu")) {
            // files-menu or files-menu:<row>: the Files tab's context menu on that visible row.
            int colon = action.indexOf(':');
            showDetail();
            detail.showFilesMenuForCapture(colon < 0 ? 0 : Integer.parseInt(action.substring(colon + 1)));
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
        } else if (action.equals("peer-menu") || action.equals("peer-ban")) {
            // The Peers tab's context menu about the first peer, its lines printed; peer-ban then
            // presses Ban Peer. Two seconds on, so the detail poll has filled the tab after a
            // select-first in the same batch (allow for it in captureAfterMs).
            showDetail();
            detail.selectTab("peers");
            javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
            later.setOnFinished(e -> {
                if (action.equals("peer-menu")) {
                    detail.showPeerMenuForCapture();
                    System.out.println("capture: peer-menu=" + detail.peerMenuLines());
                } else {
                    System.out.println("capture: peer-ban=" + detail.banFirstPeerForCapture() + " bans="
                            + settings.ipFilterBans());
                }
            });
            later.play();
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
            System.out.println("capture: content-check=" + currentDialog.contentForCapture());
            System.out.println("capture: dialog-hint=" + currentDialog.hint());
        }
        if (limitsDialog != null && limitsDialog.isShowing())
            System.out.println("capture: dialog=" + limitsDialog.title());
        if (tagsDialog != null && tagsDialog.isShowing()) System.out.println("capture: dialog=" + tagsDialog.title());
        if (promptDialog != null && promptDialog.isShowing())
            System.out.println("capture: dialog=" + promptDialog.getTitle());
        if (sidebar.isVisible()) {
            System.out.println("capture: sidebar=" + sidebar.summary());
            System.out.println("capture: sidebar-width=" + Math.round(sidebar.getPrefWidth()) + " saved="
                    + settings.get(Settings.UI_SIDEBAR_WIDTH, ""));
        }
        System.out.println("capture: filter=" + sidebarSelection.describe() + " shown=" + filtered.size());
        System.out.println("capture: omnibox=" + omnibox.chips() + " text=\"" + filter.getText() + "\" mode="
                + omnibox.mode() + " query=\"" + omnibox.query() + "\"");
        System.out.println("capture: welcome=" + welcome.describeForCapture());
        System.out.println("capture: focus-sidebar=" + sidebar.hasFocus() + " cursor=" + sidebar.cursorForCapture());
        for (TorrentRow row : table.getItems()) {
            Torrent t = row.torrent();
            StatusLine line = StatusLine.of(t, row.quietSeconds(), shareLimitFor(t));
            System.out.println("capture: row=" + line.state().glyph() + " " + t.name() + " | " + line.text());
        }
        System.out.println("capture: layout=sidebar:"
                + SidebarPlace.of(sidebar.isVisible(), settings.sidebarSide()).id() + " detail:" + detailPlace.id());
        if (detailWanted) System.out.println("capture: detail=" + detail.summary());
        if (settingsWindow.isShowing())
            System.out.println("capture: settings=" + settingsWindow.visibleRowsForCapture());
        if (settingsWindow.isShowing())
            System.out.println("capture: settings-search=" + settingsWindow.searchStateForCapture());
        if (!settingsWindow.dialogTitle().isEmpty())
            System.out.println("capture: dialog=" + settingsWindow.dialogTitle());
        if (!settingsWindow.searchStatus().isEmpty())
            System.out.println("capture: search=" + settingsWindow.searchStatus());
        if (settingsWindow.isShowing())
            for (String row : settingsWindow.definitionsForCapture()) System.out.println("capture: definition=" + row);
        if (watcher != null) System.out.println("capture: watch=" + watchStatusText());
        System.out.println("capture: view=" + view.id() + " focus=" + focusForCapture());
        System.out.println("capture: view-strip=" + viewStrip.describe());
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
                ipFilter.refreshIfDue(this::reportIpFilter);
            });
            poller.scheduleAtFixedRate(() -> ipFilter.refreshIfDue(this::reportIpFilter), 1, 1, TimeUnit.HOURS);
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

        @Override
        public Torrent add(Engine engine, AddRequest request) throws EngineException {
            return screenedAdd(engine, request);
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
                altSpeedOn = alternative;
                if (altSpeedItem != null) altSpeedItem.setSelected(alternative);
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
        public void labelsApplied(SyncLabels.Change change) {
            LabelsHost labelsHost = new LabelsHost();
            for (String name : change.removedCategories()) labelsHost.categoryDeleted(name);
            for (String name : change.removedTags()) labelsHost.tagDeleted(name);
            labelsHost.labelsChanged();
            settingsWindow.reloadLabels();
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
        String ip = Actions.peerHost(address);
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
        startUpdateCheck(outcome -> onUpdateOutcome(outcome, false));
    }

    /** Runs a check, keeping what the About panel shows in step with it. */
    private void startUpdateCheck(java.util.function.Consumer<UpdateService.Outcome> then) {
        updateChecking = true;
        refreshAboutUpdate();
        updates.check(AppInfo.VERSION, outcome -> {
            updateChecking = false;
            updateError = outcome.error();
            then.accept(outcome);
            refreshAboutUpdate();
        });
    }

    private AboutDialog.UpdateStatus updateStatus() {
        return new AboutDialog.UpdateStatus(updateChecking, availableUpdate, updateError, settings.lastUpdateCheck());
    }

    private void refreshAboutUpdate() {
        if (aboutDialog != null && aboutDialog.isShowing()) AboutDialog.showUpdate(aboutDialog, updateStatus());
    }

    /** The user asked: ignores the interval, the setting and the skipped version, and always reports. */
    private void checkForUpdatesNow() {
        setStatus(tr("status.checkingUpdates"));
        settings.setLastUpdateCheck(System.currentTimeMillis());
        settings.saveQuietly();
        startUpdateCheck(outcome -> {
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
        aboutDialog = AboutDialog.create(stage, settings.file(), opener, updateStatus(), this::checkForUpdatesNow);
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
        refreshAboutUpdate();
    }

    /** The About panel's bounds and the window's, or empty when it is closed; read by the capture hook. */
    public String aboutBoundsForCapture() {
        if (aboutDialog == null || !aboutDialog.isShowing()) return "";
        javafx.stage.Window panel = aboutDialog.getDialogPane().getScene().getWindow();
        return String.format(
                "panel=%.0f,%.0f %.0fx%.0f window=%.0f,%.0f %.0fx%.0f",
                panel.getX(),
                panel.getY(),
                panel.getWidth(),
                panel.getHeight(),
                stage.getX(),
                stage.getY(),
                stage.getWidth(),
                stage.getHeight());
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
        showRates(Engine.Rates.ZERO, 0);
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
        StockButtons.translate(dialog);
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
                if (Inputs.isDirectLink(text)) {
                    // Not a torrent: curl downloads it, on this machine. The dialog asks where and
                    // under what name, unless the setting says to add without asking.
                    if (withoutAsking) {
                        Torrent added = e.addLink(LinkRequest.of(text));
                        Platform.runLater(() -> setStatus(tr("status.directAdded", added.name())));
                    } else {
                        Platform.runLater(() -> openLinkDialog(text));
                    }
                    return;
                }
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
     * FX thread. The Add Link dialog for a link that is not a torrent: the folder (this machine's
     * download folder to start with, whatever the engine), the name, started or paused.
     */
    private void openLinkDialog(String url) {
        if (linkDialog != null && linkDialog.isShowing()) {
            linksWaiting.add(url);
            return;
        }
        LinkDialog dialog = new LinkDialog(stage, url, settings.downloadDir(), settings.addStartPaused());
        if (linkNameForCapture != null) {
            dialog.setNameForCapture(linkNameForCapture);
            linkNameForCapture = null;
        }
        linkDialog = dialog;
        Optional<LinkRequest> request = dialog.showAndWait();
        linkDialog = null;
        table.requestFocus();
        String next = linksWaiting.poll();
        if (next != null) Platform.runLater(() -> openLinkDialog(next));
        Engine e = engine;
        if (request.isEmpty() || e == null) return;
        Thread.ofVirtual().name("engine-link").start(() -> {
            try {
                Torrent added = e.addLink(request.get());
                Platform.runLater(() -> setStatus(tr("status.directAdded", added.name())));
            } catch (EngineException ex) {
                Platform.runLater(() -> setStatus(ex.getMessage()));
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
            try {
                Torrent added = screenedAdd(e, AddDialog.withoutAsking(source, defaults));
                Platform.runLater(() -> setStatus(tr("status.added", added.name())));
            } catch (UnsafeContent.Refused refused) {
                reportFromWorker(EngineEvent.session(EngineEvent.Level.WARNING, refused.line()));
            }
            return;
        }
        TorrentPreview preview = source instanceof AddRequest.TorrentFile file ? e.inspect(file.bytes()) : null;
        Platform.runLater(() -> openDialog(source, preview, via));
    }

    /**
     * Worker. An add without a dialog, through the content check ({@link UnsafeContent},
     * {@code add.unsafeContent}): a {@code .torrent} is checked before it is added, its flagged
     * files skipped or the add refused; a magnet has no files yet, so it is added to stop once its
     * metadata is in and marked for {@link #screenPending}. What was found is logged; a refusal is
     * thrown, so each path reports it as it reports a failed add. A torrent already in the list
     * is left as it is: the engine hands it back, and its files are not this add's to change.
     */
    private Torrent screenedAdd(Engine e, AddRequest request) throws EngineException {
        UnsafeContent mode = UnsafeContent.byName(settings.addUnsafeContent());
        Set<String> before = new java.util.HashSet<>();
        for (Torrent t : e.torrents()) before.add(t.id());
        if (request.source() instanceof AddRequest.TorrentFile file) {
            TorrentPreview preview = e.inspect(file.bytes());
            if (before.contains(preview.id())) return e.add(request);
            ContentCheck check = UnsafeContent.check(preview, request);
            UnsafeContent.Decision decision = UnsafeContent.decide(mode, check, UnsafeContent.wanted(preview, request));
            if (decision.refused()) throw new UnsafeContent.Refused(preview.name(), decision, check);
            Torrent added = e.add(
                    decision == UnsafeContent.Decision.SKIP
                            ? UnsafeContent.withSkipped(request, check.indices())
                            : request);
            String line = UnsafeContent.logLine(decision, added.name(), check, false);
            if (!line.isEmpty()) reportFromWorker(EngineEvent.warning(added.id(), added.name(), line));
            return added;
        }
        // A magnet: stopped once its files are known, unless a person chose otherwise or the
        // engine cannot stop on metadata (a daemon); then it is checked while it runs.
        boolean stop = mode != UnsafeContent.ADD
                && request.stopCondition() == AddRequest.StopCondition.NONE
                && capabilities.has(Engine.Capability.STOP_CONDITION);
        Torrent added = e.add(stop ? request.withStopCondition(AddRequest.StopCondition.METADATA_RECEIVED) : request);
        if (before.contains(added.id())) return added;
        String mark = new UnsafeContent.Pending(mode, stop && !request.startPaused()).format();
        Platform.runLater(() -> {
            settings.setUnsafeContentPending(added.id(), mark);
            settings.saveQuietly();
        });
        return added;
    }

    /**
     * Poll thread. A magnet added without a dialog waits, stopped, for its file list; once it has
     * one, the content check runs under the setting it was added with: started as it is (a
     * warning when something was found), started without the flagged files, or removed before
     * anything was downloaded. The mark goes on the FX thread; {@link #screening} keeps the next
     * tick from handling a torrent twice meanwhile.
     */
    private void screenPending(Engine engine, List<Torrent> snapshot) {
        Map<String, String> pending = settings.unsafeContentPending();
        if (pending.isEmpty()) return;
        Map<String, Torrent> byId = new java.util.HashMap<>();
        for (Torrent t : snapshot) byId.put(t.id(), t);
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            String id = entry.getKey();
            Torrent t = byId.get(id);
            if (t == null) {
                // Marked after this tick's snapshot was taken, or removed since: the next tick knows.
                if (!screening.add(id)) continue;
                boolean gone;
                try {
                    gone = engine.torrents().stream().noneMatch(x -> x.id().equals(id));
                } catch (RuntimeException ex) {
                    gone = false;
                }
                if (gone) clearPending(id);
                else screening.remove(id);
                continue;
            }
            if (t.state() == com.torrenta.engine.TorrentState.FETCHING_METADATA || !screening.add(id)) continue;
            try {
                List<TorrentDetail.FileStatus> files = engine.detail(id).files();
                if (files.isEmpty()) {
                    screening.remove(id);
                    continue;
                }
                UnsafeContent.Pending p = UnsafeContent.Pending.parse(entry.getValue());
                ContentCheck check = ContentCheck.of(
                        UnsafeContent.previewOf(t, files),
                        i -> files.stream()
                                .anyMatch(f -> f.index() == i && f.priority() != com.torrenta.engine.Priority.IGNORE));
                int wanted = (int) files.stream()
                        .filter(f -> f.priority() != com.torrenta.engine.Priority.IGNORE)
                        .count();
                UnsafeContent.Decision decision = UnsafeContent.decide(p.mode(), check, wanted);
                if (decision.refused()) {
                    engine.remove(id, false);
                } else {
                    if (decision == UnsafeContent.Decision.SKIP)
                        for (int index : check.indices())
                            engine.setFilePriority(id, index, com.torrenta.engine.Priority.IGNORE);
                    if (p.resume()) engine.resume(id);
                }
                String line = UnsafeContent.logLine(decision, t.name(), check, true);
                if (!line.isEmpty()) reportFromWorker(EngineEvent.warning(id, t.name(), line));
            } catch (EngineException ex) {
                reportFromWorker(EngineEvent.warning(id, t.name(), ex.getMessage()));
            }
            clearPending(id);
        }
    }

    /** Any thread. A torrent's content-check mark cleared and saved, and the guard let go after. */
    private void clearPending(String id) {
        Platform.runLater(() -> {
            settings.setUnsafeContentPending(id, null);
            settings.saveQuietly();
            screening.remove(id);
        });
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
        private Engine.SwarmProbe fetch;
        private boolean closed;

        /** False, and the fetch cancelled, if the dialog closed first. */
        synchronized boolean set(Engine.SwarmProbe f) {
            if (closed) {
                f.cancel();
                return false;
            }
            fetch = f;
            return true;
        }

        synchronized Engine.SwarmProbe get() {
            return closed ? null : fetch;
        }

        /** Cancelled: the dialog closed without adding. */
        synchronized void close() {
            Engine.SwarmProbe f = take();
            if (f != null) f.cancel();
        }

        /**
         * Added: the fetch, for {@link Engine#add(AddRequest, Engine.SwarmProbe)} to end and
         * take the peers from; null if there was none. A fetch that starts later is cancelled.
         */
        synchronized Engine.SwarmProbe take() {
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
        else if (source instanceof AddRequest.TorrentFile file && preview != null)
            startProbe(file, preview, slot, dialog);
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
            Engine.SwarmProbe fetch = slot.take();
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
            Engine.SwarmProbe fetch;
            try {
                fetch = e.probe(magnet, settings.addProbePrivate());
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

    /**
     * Worker: a {@code .torrent}'s own look at the swarm while the dialog is open, as a magnet's
     * fetch stays to look once its files are in. Not for a private torrent unless the setting lets
     * it (a private tracker counts the probe's announce), nor where the engine cannot look. A probe
     * that cannot start (the torrent is in the list already) leaves the line off; the dialog says
     * nothing about it, since the add itself will.
     */
    private void startProbe(AddRequest.TorrentFile file, TorrentPreview preview, FetchSlot slot, AddDialog dialog) {
        Engine e = engine;
        boolean privateToo = settings.addProbePrivate();
        if (e == null || capabilities.lacks(Engine.Capability.SWARM_PREVIEW) || (preview.isPrivate() && !privateToo))
            return;
        Thread.ofVirtual().name("engine-probe").start(() -> {
            Engine.SwarmProbe probe;
            try {
                probe = e.probe(file, privateToo);
            } catch (EngineException ex) {
                LOG.log(Level.FINE, "No swarm probe: " + ex.getMessage(), ex);
                return;
            }
            if (slot.set(probe)) fetchWatch = new FetchWatch(slot, dialog);
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
        StockButtons.translate(prompt);
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
        // The welcome's card says it already, with the link's name.
        if (welcome != null && welcome.isVisible()) return;
        clipboardCandidate().ifPresent(text -> {
            if (text.equals(lastClipboardOffer)) return;
            lastClipboardOffer = text;
            setStatus(
                    Inputs.classify(text) == Inputs.Kind.MAGNET
                            ? tr(
                                    "status.clipboard.magnet",
                                    Chords.of("add-clipboard").getDisplayText())
                            : Inputs.isDirectLink(text)
                                    ? tr(
                                            "status.clipboard.link",
                                            Chords.of("add-clipboard").getDisplayText())
                                    : tr(
                                            "status.clipboard.torrent",
                                            Chords.of("add-clipboard").getDisplayText()));
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
            welcome.setDragging(true);
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        welcome.setDragging(false);
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
        Engine.SwarmProbe fetch = watch == null ? null : watch.slot().get();
        if (fetch != null) {
            if (!fetch.torrent().isDone()) {
                int peers = fetch.peers();
                Platform.runLater(() -> watch.dialog().showFetching(peers));
            } else {
                // The files are in and the fetch stays to look at the swarm, over the ticked files.
                Optional<SwarmPreview> swarm = fetch.swarm(watch.dialog().wantedFiles());
                Platform.runLater(() -> watch.dialog().showSwarm(swarm));
            }
        }
        List<Torrent> snapshot = engine.torrents();
        Engine.Rates current = engine.rates();
        long nowSeconds = System.currentTimeMillis() / 1000;
        sessionHistory.add(nowSeconds, current.download(), current.upload());
        Set<String> present = new java.util.HashSet<>();
        for (Torrent t : snapshot) {
            present.add(t.id());
            recentHistories
                    .computeIfAbsent(t.id(), k -> SpeedHistory.recent())
                    .add(nowSeconds, t.downloadRate(), t.uploadRate());
            SpeedHistory h = torrentHistories.get(t.id());
            if (h != null) h.add(nowSeconds, t.downloadRate(), t.uploadRate());
        }
        recentHistories.keySet().retainAll(present);
        torrentHistories.keySet().retainAll(present);
        // The counters are a copy of the last stats alert, cheap; still, only while someone looks.
        com.torrenta.engine.SessionStats stats = statsWanted ? engine.stats() : null;
        enforceShareLimits(engine, snapshot);
        screenPending(engine, snapshot);
        TorrentDetail shown = fetchDetail(engine);
        Connectivity connectivity = engine.connectivity();
        long unproven = portClock.tick(connectivity, NetworkStatus.anyRunning(snapshot), nowSeconds);
        Optional<NetworkStatus.Port> port = NetworkStatus.port(connectivity, unproven);
        Optional<String> dht = NetworkStatus.dht(connectivity, engine.stats().dhtNodes());
        Optional<NetworkStatus.Free> space = freeSpace(nowSeconds);
        pushTray(TrayStatus.of(snapshot, current));
        pushBadge(BadgeState.of(snapshot, current.download()));
        Platform.runLater(() -> {
            rows.apply(snapshot, nowSeconds);
            refreshSidebar(snapshot);
            showRates(current, nowSeconds);
            showNetwork(port, dht, space);
            applyDetail(shown, nowSeconds);
            if (stats != null) statsWindow.update(stats, sessionHistory, nowSeconds);
        });
    }

    /**
     * Poll thread. The detail pane costs a status with the pieces bitfield; only for one shown
     * torrent, and null for none or for one gone between the selection and the poll.
     */
    private TorrentDetail fetchDetail(Engine engine) {
        String id = detailWanted ? detailId : null;
        if (id == null) return null;
        try {
            return engine.detail(id);
        } catch (EngineException e) {
            return null;
        }
    }

    /** FX thread. A detail snapshot into the pane, unless the selection has moved on since. */
    private void applyDetail(TorrentDetail shown, long nowSeconds) {
        if (!detailWanted) return;
        if (shown != null && shown.general().id().equals(detailId)) {
            TorrentRow row = rows.get(detailId);
            detail.show(shown, row == null ? null : row.torrent(), row == null ? 0 : row.quietSeconds(), nowSeconds);
            detail.showSpeed(torrentHistories.get(detailId), nowSeconds);
        } else if (detailId == null) detail.clear();
    }

    /**
     * FX thread. The pane's torrent at once, not at the next tick: a click on a row (or showing the
     * pane) otherwise waited up to a whole poll for its first snapshot. Run on the poll thread, so
     * it never overlaps a poll; requests made while one is queued share it.
     */
    private void refreshDetailNow() {
        ScheduledExecutorService p = poller;
        if (p == null || !detailWanted || detailId == null || !detailRefreshQueued.compareAndSet(false, true)) return;
        try {
            p.execute(() -> {
                detailRefreshQueued.set(false);
                Engine engine = this.engine;
                TorrentDetail shown = engine == null ? null : fetchDetail(engine);
                if (shown == null) return;
                long nowSeconds = System.currentTimeMillis() / 1000;
                Platform.runLater(() -> applyDetail(shown, nowSeconds));
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            detailRefreshQueued.set(false); // stopping: no more polls of any kind
        }
    }

    /**
     * Poll thread. libtorrent ranks seeds by the share limits but pauses nothing by them, so the
     * rule is applied here, once a second: a finished torrent past its own or the global limit is
     * paused, removed (with its files or without) or super seeded, and the log says so. A paused one no longer qualifies, so it is done once;
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
            // A synced setting can ask a daemon for super seeding, which it lacks; failing that
            // every second would fill the log, so the limit does nothing there.
            if (due.get() == ShareLimit.Action.SUPER_SEED && capabilities.lacks(Engine.Capability.SUPER_SEEDING))
                continue;
            String text;
            try {
                text = switch (due.get()) {
                    case PAUSE -> {
                        engine.pause(t.id());
                        yield tr("log.shareLimit.paused", t.name());
                    }
                    case REMOVE -> {
                        engine.remove(t.id(), false);
                        yield tr("log.shareLimit.removed", t.name());
                    }
                    case REMOVE_DATA -> {
                        engine.remove(t.id(), true);
                        yield tr("log.shareLimit.removedData", t.name());
                    }
                    case SUPER_SEED -> {
                        engine.setSuperSeeding(t.id(), true);
                        yield tr("log.shareLimit.superSeeding", t.name());
                    }
                };
            } catch (EngineException e) {
                text = tr("log.shareLimit.failed", t.name(), e.getMessage());
            }
            String message = text;
            boolean removed = due.get() == ShareLimit.Action.REMOVE || due.get() == ShareLimit.Action.REMOVE_DATA;
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

    /**
     * Puts the detail pane under the list, beside it, or away, and remembers it: whether it shows
     * and where are two keys, so a collapse keeps the place. The divider of the place being left is
     * saved first, and the new place's comes back.
     */
    private void setDetailPlace(DetailPlace place) {
        rememberDivider();
        detailPlace = place;
        detailWanted = place.shown();
        detail.setVisible(place.shown());
        detail.setManaged(place.shown());
        detail.setBeside(place.beside());
        split.setOrientation(
                place.beside() ? javafx.geometry.Orientation.HORIZONTAL : javafx.geometry.Orientation.VERTICAL);
        if (place.shown()) {
            if (!split.getItems().contains(detail)) split.getItems().add(detail);
            split.setDividerPositions(dividerFromSettings());
            settings.setDetailSide(place.id());
            refreshDetailNow();
        } else {
            split.getItems().remove(detail);
        }
        settings.setDetailVisible(place.shown());
        settings.saveQuietly();
        if (detailItem != null) {
            detailItem.setSelected(place.shown());
            detailBottomItem.setSelected(
                    !DetailPlace.of(true, settings.detailSide()).beside());
        }
    }

    /** The place the settings describe, applied: at start, and when Settings changes it. */
    private void applyDetailPlace() {
        DetailPlace wanted = DetailPlace.of(settings.detailVisible(), settings.detailSide());
        if (wanted != detailPlace) setDetailPlace(wanted);
    }

    private void showDetail() {
        if (!detailPlace.shown()) setDetailPlace(DetailPlace.of(true, settings.detailSide()));
    }

    private void toggleDetail() {
        setDetailPlace(detailPlace.shown() ? DetailPlace.HIDDEN : DetailPlace.of(true, settings.detailSide()));
    }

    /** Under the list or beside it; a collapsed pane comes back in the other place. */
    private void switchDetailSide() {
        setDetailPlace(DetailPlace.of(true, settings.detailSide()).otherSide());
    }

    private static String dividerKey(DetailPlace place) {
        return place.beside() ? Settings.DETAIL_DIVIDER_RIGHT : Settings.DETAIL_DIVIDER;
    }

    /** The saved divider of the place the pane is in; see {@link DetailSplit} for what is not restored. */
    private double dividerFromSettings() {
        return detailPlace.divider(settings.get(dividerKey(detailPlace), null));
    }

    /**
     * The divider and the detail pane's tab. The divider only beside a list: while the empty
     * state stands in for it, the position is the layout's, not the person's (see DetailSplit).
     */
    private void rememberDivider() {
        if (split.getDividers().isEmpty() || !detailWanted) return;
        if (!rows.list().isEmpty()) {
            settings.set(
                    dividerKey(detailPlace),
                    String.format(java.util.Locale.ROOT, "%.3f", split.getDividerPositions()[0]));
        }
        settings.set(Settings.DETAIL_TAB, detail.rememberedTab());
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

    /** An action on one torrent that hands back how to take it back; null when there is nothing to. */
    private interface UndoableAction {
        UndoStack.Undo apply(Engine engine, Torrent torrent) throws EngineException;
    }

    /** What can be taken back; Shortcut+Z, Torrent → Undo, and the status bar's Undo. */
    private final UndoStack undoStack = new UndoStack();

    /** The status bar's Undo, shown for {@link UndoStack#OFFER_MS} after something undoable. */
    private final Label undoOffer = new Label();

    private final javafx.animation.PauseTransition undoOfferTimeout =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(UndoStack.OFFER_MS));

    /** Something to undo is kept: Torrent → Undo is live. */
    private final javafx.beans.property.BooleanProperty canUndo = new javafx.beans.property.SimpleBooleanProperty();

    /**
     * {@link #onTorrents}, keeping how to take each back: once the worker is done, the inverses
     * become one undo entry under the outcome's words, taken back newest first.
     */
    private void onTorrentsUndoable(String verb, List<Torrent> targets, UndoableAction action) {
        if (targets.isEmpty() || engine == null) return;
        Engine e = engine;
        Thread.ofVirtual().name("engine-action").start(() -> {
            int done = 0;
            List<String> failures = new ArrayList<>();
            List<UndoStack.Undo> inverses = new ArrayList<>();
            for (Torrent t : targets) {
                try {
                    UndoStack.Undo inverse = action.apply(e, t);
                    if (inverse != null) inverses.add(inverse);
                    done++;
                } catch (EngineException ex) {
                    failures.add(ex.getMessage());
                }
            }
            String text = Actions.outcome(verb, done, failures);
            Platform.runLater(() -> {
                setStatus(text);
                if (!inverses.isEmpty())
                    remember(text, engine -> {
                        List<String> refused = new ArrayList<>();
                        for (UndoStack.Undo inverse : inverses.reversed()) {
                            try {
                                inverse.run(engine);
                            } catch (EngineException ex) {
                                refused.add(ex.getMessage());
                            }
                        }
                        if (!refused.isEmpty()) throw new EngineException(String.join("; ", refused));
                    });
            });
        });
    }

    /** FX thread. Keeps an undo, and offers it in the status bar for ten seconds. */
    private void remember(String label, UndoStack.Undo undo) {
        undoStack.push(label, System.currentTimeMillis(), undo);
        canUndo.set(true);
        undoOffer.setVisible(true);
        undoOfferTimeout.playFromStart();
    }

    /** Shortcut+Z: the newest undo, taken back on a worker; the status bar says how it went. */
    private void undoNewest() {
        Engine e = engine;
        java.util.Optional<UndoStack.Entry> entry = undoStack.take(System.currentTimeMillis());
        canUndo.set(undoStack.newest(System.currentTimeMillis()).isPresent());
        undoOffer.setVisible(false);
        if (entry.isEmpty()) {
            setStatus(tr("status.undo.nothing"));
            return;
        }
        if (e == null) {
            setStatus(tr("status.engineStarting"));
            return;
        }
        String label = entry.get().label();
        Thread.ofVirtual().name("engine-undo").start(() -> {
            try {
                entry.get().undo().run(e);
                Platform.runLater(() -> setStatus(tr("status.undo.done", label)));
            } catch (EngineException ex) {
                Platform.runLater(
                        () -> setStatus(tr("status.undo.failed", label, ex.getMessage()), EngineEvent.Level.ERROR));
            }
        });
    }

    private List<Torrent> selectedTorrents() {
        return table.getSelectionModel().getSelectedItems().stream()
                .map(TorrentRow::torrent)
                .toList();
    }

    /** The selection's torrents, its downloads from links left out ({@link Actions#torrentsOnly}). */
    private List<Torrent> torrentsSelected() {
        return Actions.torrentsOnly(selectedTorrents());
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

    /**
     * Super seeding on the complete torrents of the selection (it means nothing for one still
     * downloading): on unless every one of them has it already.
     */
    private void toggleSuperSeeding() {
        List<Torrent> seeds = Actions.superSeedTargets(torrentsSelected());
        if (seeds.isEmpty()) {
            setStatus(tr("status.superSeed.none"));
            return;
        }
        boolean on = Actions.shouldEnableSuperSeeding(seeds);
        onTorrents(
                on ? tr("actions.verb.superSeedOn") : tr("actions.verb.superSeedOff"),
                seeds,
                (e, t) -> e.setSuperSeeding(t.id(), on));
    }

    private void toggleSequential() {
        List<Torrent> targets = torrentsSelected();
        boolean on = Actions.shouldEnableSequential(targets);
        onTorrents(
                on ? tr("actions.verb.sequentialOn") : tr("actions.verb.sequentialOff"),
                targets,
                (e, t) -> e.setSequential(t.id(), on));
    }

    private void remove(boolean deleteFiles) {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        boolean yes = RemoveConfirm.ask(
                stage,
                deleteFiles ? tr("window.remove.header.delete") : tr("common.remove"),
                Actions.removeQuestion(selected.size(), deleteFiles),
                deleteFiles ? tr("window.remove.button.delete") : tr("common.remove"));
        if (!yes) return;
        if (!deleteFiles && capabilities.has(Engine.Capability.UNDO_REMOVE)) {
            // Taken out whole, so Shortcut+Z can put each back as it was; its own share limit too.
            onTorrentsUndoable(tr("actions.verb.removed"), selected, (e, t) -> {
                java.util.Optional<ShareLimit> override = settings.shareOverride(t.id());
                Engine.Detached detached = e.detach(t.id());
                Platform.runLater(() -> forgetOverride(t.id()));
                return engine -> {
                    engine.reattach(detached);
                    override.ifPresent(limit -> Platform.runLater(() -> {
                        settings.setShareOverride(detached.id(), limit);
                        settings.saveQuietly();
                    }));
                };
            });
            return;
        }
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
        onTorrents(verb, QueueOrder.plan(torrentsSelected(), move), (e, t) -> e.queueMove(t.id(), move));
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
        onTorrentsUndoable(tr("actions.verb.moving", path), selected, (e, t) -> {
            String was = t.savePath();
            e.moveStorage(t.id(), path, moveFiles);
            // Back where it was, the same way: the files moved back, or the torrent pointed back.
            return engine -> engine.moveStorage(t.id(), was, moveFiles);
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
            setStatus(tr("status.labelsWriteFailed", labels.dir(), e.getMessage()));
        }
        refreshSidebar(rows.list().stream().map(TorrentRow::torrent).toList());
    }

    /** Files the selection under the category (empty for none), on a worker. */
    private void setCategory(String name) {
        rememberLabels(new TorrentLabels(name, List.of()));
        String verb = name.isEmpty() ? tr("actions.verb.categoryCleared") : tr("actions.verb.filedUnder", name);
        onTorrentsUndoable(verb, selectedTorrents(), (e, t) -> {
            String was = e.category(t.id());
            e.setCategory(t.id(), name);
            return engine -> engine.setCategory(t.id(), was);
        });
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
        StockButtons.translate(prompt);
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
        StockButtons.translate(prompt);
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
        onTorrentsUndoable(tr("actions.verb.tagsSet"), selected, (e, t) -> {
            List<String> was = List.copyOf(e.tags(t.id()));
            List<String> tags = new ArrayList<>(was);
            tags.removeAll(result.get().remove());
            tags.addAll(result.get().add());
            e.setTags(t.id(), tags);
            return engine -> engine.setTags(t.id(), was);
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
        lastSnapshot = snapshot;
        sidebar.update(
                sidebarViews(java.time.Instant.now().getEpochSecond()),
                labels.categories().stream().map(Category::name).toList(),
                labels.tags(),
                snapshot);
    }

    /** The saved views as the sidebar lists them, each query read with this clock. */
    private List<SidebarEntries.View> sidebarViews(long now) {
        return settings.savedViews().stream()
                .map(v -> new SidebarEntries.View(v.slug(), v.name(), FilterQuery.parse(v.query(), now)))
                .toList();
    }

    /** The n-th saved view (1 first), chosen; nothing when there are fewer. */
    private void chooseSavedView(int n) {
        List<com.torrenta.store.SavedView> views = settings.savedViews();
        if (n < 1 || n > views.size()) return;
        chooseFilter(SidebarFilter.view(views.get(n - 1).slug()));
    }

    /**
     * Save Filter as View (Shortcut+S): the omnibox's query kept under a name asked for, the view
     * then chosen and the box emptied, so the list stays as it was while the filter moves into
     * the sidebar.
     */
    private void saveView() {
        String query = omnibox.query().strip();
        if (query.isEmpty() || (promptDialog != null && promptDialog.isShowing())) return;
        // On the next pulse: a nested event loop cannot start inside an animation or layout pass.
        Platform.runLater(() -> {
            javafx.scene.control.TextInputDialog prompt = new javafx.scene.control.TextInputDialog(query);
            prompt.initOwner(stage);
            Fonts.installUiFont(prompt.getDialogPane());
            prompt.setTitle(tr("window.saveView.title"));
            prompt.setHeaderText(tr("window.saveView.header", query));
            StockButtons.translate(prompt);
            prompt.getEditor().setPrefColumnCount(32);
            String typed = saveViewForCapture;
            saveViewForCapture = null;
            if (typed != null) {
                Platform.runLater(() -> {
                    prompt.getEditor().setText(typed);
                    StockButtons.okButton(prompt).fire();
                });
            }
            promptDialog = prompt;
            prompt.showAndWait()
                    .map(String::strip)
                    .filter(name -> !name.isEmpty())
                    .ifPresent(name -> {
                        List<com.torrenta.store.SavedView> views =
                                com.torrenta.store.SavedView.added(settings.savedViews(), name, query);
                        settings.setSavedViews(views);
                        settings.saveQuietly();
                        setOmnibox(Omnibox.EMPTY);
                        refreshSidebar(lastSnapshot);
                        chooseFilter(SidebarFilter.view(views.getLast().slug()));
                        setStatus(tr("status.viewSaved", name));
                    });
        });
    }

    /** The sidebar's menu on a view: Rename…, Use the Current Filter, Delete…; by slug. */
    private final class ViewActions implements Sidebar.ViewActions {
        @Override
        public void rename(String slug) {
            com.torrenta.store.SavedView view = savedView(slug);
            if (view == null || (promptDialog != null && promptDialog.isShowing())) return;
            Platform.runLater(() -> {
                javafx.scene.control.TextInputDialog prompt = new javafx.scene.control.TextInputDialog(view.name());
                prompt.initOwner(stage);
                Fonts.installUiFont(prompt.getDialogPane());
                prompt.setTitle(tr("window.renameView.title"));
                prompt.setHeaderText(tr("window.renameView.header", view.name()));
                StockButtons.translate(prompt);
                promptDialog = prompt;
                prompt.showAndWait()
                        .map(String::strip)
                        .filter(name -> !name.isEmpty() && !name.equals(view.name()))
                        .ifPresent(name ->
                                changeViews(com.torrenta.store.SavedView.renamed(settings.savedViews(), slug, name)));
            });
        }

        @Override
        public void useCurrentFilter(String slug) {
            String query = omnibox.query().strip();
            if (savedView(slug) == null || query.isEmpty()) return;
            changeViews(com.torrenta.store.SavedView.requeried(settings.savedViews(), slug, query));
            setOmnibox(Omnibox.EMPTY);
            chooseFilter(SidebarFilter.view(slug));
        }

        @Override
        public boolean canUseCurrentFilter() {
            return filterSaveable.get();
        }

        @Override
        public void delete(String slug) {
            com.torrenta.store.SavedView view = savedView(slug);
            if (view == null) return;
            Platform.runLater(() -> {
                if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.VIEW, view.name())) return;
                changeViews(com.torrenta.store.SavedView.removed(settings.savedViews(), slug));
            });
        }
    }

    private com.torrenta.store.SavedView savedView(String slug) {
        return settings.savedViews().stream()
                .filter(v -> v.slug().equals(slug))
                .findFirst()
                .orElse(null);
    }

    /** The views replaced, saved, and the sidebar and the list told: a deleted view's choice clears itself. */
    private void changeViews(List<com.torrenta.store.SavedView> views) {
        settings.setSavedViews(views);
        settings.saveQuietly();
        refreshSidebar(lastSnapshot);
        if (sidebarSelection.view().kind() == SidebarFilter.Kind.VIEW
                && views.stream()
                        .noneMatch(v -> v.slug().equals(sidebarSelection.view().value())))
            setSidebarSelection(sidebarSelection.cleared(SidebarFilter.Axis.VIEW));
        else applyFilters();
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
        filtered.setPredicate(rowFilter(omnibox.query(), sidebarSelection, settings.savedViews()));
        filterSaveable.set(!omnibox.query().isBlank());
    }

    /**
     * Puts the sidebar on the left of the transfers or their right; the rule beside it moves to
     * the side that faces the list. Hiding it is {@link #setSidebarVisible}, and keeps the side.
     */
    private void placeSidebar(SidebarPlace side) {
        boolean right = side == SidebarPlace.RIGHT;
        // Out of both slots first: a node in one slot cannot be put in the other.
        transfersFrame.setLeft(null);
        transfersFrame.setRight(null);
        if (right) transfersFrame.setRight(sidebar);
        else transfersFrame.setLeft(sidebar);
        sidebar.pseudoClassStateChanged(BESIDE, right);
        sidebar.setRight(right);
        if (sidebarRightItem != null) sidebarRightItem.setSelected(right);
    }

    /** The side the settings name, applied; and whether it shows, when Settings changed that. */
    private void applySidebarPlace() {
        placeSidebar(SidebarPlace.of(true, settings.sidebarSide()));
        if (sidebar.isVisible() != settings.sidebar()) sidebar.setVisible(settings.sidebar());
    }

    /** Left to right and back; a hidden sidebar shows, on the new side. */
    private void switchSidebarSide() {
        SidebarPlace next = SidebarPlace.of(true, settings.sidebarSide()).otherSide();
        settings.setSidebarSide(next.id());
        settings.saveQuietly();
        placeSidebar(next);
        if (!sidebar.isVisible()) setSidebarVisible(true);
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
        copyToClipboard(key, selectedTorrents(), text);
    }

    private void copyToClipboard(String key, List<Torrent> selected, Function<Torrent, String> text) {
        if (selected.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join("\n", selected.stream().map(text).toList()));
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(tr(key, selected.size()));
    }

    private void copyMagnetLinks() {
        List<Torrent> selected = torrentsSelected();
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

    /**
     * Torrent → Rename… (F2): the name the list, the log and run-on-event show; the files keep
     * theirs (the Files tab renames those). Empty goes back to the torrent's own name. One torrent
     * at a time, since one name for several says nothing.
     */
    private void renameTorrent() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty() || engine == null || (promptDialog != null && promptDialog.isShowing())) return;
        if (selected.size() > 1) {
            setStatus(tr("status.rename.one"));
            return;
        }
        Torrent t = selected.get(0);
        // On the next pulse: a nested event loop cannot start inside an animation or layout pass.
        Platform.runLater(() -> {
            javafx.scene.control.TextInputDialog prompt = new javafx.scene.control.TextInputDialog(t.name());
            prompt.initOwner(stage);
            Fonts.installUiFont(prompt.getDialogPane());
            prompt.setTitle(tr("window.rename.title"));
            prompt.setHeaderText(tr("window.rename.header"));
            StockButtons.translate(prompt);
            prompt.getEditor().setPrefColumnCount(40);
            String typed = renameForCapture;
            renameForCapture = null;
            if (typed != null) {
                Platform.runLater(() -> {
                    prompt.getEditor().setText(typed);
                    StockButtons.okButton(prompt).fire();
                });
            }
            promptDialog = prompt;
            prompt.showAndWait()
                    .map(String::strip)
                    .filter(name -> !name.equals(t.name()))
                    .ifPresent(name -> renameTo(t, name));
        });
    }

    /** What the capture hook types into the next Rename dialog before pressing OK. */
    private String renameForCapture;

    private void renameTo(Torrent t, String name) {
        onTorrents(
                name.isEmpty() ? tr("actions.verb.nameRestored") : tr("actions.verb.renamed", name),
                List.of(t),
                (e, x) -> e.rename(x.id(), name));
    }

    /**
     * Torrent → Export .torrent…: one torrent to a file chosen in a save dialog, several into a
     * folder under their own names, never over a file already there. The bytes come from the
     * engine on a worker and are written beside their final name, then moved into place.
     */
    private void exportTorrents() {
        List<Torrent> selected = torrentsSelected();
        if (selected.isEmpty() || engine == null) return;
        Map<Torrent, Path> targets = new LinkedHashMap<>();
        if (selected.size() == 1) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(tr("window.export.title"));
            chooser.getExtensionFilters()
                    .add(new FileChooser.ExtensionFilter(tr("window.addFile.filter"), "*.torrent"));
            chooser.setInitialFileName(Actions.exportFileName(selected.get(0).name()));
            java.io.File file = chooser.showSaveDialog(stage);
            if (file == null) return;
            targets.put(selected.get(0), file.toPath());
        } else {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle(tr("window.export.folderTitle", selected.size()));
            java.io.File dir = chooser.showDialog(stage);
            if (dir == null) return;
            targets.putAll(exportTargets(selected, dir.toPath()));
        }
        export(targets);
    }

    /** Each torrent's file in {@code dir}: its own name, made unique against what is there. */
    private static Map<Torrent, Path> exportTargets(List<Torrent> torrents, Path dir) {
        Set<String> taken = new HashSet<>();
        String[] present = dir.toFile().list();
        if (present != null) taken.addAll(List.of(present));
        Map<Torrent, Path> targets = new LinkedHashMap<>();
        for (Torrent t : torrents) {
            String name = Actions.unusedName(Actions.exportFileName(t.name()), taken);
            taken.add(name);
            targets.put(t, dir.resolve(name));
        }
        return targets;
    }

    private void export(Map<Torrent, Path> targets) {
        onTorrents(tr("actions.verb.exported"), List.copyOf(targets.keySet()), (e, t) -> {
            try {
                CreateDialog.write(targets.get(t), e.exportTorrent(t.id()));
            } catch (IOException ex) {
                throw new EngineException(tr("status.export.writeFailed", targets.get(t), ex.getMessage()), ex);
            }
        });
    }

    private void openFolder() {
        List<Torrent> selected = selectedTorrents();
        if (selected.isEmpty()) return;
        String path = selected.get(0).savePath();
        if (path == null || path.isBlank()) return;
        opener.accept(Path.of(path).toUri().toString());
    }

    /** The torrent the detail pane shows, or null with no single selection or none in the list. */
    private Torrent detailTorrent() {
        String id = detailId;
        TorrentRow row = id == null ? null : rows.get(id);
        return row == null ? null : row.torrent();
    }
    /** The Queue submenu's commands, top to bottom. */
    private static final List<String> QUEUE_COMMANDS = List.of("queue-top", "queue-up", "queue-down", "queue-bottom");

    private List<Command> buildCommands() {
        BooleanBinding selection = Bindings.isNotEmpty(table.getSelectionModel().getSelectedItems());
        BooleanBinding always = Bindings.createBooleanBinding(() -> true);
        // What only a torrent understands is off for a selection of downloads from links alone, and
        // skips the downloads in a mixed one (torrentsSelected).
        BooleanBinding torrents = Bindings.createBooleanBinding(
                () -> Actions.anyTorrent(selectedTorrents()),
                table.getSelectionModel().getSelectedItems());
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
                        Chords.of("create"),
                        null,
                        gate("create", always),
                        this::createTorrent),
                new Command(
                        "add-file", tr("command.addFile"), Chords.of("add-file"), null, always, this::addTorrentFile),
                new Command(
                        "add-magnet",
                        tr("command.addMagnet"),
                        Chords.of("add-magnet"),
                        null,
                        always,
                        this::addMagnetLink),
                // The chord is a scene accelerator, not a menu one: Ctrl+V in a text field must paste.
                new Command(
                        "add-clipboard",
                        tr("command.addClipboard"),
                        null,
                        Chords.of("add-clipboard").getDisplayText(),
                        always,
                        this::addFromClipboard),
                new Command(
                        "pause-resume", tr("command.pauseResume"), null, tr("key.space"), selection, this::togglePause),
                new Command(
                        "force-resume",
                        tr("command.forceResume"),
                        Chords.of("force-resume"),
                        null,
                        selection,
                        () -> onSelection(tr("actions.verb.forced"), (e, t) -> e.forceResume(t.id()))),
                new Command(
                        "recheck",
                        tr("command.recheck"),
                        Chords.of("recheck"),
                        null,
                        torrents,
                        () -> onTorrents(
                                tr("actions.verb.rechecking"), torrentsSelected(), (e, t) -> e.recheck(t.id()))),
                new Command(
                        "reannounce",
                        tr("command.reannounce"),
                        Chords.of("reannounce"),
                        null,
                        torrents,
                        () -> onTorrents(
                                tr("actions.verb.reannounced"), torrentsSelected(), (e, t) -> e.reannounce(t.id()))),
                new Command(
                        "sequential",
                        tr("command.sequential"),
                        Chords.of("sequential"),
                        null,
                        gate("sequential", torrents),
                        this::toggleSequential),
                new Command(
                        "super-seed",
                        tr("command.superSeed"),
                        null,
                        null,
                        gate("super-seed", torrents),
                        this::toggleSuperSeeding),
                new Command("limits", tr("command.limits"), Chords.of("limits"), null, selection, this::editLimits),
                // The four chords are also taken on the table itself (onTableChord): its own
                // Ctrl+Shift+arrow bindings would consume them before the accelerators saw them.
                new Command(
                        "queue-top",
                        tr("command.queueTop"),
                        Chords.of("queue-top"),
                        null,
                        gate("queue-top", torrents),
                        () -> queueMove(Engine.QueueMove.TOP)),
                new Command(
                        "queue-up",
                        tr("command.queueUp"),
                        Chords.of("queue-up"),
                        null,
                        gate("queue-up", torrents),
                        () -> queueMove(Engine.QueueMove.UP)),
                new Command(
                        "queue-down",
                        tr("command.queueDown"),
                        Chords.of("queue-down"),
                        null,
                        gate("queue-down", torrents),
                        () -> queueMove(Engine.QueueMove.DOWN)),
                new Command(
                        "queue-bottom",
                        tr("command.queueBottom"),
                        Chords.of("queue-bottom"),
                        null,
                        gate("queue-bottom", torrents),
                        () -> queueMove(Engine.QueueMove.BOTTOM)),
                new Command(
                        "set-location",
                        tr("command.setLocation"),
                        Chords.of("set-location"),
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
                        Chords.of("set-category"),
                        null,
                        selection,
                        this::chooseCategory),
                new Command(
                        "edit-tags", tr("command.editTags"), Chords.of("edit-tags"), null, selection, this::editTags),
                new Command("manage-categories", tr("command.manageCategories"), null, null, always, () -> {
                    settingsWindow.show(stage);
                    settingsWindow.selectPageForCapture("LABELS");
                }),
                new Command(
                        "copy-name",
                        tr("command.copyName"),
                        Chords.of("copy-name"),
                        null,
                        selection,
                        () -> copyToClipboard("status.copied.name", Torrent::name)),
                new Command(
                        "copy-hash",
                        tr("command.copyHash"),
                        Chords.of("copy-hash"),
                        null,
                        torrents,
                        () -> copyToClipboard("status.copied.infoHash", torrentsSelected(), Torrent::id)),
                new Command(
                        "copy-magnet",
                        tr("command.copyMagnet"),
                        Chords.of("copy-magnet"),
                        null,
                        torrents,
                        this::copyMagnetLinks),
                // F2 is handled on the table, like Space and Delete: a text field takes it too.
                new Command("rename", tr("command.rename"), null, "F2", gate("rename", selection), this::renameTorrent),
                new Command(
                        "undo",
                        tr("command.undo"),
                        null,
                        Chords.of("undo").getDisplayText(),
                        canUndo,
                        this::undoNewest),
                new Command(
                        "export-torrent",
                        tr("command.exportTorrent"),
                        Chords.of("export-torrent"),
                        null,
                        gate("export-torrent", torrents),
                        this::exportTorrents),
                new Command(
                        "open-folder",
                        tr("command.openFolder"),
                        Chords.of("open-folder"),
                        null,
                        gate("open-folder", selection),
                        this::openFolder),
                new Command("remove", tr("command.remove"), null, tr("key.delete"), selection, () -> remove(false)),
                new Command(
                        "remove-data",
                        tr("command.removeData"),
                        null,
                        tr("key.shiftDelete"),
                        selection,
                        () -> remove(true)),
                new Command(
                        "alt-speed",
                        tr("command.altSpeed"),
                        Chords.of("alt-speed"),
                        null,
                        always,
                        this::toggleAlternativeSpeed),
                new Command("quit", tr("menu.file.quit"), Chords.of("quit"), null, always, this::quit),
                new Command("check-updates", tr("command.checkUpdates"), null, null, always, this::checkForUpdatesNow),
                new Command("about", tr("command.about", AppInfo.NAME), null, null, always, this::showAbout),
                new Command(
                        "settings",
                        tr("command.settings"),
                        Chords.of("settings"),
                        null,
                        always,
                        () -> settingsWindow.show(stage)),
                new Command("switch-profile", tr("command.switchProfile"), null, null, always, this::chooseProfile),
                new Command(
                        "retry-engine", tr("command.retryEngine"), null, null, engineDown, () -> startEngine(profile)),
                new Command("search", tr("command.search"), Chords.of("search"), null, always, this::showSearch),
                new Command("rss", tr("command.rss"), Chords.of("rss"), null, always, this::showRss),
                new Command("rss-run-rules", tr("command.rssRunRules"), null, null, always, rss::runRulesNow),
                new Command(
                        "transfers",
                        tr("command.transfers"),
                        null,
                        tr("dialog.common.key.esc"),
                        always,
                        this::showTransfers),
                // The cycling chords are a scene filter, not accelerators: Ctrl+Tab is focus
                // traversal's key, and an accelerator would lose it to the focused control.
                new Command(
                        "view-next",
                        tr("command.viewNext"),
                        null,
                        Chords.of("view-next").getDisplayText(),
                        always,
                        () -> show(ViewSwitch.next(view))),
                new Command(
                        "view-previous",
                        tr("command.viewPrevious"),
                        null,
                        Chords.of("view-previous").getDisplayText(),
                        always,
                        () -> show(ViewSwitch.previous(view))),
                new Command(
                        "filter",
                        tr("command.filter"),
                        null,
                        Chords.of("filter").getDisplayText(),
                        always,
                        this::focusFilter),
                new Command(
                        "save-view",
                        tr("command.saveView"),
                        Chords.of("save-view"),
                        null,
                        filterSaveable,
                        this::saveView),
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
                        Chords.of("select-all").getDisplayText(),
                        always,
                        () -> table.getSelectionModel().selectAll()),
                new Command(
                        "event-log",
                        tr("command.eventLog"),
                        null,
                        Chords.of("event-log").getDisplayText(),
                        always,
                        () -> logWindow.show()),
                new Command(
                        "statistics",
                        tr("command.statistics"),
                        null,
                        Chords.of("statistics").getDisplayText(),
                        always,
                        () -> statsWindow.show()),
                new Command(
                        "message-log",
                        tr("command.messageLog"),
                        null,
                        Chords.of("message-log").getDisplayText(),
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
                        Chords.of("toggle-sidebar"),
                        null,
                        always,
                        () -> setSidebarVisible(!sidebar.isVisible())),
                new Command(
                        "focus-sidebar",
                        tr("command.focusSidebar"),
                        Chords.of("focus-sidebar"),
                        null,
                        always,
                        this::focusSidebar),
                new Command("sidebar-side", tr("command.sidebarSide"), null, null, always, this::switchSidebarSide),
                new Command(
                        "toggle-detail",
                        tr("command.toggleDetail"),
                        Chords.of("toggle-detail"),
                        null,
                        always,
                        this::toggleDetail),
                new Command("detail-side", tr("command.detailSide"), null, null, always, this::switchDetailSide)));
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
        if (welcome != null) showDownloadFolder(welcome);
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
        } else if (e.getCode() == KeyCode.F2 && !e.isShortcutDown() && !e.isAltDown()) {
            // Behind its capability, unlike Space: a daemon has no name of its own to give.
            Command rename = command("rename");
            if (rename.enabled().get()) rename.run().run();
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

        MenuItem quit = menuItem("quit");

        Menu file = new Menu(tr("menu.file"));
        file.getItems()
                .addAll(
                        menuItem("create"),
                        new SeparatorMenuItem(),
                        addTorrent,
                        addMagnet,
                        addClipboard,
                        new SeparatorMenuItem(),
                        menuItem("rss-run-rules"),
                        menuItem("settings"),
                        menuItem("switch-profile"),
                        new SeparatorMenuItem(),
                        quit);

        Menu torrent = new Menu(tr("menu.torrent"));
        torrent.getItems().addAll(menuItem("undo"), new SeparatorMenuItem());
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
        sidebarItem.setAccelerator(Chords.of("toggle-sidebar"));
        sidebarItem.selectedProperty().bindBidirectional(sidebar.visibleProperty());
        sidebarRightItem = new CheckMenuItem(tr("menu.view.sidebarRight"));
        sidebarRightItem.setSelected(SidebarPlace.of(true, settings.sidebarSide()) == SidebarPlace.RIGHT);
        sidebarRightItem.setOnAction(e -> switchSidebarSide());
        detailItem = new CheckMenuItem(tr("menu.view.detail"));
        detailItem.setGraphic(menuIcon(Icons.detailPane()));
        detailItem.setAccelerator(Chords.of("toggle-detail"));
        detailItem.setSelected(detailPlace.shown());
        detailItem.setOnAction(e -> toggleDetail());
        // Checked while the alternative limits are in force, by hand or by the schedule; the
        // command flips the manual switch, as a click on the status bar's toggle does.
        Command altSpeed = command("alt-speed");
        altSpeedItem = new CheckMenuItem(altSpeed.label());
        altSpeedItem.setGraphic(menuIcon(Icons.forCommand("alt-speed")));
        altSpeedItem.setAccelerator(altSpeed.accelerator());
        altSpeedItem.setOnAction(e -> {
            altSpeedItem.setSelected(altSpeedOn);
            altSpeed.run().run();
        });
        detailBottomItem = new CheckMenuItem(tr("menu.view.detailBottom"));
        detailBottomItem.setSelected(
                !DetailPlace.of(true, settings.detailSide()).beside());
        detailBottomItem.setOnAction(e -> switchDetailSide());
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
                        menuItem("transfers"),
                        menuItem("search"),
                        menuItem("rss"),
                        menuItem("view-next"),
                        menuItem("view-previous"),
                        new SeparatorMenuItem(),
                        toolbarItem,
                        sidebarItem,
                        sidebarRightItem,
                        detailItem,
                        detailBottomItem,
                        new SeparatorMenuItem(),
                        densityItems.get(Density.COMFORTABLE),
                        densityItems.get(Density.COMPACT),
                        new SeparatorMenuItem(),
                        altSpeedItem,
                        new SeparatorMenuItem(),
                        menuItem("filter"),
                        menuItem("save-view"),
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
        for (String id : List.of(
                "pause-resume",
                "force-resume",
                "recheck",
                "reannounce",
                "sequential",
                "super-seed",
                "limits",
                "rename")) {
            items.add(menuItem(id));
        }
        items.add(queueMenu());
        items.add(menuItem("set-location"));
        items.add(menuItem("choose-files"));
        items.add(new SeparatorMenuItem());
        items.add(categoryMenu());
        items.add(menuItem("edit-tags"));
        items.add(new SeparatorMenuItem());
        for (String id : List.of("copy-name", "copy-hash", "copy-magnet", "export-torrent", "open-folder")) {
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
        table.visibleProperty().bind(Bindings.isNotEmpty(rows.list()));
        table.managedProperty().bind(table.visibleProperty());
        // The first torrent makes the table appear; it takes the focus then, unless the person is
        // typing a filter at that moment.
        table.visibleProperty().addListener((o, was, now) -> {
            if (now && !filter.isFocused() && !searchShowing() && !rssShowing()) table.requestFocus();
        });
        centre = new StackPane(table);
        // The filter bar heads the list's side of the split, so a detail pane on the right runs
        // the full height beside both, and one at the bottom sits under both as it always did.
        VBox listColumn = new VBox(buildFilterBar(), centre);
        VBox.setVgrow(centre, Priority.ALWAYS);
        split = new SplitPane(listColumn);
        split.getStyleClass().add("transfers-split");
        split.getDividers().addListener((javafx.collections.ListChangeListener<SplitPane.Divider>) c -> {
            while (c.next()) {
                for (SplitPane.Divider d : c.getAddedSubList())
                    d.positionProperty().addListener(o -> rememberDivider());
            }
        });

        // The pane follows the selection: one torrent shows it, anything else clears it.
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<TorrentRow>)
                c -> {
                    var selected = table.getSelectionModel().getSelectedItems();
                    detailId = selected.size() == 1 ? selected.get(0).id() : null;
                    // The Speed tab's full history starts with the first selection, from the short one
                    // every torrent keeps, so the tab has the last two minutes to show at once.
                    if (detailId != null)
                        torrentHistories.computeIfAbsent(detailId, k -> {
                            SpeedHistory recent = recentHistories.get(k);
                            return recent == null ? new SpeedHistory() : recent.widened();
                        });
                    if (detailId == null && detailWanted) detail.clear();
                    refreshDetailNow();
                });
        detail.selectTab(settings.get(Settings.DETAIL_TAB, "overview"));
        detail.setOnFilePriority((index, priority) -> {
            String id = detailId;
            if (id != null) onTorrent("status.prioritySet", id, (e, t) -> e.setFilePriority(t, index, priority));
        });
        detail.setOnOpenFile(row -> {
            Torrent t = detailTorrent();
            if (t != null)
                opener.accept(Path.of(t.savePath()).resolve(row.path()).toUri().toString());
        });
        detail.setOnOpenFolder(row -> {
            Torrent t = detailTorrent();
            if (t == null || t.savePath() == null || t.savePath().isBlank()) return;
            Path savePath = Path.of(t.savePath());
            // A folder no piece has reached is not on the disk yet: the nearest one above it opens.
            // Looking is disk I/O (a network mount can stall), so it is not done on the FX thread.
            Thread.ofVirtual().name("open-folder").start(() -> {
                String uri = FileRows.nearestFolder(
                                savePath, FileRows.folderOf(savePath, row), java.nio.file.Files::isDirectory)
                        .toUri()
                        .toString();
                Platform.runLater(() -> opener.accept(uri));
            });
        });
        detail.setOnRename((row, name) -> {
            String id = detailId;
            if (id != null) onTorrent("status.fileRenamed", id, (e, t) -> e.renamePath(t, row.path(), name));
        });
        detail.setOnAddTrackers(urls -> {
            String id = detailId;
            if (id != null) onTorrent("status.trackersAdded", id, (e, t) -> e.addTrackers(t, urls));
        });
        detail.setOnAddWebSeeds(urls -> {
            String id = detailId;
            if (id != null) onTorrent("status.webSeedsAdded", id, (e, t) -> e.addWebSeeds(t, urls));
        });
        detail.setOnRemoveWebSeed(url -> {
            String id = detailId;
            if (id != null) onTorrent("status.webSeedRemoved", id, (e, t) -> e.removeWebSeed(t, url));
        });
        detail.setOnRemoveTracker(url -> {
            String id = detailId;
            if (id != null) onTorrent("status.trackerRemoved", id, (e, t) -> e.removeTracker(t, url));
        });
        detail.setOnReannounce(() -> {
            String id = detailId;
            if (id != null) onTorrent("status.reannounced", id, (e, t) -> e.reannounce(t));
        });
        Platform.runLater(this::applyDetailPlace);
        // The sidebar runs the full height beside the filter bar, the list and the detail pane, on
        // the side the settings name; hidden and unmanaged together, so View → Sidebar gives the
        // width back to the list.
        sidebar = new Sidebar(this::onSidebarChoice, this::onSidebarCollapse, () -> table.requestFocus(), width -> {
            settings.setSidebarWidth(width);
            settings.saveQuietly();
        });
        sidebar.setSidebarWidth(settings.sidebarWidth(SidebarWidth.DEFAULT));
        sidebar.setViewActions(new ViewActions());
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
                settings.sidebarChoice("tracker"),
                settings.sidebarChoice("view"));
        sidebar.select(sidebarSelection);
        applyFilters();
        transfersFrame = new BorderPane(split);
        placeSidebar(SidebarPlace.of(true, settings.sidebarSide()));
        transfersView = transfersFrame;
        // The views share the centre: the one showing is visible and managed, the others neither,
        // so the transfers look exactly as they did while Search or RSS is away.
        searchPane = new SearchPane(new SearchHost(), settings);
        rssPane = new RssPane(new RssHost(), rss, settings);
        panes.put(View.TRANSFERS, transfersView);
        panes.put(View.SEARCH, searchPane);
        panes.put(View.RSS, rssPane);
        StackPane centre2 = new StackPane(transfersFrame, searchPane, rssPane);
        VBox.setVgrow(centre2, Priority.ALWAYS);
        viewStrip = new ViewStrip(this::show);
        place(View.TRANSFERS);
        return new VBox(viewStrip, centre2);
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
        return view == View.SEARCH;
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
            screenedAdd(e, RuleAdd.request(rule, source, addDefaults(), categoryFolder));
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
                Torrent added = screenedAdd(e, AddDialog.withoutAsking(source, defaults));
                rss.markDownloaded(article.feedId(), article.identity());
                Platform.runLater(() -> setStatus(tr("status.added", added.name())));
            } catch (UnsafeContent.Refused refused) {
                reportFromWorker(EngineEvent.session(EngineEvent.Level.WARNING, refused.line()));
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
        return view == View.RSS;
    }

    /** Ctrl+R: the RSS view in place of the transfers, the articles focused. */
    private void showRss() {
        show(View.RSS);
    }

    // ---- the views ------------------------------------------------------------------------------

    /**
     * Show one view in the centre and give it the focus: the transfers' list, Search's query, the
     * RSS articles (the feeds while there are none). The menu, the chords, Ctrl+Tab and the strip
     * all come here.
     */
    private void show(View wanted) {
        if (wanted == View.SEARCH) refreshSearchDefinitions();
        place(wanted);
        switch (wanted) {
            case TRANSFERS -> table.requestFocus();
            case SEARCH -> searchPane.focusQuery();
            case RSS -> rssPane.focusDefault();
        }
    }

    /** The view's pane shown and managed, the others neither, and the strip marked; no focus. */
    private void place(View wanted) {
        view = wanted;
        panes.forEach((v, pane) -> {
            boolean on = v == wanted;
            pane.setVisible(on);
            pane.setManaged(on);
        });
        viewStrip.select(wanted);
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

    /** Ctrl+Shift+F: the Search view in place of the transfers, the query field focused. */
    private void showSearch() {
        show(View.SEARCH);
    }

    /** The folder is re-read when Search opens, so a definition dropped in is a chip at once. */
    private void refreshSearchDefinitions() {
        if (definitionStore.refresh()) {
            reportDefinitionProblems();
            searchPane.reloadProviders();
        }
    }

    /** Escape in the query or the articles, the strip, the menu: the transfers back, as they were. */
    private void showTransfers() {
        show(View.TRANSFERS);
    }

    /** Where the focus is in the view showing, for the capture's {@code view=} line. */
    private String focusForCapture() {
        // The scene's focus owner, not isFocused(): a capture's window may never get the desktop's
        // focus, and a node is "focused" only while its window is.
        javafx.scene.Node owner = stage.getScene().getFocusOwner();
        return switch (view) {
            case TRANSFERS -> owner == table ? "table" : owner == filter ? "filter" : "other";
            case SEARCH -> searchPane.focusForCapture(owner);
            case RSS -> rssPane.focusForCapture(owner);
        };
    }

    /** The view that is showing takes the focus back: after a dialog, after the palette. */
    private void focusList() {
        switch (view) {
            case TRANSFERS -> table.requestFocus();
            case SEARCH -> searchPane.focusResults();
            case RSS -> rssPane.focusArticles();
        }
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
        Label badge = new Label(Chords.of("filter").getDisplayText());
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
        return rowFilter(text, sidebar, List.of(), now);
    }

    /** {@link #rowFilter(String, SidebarSelection)} with the saved views a chosen one is read from. */
    static java.util.function.Predicate<TorrentRow> rowFilter(
            String text, SidebarSelection sidebar, List<com.torrenta.store.SavedView> views) {
        return rowFilter(text, sidebar, views, java.time.Instant.now().getEpochSecond());
    }

    /**
     * Every filter, the chosen saved view's query among them: the box, the view, the sidebar's
     * sections, all of them at once. A view chosen and since deleted filters nothing.
     */
    static java.util.function.Predicate<TorrentRow> rowFilter(
            String text, SidebarSelection sidebar, List<com.torrenta.store.SavedView> views, long now) {
        FilterQuery query = FilterQuery.parse(text, now);
        SidebarSelection s = sidebar == null ? SidebarSelection.ALL : sidebar;
        FilterQuery view = viewQuery(s.view(), views, now);
        return row -> query.matches(row.torrent()) && view.matches(row.torrent()) && s.matches(row.torrent());
    }

    /** The chosen view's query; {@link FilterQuery#EMPTY} when none is chosen or it is gone. */
    static FilterQuery viewQuery(SidebarFilter chosen, List<com.torrenta.store.SavedView> views, long now) {
        if (chosen.kind() != SidebarFilter.Kind.VIEW) return FilterQuery.EMPTY;
        return views.stream()
                .filter(v -> v.slug().equals(chosen.value()))
                .findFirst()
                .map(v -> FilterQuery.parse(v.query(), now))
                .orElse(FilterQuery.EMPTY);
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
        applySidebarPlace();
        applyDetailPlace();
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
    record RowView(String name, StatusLine line, double progress, boolean direct) {}

    private TableColumn<TorrentRow, RowView> nameColumn() {
        TableColumn<TorrentRow, RowView> column = new TableColumn<>(tr("common.name"));
        column.setId("name");
        column.setPrefWidth(360);
        column.setCellValueFactory(cell -> {
            TorrentRow row = cell.getValue();
            return row.torrentProperty()
                    .map(t -> new RowView(
                            t.name(),
                            StatusLine.of(t, row.quietSeconds(), shareLimitFor(t)),
                            t.progress(),
                            t.direct()));
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
        /** Before a download's name, in either density: it came from a link, not a torrent. */
        private final Icons.Glyph link = Icons.link().at(13);

        NameCell() {
            link.getStyleClass().add("row-link");
            Tooltip.install(link, new Tooltip(tr("row.directTooltip")));
            name.setGraphicTextGap(6);
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
                name.setGraphic(null);
                setGraphic(item.direct() ? link : null);
                setGraphicTextGap(6);
                setText(item.name());
            } else {
                setText(null);
                glyph.setText(item.line().state().glyph());
                name.setText(item.name());
                name.setGraphic(item.direct() ? link : null);
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

    /**
     * When the engine could not start: the reason, and the two ways out, under the welcome's drop
     * zone. Hidden otherwise; the palette offers both as commands as well.
     */
    private VBox buildEngineProblem() {
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
        engineProblem.setVisible(false);
        engineProblem.setManaged(false);
        return engineProblem;
    }

    /**
     * The welcome over the table: the whole centre until the first torrent, and the table after it;
     * a filter that matches nothing shows the table's own placeholder instead. After the commands,
     * since its keys are theirs.
     */
    private void installWelcome() {
        welcome = buildWelcome();
        welcome.setProblem(buildEngineProblem());
        welcome.visibleProperty().bind(Bindings.isEmpty(rows.list()));
        welcome.managedProperty().bind(welcome.visibleProperty());
        welcome.visibleProperty().addListener((o, was, now) -> refreshWelcomeClipboard());
        // The welcome never sizes the split: its height would push the divider down (see
        // DetailSplit). The list appearing puts the saved position back.
        welcome.setMinHeight(0);
        welcome.setMinWidth(0); // and its width, with the pane beside the list
        welcome.visibleProperty().addListener((o, was, now) -> {
            if (!now && detailWanted && !split.getDividers().isEmpty())
                split.setDividerPositions(dividerFromSettings());
        });
        centre.getChildren().add(welcome);
        refreshWelcomeClipboard();
    }

    private Welcome buildWelcome() {
        List<Welcome.Key> keys = new ArrayList<>();
        for (var k : List.of(
                Map.entry("add-file", "welcome.key.open"),
                Map.entry("add-clipboard", "welcome.key.clipboard"),
                Map.entry("filter", "welcome.key.omnibox"),
                Map.entry("settings", "welcome.key.settings"))) {
            Command c = command(k.getKey());
            keys.add(new Welcome.Key(c.hint(), tr(k.getValue()), c.run()));
        }
        Welcome w = new Welcome(
                keys, this::addFromClipboard, command("add-clipboard").hint(), this::changeDownloadFolder);
        showDownloadFolder(w);
        return w;
    }

    /**
     * The welcome's footer: this machine's download folder with a "change", or, when the files are
     * not here, the daemon's folder as it names it (a path on its machine, changed in its own
     * Settings rows).
     */
    private void showDownloadFolder(Welcome w) {
        Engine e = engine;
        if (e != null && capabilities.lacks(Engine.Capability.LOCAL_FILES)) w.showRemoteFolder(e.downloadDir());
        else w.showFolder(folderText(settings.downloadDir()));
    }

    /** The welcome's clipboard card, read now: the clipboard has no change event, so on each look. */
    private void refreshWelcomeClipboard() {
        if (welcome == null || !welcome.isVisible()) return;
        welcome.showClipboard(clipboardCandidate().orElse(""));
    }

    /** The welcome footer's "change": the download folder, chosen and saved; the engine reads it live. */
    private void changeDownloadFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(tr("settings.general.downloadDir"));
        java.io.File current = settings.downloadDir().toFile();
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        java.io.File chosen = chooser.showDialog(stage);
        if (chosen == null) return;
        settings.downloadDir(chosen.toPath());
        settings.saveQuietly();
        showDownloadFolder(welcome);
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
        // The rates lead the right-hand group, as the redesign has them: each with its minute.
        downRate.getStyleClass().add("status-rate-down");
        upRate.getStyleClass().add("status-rate-up");
        // The session starts idle, so the minute before it began is drawn as the zero it was.
        downSpark.setZeroBefore(true);
        upSpark.setZeroBefore(true);
        HBox down = new HBox(6, downRate, downSpark);
        HBox up = new HBox(6, upRate, upSpark);
        down.setAlignment(Pos.CENTER_LEFT);
        up.setAlignment(Pos.CENTER_LEFT);
        down.getStyleClass().add("status-rate");
        up.getStyleClass().add("status-rate");
        // The port and the DHT lead, quiet until the engine says something; a click opens the
        // Settings page that changes them. Free space follows the alternative-speed switch; a click
        // opens the folder.
        portDot.getStyleClass().add("port-dot");
        portStatus.setGraphic(portDot);
        portStatus.getStyleClass().add("network-status");
        portStatus.setTooltip(portTip);
        portStatus.setOnMouseClicked(e -> openSettingsPage("CONNECTION"));
        dhtStatus.getStyleClass().add("network-status");
        dhtStatus.setTooltip(new Tooltip(tr("status.dht.tip")));
        dhtStatus.setOnMouseClicked(e -> openSettingsPage("PROTOCOL"));
        freeStatus.getStyleClass().add("network-status");
        freeStatus.setTooltip(freeTip);
        freeStatus.setOnMouseClicked(
                e -> opener.accept(settings.downloadDir().toUri().toString()));
        for (Label l : List.of(portStatus, dhtStatus, freeStatus)) reveal(l, false);
        // The message is the one that gives way: the rates, the switch and the counts keep their width.
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.SOMETIMES);
        // Undo, after the message that said what was done; for ten seconds, then Shortcut+Z alone.
        undoOffer.setText(tr("status.undo.offer", Chords.of("undo").getDisplayText()));
        undoOffer.getStyleClass().add("status-undo");
        undoOffer.setMinWidth(Region.USE_PREF_SIZE);
        undoOffer.managedProperty().bind(undoOffer.visibleProperty());
        undoOffer.setVisible(false);
        undoOffer.setOnMouseClicked(e -> undoNewest());
        undoOfferTimeout.setOnFinished(e -> undoOffer.setVisible(false));
        HBox bar = new HBox(
                12,
                portStatus,
                dhtStatus,
                status,
                undoOffer,
                spacer,
                down,
                up,
                altSpeedToggle,
                freeStatus,
                updateBadge,
                syncBadge,
                errorBadge);
        bar.setAlignment(Pos.CENTER_LEFT);
        for (Region r : List.<Region>of(
                portStatus, dhtStatus, down, up, altSpeedToggle, freeStatus, updateBadge, syncBadge, errorBadge))
            r.setMinWidth(Region.USE_PREF_SIZE);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void openSettingsPage(String page) {
        settingsWindow.show(stage);
        settingsWindow.selectPageForCapture(page);
    }

    /** FX thread: the network entries as the poll found them; an absent one is hidden. */
    private void showNetwork(
            Optional<NetworkStatus.Port> port, Optional<String> dht, Optional<NetworkStatus.Free> space) {
        reveal(portStatus, port.isPresent());
        port.ifPresent(p -> {
            portStatus.setText(p.text());
            portTip.setText(p.tooltip());
            var classes = portDot.getStyleClass();
            for (NetworkStatus.Dot d : NetworkStatus.Dot.values()) if (d != p.dot()) classes.remove(d.styleClass);
            if (!classes.contains(p.dot().styleClass)) classes.add(p.dot().styleClass);
        });
        reveal(dhtStatus, dht.isPresent());
        dht.ifPresent(dhtStatus::setText);
        reveal(freeStatus, space.isPresent());
        space.ifPresent(f -> {
            freeStatus.setText(f.text());
            freeTip.setText(f.tooltip());
            if (f.low() != freeStatus.getStyleClass().contains("free-low")) {
                if (f.low()) freeStatus.getStyleClass().add("free-low");
                else freeStatus.getStyleClass().remove("free-low");
            }
        });
    }

    private static void reveal(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /**
     * Poll thread: the free space where downloads go, read every five seconds (a file-store
     * query, cheap but not free) and kept in between.
     */
    private Optional<NetworkStatus.Free> freeSpace(long nowSeconds) {
        // A daemon's files are on its machine: this one's disk says nothing about them.
        if (capabilities.lacks(Engine.Capability.LOCAL_FILES)) {
            freeCheckedAt = 0;
            return Optional.empty();
        }
        if (freeCheckedAt > 0 && nowSeconds - freeCheckedAt < 5) return free;
        freeCheckedAt = nowSeconds;
        Path dir = settings.downloadDir();
        free = FreeSpace.disk(dir).map(d -> NetworkStatus.free(d, folderText(dir)));
        return free;
    }

    /** A folder as the welcome and the status bar write it: {@code ~} under home, off Windows. */
    private static String folderText(Path dir) {
        String home = System.getProperty("user.home");
        boolean tilde = !System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
        return WelcomeText.folder(dir, home == null ? null : Path.of(home), tilde);
    }

    /** FX thread. The session's rates and their last minute, from the history the poll fills. */
    private void showRates(Engine.Rates r, long nowSeconds) {
        downRate.setText(tr("status.rateDown", Bytes.rate(r.download())));
        upRate.setText(tr("status.rateUp", Bytes.rate(r.upload())));
        List<SpeedHistory.Sample> minute = sessionHistory.samples(60, nowSeconds);
        downSpark.show(minute, 60, nowSeconds);
        upSpark.show(minute, 60, nowSeconds);
    }

    /** The status bar's right-hand group as words, for the capture hook. */
    private String statusBarForCapture() {
        List<String> parts = new ArrayList<>(List.of(downRate.getText(), upRate.getText(), altSpeedToggle.getText()));
        if (freeStatus.isVisible()) parts.add(freeStatus.getText());
        if (errorBadge.isVisible()) parts.add(errorBadge.getText());
        return String.join(" | ", parts);
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
