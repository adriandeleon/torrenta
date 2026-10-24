package com.torrenta.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.torrenta.AppInfo;
import com.torrenta.automation.AutomationSettings;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.WatchFolder;
import com.torrenta.engine.transmission.TransmissionEngine;
import com.torrenta.rss.Feed;
import com.torrenta.rss.RssService;
import com.torrenta.rss.Rule;
import com.torrenta.search.SearchException;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.search.TorznabProvider;
import com.torrenta.store.DefinitionStore;
import com.torrenta.store.LabelStore;
import com.torrenta.store.ProfileStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;
import com.torrenta.sync.SyncEngine;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Settings window: a sidebar of pages, a search box that filters rows across all of them, and
 * controls that write through the moment they change. Carried over from Termina's.
 *
 * <p>There is no OK button. Every control applies live and {@link Settings#saveQuietly()} writes
 * the file; Close is just a way to dismiss the window. Reset forgets this window's keys.
 */
final class SettingsWindow {

    /** Sidebar groups; a page belongs to one. */
    enum Group {
        APPLICATION("application"),
        ENGINE("engine");

        private final String id;

        Group(String id) {
            this.id = id;
        }

        /** The sidebar heading in the UI language, read at call time: the language is chosen after enums load. */
        String title() {
            return tr("settings.group." + id);
        }
    }

    /** The pages. Connection, Speed, BitTorrent and Advanced join as their settings arrive. */
    enum Category {
        GENERAL(Group.APPLICATION, "general"),
        ADDING(Group.APPLICATION, "adding"),
        WATCH(Group.APPLICATION, "watchFolders"),
        AUTOMATION(Group.APPLICATION, "automation"),
        SEARCH(Group.APPLICATION, "search"),
        RSS(Group.APPLICATION, "rss"),
        SYNC(Group.APPLICATION, "sync"),
        LABELS(Group.APPLICATION, "labels"),
        ENGINE_PROFILES(Group.ENGINE, "engine"),
        CONNECTION(Group.ENGINE, "connection"),
        PROTOCOL(Group.ENGINE, "protocol"),
        IP_FILTER(Group.ENGINE, "ipFilter"),
        SPEED(Group.ENGINE, "speed"),
        QUEUE(Group.ENGINE, "queue");

        final Group group;
        /** The page's key segment: {@code settings.<id>.*} in the catalogue. */
        final String id;

        Category(Group group, String id) {
            this.group = group;
            this.id = id;
        }

        /** The page title in the UI language, read at call time: the language is chosen after enums load. */
        String title() {
            return tr("settings." + id + ".title");
        }
    }

    /** {@code capability} is what the row needs of the engine, or null for every engine. */
    private record Row(Category category, Node node, String keywords, VBox section, Engine.Capability capability) {
        Row(Category category, Node node, String keywords, VBox section) {
            this(category, node, keywords, section, null);
        }
    }

    /** Rows and pages the engine in use cannot act on; hidden until another engine comes up. */
    private final Set<Node> engineHiddenRows = new java.util.HashSet<>();

    private final Set<Category> engineHiddenPages = new LinkedHashSet<>();
    private String engineQuery = "";

    /** The keys this window owns, for Reset. */
    private static final String[] OWNED_KEYS = concat(
            new String[] {
                Settings.UI_LANGUAGE,
                Settings.THEME,
                Settings.UPDATE_CHECK,
                Settings.ADD_START_PAUSED,
                Settings.ADD_SEQUENTIAL,
                Settings.ADD_CONTENT_LAYOUT,
                Settings.ADD_WITHOUT_ASKING,
                Settings.UI_STATE_COLORS,
                Settings.UI_DENSITY,
                Settings.UI_SIDEBAR,
                Settings.UI_SIDEBAR_SIDE,
                Settings.DETAIL_VISIBLE,
                Settings.DETAIL_SIDE,
                Settings.UI_EXTENDED_WINDOW,
                Settings.UI_PALETTE_KEY,
                Settings.UI_TRAY,
                Settings.UI_CLOSE_TO_TRAY,
                Settings.OS_HANDLER,
                Settings.OS_BADGE
            },
            concat(
                    concat(Settings.DOWNLOAD_KEYS, concat(Settings.AUTOMATION_KEYS, Settings.RSS_KEYS)),
                    concat(
                            Settings.CONNECTION_KEYS,
                            concat(
                                    Settings.PROTOCOL_KEYS,
                                    concat(
                                            Settings.IPFILTER_KEYS,
                                            concat(Settings.SPEED_KEYS, Settings.QUEUE_KEYS))))));

    /** Told after an IP filter setting changed; {@code true} asks for a download now. */
    private java.util.function.Consumer<Boolean> onIpFilterChanged = force -> {};
    /** Told after an appearance setting the main window draws from changed. */
    private Runnable onAppearanceChanged = () -> {};
    /** Told after the tray switch changed; the main window brings the icon up or takes it down. */
    private Runnable onTrayChanged = () -> {};
    /** Told after the handler switch changed; the main window (re)registers or unregisters. */
    private Runnable onHandlerChanged = () -> {};
    /** Told after the badge switch changed; the main window brings the badge up or clears it. */
    private Runnable onBadgeChanged = () -> {};

    private java.util.function.Supplier<String> ipFilterStatus = () -> "";

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Told after any connection setting changed; the window applies them to the engine. */
    private Runnable onEngineSettingsChanged = () -> {};

    /** What the Categories and tags page tells the window, on the FX thread. */
    interface LabelsHost {
        /** A category was added or its save path changed, or a tag was added. */
        void labelsChanged();

        /** The category is gone; the torrents filed under it are to be unfiled. */
        void categoryDeleted(String name);

        /** The tag is gone; the torrents carrying it are to drop it. */
        void tagDeleted(String name);
    }

    private LabelStore labelStore;
    private LabelsHost labelsHost = new LabelsHost() {
        @Override
        public void labelsChanged() {}

        @Override
        public void categoryDeleted(String name) {}

        @Override
        public void tagDeleted(String name) {}
    };
    private TableView<com.torrenta.engine.Category> categoryTable;
    private TextField categoryName;
    private TextField categoryPath;
    private Button categoryDelete;
    private ListView<String> tagList;
    private TextField newTag;
    private Button tagDelete;

    /** The store the Categories and tags page edits, and who to tell. */
    void setLabels(LabelStore store, LabelsHost host) {
        this.labelStore = store;
        this.labelsHost = host;
        reloadLabels();
    }

    /** What the Watch folders page tells the window, on the FX thread. */
    interface WatchHost {
        /** The list changed; the watcher is to be restarted on it. */
        void watchFoldersChanged();

        /** The watcher's state, for the page's status line. */
        String watchStatus();
    }

    private WatchFolderStore watchStore;
    private WatchHost watchHost = new WatchHost() {
        @Override
        public void watchFoldersChanged() {}

        @Override
        public String watchStatus() {
            return "";
        }
    };
    private TableView<WatchFolder> watchTable;
    private Label watchStatusLabel;
    private WatchFolderDialog watchDialog;

    private TextField onAdded;
    private TextField onFinished;
    private TextField onSeedingDone;
    private CheckBox notifyAdded;
    private CheckBox notifyFinished;
    private CheckBox notifySeedingDone;
    private CheckBox notifyError;
    /** The Automation page's Test button; the outcome lands in the event log. */
    private Runnable testNotification = () -> {};

    void setOnTestNotification(Runnable test) {
        this.testNotification = test;
    }

    /** The General page's "Show debug log" button; the window that owns the log's window answers. */
    private Runnable showDebugLog = () -> {};

    void setOnShowDebugLog(Runnable show) {
        this.showDebugLog = show;
    }

    /** The store the Watch folders page edits, and who to tell. */
    void setWatchFolders(WatchFolderStore store, WatchHost host) {
        this.watchStore = store;
        this.watchHost = host;
        reloadWatch();
    }

    /** FX thread: the page's status line, after the watcher moved. */
    void refreshWatchStatus() {
        if (watchStatusLabel != null) watchStatusLabel.setText(watchHost.watchStatus());
    }

    private SearchProviderStore searchStore;
    private Runnable onSearchProvidersChanged = () -> {};
    private TableView<TorznabEndpoint> searchTable;
    private Label searchStatusLabel;
    private SearchProviderDialog searchDialog;
    /** Asks a server for the page's Test; a capture swaps a fake in. */
    private SearchProviderDialog.Tester searchTester = endpoint -> new TorznabProvider(endpoint).capabilities();

    /** The store the Search page edits, and who to tell when the list changed (FX thread). */
    void setSearchProviders(SearchProviderStore store, Runnable onChanged) {
        this.searchStore = store;
        this.onSearchProvidersChanged = onChanged;
        reloadSearch();
    }

    private RssService rssService;
    private TableView<Feed> rssTable;
    private Label rssStatusLabel;
    private FeedDialog rssDialog;
    private Spinner<Integer> rssMinutes;
    private CheckBox rssAutoDownload;
    private TableView<Rule> rulesTable;
    private Label rulesStatusLabel;
    private RuleDialog ruleDialog;

    /** The reader the RSS page edits; its changes come back through the service's listener (FX thread). */
    void setRss(RssService service) {
        this.rssService = service;
        service.addListener(id -> reloadRss());
        reloadRss();
    }

    /** The RSS page's status line, or the open feed dialog's, for a capture; empty when it says nothing. */
    String rssStatus() {
        if (rssDialog != null && rssDialog.isShowing()) return rssDialog.statusText();
        return rssStatusLabel == null ? "" : rssStatusLabel.getText();
    }

    /** Chooses the two layout rows as a person would, by id ({@code right/bottom}); the listeners write and apply. */
    void chooseLayoutForCapture(String sidebarId, String detailId) {
        for (SidebarPlace p : SidebarPlace.values()) if (p.id().equals(sidebarId)) sidebarPlace.setValue(p);
        for (DetailPlace p : DetailPlace.values()) if (p.id().equals(detailId)) detailPlace.setValue(p);
    }

    void hideForCapture() {
        stage.hide();
    }

    /** Opens the Add Feed dialog from the RSS page, on the next pulse, with the URL typed in. */
    void openRssDialogForCapture(String url) {
        selectPageForCapture("RSS");
        javafx.application.Platform.runLater(() -> addRssFeed(url));
    }

    /** Opens the Add RSS Rule dialog from the RSS page, on the next pulse, must-contain typed in. */
    void openRuleDialogForCapture(String mustContain) {
        selectPageForCapture("RSS");
        javafx.application.Platform.runLater(() -> addRule(mustContain.isEmpty() ? "" : mustContain, mustContain));
    }

    /** The Rules table's lines, for a capture: "name | on | feeds | last match". */
    List<String> rulesForCapture() {
        List<String> out = new ArrayList<>();
        if (rulesTable == null || rssService == null) return out;
        Map<String, String> names = rssService.namesById();
        for (Rule r : rulesTable.getItems()) {
            out.add(r.name() + " | " + (r.enabled() ? "on" : "off") + " | " + RuleText.feedsText(r, names) + " | "
                    + RuleText.lastMatchText(r, System.currentTimeMillis()));
        }
        return out;
    }

    /** The open rule dialog's Matches heading and lines, for a capture; empty while none is open. */
    String ruleMatchesForCapture() {
        return ruleDialog != null && ruleDialog.isShowing() ? ruleDialog.matchesForCapture() : "";
    }

    /** The open rule dialog's problem line, for a capture. */
    String ruleProblemForCapture() {
        return ruleDialog != null && ruleDialog.isShowing() ? ruleDialog.problemText() : "";
    }

    /**
     * "Create rule from title": the RSS page, then the Add RSS Rule dialog with the name and
     * must-contain filled from the title. FX thread, the window shown already.
     */
    void createRuleFromTitle(String title) {
        selectPageForCapture("RSS");
        String phrase = RuleText.mustContainFromTitle(title);
        javafx.application.Platform.runLater(() -> addRule(phrase, phrase));
    }

    private DefinitionStore definitionStore;
    private Runnable openDefinitionFolder = () -> {};
    private TableView<DefinitionStore.Entry> definitionTable;
    private Label definitionStatusLabel;

    /**
     * The folder of definition files the Search page lists, and how to open it with the desktop;
     * changes (a reload, a switch) are told through the same hook as the Torznab list's.
     */
    void setSearchDefinitions(DefinitionStore store, Runnable openFolder) {
        this.definitionStore = store;
        this.openDefinitionFolder = openFolder;
        reloadDefinitions();
    }

    /** The definitions table's rows, "name | file | type | status | on", for a capture. */
    List<String> definitionsForCapture() {
        List<String> out = new ArrayList<>();
        if (definitionTable == null) return out;
        Set<String> off = settings.disabledSearchDefinitions();
        for (DefinitionStore.Entry e : definitionTable.getItems()) {
            out.add(e.name() + " | " + e.file().getFileName() + " | " + definitionType(e) + " | " + definitionStatus(e)
                    + " | " + (!e.loaded() ? "" : off.contains(e.id()) ? "off" : "on"));
        }
        return out;
    }

    /** Presses Reload definitions, for a capture. */
    void reloadDefinitionsForCapture() {
        selectPageForCapture("SEARCH");
        reloadDefinitionsPressed();
    }

    /** Shows the window on the Search page: the palette's "Search…" until the tab exists. */
    void showSearchProviders(Window owner) {
        show(owner);
        selectPageForCapture("SEARCH");
    }

    /** The Search page's status line, for a capture; empty when it says nothing. */
    String searchStatus() {
        if (searchDialog != null && searchDialog.isShowing()) return searchDialog.statusText();
        return searchStatusLabel == null ? "" : searchStatusLabel.getText();
    }

    /** The open watch-folder or search-provider dialog's scene, for a capture; null while none is open. */
    Scene dialogScene() {
        if (watchDialog != null && watchDialog.isShowing()) return watchDialog.scene();
        if (searchDialog != null && searchDialog.isShowing()) return searchDialog.scene();
        if (rssDialog != null && rssDialog.isShowing()) return rssDialog.scene();
        if (ruleDialog != null && ruleDialog.isShowing()) return ruleDialog.scene();
        if (profileDialog != null && profileDialog.isShowing()) return profileDialog.scene();
        return null;
    }

    /** The open dialog's title, for a capture; empty while none is open. */
    String dialogTitle() {
        if (watchDialog != null && watchDialog.isShowing()) return watchDialog.title();
        if (searchDialog != null && searchDialog.isShowing()) return searchDialog.title();
        if (rssDialog != null && rssDialog.isShowing()) return rssDialog.title();
        if (ruleDialog != null && ruleDialog.isShowing()) return ruleDialog.title();
        if (profileDialog != null && profileDialog.isShowing()) return profileDialog.title();
        return "";
    }

    /** Dismisses an open dialog, so the capture hook can exit outside its nested event loop. */
    void closeDialogsForCapture() {
        if (watchDialog != null && watchDialog.isShowing()) watchDialog.cancel();
        if (searchDialog != null && searchDialog.isShowing()) searchDialog.cancel();
        if (rssDialog != null && rssDialog.isShowing()) rssDialog.cancel();
        if (ruleDialog != null && ruleDialog.isShowing()) ruleDialog.cancel();
        if (profileDialog != null && profileDialog.isShowing()) profileDialog.cancel();
    }

    /**
     * Opens the Add Search Provider dialog, on the next pulse (a nested event loop cannot start
     * from inside the capture hook's animation), with the URL filled in when one is given, and
     * presses Test when {@code test} is set.
     */
    void openSearchDialogForCapture(String url, boolean test) {
        selectPageForCapture("SEARCH");
        javafx.application.Platform.runLater(() -> {
            if (searchStore == null) return;
            searchDialog = new SearchProviderDialog(stage, null, searchTester);
            searchDialog.prefillUrl(url);
            if (test) javafx.application.Platform.runLater(searchDialog::runTest);
            Optional<TorznabEndpoint> made = searchDialog.showAndWait();
            searchDialog = null;
            made.ifPresent(this::addSearchProvider);
        });
    }

    /** Selects the first provider and presses the page's Test, for a capture. */
    void testSearchProviderForCapture() {
        selectPageForCapture("SEARCH");
        if (searchTable == null || searchTable.getItems().isEmpty()) return;
        searchTable.getSelectionModel().selectFirst();
        testSearchProvider();
    }

    /**
     * Opens the Add Watch Folder dialog, on the next pulse: a nested event loop cannot start from
     * inside the capture hook's animation.
     */
    void openWatchDialogForCapture() {
        selectPageForCapture("WATCH");
        javafx.application.Platform.runLater(this::addWatchFolder);
    }

    private final Settings settings;
    private final Stage stage = new Stage();
    private final Map<Category, Region> pages = new EnumMap<>(Category.class);
    private final List<Row> rows = new ArrayList<>();
    private final ListView<Object> sidebar = new ListView<>();
    private final StackPane pageHost = new StackPane();
    private final TextField search = new TextField();
    private final Set<Category> hiddenCategories = new LinkedHashSet<>();

    /** Guards control listeners while a page is being populated from stored values. */
    private boolean loading;

    private ChoiceBox<String> language;
    private ChoiceBox<Theme> theme;
    private TextField downloadDir;
    private CheckBox incompleteEnabled;
    private TextField incompleteDir;
    private CheckBox partSuffix;
    private CheckBox preallocate;
    private CheckBox updateCheck;
    private CheckBox stateColors;
    private ChoiceBox<Density> density;
    private ChoiceBox<SidebarPlace> sidebarPlace;
    private ChoiceBox<DetailPlace> detailPlace;
    private CheckBox extendedWindow;
    private ChoiceBox<PaletteKey> paletteKey;
    private CheckBox tray;
    private CheckBox closeToTray;
    private CheckBox osHandler;
    private CheckBox osBadge;
    private CheckBox addStartPaused;
    private CheckBox addSequential;
    private ChoiceBox<com.torrenta.engine.FileRenames.Layout> addLayout;
    private CheckBox addWithoutAsking;
    private Spinner<Integer> port;
    private CheckBox randomPort;
    private TextField listenInterface;
    private CheckBox ipv6;
    private CheckBox upnp;
    private Spinner<Integer> maxConnections;
    private Spinner<Integer> maxPerTorrent;
    private Spinner<Integer> uploadSlots;
    private ChoiceBox<EngineSettings.ProxyType> proxyType;
    private TextField proxyHost;
    private Spinner<Integer> proxyPort;
    private TextField proxyUsername;
    private PasswordField proxyPassword;
    private CheckBox proxyPeers;
    private CheckBox dht;
    private CheckBox pex;
    private CheckBox lsd;
    private CheckBox utp;
    private ChoiceBox<EngineSettings.Protocol.Encryption> encryption;
    private CheckBox anonymous;
    private TextArea defaultTrackers;

    void setOnEngineSettingsChanged(Runnable handler) {
        onEngineSettingsChanged = handler;
    }

    private SettingsSync sync;
    private CheckBox syncEnabled;
    private TextField syncUrl;
    private TextField syncBranch;
    private Spinner<Integer> syncInterval;
    private Label syncStatus;
    private Button syncNow;
    private Button syncDisconnect;

    /** The service the Sync page drives and reports on. */
    void setSync(SettingsSync sync) {
        this.sync = sync;
        sync.addListener(this::refreshSyncStatus);
        refreshSyncStatus();
    }

    /** FX thread: the live settings were replaced by a sync; show them. */
    void reloadFromSettings() {
        reload();
    }

    private void buildSync(VBox page) {
        VBox repository = section(page, tr("settings.sync.repository"));
        syncEnabled = new CheckBox();
        syncEnabled.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            if (now) connectSync();
            else {
                settings.setSyncEnabled(false);
                settings.saveQuietly();
                if (sync != null) sync.settingsChanged();
            }
        });
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.enabled"),
                tr("settings.sync.enabled.desc"),
                syncEnabled,
                tr("settings.sync.enabled.keywords"));
        syncUrl = new TextField();
        syncUrl.setPrefColumnCount(18);
        syncUrl.setPromptText("https://github.com/you/torrenta-settings.git");
        syncUrl.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setSyncUrl(now);
            settings.saveQuietly();
            if (sync != null) sync.settingsChanged();
        });
        Button create = new Button(tr("settings.sync.createOnGitHub"));
        create.setOnAction(e -> createSyncRepository());
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.url"),
                tr("settings.sync.url.desc"),
                new HBox(6, syncUrl, create),
                tr("settings.sync.url.keywords"));
        syncBranch = new TextField();
        syncBranch.setPrefColumnCount(10);
        syncBranch.setPromptText("main");
        syncBranch.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setSyncBranch(now);
            settings.saveQuietly();
        });
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.branch"),
                tr("settings.sync.branch.desc"),
                syncBranch,
                tr("settings.sync.branch.keywords"));
        syncInterval = new Spinner<>(1, 1440, 15, 5);
        syncInterval.setEditable(true);
        Spinners.commitOnBlur(syncInterval);
        syncInterval.setPrefWidth(90);
        syncInterval.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setSyncIntervalMinutes(now);
            settings.saveQuietly();
            if (sync != null) sync.settingsChanged();
        });
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.interval"),
                tr("settings.sync.interval.desc"),
                syncInterval,
                tr("settings.sync.interval.keywords"));

        VBox status = section(page, tr("settings.sync.status"));
        syncStatus = new Label(tr("settings.sync.notConnected"));
        syncStatus.setWrapText(true);
        syncStatus.getStyleClass().add("settings-row-description");
        syncNow = new Button(tr("settings.sync.syncNow"));
        syncNow.setOnAction(e -> {
            if (sync != null) sync.syncNow();
        });
        syncDisconnect = new Button(tr("settings.sync.disconnect"));
        syncDisconnect.setOnAction(e -> disconnectSync());
        VBox statusBox = new VBox(8, syncStatus, new HBox(8, syncNow, syncDisconnect));
        status.getChildren().add(statusBox);
        rows.add(new Row(Category.SYNC, statusBox, tr("settings.sync.status.keywords"), status));
    }

    /** The switch went on: refuse a URL with a secret, show what connecting brings, then sync. */
    private void connectSync() {
        String url = syncUrl.getText().trim();
        if (!SyncEngine.isUsableUrl(url)) {
            syncEnabled.setSelected(false);
            syncStatus.setText(tr("settings.sync.enterUrl"));
            return;
        }
        if (SyncEngine.hasCredentials(url)) {
            syncEnabled.setSelected(false);
            syncStatus.setText(tr("settings.sync.urlHasSecret"));
            return;
        }
        settings.setSyncUrl(url);
        settings.setSyncEnabled(true);
        settings.saveQuietly();
        if (sync == null) return;
        syncStatus.setText(tr("settings.sync.looking"));
        sync.preview(url, settings.syncBranch(), report -> {
            if (!report.ok()) {
                settings.setSyncEnabled(false);
                settings.saveQuietly();
                reload();
                syncStatus.setText(report.summary());
                return;
            }
            if (!report.received().isEmpty()) {
                Alert ask = new Alert(
                        Alert.AlertType.CONFIRMATION,
                        tr(
                                "settings.sync.connectQuestion",
                                report.received().size(),
                                report.conflicts().size()));
                ask.setTitle(tr("settings.sync.connectHeader"));
                ask.setHeaderText(tr("settings.sync.connectHeader"));
                ask.initOwner(stage);
                Fonts.installUiFont(ask.getDialogPane());
                StockButtons.translate(ask);
                if (!StockButtons.confirmed(ask.showAndWait())) {
                    settings.setSyncEnabled(false);
                    settings.saveQuietly();
                    reload();
                    syncStatus.setText(tr("settings.sync.notConnected"));
                    return;
                }
            }
            sync.settingsChanged();
            sync.syncNow();
        });
    }

    private void createSyncRepository() {
        TextInputDialog ask = new TextInputDialog("torrenta-settings");
        ask.initOwner(stage);
        ask.setTitle(tr("settings.sync.create.title"));
        ask.setHeaderText(tr("settings.sync.create.header"));
        ask.setContentText(tr("settings.sync.create.name"));
        StockButtons.translate(ask);
        Fonts.installUiFont(ask.getDialogPane());
        ask.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (sync == null) return;
            syncStatus.setText(tr("settings.sync.creating", name));
            sync.createOnGitHub(name, created -> {
                if (created.ok()) {
                    syncUrl.setText(created.url());
                    syncStatus.setText(tr("settings.sync.created"));
                } else {
                    syncStatus.setText(tr("settings.sync.ghError", created.error()));
                }
            });
        });
    }

    private void disconnectSync() {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, tr("settings.sync.disconnectQuestion"));
        ask.setTitle(tr("settings.sync.disconnect"));
        ask.setHeaderText(tr("settings.sync.disconnect"));
        ask.initOwner(stage);
        Fonts.installUiFont(ask.getDialogPane());
        StockButtons.translate(ask);
        if (!StockButtons.confirmed(ask.showAndWait())) return;
        if (sync != null) sync.disconnect();
        reload();
    }

    /** FX thread: the Status section, from the service's state. */
    private void refreshSyncStatus() {
        if (syncStatus == null || sync == null) return;
        SettingsSync.State s = sync.state();
        String text =
                switch (s.phase()) {
                    case OFF -> tr("settings.sync.notConnected");
                    case SYNCING -> tr("settings.sync.syncing");
                    case IDLE ->
                        s.report() == null
                                ? tr("settings.sync.firstDue")
                                : tr(
                                        "settings.sync.lastSync",
                                        at(s.at()),
                                        s.report().summary());
                    case PROBLEM -> {
                        String summary = s.report() == null ? "" : s.report().summary();
                        yield s.at() == null
                                ? tr("settings.sync.problem", summary)
                                : tr("settings.sync.problemAt", summary, at(s.at()));
                    }
                };
        if (!s.account().isEmpty()) text += "\n" + tr("settings.sync.signingIn", s.account());
        syncStatus.setText(text);
        boolean on = s.phase() != SettingsSync.Phase.OFF;
        syncNow.setDisable(!on || s.phase() == SettingsSync.Phase.SYNCING);
        syncDisconnect.setDisable(!on && settings.syncUrl().isEmpty());
    }

    private static String at(java.time.LocalDateTime when) {
        return when == null ? "" : when.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    void setOnAppearanceChanged(Runnable handler) {
        onAppearanceChanged = handler;
    }

    void setOnTrayChanged(Runnable handler) {
        onTrayChanged = handler;
    }

    void setOnHandlerChanged(Runnable handler) {
        onHandlerChanged = handler;
    }

    void setOnBadgeChanged(Runnable handler) {
        onBadgeChanged = handler;
    }

    void setOnIpFilterChanged(
            java.util.function.Consumer<Boolean> handler, java.util.function.Supplier<String> status) {
        onIpFilterChanged = handler;
        ipFilterStatus = status;
    }

    /** FX thread: the page's status line, after an apply. */
    void refreshIpFilterStatus() {
        if (ipFilterStatusLabel != null) ipFilterStatusLabel.setText(ipFilterStatus.get());
    }

    private CheckBox ipFilterEnabled;
    private TextField ipFilterUrl;
    private Spinner<Integer> ipFilterHours;
    private TextField ipFilterFile;
    private TextArea ipFilterBans;
    private Label ipFilterStatusLabel;
    private Spinner<Integer> speedDown;
    private Spinner<Integer> speedUp;
    private Spinner<Integer> altDown;
    private Spinner<Integer> altUp;
    private CheckBox altOn;
    private CheckBox scheduleOn;
    private TextField scheduleFrom;
    private TextField scheduleTo;
    private final java.util.Map<java.time.DayOfWeek, CheckBox> scheduleDays =
            new java.util.EnumMap<>(java.time.DayOfWeek.class);
    private CheckBox countOverhead;
    private Spinner<Integer> activeDownloads;
    private Spinner<Integer> activeSeeds;
    private Spinner<Integer> activeLimit;
    private CheckBox ignoreSlow;
    private Spinner<Double> shareRatio;
    private Spinner<Integer> shareMinutes;
    private ChoiceBox<ShareLimit.Action> shareAction;

    SettingsWindow(Settings settings) {
        this.settings = settings;
        build();
    }

    /** Types into the search box as the user would, so a capture can reach any row. */
    void searchForCapture(String query) {
        search.setText(query);
    }

    /** Selects a page by its enum name, so a capture can photograph it whole. */
    void selectPageForCapture(String name) {
        for (Category category : Category.values()) {
            if (category.name().equalsIgnoreCase(name))
                sidebar.getSelectionModel().select(category);
        }
    }

    Scene scene() {
        return stage.getScene();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void show(Window owner) {
        if (owner != null && stage.getOwner() == null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // A search is for the visit it was typed in: the window opens again on every row.
        search.clear();
        reload();
        stage.show();
    }

    // ---------------------------------------------------------------- shell

    private void build() {
        stage.setTitle(tr("settings.title", AppInfo.NAME));
        for (Category category : Category.values()) pages.put(category, buildPage(category));

        search.setPromptText(tr("settings.searchBox.prompt"));
        search.textProperty().addListener((o, old, value) -> filter(value));
        VBox.setMargin(search, new Insets(0, 0, 8, 0));

        sidebar.setPrefWidth(sidebarWidth());
        sidebar.setMinWidth(150);
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.setCellFactory(list -> new SidebarCell());
        sidebar.getItems().setAll(sidebarItems());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item instanceof Category category) showPage(category);
        });

        pageHost.setPadding(new Insets(4, 0, 0, PAGE_GUTTER));
        pageHost.setAlignment(Pos.TOP_LEFT);

        Button reset = new Button(tr("settings.reset"));
        reset.setOnAction(e -> resetToDefaults());
        Button close = new Button(tr("common.close"));
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, reset, spacer, close);
        footer.setPadding(new Insets(12, 0, 0, 0));
        footer.setAlignment(Pos.CENTER_LEFT);

        BorderPane body = new BorderPane();
        body.setLeft(sidebar);
        body.setCenter(pageHost);

        VBox root = new VBox(search, body, footer);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.setPadding(new Insets(14));

        javafx.geometry.Rectangle2D visible = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene =
                new Scene(root, Math.min(900, visible.getWidth() * 0.9), Math.min(640, visible.getHeight() * 0.9));
        scene.getStylesheets()
                .add(SettingsWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        scene.getStylesheets()
                .add(SettingsWindow.class
                        .getResource("/com/torrenta/styles/settings.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            if (escapeClears(search.getText())) search.clear();
            else stage.hide();
            e.consume();
        });
        stage.setScene(scene);
        selectFirstVisible();
    }

    private List<Object> sidebarItems() {
        List<Object> items = new ArrayList<>();
        Group current = null;
        for (Category category : Category.values()) {
            if (engineHiddenPages.contains(category)) continue;
            if (category.group != current) {
                current = category.group;
                items.add(current);
            }
            items.add(category);
        }
        return items;
    }

    /** Group headers are labels, not choices — they must not be selectable or clickable. */
    /**
     * Wide enough for the longest page title in the interface language (German's "Warteschlange und
     * Verteilungslimits" is twice the English), never narrower than the 190 the English layout had,
     * so no title is cut and the list never grows a horizontal scrollbar.
     */
    private static double sidebarWidth() {
        double widest = 0;
        for (Category category : Category.values()) {
            javafx.scene.text.Text text = new javafx.scene.text.Text(category.title());
            widest = Math.max(widest, text.getLayoutBounds().getWidth());
        }
        // The cell's 20 + 10 padding, the glyph and its gap, and room for the list's own border.
        return Math.max(190, Math.ceil(widest) + 30 + Icons.MENU_SIZE + 8 + 14);
    }

    private final class SidebarCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("settings-group-header", "settings-sidebar-item", "settings-dimmed");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setDisable(false);
                setMouseTransparent(false);
                return;
            }
            if (item instanceof Group group) {
                setText(group.title().toUpperCase(Locale.ROOT));
                setGraphic(null);
                getStyleClass().add("settings-group-header");
                setMouseTransparent(true);
                setDisable(false);
            } else {
                Category category = (Category) item;
                setText(category.title());
                // A square box per glyph, so the titles line up whatever each glyph's own width.
                StackPane glyph =
                        new StackPane(Icons.forSettingsPage(category.id).at(Icons.MENU_SIZE));
                glyph.setMinSize(Icons.MENU_SIZE, Icons.MENU_SIZE);
                glyph.setMaxSize(Icons.MENU_SIZE, Icons.MENU_SIZE);
                setGraphic(glyph);
                getStyleClass().add("settings-sidebar-item");
                setMouseTransparent(false);
                boolean dimmed = hiddenCategories.contains(category);
                setDisable(dimmed);
                if (dimmed) getStyleClass().add("settings-dimmed");
            }
        }
    }

    private void showPage(Category category) {
        pageHost.getChildren().setAll(pages.get(category));
    }

    private void selectFirstVisible() {
        for (Object item : sidebar.getItems()) {
            if (item instanceof Category category && !hiddenCategories.contains(category)) {
                sidebar.getSelectionModel().select(item);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- reset

    /** What a Reset re-applies only when its key changed, since each one acts on the desktop. */
    enum ResetHook {
        THEME,
        TRAY,
        HANDLER,
        BADGE
    }

    /** The hooks a Reset must run, from the owned keys' values before and after it (null: unset). */
    static Set<ResetHook> resetHooks(Map<String, String> before, Map<String, String> after) {
        Set<ResetHook> hooks = java.util.EnumSet.noneOf(ResetHook.class);
        java.util.function.Predicate<String> changed =
                key -> !java.util.Objects.equals(before.get(key), after.get(key));
        if (changed.test(Settings.THEME)) hooks.add(ResetHook.THEME);
        if (changed.test(Settings.UI_TRAY) || changed.test(Settings.UI_CLOSE_TO_TRAY)) hooks.add(ResetHook.TRAY);
        if (changed.test(Settings.OS_HANDLER)) hooks.add(ResetHook.HANDLER);
        if (changed.test(Settings.OS_BADGE)) hooks.add(ResetHook.BADGE);
        return hooks;
    }

    private Map<String, String> ownedValues() {
        Map<String, String> values = new java.util.HashMap<>();
        for (String key : OWNED_KEYS) values.put(key, settings.get(key, null));
        return values;
    }

    /**
     * Footer's Reset, after a confirm: every owned key back to its default, the controls
     * reloaded, and everything those keys drive re-applied, as a live edit of each would have.
     */
    private void resetToDefaults() {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, tr("settings.reset.question"));
        ask.setTitle(tr("settings.reset"));
        ask.setHeaderText(tr("settings.reset.header"));
        ask.initOwner(stage);
        Fonts.installUiFont(ask.getDialogPane());
        StockButtons.translate(ask);
        if (!StockButtons.confirmed(ask.showAndWait())) return;
        Map<String, String> before = ownedValues();
        settings.reset(OWNED_KEYS);
        settings.saveQuietly();
        Set<ResetHook> hooks = resetHooks(before, ownedValues());
        reload();
        if (hooks.contains(ResetHook.THEME)) {
            javafx.application.Application.setUserAgentStylesheet(
                    Theme.byId(settings.theme(), Theme.DEFAULT).stylesheet());
        }
        onAppearanceChanged.run();
        onEngineSettingsChanged.run();
        onIpFilterChanged.accept(false);
        if (hooks.contains(ResetHook.TRAY)) onTrayChanged.run();
        if (hooks.contains(ResetHook.HANDLER)) onHandlerChanged.run();
        if (hooks.contains(ResetHook.BADGE)) onBadgeChanged.run();
    }

    // ---------------------------------------------------------------- search

    /** Case-insensitive substring over a row's own keywords. */
    static boolean matches(String query, String keywords) {
        if (query == null || query.isBlank()) return true;
        return keywords.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * How well a row answers a query, by where the words are: 3 in its title, 2 in its description,
     * 1 in its keywords only, 0 not at all. {@code keywords} is {@code title | description |
     * keywords}, or {@code title | keywords} for a row with no description of its own.
     */
    static int rank(String query, String keywords) {
        if (query == null || query.isBlank()) return 0;
        String q = query.trim().toLowerCase(Locale.ROOT);
        String[] parts = keywords.toLowerCase(Locale.ROOT).split(" \\| ", 3);
        if (parts[0].contains(q)) return 3;
        if (parts.length == 3 && parts[1].contains(q)) return 2;
        for (int i = 1; i < parts.length; i++) if (parts[i].contains(q)) return 1;
        return 0;
    }

    /**
     * The page a search should show: the one holding the best-ranked row ({@link #rank}), the
     * earlier one in sidebar order on a tie; empty when nothing matches. "proxy" opened Sync, whose
     * description names the proxy password, ahead of Connection, whose rows are the proxy.
     *
     * @param rows each row's page and keywords, in sidebar order
     */
    static <P> java.util.Optional<P> bestPage(String query, List<Map.Entry<P, String>> rows) {
        P best = null;
        int bestRank = 0;
        for (Map.Entry<P, String> row : rows) {
            int r = rank(query, row.getValue());
            if (r > bestRank) {
                bestRank = r;
                best = row.getKey();
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** Escape in the window: a search in force is cleared first, and only then does it close. */
    static boolean escapeClears(String query) {
        return query != null && !query.isEmpty();
    }

    /** The search box's text and the page shown, for a capture. */
    String searchStateForCapture() {
        Object page = sidebar.getSelectionModel().getSelectedItem();
        return "query=\"" + search.getText() + "\" page=" + (page instanceof Category c ? c.name() : "none");
    }

    /** Presses a key in the window, where the focus is, as a person would; for the capture hook. */
    void keyForCapture(KeyCode code) {
        Node target = stage.getScene().getFocusOwner() != null
                ? stage.getScene().getFocusOwner()
                : stage.getScene().getRoot();
        target.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    /** The visible rows' titles, for a capture. */
    List<String> visibleRowsForCapture() {
        List<String> out = new ArrayList<>();
        for (Row row : rows) if (row.node().isManaged()) out.add(row.keywords().split(" \\| ")[0]);
        return out;
    }

    private void filter(String query) {
        engineQuery = query == null ? "" : query;
        hiddenCategories.clear();
        Set<Category> withHits = new LinkedHashSet<>();
        for (Row row : rows) {
            boolean visible = matches(query, row.keywords()) && !engineHiddenRows.contains(row.node());
            row.node().setVisible(visible);
            row.node().setManaged(visible);
            if (visible) withHits.add(row.category());
        }
        // A section whose every row is filtered out would otherwise leave a stray heading behind.
        for (Row row : rows) {
            VBox section = row.section();
            boolean any = section.getChildren().stream().skip(1).anyMatch(Node::isManaged);
            section.setVisible(any);
            section.setManaged(any);
        }
        for (Category category : Category.values()) {
            if (!withHits.contains(category)) hiddenCategories.add(category);
        }
        sidebar.refresh();
        // A search shows the page that answers it best; with none, the page shown stays if it can.
        List<Map.Entry<Category, String>> visibleRows = rows.stream()
                .filter(row -> !engineHiddenRows.contains(row.node()))
                .filter(row -> !engineHiddenPages.contains(row.category()))
                .sorted(java.util.Comparator.comparing(Row::category))
                .map(row -> Map.entry(row.category(), row.keywords()))
                .toList();
        java.util.Optional<Category> best = bestPage(query, visibleRows);
        if (best.isPresent()) {
            sidebar.getSelectionModel().select(best.get());
            return;
        }
        Object selected = sidebar.getSelectionModel().getSelectedItem();
        if (!(selected instanceof Category category) || hiddenCategories.contains(category)) selectFirstVisible();
    }

    // ---------------------------------------------------------------- page building

    private static final double PAGE_GUTTER = 14;

    private Region buildPage(Category category) {
        VBox page = new VBox(4);
        page.setFillWidth(true);
        switch (category) {
            case GENERAL -> buildGeneral(page);
            case ENGINE_PROFILES -> buildEngine(page);
            case ADDING -> buildAdding(page);
            case WATCH -> buildWatch(page);
            case AUTOMATION -> buildAutomation(page);
            case SEARCH -> buildSearch(page);
            case RSS -> buildRss(page);
            case CONNECTION -> buildConnection(page);
            case PROTOCOL -> buildProtocol(page);
            case IP_FILTER -> buildIpFilter(page);
            case SYNC -> buildSync(page);
            case LABELS -> buildLabels(page);
            case SPEED -> buildSpeed(page);
            case QUEUE -> buildQueue(page);
        }
        page.setPadding(new Insets(0, PAGE_GUTTER, 0, 0));
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private VBox section(VBox page, String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("settings-section-header");
        VBox box = new VBox(6, heading);
        box.setPadding(new Insets(10, 0, 10, 0));
        page.getChildren().add(box);
        return box;
    }

    /** A titled row with an explanatory line under it. The title leads the keywords, for captures. */
    private Node row(
            Category category, VBox section, String title, String description, Region control, String keywords) {
        return row(category, section, title, description, control, keywords, null);
    }

    /** A row the engine in use may lack: hidden by {@link #applyCapabilities} when it does. */
    private Node row(
            Category category,
            VBox section,
            String title,
            String description,
            Region control,
            String keywords,
            Engine.Capability capability) {
        Label name = new Label(title);
        name.getStyleClass().add("settings-row-title");
        VBox text = new VBox(2, name);
        if (description != null && !description.isBlank()) {
            Label hint = new Label(description);
            hint.getStyleClass().add("settings-row-description");
            hint.setWrapText(true);
            text.getChildren().add(hint);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        control.setMinWidth(Region.USE_PREF_SIZE);
        HBox line = new HBox(12, text, control);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("settings-row");
        section.getChildren().add(line);
        String hint = description == null ? "" : description;
        // The section's heading is a keyword of each of its rows: "proxy" finds the proxy's Host.
        String heading = section.getChildren().getFirst() instanceof Label l ? " " + l.getText() : "";
        rows.add(new Row(category, line, title + " | " + hint + " | " + keywords + heading, section, capability));
        return line;
    }

    /**
     * FX thread. Hides the rows the engine has no capability for, and drops from the sidebar a
     * page left with no row; the search filter keeps them hidden. Everything comes back for an
     * engine that has it all.
     */
    void applyCapabilities(Engine.Capabilities caps) {
        boolean wasLoading = loading;
        loading = true;
        try {
            ShareLimit.Action chosen = shareAction.getValue();
            shareAction.getItems().setAll(CapabilityRules.shareActions(caps));
            shareAction.setValue(chosen);
        } finally {
            loading = wasLoading;
        }
        engineHiddenRows.clear();
        engineHiddenPages.clear();
        for (Row row : rows) {
            if (row.capability() != null && caps.lacks(row.capability())) engineHiddenRows.add(row.node());
        }
        for (Category category : Category.values()) {
            if (!CapabilityRules.pageShown(category.name(), caps)) {
                engineHiddenPages.add(category);
                continue;
            }
            boolean anyRow = false;
            boolean anyShown = false;
            for (Row row : rows) {
                if (row.category() != category) continue;
                anyRow = true;
                if (!engineHiddenRows.contains(row.node())) anyShown = true;
            }
            if (anyRow && !anyShown) engineHiddenPages.add(category);
        }
        Object selected = sidebar.getSelectionModel().getSelectedItem();
        sidebar.getItems().setAll(sidebarItems());
        filter(engineQuery);
        if (selected instanceof Category c && sidebar.getItems().contains(c))
            sidebar.getSelectionModel().select(c);
        else selectFirstVisible();
    }

    // ---------------------------------------------------------------- pages

    private void buildGeneral(VBox page) {
        VBox languageSection = section(page, tr("settings.general.language.section"));
        language = new ChoiceBox<String>();
        language.getItems().add(com.torrenta.i18n.Messages.SYSTEM);
        language.getItems().addAll(com.torrenta.i18n.Messages.available().keySet());
        language.setConverter(new StringConverter<>() {
            @Override
            public String toString(String code) {
                return code == null || com.torrenta.i18n.Messages.isAutomatic(code)
                        ? tr("settings.general.language.system")
                        : com.torrenta.i18n.Messages.languageName(code);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        language.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setUiLanguage(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                languageSection,
                tr("settings.general.language"),
                tr("settings.general.language.desc"),
                language,
                tr("settings.general.language.keywords"));

        VBox appearance = section(page, tr("settings.general.appearance"));
        theme = new ChoiceBox<>();
        theme.getItems().addAll(Theme.values());
        theme.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setTheme(now.id());
            settings.saveQuietly();
            // Global: every open window restyles, this one included.
            javafx.application.Application.setUserAgentStylesheet(now.stylesheet());
            // The main window draws a few things from the theme itself (the window buttons).
            onAppearanceChanged.run();
        });
        row(
                Category.GENERAL,
                appearance,
                tr("settings.general.theme"),
                tr("settings.general.theme.desc"),
                theme,
                tr("settings.general.theme.keywords"));
        density = new ChoiceBox<>();
        density.getItems().addAll(Density.values());
        density.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setDensity(now.id());
            settings.saveQuietly();
            onAppearanceChanged.run();
        });
        row(
                Category.GENERAL,
                appearance,
                tr("settings.general.density"),
                tr("settings.general.density.desc"),
                density,
                tr("settings.general.density.keywords"));
        // Each a pair of keys: whether it shows and where, so hiding keeps the side.
        sidebarPlace = new ChoiceBox<>();
        sidebarPlace.getItems().addAll(SidebarPlace.values());
        sidebarPlace.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setSidebar(now.shown());
            if (now.shown()) settings.setSidebarSide(now.id());
            settings.saveQuietly();
            onAppearanceChanged.run();
        });
        row(
                Category.GENERAL,
                appearance,
                tr("settings.general.sidebarPlace"),
                tr("settings.general.sidebarPlace.desc"),
                sidebarPlace,
                tr("settings.general.sidebarPlace.keywords"));
        detailPlace = new ChoiceBox<>();
        detailPlace.getItems().addAll(DetailPlace.values());
        detailPlace.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setDetailVisible(now.shown());
            if (now.shown()) settings.setDetailSide(now.id());
            settings.saveQuietly();
            onAppearanceChanged.run();
        });
        row(
                Category.GENERAL,
                appearance,
                tr("settings.general.detailPlace"),
                tr("settings.general.detailPlace.desc"),
                detailPlace,
                tr("settings.general.detailPlace.keywords"));
        stateColors = new CheckBox();
        stateColors.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setStateColors(now);
            settings.saveQuietly();
            onAppearanceChanged.run();
        });
        row(
                Category.GENERAL,
                appearance,
                tr("settings.general.stateColors"),
                tr("settings.general.stateColors.desc"),
                stateColors,
                tr("settings.general.stateColors.keywords"));
        extendedWindow = new CheckBox();
        extendedWindow.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setExtendedWindow(now);
            settings.saveQuietly();
            onAppearanceChanged.run();
        });
        // Not offered on macOS: the menu belongs to the system menu bar there, so the setting could
        // only ever be a switch that does nothing. Disabled rather than hidden, so the note still
        // explains why.
        extendedWindow.setDisable(!ExtendedWindow.supportedOn(System.getProperty("os.name")));
        row(
                Category.GENERAL,
                appearance,
                tr("settings.general.extendedWindow"),
                tr("settings.general.extendedWindow.desc"),
                extendedWindow,
                tr("settings.general.extendedWindow.keywords"));

        VBox keyboard = section(page, tr("settings.general.keyboard"));
        paletteKey = new ChoiceBox<>();
        paletteKey.getItems().addAll(PaletteKey.values());
        paletteKey.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setPaletteKey(now.id());
            settings.saveQuietly();
            onAppearanceChanged.run();
        });
        row(
                Category.GENERAL,
                keyboard,
                tr("settings.general.paletteKey"),
                tr("settings.general.paletteKey.desc"),
                paletteKey,
                tr("settings.general.paletteKey.keywords"));

        VBox trayBox = section(page, tr("settings.general.systemTray"));
        tray = new CheckBox();
        tray.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setTray(now);
            settings.saveQuietly();
            onTrayChanged.run();
        });
        row(
                Category.GENERAL,
                trayBox,
                tr("settings.general.trayIcon"),
                tr("settings.general.trayIcon.desc"),
                tray,
                tr("settings.general.trayIcon.keywords"));
        closeToTray = new CheckBox();
        closeToTray.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setCloseToTray(now);
            settings.saveQuietly();
        });
        // Bound, never set: setDisable on a bound property throws.
        closeToTray.disableProperty().bind(tray.selectedProperty().not());
        row(
                Category.GENERAL,
                trayBox,
                tr("settings.general.closeToTray"),
                tr("settings.general.closeToTray.desc"),
                closeToTray,
                tr("settings.general.closeToTray.keywords"));

        VBox desktop = section(page, tr("settings.general.operatingSystem"));
        osHandler = new CheckBox();
        osHandler.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setOsHandler(now);
            settings.saveQuietly();
            onHandlerChanged.run();
        });
        row(
                Category.GENERAL,
                desktop,
                tr("settings.general.osHandler"),
                tr("settings.general.osHandler.desc"),
                osHandler,
                tr("settings.general.osHandler.keywords"));
        osBadge = new CheckBox();
        osBadge.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setOsBadge(now);
            settings.saveQuietly();
            onBadgeChanged.run();
        });
        row(
                Category.GENERAL,
                desktop,
                tr("settings.general.osBadge"),
                tr("settings.general.osBadge.desc"),
                osBadge,
                tr("settings.general.osBadge.keywords"));

        VBox downloads = section(page, tr("settings.general.downloads"));
        downloadDir = new TextField();
        downloadDir.setPrefColumnCount(28);
        downloadDir.textProperty().addListener((o, was, now) -> {
            if (loading || now.isBlank()) return;
            settings.downloadDir(Path.of(now.trim()));
            settings.saveQuietly();
        });
        Button browse = new Button(tr("common.browse"));
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("settings.general.downloadDir"));
            File current = new File(downloadDir.getText().trim());
            while (current != null && !current.isDirectory()) current = current.getParentFile();
            if (current != null) chooser.setInitialDirectory(current);
            File chosen = chooser.showDialog(stage);
            if (chosen != null) downloadDir.setText(chosen.getAbsolutePath());
        });
        row(
                Category.GENERAL,
                downloads,
                tr("settings.general.downloadDir"),
                tr("settings.general.downloadDir.desc"),
                new HBox(6, downloadDir, browse),
                tr("settings.general.downloadDir.keywords"),
                Engine.Capability.LOCAL_FILES);

        incompleteEnabled = new CheckBox();
        incompleteEnabled.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setIncompleteEnabled(now);
            settings.saveQuietly();
        });
        incompleteDir = new TextField();
        incompleteDir.setPrefColumnCount(22);
        incompleteDir.textProperty().addListener((o, was, now) -> {
            if (loading || now.isBlank()) return;
            settings.setIncompleteDir(Path.of(now.trim()));
            settings.saveQuietly();
        });
        incompleteDir
                .disableProperty()
                .bind(incompleteEnabled.selectedProperty().not());
        Button browseIncomplete = new Button(tr("common.browse"));
        browseIncomplete
                .disableProperty()
                .bind(incompleteEnabled.selectedProperty().not());
        browseIncomplete.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("settings.general.incompleteDir"));
            File current = new File(incompleteDir.getText().trim());
            while (current != null && !current.isDirectory()) current = current.getParentFile();
            if (current != null) chooser.setInitialDirectory(current);
            File chosen = chooser.showDialog(stage);
            if (chosen != null) incompleteDir.setText(chosen.getAbsolutePath());
        });
        HBox incompleteRow = new HBox(6, incompleteEnabled, incompleteDir, browseIncomplete);
        incompleteRow.setAlignment(Pos.CENTER_LEFT);
        row(
                Category.GENERAL,
                downloads,
                tr("settings.general.incompleteDir"),
                tr("settings.general.incompleteDir.desc"),
                incompleteRow,
                tr("settings.general.incompleteDir.keywords"),
                Engine.Capability.STORAGE_OPTIONS);
        partSuffix = new CheckBox();
        partSuffix.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setPartSuffix(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                downloads,
                tr("settings.general.partSuffix"),
                tr("settings.general.partSuffix.desc"),
                partSuffix,
                tr("settings.general.partSuffix.keywords"),
                Engine.Capability.STORAGE_OPTIONS);
        preallocate = new CheckBox();
        preallocate.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setPreallocate(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                downloads,
                tr("settings.general.preallocate"),
                tr("settings.general.preallocate.desc"),
                preallocate,
                tr("settings.general.preallocate.keywords"),
                Engine.Capability.STORAGE_OPTIONS);

        VBox updates = section(page, tr("settings.general.updates"));
        updateCheck = new CheckBox();
        updateCheck.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setUpdateCheck(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                updates,
                tr("settings.general.updateCheck"),
                tr("settings.general.updateCheck.desc"),
                updateCheck,
                tr("settings.general.updateCheck.keywords"));

        VBox diagnostics = section(page, tr("settings.general.diagnostics"));
        Button debugLog = new Button(tr("settings.general.debugLog.button"));
        debugLog.setOnAction(e -> showDebugLog.run());
        row(
                Category.GENERAL,
                diagnostics,
                tr("settings.general.debugLog"),
                tr("settings.general.debugLog.desc", DebugLog.FILE_NAME),
                debugLog,
                tr("settings.general.debugLog.keywords"));
    }

    /**
     * Categories (name and save path) and tags, as lists with a form under each. Not settings
     * keys: the lists live in {@code categories.json} and {@code tags.json}, through the store,
     * and the window re-files the torrents when one is deleted.
     */
    private void buildLabels(VBox page) {
        VBox categories = section(page, tr("settings.labels.categories"));
        categoryTable = new TableView<>();
        categoryTable.setPrefHeight(170);
        categoryTable.setPlaceholder(new Label(tr("settings.labels.categories.empty")));
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<com.torrenta.engine.Category, String> name = new TableColumn<>(tr("common.name"));
        name.setPrefWidth(160);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<com.torrenta.engine.Category, String> path = new TableColumn<>(tr("settings.labels.savePath"));
        path.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().savePath().isEmpty()
                        ? tr("settings.labels.downloadFolder")
                        : c.getValue().savePath()));
        categoryTable.getColumns().addAll(List.of(name, path));
        categoryName = new TextField();
        categoryName.setPromptText(tr("settings.labels.categoryName.prompt"));
        categoryName.setPrefColumnCount(12);
        categoryPath = new TextField();
        categoryPath.setPromptText(tr("settings.labels.categoryPath.prompt"));
        HBox.setHgrow(categoryPath, Priority.ALWAYS);
        Button browse = new Button(tr("common.browse"));
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("settings.labels.categoryPath.chooser"));
            File current = new File(categoryPath.getText().trim());
            while (current != null && !current.isDirectory()) current = current.getParentFile();
            if (current != null) chooser.setInitialDirectory(current);
            File chosen = chooser.showDialog(stage);
            if (chosen != null) categoryPath.setText(chosen.getAbsolutePath());
        });
        Button save = new Button(tr("common.save"));
        save.setDefaultButton(false);
        save.disableProperty().bind(categoryName.textProperty().map(String::isBlank));
        save.setOnAction(e -> saveCategory());
        categoryName.setOnAction(e -> saveCategory());
        categoryPath.setOnAction(e -> saveCategory());
        categoryDelete = new Button(tr("common.remove"));
        categoryDelete
                .disableProperty()
                .bind(categoryTable.getSelectionModel().selectedItemProperty().isNull());
        categoryDelete.setOnAction(e -> deleteCategory());
        categoryTable.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == null) return;
            categoryName.setText(now.name());
            categoryPath.setText(now.savePath());
        });
        HBox form = new HBox(6, categoryName, categoryPath, browse, save, categoryDelete);
        form.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(tr("settings.labels.categories.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        VBox categoryBox = new VBox(8, hint, categoryTable, form);
        categories.getChildren().add(categoryBox);
        rows.add(new Row(
                Category.LABELS,
                categoryBox,
                tr("settings.labels.categories") + " | " + tr("settings.labels.categories.keywords"),
                categories));

        VBox tags = section(page, tr("settings.labels.tags"));
        tagList = new ListView<>();
        tagList.setPrefHeight(130);
        tagList.setPlaceholder(new Label(tr("settings.labels.tags.empty")));
        newTag = new TextField();
        newTag.setPromptText(tr("settings.labels.newTag.prompt"));
        HBox.setHgrow(newTag, Priority.ALWAYS);
        Button addTag = new Button(tr("common.add"));
        addTag.disableProperty().bind(newTag.textProperty().map(String::isBlank));
        addTag.setOnAction(e -> addTags());
        newTag.setOnAction(e -> addTags());
        tagDelete = new Button(tr("common.remove"));
        tagDelete
                .disableProperty()
                .bind(tagList.getSelectionModel().selectedItemProperty().isNull());
        tagDelete.setOnAction(e -> deleteTag());
        HBox tagForm = new HBox(6, newTag, addTag, tagDelete);
        tagForm.setAlignment(Pos.CENTER_LEFT);
        Label tagHint = new Label(tr("settings.labels.tags.hint"));
        tagHint.setWrapText(true);
        tagHint.getStyleClass().add("settings-row-description");
        VBox tagBox = new VBox(8, tagHint, tagList, tagForm);
        tags.getChildren().add(tagBox);
        rows.add(new Row(
                Category.LABELS,
                tagBox,
                tr("settings.labels.tags") + " | " + tr("settings.labels.tags.keywords"),
                tags));
    }

    private void saveCategory() {
        if (labelStore == null || categoryName.getText().isBlank()) return;
        try {
            labelStore.putCategory(new com.torrenta.engine.Category(categoryName.getText(), categoryPath.getText()));
        } catch (IOException | IllegalArgumentException e) {
            report(tr("settings.labels.saveCategoryFailed", e.getMessage()));
            return;
        }
        String saved = categoryName.getText().trim();
        reloadLabels();
        categoryTable.getItems().stream()
                .filter(c -> c.name().equals(saved))
                .findFirst()
                .ifPresent(c -> categoryTable.getSelectionModel().select(c));
        labelsHost.labelsChanged();
    }

    private void deleteCategory() {
        com.torrenta.engine.Category selected =
                categoryTable.getSelectionModel().getSelectedItem();
        if (labelStore == null || selected == null) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.CATEGORY, selected.name())) return;
        try {
            labelStore.removeCategory(selected.name());
        } catch (IOException e) {
            report(tr("settings.labels.deleteCategoryFailed", e.getMessage()));
            return;
        }
        categoryName.clear();
        categoryPath.clear();
        reloadLabels();
        labelsHost.categoryDeleted(selected.name());
    }

    private void addTags() {
        if (labelStore == null || newTag.getText().isBlank()) return;
        try {
            labelStore.addTags(List.of(newTag.getText()));
        } catch (IOException e) {
            report(tr("settings.labels.saveTagsFailed", e.getMessage()));
            return;
        }
        newTag.clear();
        reloadLabels();
        labelsHost.labelsChanged();
    }

    private void deleteTag() {
        String selected = tagList.getSelectionModel().getSelectedItem();
        if (labelStore == null || selected == null) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.TAG, selected)) return;
        try {
            labelStore.removeTag(selected);
        } catch (IOException e) {
            report(tr("settings.labels.deleteTagFailed", e.getMessage()));
            return;
        }
        reloadLabels();
        labelsHost.tagDeleted(selected);
    }

    /** The store's lists into the two controls; keeps the category selection where it can. */
    private void reloadLabels() {
        if (labelStore == null || categoryTable == null) return;
        com.torrenta.engine.Category selected =
                categoryTable.getSelectionModel().getSelectedItem();
        categoryTable.getItems().setAll(labelStore.categories());
        if (selected != null) {
            categoryTable.getItems().stream()
                    .filter(c -> c.name().equals(selected.name()))
                    .findFirst()
                    .ifPresent(c -> categoryTable.getSelectionModel().select(c));
        }
        tagList.getItems().setAll(labelStore.tags());
    }

    /** A failed write has nowhere to go but a dialog here: the window's status bar is not ours. */
    private void report(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.setTitle(tr("settings.title", AppInfo.NAME));
        alert.setHeaderText(null);
        StockButtons.translate(alert);
        alert.initOwner(stage);
        Fonts.installUiFont(alert.getDialogPane());
        alert.showAndWait();
    }

    private void buildWatch(VBox page) {
        VBox folders = section(page, tr("settings.watchFolders.folders"));
        watchTable = new TableView<>();
        watchTable.setPrefHeight(190);
        watchTable.setPlaceholder(new Label(tr("settings.watchFolders.empty")));
        watchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<WatchFolder, String> folder = new TableColumn<>(tr("settings.watchFolders.column.folder"));
        folder.setPrefWidth(200);
        folder.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().folder()));
        TableColumn<WatchFolder, String> saveTo = new TableColumn<>(tr("settings.watchFolders.column.saveTo"));
        saveTo.setPrefWidth(170);
        saveTo.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                switch (c.getValue().saveTo()) {
                    case DEFAULT -> tr("settings.watchFolders.saveTo.downloadFolder");
                    case WATCH_FOLDER -> tr("settings.watchFolders.saveTo.watchFolder");
                    case FOLDER -> c.getValue().savePath();
                }));
        TableColumn<WatchFolder, String> after = new TableColumn<>(tr("settings.watchFolders.column.afterAdding"));
        after.setPrefWidth(110);
        after.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                switch (c.getValue().afterAdd()) {
                    case DELETE -> tr("common.delete");
                    case RENAME -> tr("settings.watchFolders.afterAdd.rename");
                }));
        TableColumn<WatchFolder, String> category = new TableColumn<>(tr("settings.watchFolders.column.category"));
        category.setPrefWidth(100);
        category.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().category().isEmpty() ? "" : c.getValue().category()));
        TableColumn<WatchFolder, String> paused = new TableColumn<>(tr("settings.watchFolders.column.paused"));
        paused.setPrefWidth(60);
        paused.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().startPaused() ? tr("settings.table.yes") : ""));
        watchTable.getColumns().addAll(List.of(folder, saveTo, after, category, paused));
        watchTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<WatchFolder> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) editWatchFolder();
            });
            return row;
        });
        Button add = new Button(tr("settings.table.add"));
        add.setOnAction(e -> addWatchFolder());
        Button edit = new Button(tr("common.edit"));
        edit.disableProperty()
                .bind(watchTable.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> editWatchFolder());
        Button remove = new Button(tr("common.remove"));
        remove.disableProperty()
                .bind(watchTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeWatchFolder());
        HBox buttons = new HBox(6, add, edit, remove);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(tr("settings.watchFolders.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        watchStatusLabel = new Label();
        watchStatusLabel.setWrapText(true);
        watchStatusLabel.getStyleClass().add("settings-row-description");
        VBox box = new VBox(8, hint, watchTable, buttons, watchStatusLabel);
        folders.getChildren().add(box);
        rows.add(new Row(
                Category.WATCH,
                box,
                tr("settings.watchFolders.title") + " | " + tr("settings.watchFolders.keywords"),
                folders));
    }

    private void addWatchFolder() {
        if (watchStore == null) return;
        watchDialog = new WatchFolderDialog(stage, null, labelStore == null ? List.of() : labelStore.categories());
        Optional<WatchFolder> made = watchDialog.showAndWait();
        watchDialog = null;
        if (made.isEmpty()) return;
        List<WatchFolder> list = new ArrayList<>(watchStore.entries());
        list.add(made.get());
        saveWatchFolders(list, made.get());
    }

    private void editWatchFolder() {
        WatchFolder selected = watchTable.getSelectionModel().getSelectedItem();
        if (watchStore == null || selected == null) return;
        watchDialog = new WatchFolderDialog(stage, selected, labelStore == null ? List.of() : labelStore.categories());
        Optional<WatchFolder> edited = watchDialog.showAndWait();
        watchDialog = null;
        if (edited.isEmpty()) return;
        List<WatchFolder> list = new ArrayList<>(watchStore.entries());
        int at = list.indexOf(selected);
        if (at < 0) list.add(edited.get());
        else list.set(at, edited.get());
        saveWatchFolders(list, edited.get());
    }

    private void removeWatchFolder() {
        WatchFolder selected = watchTable.getSelectionModel().getSelectedItem();
        if (watchStore == null || selected == null) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.WATCH_FOLDER, selected.folder())) return;
        List<WatchFolder> list = new ArrayList<>(watchStore.entries());
        list.remove(selected);
        saveWatchFolders(list, null);
    }

    private void saveWatchFolders(List<WatchFolder> list, WatchFolder select) {
        try {
            watchStore.set(list);
        } catch (IOException e) {
            report(tr("settings.watchFolders.saveFailed", e.getMessage()));
            return;
        }
        reloadWatch();
        if (select != null) watchTable.getSelectionModel().select(select);
        watchHost.watchFoldersChanged();
        refreshWatchStatus();
    }

    /** The store's list into the table; keeps the selection where it can. */
    private void reloadWatch() {
        if (watchStore == null || watchTable == null) return;
        WatchFolder selected = watchTable.getSelectionModel().getSelectedItem();
        watchTable.getItems().setAll(watchStore.entries());
        if (selected != null && watchTable.getItems().contains(selected))
            watchTable.getSelectionModel().select(selected);
        refreshWatchStatus();
    }

    private void buildRss(VBox page) {
        VBox refresh = section(page, tr("settings.rss.refreshing"));
        rssMinutes = new Spinner<>(Feed.MIN_MINUTES, Feed.MAX_MINUTES, 30, 5);
        rssMinutes.setEditable(true);
        Spinners.commitOnBlur(rssMinutes);
        rssMinutes.setPrefWidth(90);
        rssMinutes.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setRssRefreshMinutes(now);
            settings.saveQuietly();
        });
        row(
                Category.RSS,
                refresh,
                tr("settings.rss.refreshMinutes"),
                tr("settings.rss.refreshMinutes.desc"),
                rssMinutes,
                tr("settings.rss.refreshMinutes.keywords"));
        rssAutoDownload = new CheckBox();
        rssAutoDownload.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setRssAutoDownload(now);
            settings.saveQuietly();
            reloadRules();
        });
        row(
                Category.RSS,
                refresh,
                tr("settings.rss.autoDownload"),
                tr("settings.rss.autoDownload.desc"),
                rssAutoDownload,
                tr("settings.rss.autoDownload.keywords"));

        VBox feeds = section(page, tr("settings.rss.feeds"));
        rssTable = new TableView<>();
        rssTable.setPrefHeight(190);
        rssTable.setPlaceholder(new Label(tr("settings.rss.feeds.empty")));
        rssTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Feed, String> name = new TableColumn<>(tr("common.name"));
        name.setPrefWidth(130);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().displayName()));
        TableColumn<Feed, String> url = new TableColumn<>(tr("settings.rss.column.url"));
        url.setPrefWidth(190);
        url.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().url()));
        TableColumn<Feed, String> last = new TableColumn<>(tr("settings.rss.column.lastRefresh"));
        last.setPrefWidth(110);
        last.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(rssLastRefresh(c.getValue())));
        TableColumn<Feed, String> unread = new TableColumn<>(tr("settings.rss.column.unread"));
        unread.setPrefWidth(70);
        unread.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                rssService == null
                        ? ""
                        : Integer.toString(rssService.unread(c.getValue().id()))));
        TableColumn<Feed, String> status = new TableColumn<>(tr("settings.rss.column.status"));
        status.setPrefWidth(140);
        status.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(rssFeedStatus(c.getValue())));
        rssTable.getColumns().addAll(List.of(name, url, last, unread, status));
        rssTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<Feed> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) editRssFeed();
            });
            return row;
        });
        Button add = new Button(tr("settings.table.add"));
        add.setOnAction(e -> addRssFeed(""));
        Button edit = new Button(tr("common.edit"));
        edit.disableProperty()
                .bind(rssTable.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> editRssFeed());
        Button remove = new Button(tr("common.remove"));
        remove.disableProperty()
                .bind(rssTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeRssFeed());
        Button refreshNow = new Button(tr("settings.rss.refresh"));
        refreshNow.setOnAction(e -> {
            if (rssService == null) return;
            Feed selected = rssTable.getSelectionModel().getSelectedItem();
            if (selected == null) rssService.refreshAll();
            else rssService.refresh(selected.id());
        });
        HBox buttons = new HBox(6, add, edit, remove, refreshNow);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(tr("settings.rss.feeds.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        rssStatusLabel = new Label();
        rssStatusLabel.setWrapText(true);
        rssStatusLabel.getStyleClass().add("settings-row-description");
        VBox box = new VBox(8, hint, rssTable, buttons, rssStatusLabel);
        feeds.getChildren().add(box);
        rows.add(new Row(
                Category.RSS, box, tr("settings.rss.feeds") + " | " + tr("settings.rss.feeds.keywords"), feeds));

        VBox rulesSection = section(page, tr("settings.rss.rules"));
        rulesTable = new TableView<>();
        rulesTable.setPrefHeight(170);
        rulesTable.setPlaceholder(new Label(tr("settings.rss.rules.empty")));
        rulesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Rule, String> ruleName = new TableColumn<>(tr("common.name"));
        ruleName.setPrefWidth(160);
        ruleName.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<Rule, String> ruleOn = new TableColumn<>(tr("common.enabled"));
        ruleOn.setPrefWidth(70);
        ruleOn.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().enabled() ? tr("settings.table.yes") : tr("settings.table.no")));
        TableColumn<Rule, String> ruleFeeds = new TableColumn<>(tr("settings.rss.feeds"));
        ruleFeeds.setPrefWidth(200);
        ruleFeeds.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                rssService == null ? "" : RuleText.feedsText(c.getValue(), rssService.namesById())));
        TableColumn<Rule, String> ruleLast = new TableColumn<>(tr("settings.rss.column.lastMatch"));
        ruleLast.setPrefWidth(110);
        ruleLast.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                RuleText.lastMatchText(c.getValue(), System.currentTimeMillis())));
        rulesTable.getColumns().addAll(List.of(ruleName, ruleOn, ruleFeeds, ruleLast));
        rulesTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<Rule> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) editRule();
            });
            return row;
        });
        Button addRule = new Button(tr("settings.table.add"));
        addRule.setOnAction(e -> addRule("", ""));
        Button editRule = new Button(tr("common.edit"));
        editRule.disableProperty()
                .bind(rulesTable.getSelectionModel().selectedItemProperty().isNull());
        editRule.setOnAction(e -> editRule());
        Button removeRule = new Button(tr("common.remove"));
        removeRule
                .disableProperty()
                .bind(rulesTable.getSelectionModel().selectedItemProperty().isNull());
        removeRule.setOnAction(e -> removeRule());
        Button duplicateRule = new Button(tr("settings.rss.duplicate"));
        duplicateRule
                .disableProperty()
                .bind(rulesTable.getSelectionModel().selectedItemProperty().isNull());
        duplicateRule.setOnAction(e -> duplicateRule());
        Button runRules = new Button(tr("settings.rss.runRules"));
        runRules.setOnAction(e -> {
            if (rssService != null) rssService.runRulesNow();
        });
        HBox ruleButtons = new HBox(6, addRule, editRule, removeRule, duplicateRule, runRules);
        ruleButtons.setAlignment(Pos.CENTER_LEFT);
        Label rulesHint = new Label(tr("settings.rss.rules.hint"));
        rulesHint.setWrapText(true);
        rulesHint.getStyleClass().add("settings-row-description");
        rulesStatusLabel = new Label();
        rulesStatusLabel.setWrapText(true);
        rulesStatusLabel.getStyleClass().add("settings-row-description");
        VBox rulesBox = new VBox(8, rulesHint, rulesTable, ruleButtons, rulesStatusLabel);
        rulesSection.getChildren().add(rulesBox);
        rows.add(new Row(
                Category.RSS,
                rulesBox,
                tr("settings.rss.rules") + " | " + tr("settings.rss.rules.keywords"),
                rulesSection));
    }

    private void addRule(String name, String mustContain) {
        if (rssService == null || (ruleDialog != null && ruleDialog.isShowing())) return;
        Set<String> taken = new LinkedHashSet<>();
        for (Rule r : rssService.rules()) taken.add(r.name());
        ruleDialog = new RuleDialog(stage, null, rssService.feeds(), categoriesForRules(), taken, ruleArticles());
        if (!name.isEmpty() || !mustContain.isEmpty()) ruleDialog.prefill(RuleText.freshName(name, taken), mustContain);
        Optional<Rule> made = ruleDialog.showAndWait();
        ruleDialog = null;
        if (made.isEmpty()) return;
        saveRule(null, made.get());
    }

    private void editRule() {
        Rule selected = rulesTable.getSelectionModel().getSelectedItem();
        if (rssService == null || selected == null || (ruleDialog != null && ruleDialog.isShowing())) return;
        Set<String> taken = new LinkedHashSet<>();
        for (Rule r : rssService.rules()) if (!r.name().equals(selected.name())) taken.add(r.name());
        ruleDialog = new RuleDialog(stage, selected, rssService.feeds(), categoriesForRules(), taken, ruleArticles());
        Optional<Rule> edited = ruleDialog.showAndWait();
        ruleDialog = null;
        if (edited.isEmpty()) return;
        saveRule(selected.name(), edited.get());
    }

    private void duplicateRule() {
        Rule selected = rulesTable.getSelectionModel().getSelectedItem();
        if (rssService == null || selected == null) return;
        Set<String> taken = new LinkedHashSet<>();
        for (Rule r : rssService.rules()) taken.add(r.name());
        saveRule(null, selected.duplicate(RuleText.copyName(selected.name(), taken)));
    }

    private void removeRule() {
        Rule selected = rulesTable.getSelectionModel().getSelectedItem();
        if (rssService == null || selected == null) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.RULE, selected.name())) return;
        try {
            rssService.removeRule(selected.name());
        } catch (IOException e) {
            report(tr("settings.rss.removeRuleFailed", e.getMessage()));
        }
        reloadRules();
    }

    private void saveRule(String replacing, Rule rule) {
        try {
            rssService.putRule(replacing, rule);
        } catch (IOException e) {
            report(tr("settings.rss.saveRuleFailed", e.getMessage()));
            return;
        } catch (IllegalArgumentException e) {
            report(e.getMessage());
            return;
        }
        reloadRules();
        for (Rule r : rulesTable.getItems())
            if (r.name().equals(rule.name())) rulesTable.getSelectionModel().select(r);
    }

    private List<com.torrenta.engine.Category> categoriesForRules() {
        return labelStore == null ? List.of() : labelStore.categories();
    }

    private RuleDialog.Articles ruleArticles() {
        return new RuleDialog.Articles() {
            @Override
            public List<com.torrenta.rss.Article> all() {
                return rssService.allArticles();
            }

            @Override
            public Map<String, String> namesById() {
                return rssService.namesById();
            }
        };
    }

    /** The service's rules into the table; keeps the selection where it can. */
    private void reloadRules() {
        if (rssService == null || rulesTable == null) return;
        Rule selected = rulesTable.getSelectionModel().getSelectedItem();
        List<Rule> rules = rssService.rules();
        rulesTable.getItems().setAll(rules);
        if (selected != null) {
            for (Rule r : rules)
                if (r.name().equals(selected.name()))
                    rulesTable.getSelectionModel().select(r);
        }
        rulesStatusLabel.setText(RuleText.rulesStatus(rules, settings.rssAutoDownload()));
    }

    private String rssLastRefresh(Feed feed) {
        if (rssService == null) return "";
        RssService.Status s = rssService.status(feed.id());
        return s.refreshing()
                ? tr("settings.rss.feedRefreshing")
                : s.refreshedEpochMs() <= 0
                        ? tr("settings.rss.never")
                        : RssText.ago(s.refreshedEpochMs(), System.currentTimeMillis());
    }

    private String rssFeedStatus(Feed feed) {
        if (rssService == null) return "";
        RssService.Status s = rssService.status(feed.id());
        return s.failed()
                ? tr("settings.rss.lastError", s.error())
                : s.refreshedEpochMs() <= 0 ? "" : tr("settings.rss.ok");
    }

    private void addRssFeed(String prefill) {
        if (rssService == null || (rssDialog != null && rssDialog.isShowing())) return;
        rssDialog = new FeedDialog(stage, null, rssService.source(), settings.rssRefreshMinutes());
        if (!prefill.isEmpty()) rssDialog.prefillUrl(prefill);
        Optional<Feed> made = rssDialog.showAndWait();
        rssDialog = null;
        if (made.isEmpty()) return;
        try {
            rssService.addFeed(made.get());
        } catch (IOException e) {
            report(tr("settings.rss.saveFeedFailed", e.getMessage()));
            return;
        }
        reloadRss();
        rssTable.getSelectionModel().select(made.get());
    }

    private void editRssFeed() {
        Feed selected = rssTable.getSelectionModel().getSelectedItem();
        if (rssService == null || selected == null) return;
        rssDialog = new FeedDialog(stage, selected, rssService.source(), settings.rssRefreshMinutes());
        Optional<Feed> edited = rssDialog.showAndWait();
        rssDialog = null;
        if (edited.isEmpty()) return;
        try {
            rssService.updateFeed(edited.get());
        } catch (IOException e) {
            report(tr("settings.rss.saveFeedFailed", e.getMessage()));
            return;
        }
        reloadRss();
        rssTable.getSelectionModel().select(edited.get());
    }

    private void removeRssFeed() {
        Feed selected = rssTable.getSelectionModel().getSelectedItem();
        if (rssService == null || selected == null) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.FEED, selected.displayName())) return;
        try {
            rssService.removeFeed(selected.id());
        } catch (IOException e) {
            report(tr("settings.rss.removeFeedFailed", e.getMessage()));
        }
        reloadRss();
    }

    /** The service's list into the table; keeps the selection where it can. */
    private void reloadRss() {
        if (rssService == null || rssTable == null) return;
        Feed selected = rssTable.getSelectionModel().getSelectedItem();
        List<Feed> feeds = rssService.feeds();
        rssTable.getItems().setAll(feeds);
        if (selected != null) {
            for (Feed f : feeds)
                if (f.id().equals(selected.id())) rssTable.getSelectionModel().select(f);
        }
        reloadRules();
        int unread = rssService.unreadTotal();
        rssStatusLabel.setText(feeds.isEmpty() ? "" : tr("settings.rss.status", feeds.size(), unread));
    }

    private void buildSearch(VBox page) {
        VBox providers = section(page, tr("settings.search.providers"));
        searchTable = new TableView<>();
        searchTable.setPrefHeight(190);
        searchTable.setPlaceholder(new Label(tr("settings.search.providers.empty")));
        searchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<TorznabEndpoint, String> name = new TableColumn<>(tr("common.name"));
        name.setPrefWidth(120);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<TorznabEndpoint, String> url = new TableColumn<>(tr("settings.search.column.url"));
        url.setPrefWidth(260);
        url.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().url()));
        TableColumn<TorznabEndpoint, String> key = new TableColumn<>(tr("settings.search.column.apiKey"));
        key.setPrefWidth(80);
        key.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().maskedKey()));
        TableColumn<TorznabEndpoint, String> enabled = new TableColumn<>(tr("common.enabled"));
        enabled.setPrefWidth(60);
        enabled.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().enabled() ? tr("settings.table.yes") : ""));
        TableColumn<TorznabEndpoint, String> categories = new TableColumn<>(tr("settings.search.column.categories"));
        categories.setPrefWidth(90);
        categories.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().categories() < 0
                        ? tr("settings.search.notTested")
                        : Integer.toString(c.getValue().categories())));
        searchTable.getColumns().addAll(List.of(name, url, key, enabled, categories));
        searchTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<TorznabEndpoint> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) editSearchProvider();
            });
            return row;
        });
        Button add = new Button(tr("settings.table.add"));
        add.setOnAction(e -> addSearchProvider());
        Button edit = new Button(tr("common.edit"));
        edit.disableProperty()
                .bind(searchTable.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> editSearchProvider());
        Button remove = new Button(tr("common.remove"));
        remove.disableProperty()
                .bind(searchTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeSearchProvider());
        Button test = new Button(tr("settings.search.test"));
        test.disableProperty()
                .bind(searchTable.getSelectionModel().selectedItemProperty().isNull());
        test.setOnAction(e -> testSearchProvider());
        HBox buttons = new HBox(6, add, edit, remove, test);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(tr("settings.search.providers.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        searchStatusLabel = new Label();
        searchStatusLabel.setWrapText(true);
        searchStatusLabel.getStyleClass().add("settings-row-description");
        VBox box = new VBox(8, hint, searchTable, buttons, searchStatusLabel);
        providers.getChildren().add(box);
        rows.add(new Row(
                Category.SEARCH,
                box,
                tr("settings.search.providers") + " | " + tr("settings.search.providers.keywords"),
                providers));
        buildDefinitions(page);
    }

    /** The definition files: listed read-only, switched on or off, reloaded, the folder opened. */
    private void buildDefinitions(VBox page) {
        VBox section = section(page, tr("settings.search.definitions"));
        definitionTable = new TableView<>();
        definitionTable.setPrefHeight(160);
        definitionTable.setPlaceholder(new Label(tr("settings.search.definitions.empty")));
        definitionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<DefinitionStore.Entry, String> name = new TableColumn<>(tr("common.name"));
        name.setPrefWidth(140);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<DefinitionStore.Entry, String> file = new TableColumn<>(tr("settings.search.column.file"));
        file.setPrefWidth(150);
        file.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().file().getFileName().toString()));
        TableColumn<DefinitionStore.Entry, String> type = new TableColumn<>(tr("settings.search.column.type"));
        type.setPrefWidth(50);
        type.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(definitionType(c.getValue())));
        TableColumn<DefinitionStore.Entry, String> on = new TableColumn<>(tr("common.enabled"));
        on.setPrefWidth(60);
        on.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                !c.getValue().loaded()
                        ? ""
                        : settings.disabledSearchDefinitions()
                                        .contains(c.getValue().id())
                                ? ""
                                : tr("settings.table.yes")));
        TableColumn<DefinitionStore.Entry, String> status = new TableColumn<>(tr("settings.search.column.status"));
        status.setPrefWidth(220);
        status.setCellValueFactory(
                c -> new javafx.beans.property.ReadOnlyStringWrapper(definitionStatus(c.getValue())));
        definitionTable.getColumns().addAll(List.of(name, file, type, on, status));
        definitionTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<DefinitionStore.Entry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) toggleDefinition();
            });
            return row;
        });
        definitionTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                toggleDefinition();
                e.consume();
            }
        });
        Button toggle = new Button(tr("settings.search.toggle"));
        toggle.disableProperty()
                .bind(definitionTable.getSelectionModel().selectedItemProperty().isNull());
        toggle.setOnAction(e -> toggleDefinition());
        Button reload = new Button(tr("settings.search.reload"));
        reload.setOnAction(e -> reloadDefinitionsPressed());
        Button open = new Button(tr("settings.search.openFolder"));
        open.setOnAction(e -> openDefinitionFolder.run());
        HBox buttons = new HBox(6, toggle, reload, open);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(tr("settings.search.definitions.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        definitionStatusLabel = new Label();
        definitionStatusLabel.setWrapText(true);
        definitionStatusLabel.getStyleClass().add("settings-row-description");
        VBox box = new VBox(8, hint, definitionTable, buttons, definitionStatusLabel);
        section.getChildren().add(box);
        rows.add(new Row(
                Category.SEARCH,
                box,
                tr("settings.search.definitions") + " | " + tr("settings.search.definitions.keywords"),
                section));
    }

    private static String definitionType(DefinitionStore.Entry e) {
        return e.loaded() ? e.definition().type().name().toLowerCase(Locale.ROOT) : "";
    }

    private static String definitionStatus(DefinitionStore.Entry e) {
        if (e.loaded()) return tr("settings.search.loaded");
        String problem = e.problem();
        String prefix = e.file().getFileName() + ": ";
        return problem.startsWith(prefix) ? problem.substring(prefix.length()) : problem;
    }

    private void toggleDefinition() {
        DefinitionStore.Entry selected = definitionTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.loaded()) return;
        Set<String> off = new LinkedHashSet<>(settings.disabledSearchDefinitions());
        if (!off.remove(selected.id())) off.add(selected.id());
        settings.setDisabledSearchDefinitions(off);
        settings.saveQuietly();
        reloadDefinitions();
        definitionTable.getSelectionModel().select(selected);
        onSearchProvidersChanged.run();
    }

    private void reloadDefinitionsPressed() {
        if (definitionStore == null) return;
        definitionStore.load();
        reloadDefinitions();
        onSearchProvidersChanged.run();
    }

    /** The store's entries into the table; keeps the selection where it can. */
    private void reloadDefinitions() {
        if (definitionStore == null || definitionTable == null) return;
        DefinitionStore.Entry selected = definitionTable.getSelectionModel().getSelectedItem();
        List<DefinitionStore.Entry> entries = definitionStore.entries();
        definitionTable.getItems().setAll(entries);
        if (selected != null) {
            for (DefinitionStore.Entry e : entries) {
                if (e.file().equals(selected.file()))
                    definitionTable.getSelectionModel().select(e);
            }
        }
        definitionTable.refresh();
        if (definitionStatusLabel != null) {
            Set<String> off = settings.disabledSearchDefinitions();
            long loaded = entries.stream().filter(DefinitionStore.Entry::loaded).count();
            long on = entries.stream()
                    .filter(e -> e.loaded() && !off.contains(e.id()))
                    .count();
            long broken = entries.size() - loaded;
            definitionStatusLabel.setText(
                    entries.isEmpty()
                            ? tr("settings.search.definitions.folder", definitionStore.dir())
                            : tr("settings.search.definitions.status", on, loaded, broken, definitionStore.dir()));
        }
    }

    private void addSearchProvider() {
        if (searchStore == null) return;
        searchDialog = new SearchProviderDialog(stage, null, searchTester);
        Optional<TorznabEndpoint> made = searchDialog.showAndWait();
        searchDialog = null;
        made.ifPresent(this::addSearchProvider);
    }

    private void addSearchProvider(TorznabEndpoint made) {
        List<TorznabEndpoint> list = new ArrayList<>(searchStore.entries());
        list.add(made);
        saveSearchProviders(list, made);
    }

    private void editSearchProvider() {
        TorznabEndpoint selected = searchTable.getSelectionModel().getSelectedItem();
        if (searchStore == null || selected == null) return;
        searchDialog = new SearchProviderDialog(stage, selected, searchTester);
        Optional<TorznabEndpoint> edited = searchDialog.showAndWait();
        searchDialog = null;
        if (edited.isEmpty()) return;
        List<TorznabEndpoint> list = new ArrayList<>(searchStore.entries());
        int at = list.indexOf(selected);
        if (at < 0) list.add(edited.get());
        else list.set(at, edited.get());
        saveSearchProviders(list, edited.get());
    }

    private void removeSearchProvider() {
        TorznabEndpoint selected = searchTable.getSelectionModel().getSelectedItem();
        if (searchStore == null || selected == null) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.SEARCH_PROVIDER, selected.name())) return;
        List<TorznabEndpoint> list = new ArrayList<>(searchStore.entries());
        list.remove(selected);
        saveSearchProviders(list, null);
    }

    /** Asks the selected server for its capabilities, off the FX thread, and writes the count back. */
    private void testSearchProvider() {
        TorznabEndpoint selected = searchTable.getSelectionModel().getSelectedItem();
        if (searchStore == null || selected == null) return;
        searchStatusLabel.setText(tr("settings.search.testing", selected.name()));
        Thread.ofVirtual().name("torznab-test").start(() -> {
            String line;
            TorznabEndpoint tested = null;
            try {
                TorznabProvider.Capabilities caps = searchTester.test(selected);
                tested = selected.tested(caps.title(), caps.categories().size());
                String found = SearchProviderDialog.categoriesLine(
                        caps.title(), caps.categories().size());
                line = caps.title().equalsIgnoreCase(selected.name())
                        ? found
                        : tr("settings.search.testResult", selected.name(), found);
            } catch (SearchException e) {
                line = tr("settings.search.testResult", selected.name(), e.getMessage());
            } catch (RuntimeException e) {
                line = tr("settings.search.testFailed", selected.name(), e);
            }
            TorznabEndpoint found = tested;
            String text = line;
            javafx.application.Platform.runLater(() -> {
                searchStatusLabel.setText(text);
                if (found == null || found.equals(selected)) return;
                List<TorznabEndpoint> list = new ArrayList<>(searchStore.entries());
                int at = list.indexOf(selected);
                if (at < 0) return;
                list.set(at, found);
                saveSearchProviders(list, found);
                searchStatusLabel.setText(text);
            });
        });
    }

    private void saveSearchProviders(List<TorznabEndpoint> list, TorznabEndpoint select) {
        try {
            searchStore.set(list);
        } catch (IOException e) {
            report(tr("settings.search.saveFailed", e.getMessage()));
            return;
        }
        reloadSearch();
        if (select != null) searchTable.getSelectionModel().select(select);
        onSearchProvidersChanged.run();
    }

    /** The store's list into the table; keeps the selection where it can. */
    private void reloadSearch() {
        if (searchStore == null || searchTable == null) return;
        TorznabEndpoint selected = searchTable.getSelectionModel().getSelectedItem();
        searchTable.getItems().setAll(searchStore.entries());
        if (selected != null && searchTable.getItems().contains(selected))
            searchTable.getSelectionModel().select(selected);
        if (searchStatusLabel != null && searchStatusLabel.getText().isEmpty())
            searchStatusLabel.setText(
                    searchStore.entries().isEmpty()
                            ? ""
                            : tr(
                                    "settings.search.enabledCount",
                                    searchStore.enabled().size(),
                                    searchStore.entries().size()));
    }

    // ---------------------------------------------------------------- engine profiles

    /** What the Engine page tells the window, on the FX thread. */
    interface ProfilesHost {
        /** Switch to this profile now, in place. */
        void switchTo(EngineProfile profile);

        /** The profile whose engine is running. */
        EngineProfile inUse();

        /** The running engine's description, or empty while none is up. */
        String engineLine();
    }

    private ProfileStore profileStore;
    private ProfilesHost profilesHost;
    private TableView<EngineProfile> profileTable;
    private Label profileStatusLabel;
    private Label profileInUseLabel;
    private EngineProfileDialog profileDialog;
    /** Asks a daemon who it is; swapped for a fake in a capture or a test. */
    private EngineProfileDialog.Tester profileTester = TransmissionEngine::probe;

    /** The store the Engine page edits, and who to tell. */
    void setProfiles(ProfileStore store, ProfilesHost host) {
        this.profileStore = store;
        this.profilesHost = host;
        reloadProfiles();
    }

    /** The window's engine changed: the "in use" line follows. */
    void refreshProfiles() {
        reloadProfiles();
    }

    private void buildEngine(VBox page) {
        VBox section = section(page, tr("settings.engine.profiles"));
        profileTable = new TableView<>();
        profileTable.setPrefHeight(170);
        profileTable.setPlaceholder(new Label(tr("settings.engine.profiles.empty")));
        profileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<EngineProfile, String> name = new TableColumn<>(tr("common.name"));
        name.setPrefWidth(150);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().displayName()));
        TableColumn<EngineProfile, String> kind = new TableColumn<>(tr("settings.engine.column.kind"));
        kind.setPrefWidth(80);
        kind.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().kind().label()));
        TableColumn<EngineProfile, String> url = new TableColumn<>(tr("settings.engine.column.url"));
        url.setPrefWidth(260);
        url.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().url()));
        TableColumn<EngineProfile, String> user = new TableColumn<>(tr("settings.engine.column.username"));
        user.setPrefWidth(100);
        user.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().username()));
        TableColumn<EngineProfile, String> next = new TableColumn<>(tr("settings.engine.column.nextStart"));
        next.setPrefWidth(90);
        next.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().id().equals(settings.engineProfile()) ? tr("settings.table.yes") : ""));
        profileTable.getColumns().addAll(List.of(name, kind, url, user, next));
        profileTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<EngineProfile> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && !row.getItem().isLocal()) editProfile();
            });
            return row;
        });
        javafx.beans.binding.BooleanBinding remoteSelected = Bindings.createBooleanBinding(
                () -> {
                    EngineProfile p = profileTable.getSelectionModel().getSelectedItem();
                    return p != null && !p.isLocal();
                },
                profileTable.getSelectionModel().selectedItemProperty());
        Button add = new Button(tr("settings.table.add"));
        add.setOnAction(e -> addProfile());
        Button edit = new Button(tr("common.edit"));
        edit.disableProperty().bind(remoteSelected.not());
        edit.setOnAction(e -> editProfile());
        Button remove = new Button(tr("common.remove"));
        remove.disableProperty().bind(remoteSelected.not());
        remove.setOnAction(e -> removeProfile());
        Button test = new Button(tr("settings.engine.test"));
        test.disableProperty().bind(remoteSelected.not());
        test.setOnAction(e -> testProfile());
        Button useNext = new Button(tr("settings.engine.useNext"));
        useNext.disableProperty()
                .bind(profileTable.getSelectionModel().selectedItemProperty().isNull());
        useNext.setOnAction(e -> useProfileNext());
        Button switchNow = new Button(tr("settings.engine.switchNow"));
        switchNow
                .disableProperty()
                .bind(profileTable.getSelectionModel().selectedItemProperty().isNull());
        switchNow.setOnAction(e -> switchProfileNow());
        HBox buttons = new HBox(6, add, edit, remove, test, useNext, switchNow);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(tr("settings.engine.profiles.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        profileInUseLabel = new Label();
        profileInUseLabel.setWrapText(true);
        profileInUseLabel.getStyleClass().add("settings-row-title");
        profileStatusLabel = new Label();
        profileStatusLabel.setWrapText(true);
        profileStatusLabel.getStyleClass().add("settings-row-description");
        VBox box = new VBox(8, hint, profileInUseLabel, profileTable, buttons, profileStatusLabel);
        section.getChildren().add(box);
        rows.add(new Row(
                Category.ENGINE_PROFILES,
                box,
                tr("settings.engine.profiles") + " | " + tr("settings.engine.profiles.keywords"),
                section));
    }

    private void addProfile() {
        if (profileStore == null) return;
        profileDialog = new EngineProfileDialog(stage, null, profileTester);
        Optional<EngineProfile> made = profileDialog.showAndWait();
        profileDialog = null;
        if (made.isEmpty()) return;
        List<EngineProfile> list = new ArrayList<>(profileStore.entries());
        list.add(made.get());
        saveProfiles(list, made.get());
    }

    private void editProfile() {
        EngineProfile selected = profileTable.getSelectionModel().getSelectedItem();
        if (profileStore == null || selected == null || selected.isLocal()) return;
        profileDialog = new EngineProfileDialog(stage, selected, profileTester);
        Optional<EngineProfile> edited = profileDialog.showAndWait();
        profileDialog = null;
        if (edited.isEmpty()) return;
        List<EngineProfile> list = new ArrayList<>(profileStore.entries());
        int at = list.indexOf(selected);
        if (at < 0) list.add(edited.get());
        else list.set(at, edited.get());
        saveProfiles(list, edited.get());
    }

    private void removeProfile() {
        EngineProfile selected = profileTable.getSelectionModel().getSelectedItem();
        if (profileStore == null || selected == null || selected.isLocal()) return;
        if (!RemoveConfirm.ask(stage, RemoveConfirm.Kind.ENGINE_PROFILE, selected.displayName())) return;
        List<EngineProfile> list = new ArrayList<>(profileStore.entries());
        list.remove(selected);
        if (settings.engineProfile().equals(selected.id())) {
            settings.setEngineProfile(EngineProfile.LOCAL_ID);
            settings.saveQuietly();
        }
        saveProfiles(list, null);
    }

    /** Asks the selected daemon who it is, off the FX thread, and says so under the table. */
    private void testProfile() {
        EngineProfile selected = profileTable.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isLocal()) return;
        profileStatusLabel.setText(tr("settings.engine.testing", selected.displayName()));
        Thread.ofVirtual().name("profile-test").start(() -> {
            String line;
            try {
                TransmissionEngine.Daemon d = profileTester.test(selected);
                line = tr("settings.engine.testResult", selected.displayName(), EngineProfileDialog.daemonLine(d));
            } catch (com.torrenta.engine.EngineException e) {
                line = tr("settings.engine.testFailed", selected.displayName(), e.getMessage());
            } catch (RuntimeException e) {
                line = tr("settings.engine.testFailed", selected.displayName(), e.toString());
            }
            String text = line;
            javafx.application.Platform.runLater(() -> profileStatusLabel.setText(text));
        });
    }

    private void useProfileNext() {
        EngineProfile selected = profileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        settings.setEngineProfile(selected.id());
        settings.saveQuietly();
        reloadProfiles();
        profileTable.getSelectionModel().select(selected);
        profileStatusLabel.setText(tr("settings.engine.nextStart", selected.displayName()));
    }

    private void switchProfileNow() {
        EngineProfile selected = profileTable.getSelectionModel().getSelectedItem();
        if (selected == null || profilesHost == null) return;
        profilesHost.switchTo(selected);
        reloadProfiles();
    }

    private void saveProfiles(List<EngineProfile> list, EngineProfile select) {
        try {
            profileStore.set(list);
        } catch (IOException e) {
            report(tr("settings.engine.saveFailed", e.getMessage()));
            return;
        }
        reloadProfiles();
        if (select != null) profileTable.getSelectionModel().select(select);
    }

    /** The store's list into the table, the local engine first; the "in use" line refreshed. */
    private void reloadProfiles() {
        if (profileStore == null || profileTable == null) return;
        EngineProfile selected = profileTable.getSelectionModel().getSelectedItem();
        profileTable.getItems().setAll(profileStore.all());
        if (selected != null && profileTable.getItems().contains(selected))
            profileTable.getSelectionModel().select(selected);
        if (profilesHost != null) {
            EngineProfile inUse = profilesHost.inUse();
            String engine = profilesHost.engineLine();
            EngineProfile next = profileStore.byId(settings.engineProfile()).orElse(EngineProfile.LOCAL);
            profileInUseLabel.setText(tr(
                    "settings.engine.inUse",
                    inUse.displayName(),
                    engine.isEmpty() ? 0 : 1,
                    engine,
                    next.id().equals(inUse.id()) ? 0 : 1,
                    next.displayName()));
        }
    }

    /** The open profile dialog's test line, for a capture. */
    String profileDialogStatusForCapture() {
        return profileDialog != null && profileDialog.isShowing() ? profileDialog.statusText() : "";
    }

    /** Opens the Add Profile dialog on the next pulse, with the URL filled in and Test pressed when asked. */
    void openProfileDialogForCapture(String url, boolean test) {
        selectPageForCapture("ENGINE_PROFILES");
        javafx.application.Platform.runLater(() -> {
            if (profileStore == null) return;
            profileDialog = new EngineProfileDialog(stage, null, profileTester);
            profileDialog.prefillUrl(url);
            if (test) javafx.application.Platform.runLater(profileDialog::runTest);
            Optional<EngineProfile> made = profileDialog.showAndWait();
            profileDialog = null;
            if (made.isPresent()) {
                List<EngineProfile> list = new ArrayList<>(profileStore.entries());
                list.add(made.get());
                saveProfiles(list, made.get());
            }
        });
    }

    private void buildAutomation(VBox page) {
        VBox run = section(page, tr("settings.automation.runOnEvent"));
        onAdded = commandField(EngineEvent.Kind.ADDED);
        row(
                Category.AUTOMATION,
                run,
                tr("settings.automation.onAdded"),
                tr("settings.automation.onAdded.desc"),
                onAdded,
                tr("settings.automation.onAdded.keywords"));
        onFinished = commandField(EngineEvent.Kind.FINISHED);
        row(
                Category.AUTOMATION,
                run,
                tr("settings.automation.onFinished"),
                tr("settings.automation.onFinished.desc"),
                onFinished,
                tr("settings.automation.onFinished.keywords"));
        onSeedingDone = commandField(EngineEvent.Kind.SEEDING_DONE);
        row(
                Category.AUTOMATION,
                run,
                tr("settings.automation.onSeedingDone"),
                tr("settings.automation.onSeedingDone.desc"),
                onSeedingDone,
                tr("settings.automation.onSeedingDone.keywords"));
        Label legend = new Label(tr("settings.automation.placeholders.legend"));
        legend.setWrapText(true);
        legend.getStyleClass().add("settings-row-description");
        run.getChildren().add(legend);
        rows.add(new Row(
                Category.AUTOMATION,
                legend,
                tr("settings.automation.placeholders") + " | " + tr("settings.automation.placeholders.keywords"),
                run));

        VBox notify = section(page, tr("settings.automation.notifications"));
        notifyAdded = notifyBox(EngineEvent.Kind.ADDED);
        row(
                Category.AUTOMATION,
                notify,
                tr("settings.automation.notifyAdded"),
                "",
                notifyAdded,
                tr("settings.automation.notifyAdded.keywords"));
        notifyFinished = notifyBox(EngineEvent.Kind.FINISHED);
        row(
                Category.AUTOMATION,
                notify,
                tr("settings.automation.notifyFinished"),
                "",
                notifyFinished,
                tr("settings.automation.notifyFinished.keywords"));
        notifySeedingDone = notifyBox(EngineEvent.Kind.SEEDING_DONE);
        row(
                Category.AUTOMATION,
                notify,
                tr("settings.automation.notifySeedingDone"),
                "",
                notifySeedingDone,
                tr("settings.automation.notifySeedingDone.keywords"));
        notifyError = notifyBox(EngineEvent.Kind.ERROR);
        row(
                Category.AUTOMATION,
                notify,
                tr("settings.automation.notifyError"),
                "",
                notifyError,
                tr("settings.automation.notifyError.keywords"));
        Button test = new Button(tr("settings.automation.testNotification"));
        test.setOnAction(e -> testNotification.run());
        row(
                Category.AUTOMATION,
                notify,
                tr("settings.automation.test"),
                tr("settings.automation.test.desc"),
                test,
                tr("settings.automation.test.keywords"));
    }

    private TextField commandField(EngineEvent.Kind kind) {
        TextField field = new TextField();
        field.setPrefColumnCount(30);
        field.setPromptText(tr("settings.automation.command.prompt"));
        field.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setEventCommand(kind, now);
            settings.saveQuietly();
        });
        return field;
    }

    private CheckBox notifyBox(EngineEvent.Kind kind) {
        CheckBox box = new CheckBox();
        box.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setNotifies(kind, now);
            settings.saveQuietly();
        });
        return box;
    }

    private void buildAdding(VBox page) {
        VBox defaults = section(page, tr("settings.adding.defaults"));
        addStartPaused = new CheckBox();
        addStartPaused.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setAddStartPaused(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                tr("settings.adding.startPaused"),
                tr("settings.adding.startPaused.desc"),
                addStartPaused,
                tr("settings.adding.startPaused.keywords"));
        addSequential = new CheckBox();
        addSequential.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setAddSequential(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                tr("settings.adding.sequential"),
                tr("settings.adding.sequential.desc"),
                addSequential,
                tr("settings.adding.sequential.keywords"),
                Engine.Capability.SEQUENTIAL_DOWNLOAD);
        addLayout = new ChoiceBox<>();
        addLayout.getItems().addAll(com.torrenta.engine.FileRenames.Layout.values());
        addLayout.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setAddContentLayout(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                tr("settings.adding.layout"),
                tr("settings.adding.layout.desc"),
                addLayout,
                tr("settings.adding.layout.keywords"),
                Engine.Capability.CONTENT_LAYOUT);
        addWithoutAsking = new CheckBox();
        addWithoutAsking.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setAddWithoutAsking(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                tr("settings.adding.withoutAsking"),
                tr("settings.adding.withoutAsking.desc"),
                addWithoutAsking,
                tr("settings.adding.withoutAsking.keywords"));
    }

    private void buildConnection(VBox page) {
        VBox listening = section(page, tr("settings.connection.listening"));
        port = intSpinner(1024, 65535, Settings.CONN_PORT);
        row(
                Category.CONNECTION,
                listening,
                tr("settings.connection.port"),
                tr("settings.connection.port.desc"),
                port,
                tr("settings.connection.port.keywords"));
        randomPort = bool(Settings.CONN_RANDOM_PORT);
        port.disableProperty().bind(randomPort.selectedProperty());
        row(
                Category.CONNECTION,
                listening,
                tr("settings.connection.randomPort"),
                tr("settings.connection.randomPort.desc"),
                randomPort,
                tr("settings.connection.randomPort.keywords"));
        upnp = bool(Settings.CONN_UPNP);
        row(
                Category.CONNECTION,
                listening,
                tr("settings.connection.upnp"),
                tr("settings.connection.upnp.desc"),
                upnp,
                tr("settings.connection.upnp.keywords"));
        ipv6 = bool(Settings.CONN_IPV6);
        row(
                Category.CONNECTION,
                listening,
                tr("settings.connection.ipv6"),
                tr("settings.connection.ipv6.desc"),
                ipv6,
                tr("settings.connection.ipv6.keywords"),
                Engine.Capability.LISTEN_DETAILS);
        listenInterface = text(Settings.CONN_INTERFACE);
        listenInterface.setPrefColumnCount(18);
        listenInterface.setPromptText(tr("settings.connection.interface.prompt"));
        row(
                Category.CONNECTION,
                listening,
                tr("settings.connection.interface"),
                tr("settings.connection.interface.desc"),
                listenInterface,
                tr("settings.connection.interface.keywords"),
                Engine.Capability.LISTEN_DETAILS);

        VBox limits = section(page, tr("settings.connection.limits"));
        maxConnections = intSpinner(2, 10_000, Settings.CONN_MAX_CONNECTIONS);
        row(
                Category.CONNECTION,
                limits,
                tr("settings.connection.maxConnections"),
                tr("settings.connection.maxConnections.desc"),
                maxConnections,
                tr("settings.connection.maxConnections.keywords"));
        maxPerTorrent = intSpinner(1, 10_000, Settings.CONN_MAX_PER_TORRENT);
        row(
                Category.CONNECTION,
                limits,
                tr("settings.connection.maxPerTorrent"),
                tr("settings.connection.maxPerTorrent.desc"),
                maxPerTorrent,
                tr("settings.connection.maxPerTorrent.keywords"));
        uploadSlots = intSpinner(1, 1_000, Settings.CONN_UPLOAD_SLOTS);
        row(
                Category.CONNECTION,
                limits,
                tr("settings.connection.uploadSlots"),
                tr("settings.connection.uploadSlots.desc"),
                uploadSlots,
                tr("settings.connection.uploadSlots.keywords"),
                Engine.Capability.UPLOAD_SLOTS);

        VBox proxy = section(page, tr("settings.connection.proxy"));
        proxyType = new ChoiceBox<>();
        proxyType.getItems().addAll(EngineSettings.ProxyType.values());
        proxyType.setConverter(new StringConverter<>() {
            @Override
            public String toString(EngineSettings.ProxyType t) {
                return t == null ? "" : t.label();
            }

            @Override
            public EngineSettings.ProxyType fromString(String s) {
                return null;
            }
        });
        proxyType.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.set(Settings.PROXY_TYPE, now.name());
            changed();
        });
        row(
                Category.CONNECTION,
                proxy,
                tr("settings.connection.proxyType"),
                tr("settings.connection.proxyType.desc"),
                proxyType,
                tr("settings.connection.proxyType.keywords"),
                Engine.Capability.PROXY);
        proxyHost = text(Settings.PROXY_HOST);
        proxyHost.setPrefColumnCount(18);
        row(
                Category.CONNECTION,
                proxy,
                tr("settings.connection.proxyHost"),
                tr("settings.connection.proxyHost.desc"),
                proxyHost,
                tr("settings.connection.proxyHost.keywords"),
                Engine.Capability.PROXY);
        proxyPort = intSpinner(1, 65535, Settings.PROXY_PORT);
        row(
                Category.CONNECTION,
                proxy,
                tr("settings.connection.proxyPort"),
                tr("settings.connection.proxyPort.desc"),
                proxyPort,
                tr("settings.connection.proxyPort.keywords"),
                Engine.Capability.PROXY);
        proxyUsername = text(Settings.PROXY_USERNAME);
        row(
                Category.CONNECTION,
                proxy,
                tr("settings.connection.proxyUsername"),
                tr("settings.connection.proxyUsername.desc"),
                proxyUsername,
                tr("settings.connection.proxyUsername.keywords"),
                Engine.Capability.PROXY);
        proxyPassword = new PasswordField();
        proxyPassword.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.PROXY_PASSWORD, now);
            changed();
        });
        row(
                Category.CONNECTION,
                proxy,
                tr("settings.connection.proxyPassword"),
                tr("settings.connection.proxyPassword.desc"),
                proxyPassword,
                tr("settings.connection.proxyPassword.keywords"),
                Engine.Capability.PROXY);
        proxyPeers = bool(Settings.PROXY_PEERS);
        row(
                Category.CONNECTION,
                proxy,
                tr("settings.connection.proxyPeers"),
                tr("settings.connection.proxyPeers.desc"),
                proxyPeers,
                tr("settings.connection.proxyPeers.keywords"),
                Engine.Capability.PROXY);
        for (var control : List.of(proxyHost, proxyPort, proxyUsername, proxyPassword, proxyPeers)) {
            control.disableProperty().bind(proxyType.valueProperty().isEqualTo(EngineSettings.ProxyType.NONE));
        }
    }

    private void buildProtocol(VBox page) {
        VBox discovery = section(page, tr("settings.protocol.findingPeers"));
        dht = bool(Settings.PROTO_DHT);
        row(
                Category.PROTOCOL,
                discovery,
                tr("settings.protocol.dht"),
                tr("settings.protocol.dht.desc"),
                dht,
                tr("settings.protocol.dht.keywords"));
        pex = bool(Settings.PROTO_PEX);
        row(
                Category.PROTOCOL,
                discovery,
                tr("settings.protocol.pex"),
                tr("settings.protocol.pex.desc"),
                pex,
                tr("settings.protocol.pex.keywords"));
        lsd = bool(Settings.PROTO_LSD);
        row(
                Category.PROTOCOL,
                discovery,
                tr("settings.protocol.lsd"),
                tr("settings.protocol.lsd.desc"),
                lsd,
                tr("settings.protocol.lsd.keywords"));

        VBox transport = section(page, tr("settings.protocol.transport"));
        utp = bool(Settings.PROTO_UTP);
        row(
                Category.PROTOCOL,
                transport,
                tr("settings.protocol.utp"),
                tr("settings.protocol.utp.desc"),
                utp,
                tr("settings.protocol.utp.keywords"));
        encryption = new ChoiceBox<>();
        encryption.getItems().addAll(EngineSettings.Protocol.Encryption.values());
        encryption.setConverter(new StringConverter<>() {
            @Override
            public String toString(EngineSettings.Protocol.Encryption e) {
                return e == null ? "" : e.label();
            }

            @Override
            public EngineSettings.Protocol.Encryption fromString(String s) {
                return null;
            }
        });
        encryption.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.set(Settings.PROTO_ENCRYPTION, now.name());
            changed();
        });
        row(
                Category.PROTOCOL,
                transport,
                tr("settings.protocol.encryption"),
                tr("settings.protocol.encryption.desc"),
                encryption,
                tr("settings.protocol.encryption.keywords"));
        anonymous = bool(Settings.PROTO_ANONYMOUS);
        row(
                Category.PROTOCOL,
                transport,
                tr("settings.protocol.anonymous"),
                tr("settings.protocol.anonymous.desc"),
                anonymous,
                tr("settings.protocol.anonymous.keywords"),
                Engine.Capability.ANONYMOUS_MODE);

        VBox trackers = section(page, tr("settings.protocol.trackers"));
        defaultTrackers = new TextArea();
        defaultTrackers.setPrefRowCount(4);
        defaultTrackers.setPrefColumnCount(26);
        defaultTrackers.setPromptText("udp://tracker.example.org:6969/announce");
        defaultTrackers.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(
                    Settings.PROTO_DEFAULT_TRACKERS, String.join(" ", now.trim().split("\\s+")));
            changed();
        });
        row(
                Category.PROTOCOL,
                trackers,
                tr("settings.protocol.defaultTrackers"),
                tr("settings.protocol.defaultTrackers.desc"),
                defaultTrackers,
                tr("settings.protocol.defaultTrackers.keywords"));
    }

    private void buildIpFilter(VBox page) {
        VBox list = section(page, tr("settings.ipFilter.blocklist"));
        ipFilterEnabled = new CheckBox();
        ipFilterEnabled.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.IPFILTER_ENABLED, String.valueOf(now));
            settings.saveQuietly();
            onIpFilterChanged.accept(false);
        });
        row(
                Category.IP_FILTER,
                list,
                tr("settings.ipFilter.enabled"),
                tr("settings.ipFilter.enabled.desc"),
                ipFilterEnabled,
                tr("settings.ipFilter.enabled.keywords"));
        ipFilterUrl = new TextField();
        ipFilterUrl.setPrefColumnCount(16);
        ipFilterUrl.setPromptText("https://…/list.p2p.gz");
        ipFilterUrl.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.IPFILTER_URL, now.trim());
            settings.saveQuietly();
        });
        Button refresh = new Button(tr("settings.ipFilter.downloadNow"));
        refresh.setOnAction(e -> onIpFilterChanged.accept(true));
        row(
                Category.IP_FILTER,
                list,
                tr("settings.ipFilter.url"),
                tr("settings.ipFilter.url.desc"),
                new HBox(6, ipFilterUrl, refresh),
                tr("settings.ipFilter.url.keywords"));
        ipFilterHours = intSpinner(1, 720, Settings.IPFILTER_REFRESH_HOURS);
        row(
                Category.IP_FILTER,
                list,
                tr("settings.ipFilter.refreshHours"),
                tr("settings.ipFilter.refreshHours.desc"),
                ipFilterHours,
                tr("settings.ipFilter.refreshHours.keywords"),
                Engine.Capability.IP_FILTER);
        ipFilterFile = new TextField();
        ipFilterFile.setPrefColumnCount(16);
        ipFilterFile.setPromptText(tr("settings.ipFilter.file.prompt"));
        ipFilterFile.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.IPFILTER_FILE, now.trim());
            settings.saveQuietly();
            onIpFilterChanged.accept(false);
        });
        Button browseFile = new Button(tr("common.browse"));
        browseFile.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(tr("settings.ipFilter.file.chooser"));
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) ipFilterFile.setText(chosen.getAbsolutePath());
        });
        row(
                Category.IP_FILTER,
                list,
                tr("settings.ipFilter.file"),
                tr("settings.ipFilter.file.desc"),
                new HBox(6, ipFilterFile, browseFile),
                tr("settings.ipFilter.file.keywords"),
                Engine.Capability.IP_FILTER);

        VBox bans = section(page, tr("settings.ipFilter.bans"));
        ipFilterBans = new TextArea();
        ipFilterBans.setPrefRowCount(4);
        ipFilterBans.setPrefColumnCount(24);
        ipFilterBans.setPromptText("1.2.3.4\n10.0.0.0/8\n1.2.3.0-1.2.3.255");
        ipFilterBans.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setIpFilterBans(java.util.Arrays.stream(now.trim().split("\\s+"))
                    .filter(t -> !t.isEmpty())
                    .distinct()
                    .toList());
            settings.saveQuietly();
            onIpFilterChanged.accept(false);
        });
        row(
                Category.IP_FILTER,
                bans,
                tr("settings.ipFilter.banned"),
                tr("settings.ipFilter.banned.desc"),
                ipFilterBans,
                tr("settings.ipFilter.banned.keywords"),
                Engine.Capability.IP_FILTER);
        ipFilterStatusLabel = new Label("");
        ipFilterStatusLabel.getStyleClass().add("settings-row-description");
        ipFilterStatusLabel.setWrapText(true);
        row(
                Category.IP_FILTER,
                bans,
                tr("settings.ipFilter.status"),
                "",
                ipFilterStatusLabel,
                tr("settings.ipFilter.status.keywords"));
    }

    private void buildSpeed(VBox page) {
        VBox limits = section(page, tr("settings.speed.limits"));
        speedDown = intSpinner(0, 10_000_000, Settings.SPEED_DOWNLOAD);
        row(
                Category.SPEED,
                limits,
                tr("settings.speed.download"),
                tr("settings.speed.download.desc"),
                speedDown,
                tr("settings.speed.download.keywords"));
        speedUp = intSpinner(0, 10_000_000, Settings.SPEED_UPLOAD);
        row(
                Category.SPEED,
                limits,
                tr("settings.speed.upload"),
                tr("settings.speed.upload.desc"),
                speedUp,
                tr("settings.speed.upload.keywords"));

        VBox alternative = section(page, tr("settings.speed.alternative"));
        altDown = intSpinner(0, 10_000_000, Settings.SPEED_ALT_DOWNLOAD);
        row(
                Category.SPEED,
                alternative,
                tr("settings.speed.altDownload"),
                tr("settings.speed.altDownload.desc"),
                altDown,
                tr("settings.speed.altDownload.keywords"));
        altUp = intSpinner(0, 10_000_000, Settings.SPEED_ALT_UPLOAD);
        row(
                Category.SPEED,
                alternative,
                tr("settings.speed.altUpload"),
                "",
                altUp,
                tr("settings.speed.altUpload.keywords"));
        altOn = bool(Settings.SPEED_ALT_ON);
        row(
                Category.SPEED,
                alternative,
                tr("settings.speed.altOn"),
                tr("settings.speed.altOn.desc"),
                altOn,
                tr("settings.speed.altOn.keywords"));

        VBox schedule = section(page, tr("settings.speed.schedule"));
        scheduleOn = bool(Settings.SPEED_SCHEDULE_ON);
        row(
                Category.SPEED,
                schedule,
                tr("settings.speed.scheduleOn"),
                tr("settings.speed.scheduleOn.desc"),
                scheduleOn,
                tr("settings.speed.scheduleOn.keywords"));
        scheduleFrom = timeField(Settings.SPEED_SCHEDULE_FROM);
        row(
                Category.SPEED,
                schedule,
                tr("settings.speed.from"),
                tr("settings.speed.from.desc"),
                scheduleFrom,
                tr("settings.speed.from.keywords"));
        scheduleTo = timeField(Settings.SPEED_SCHEDULE_TO);
        row(Category.SPEED, schedule, tr("settings.speed.to"), "", scheduleTo, tr("settings.speed.to.keywords"));
        HBox days = new HBox(8);
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            CheckBox box = new CheckBox(day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()));
            box.selectedProperty().addListener((o, was, now) -> {
                if (loading) return;
                settings.set(Settings.SPEED_SCHEDULE_DAYS, Settings.formatDays(selectedDays()));
                changed();
            });
            scheduleDays.put(day, box);
            days.getChildren().add(box);
        }
        row(Category.SPEED, schedule, tr("settings.speed.days"), "", days, tr("settings.speed.days.keywords"));

        VBox options = section(page, tr("settings.speed.options"));
        countOverhead = bool(Settings.SPEED_COUNT_OVERHEAD);
        row(
                Category.SPEED,
                options,
                tr("settings.speed.countOverhead"),
                tr("settings.speed.countOverhead.desc"),
                countOverhead,
                tr("settings.speed.countOverhead.keywords"),
                Engine.Capability.OVERHEAD_IN_LIMITS);
    }

    private void buildQueue(VBox page) {
        VBox queue = section(page, tr("settings.queue.queue"));
        activeDownloads = intSpinner(0, 10_000, Settings.QUEUE_ACTIVE_DOWNLOADS);
        row(
                Category.QUEUE,
                queue,
                tr("settings.queue.activeDownloads"),
                tr("settings.queue.activeDownloads.desc"),
                activeDownloads,
                tr("settings.queue.activeDownloads.keywords"));
        activeSeeds = intSpinner(0, 10_000, Settings.QUEUE_ACTIVE_SEEDS);
        row(
                Category.QUEUE,
                queue,
                tr("settings.queue.activeSeeds"),
                tr("settings.queue.activeSeeds.desc"),
                activeSeeds,
                tr("settings.queue.activeSeeds.keywords"));
        activeLimit = intSpinner(0, 10_000, Settings.QUEUE_ACTIVE_LIMIT);
        row(
                Category.QUEUE,
                queue,
                tr("settings.queue.activeLimit"),
                tr("settings.queue.activeLimit.desc"),
                activeLimit,
                tr("settings.queue.activeLimit.keywords"),
                Engine.Capability.ACTIVE_TOTAL_LIMIT);
        ignoreSlow = bool(Settings.QUEUE_IGNORE_SLOW);
        row(
                Category.QUEUE,
                queue,
                tr("settings.queue.ignoreSlow"),
                tr("settings.queue.ignoreSlow.desc"),
                ignoreSlow,
                tr("settings.queue.ignoreSlow.keywords"));

        VBox share = section(page, tr("settings.queue.share"));
        shareRatio = ratioSpinner(Settings.SHARE_RATIO);
        row(
                Category.QUEUE,
                share,
                tr("settings.queue.shareRatio"),
                tr("settings.queue.shareRatio.desc"),
                shareRatio,
                tr("settings.queue.shareRatio.keywords"));
        shareMinutes = intSpinner(0, 366 * 24 * 60, Settings.SHARE_SEED_MINUTES);
        row(
                Category.QUEUE,
                share,
                tr("settings.queue.shareMinutes"),
                tr("settings.queue.shareMinutes.desc"),
                shareMinutes,
                tr("settings.queue.shareMinutes.keywords"));
        shareAction = new ChoiceBox<>();
        shareAction.getItems().addAll(ShareLimit.Action.values());
        shareAction.setConverter(new StringConverter<>() {
            @Override
            public String toString(ShareLimit.Action action) {
                return action == null ? "" : action.label();
            }

            @Override
            public ShareLimit.Action fromString(String s) {
                return null;
            }
        });
        shareAction.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.set(Settings.SHARE_ACTION, now.name());
            changed();
        });
        row(
                Category.QUEUE,
                share,
                tr("settings.queue.shareAction"),
                tr("settings.queue.shareAction.desc"),
                shareAction,
                tr("settings.queue.shareAction.keywords"));
    }

    /** A decimal spinner in tenths, which writes its key as a person would type the number. */
    private Spinner<Double> ratioSpinner(String key) {
        Spinner<Double> spinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1000, 0, 0.1));
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        Spinners.commitOnBlur(spinner);
        spinner.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.set(key, ShareLimit.formatRatio(now));
            changed();
        });
        return spinner;
    }

    private java.util.Set<java.time.DayOfWeek> selectedDays() {
        java.util.Set<java.time.DayOfWeek> out = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        scheduleDays.forEach((day, box) -> {
            if (box.isSelected()) out.add(day);
        });
        return out;
    }

    /** An HH:mm field that writes its key only when the text is a valid time. */
    private TextField timeField(String key) {
        TextField field = new TextField();
        field.setPrefColumnCount(5);
        field.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            if (Settings.parseMinutes(now).isPresent()) {
                settings.set(key, now.trim());
                changed();
            }
        });
        return field;
    }

    /** A spinner that writes its key when the value changes, typed or stepped. */
    private Spinner<Integer> intSpinner(int min, int max, String key) {
        Spinner<Integer> spinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, min));
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        // Typed values commit on focus loss as well as Enter, or a typed port is lost on Tab.
        Spinners.commitOnBlur(spinner);
        spinner.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.set(key, String.valueOf(now));
            changed();
        });
        return spinner;
    }

    private CheckBox bool(String key) {
        CheckBox box = new CheckBox();
        box.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(key, String.valueOf(now));
            changed();
        });
        return box;
    }

    private TextField text(String key) {
        TextField field = new TextField();
        field.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(key, now.trim());
            changed();
        });
        return field;
    }

    private void changed() {
        settings.saveQuietly();
        onEngineSettingsChanged.run();
    }

    /** Puts the stored values into the controls, with the listeners held off. */
    private void reload() {
        loading = true;
        try {
            reloadLabels();
            reloadProfiles();
            reloadWatch();
            reloadDefinitions();
            reloadRss();
            rssMinutes.getValueFactory().setValue(settings.rssRefreshMinutes());
            rssAutoDownload.setSelected(settings.rssAutoDownload());
            language.setValue(settings.uiLanguage());
            theme.setValue(Theme.byId(settings.theme(), Theme.DEFAULT));
            downloadDir.setText(settings.downloadDir().toString());
            incompleteEnabled.setSelected(settings.incompleteEnabled());
            incompleteDir.setText(settings.incompleteDir().toString());
            partSuffix.setSelected(settings.partSuffix());
            preallocate.setSelected(settings.preallocate());
            updateCheck.setSelected(settings.updateCheck());
            stateColors.setSelected(settings.stateColors());
            density.setValue(Density.byId(settings.density(), Density.DEFAULT));
            sidebarPlace.setValue(SidebarPlace.of(settings.sidebar(), settings.sidebarSide()));
            detailPlace.setValue(DetailPlace.of(settings.detailVisible(), settings.detailSide()));
            extendedWindow.setSelected(settings.extendedWindow());
            paletteKey.setValue(PaletteKey.byId(settings.paletteKey(), PaletteKey.DEFAULT));
            tray.setSelected(settings.tray());
            closeToTray.setSelected(settings.closeToTray());
            osHandler.setSelected(settings.osHandler());
            osBadge.setSelected(settings.osBadge());
            syncEnabled.setSelected(settings.syncEnabled());
            syncUrl.setText(settings.syncUrl());
            syncBranch.setText(settings.syncBranch().equals("main") ? "" : settings.syncBranch());
            syncInterval.getValueFactory().setValue(settings.syncIntervalMinutes());
            addStartPaused.setSelected(settings.addStartPaused());
            addSequential.setSelected(settings.addSequential());
            addLayout.setValue(settings.addContentLayout());
            addWithoutAsking.setSelected(settings.addWithoutAsking());
            AutomationSettings a = settings.automation();
            onAdded.setText(a.onAdded());
            onFinished.setText(a.onFinished());
            onSeedingDone.setText(a.onSeedingDone());
            notifyAdded.setSelected(a.notifyAdded());
            notifyFinished.setSelected(a.notifyFinished());
            notifySeedingDone.setSelected(a.notifySeedingDone());
            notifyError.setSelected(a.notifyError());
            EngineSettings e = settings.engineSettings();
            port.getValueFactory().setValue(e.listenPort());
            randomPort.setSelected(e.randomPort());
            upnp.setSelected(e.upnp());
            ipv6.setSelected(e.ipv6());
            listenInterface.setText(e.listenInterface());
            maxConnections.getValueFactory().setValue(e.maxConnections());
            maxPerTorrent.getValueFactory().setValue(e.maxConnectionsPerTorrent());
            uploadSlots.getValueFactory().setValue(e.uploadSlots());
            proxyType.setValue(e.proxy());
            proxyHost.setText(e.proxyHost());
            proxyPort.getValueFactory().setValue(e.proxyPort());
            proxyUsername.setText(e.proxyUsername());
            proxyPassword.setText(e.proxyPassword());
            proxyPeers.setSelected(e.proxyPeers());
            EngineSettings.Protocol p = e.protocol();
            dht.setSelected(p.dht());
            pex.setSelected(p.pex());
            lsd.setSelected(p.lsd());
            utp.setSelected(p.utp());
            encryption.setValue(p.encryption());
            anonymous.setSelected(p.anonymous());
            defaultTrackers.setText(String.join("\n", p.defaultTrackers()));
            ipFilterEnabled.setSelected(settings.ipFilterEnabled());
            ipFilterUrl.setText(settings.ipFilterUrl());
            ipFilterHours.getValueFactory().setValue(settings.ipFilterRefreshHours());
            ipFilterFile.setText(settings.ipFilterFile());
            ipFilterBans.setText(String.join("\n", settings.ipFilterBans()));
            ipFilterStatusLabel.setText(ipFilterStatus.get());
            EngineSettings.Speed sp = e.speed();
            speedDown.getValueFactory().setValue(sp.download());
            speedUp.getValueFactory().setValue(sp.upload());
            altDown.getValueFactory().setValue(sp.alternativeDownload());
            altUp.getValueFactory().setValue(sp.alternativeUpload());
            altOn.setSelected(sp.alternativeOn());
            scheduleOn.setSelected(sp.scheduleOn());
            scheduleFrom.setText(Settings.formatMinutes(sp.scheduleFrom()));
            scheduleTo.setText(Settings.formatMinutes(sp.scheduleTo()));
            scheduleDays.forEach((day, box) -> box.setSelected(sp.scheduleDays().contains(day)));
            countOverhead.setSelected(sp.countOverhead());
            EngineSettings.Queue q = e.queue();
            activeDownloads.getValueFactory().setValue(q.activeDownloads());
            activeSeeds.getValueFactory().setValue(q.activeSeeds());
            activeLimit.getValueFactory().setValue(q.activeLimit());
            ignoreSlow.setSelected(q.ignoreSlowTorrents());
            shareRatio.getValueFactory().setValue(q.share().ratio());
            shareMinutes.getValueFactory().setValue(q.share().seedingMinutes());
            shareAction.setValue(q.share().action());
        } finally {
            loading = false;
        }
    }
}
