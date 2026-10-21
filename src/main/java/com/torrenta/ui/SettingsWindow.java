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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.WatchFolder;
import com.torrenta.rss.Feed;
import com.torrenta.rss.RssService;
import com.torrenta.rss.Rule;
import com.torrenta.search.SearchException;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.search.TorznabProvider;
import com.torrenta.store.DefinitionStore;
import com.torrenta.store.LabelStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;
import com.torrenta.sync.SyncEngine;

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
        APPLICATION("Application"),
        ENGINE("Engine");

        private final String title;

        Group(String title) {
            this.title = title;
        }

        String title() {
            return title;
        }
    }

    /** The pages. Connection, Speed, BitTorrent and Advanced join as their settings arrive. */
    enum Category {
        GENERAL(Group.APPLICATION, "General"),
        ADDING(Group.APPLICATION, "Adding torrents"),
        WATCH(Group.APPLICATION, "Watch folders"),
        AUTOMATION(Group.APPLICATION, "Automation"),
        SEARCH(Group.APPLICATION, "Search"),
        RSS(Group.APPLICATION, "RSS"),
        SYNC(Group.APPLICATION, "Sync"),
        LABELS(Group.APPLICATION, "Categories and tags"),
        CONNECTION(Group.ENGINE, "Connection"),
        PROTOCOL(Group.ENGINE, "Protocol"),
        IP_FILTER(Group.ENGINE, "IP filter"),
        SPEED(Group.ENGINE, "Speed"),
        QUEUE(Group.ENGINE, "Queue and share limits");

        final Group group;
        private final String title;

        Category(Group group, String title) {
            this.group = group;
            this.title = title;
        }

        String title() {
            return title;
        }
    }

    private record Row(Category category, Node node, String keywords, VBox section) {}

    /** The keys this window owns, for Reset. */
    private static final String[] OWNED_KEYS = concat(
            new String[] {
                Settings.UPDATE_CHECK,
                Settings.ADD_START_PAUSED,
                Settings.ADD_SEQUENTIAL,
                Settings.ADD_WITHOUT_ASKING,
                Settings.UI_STATE_COLORS,
                Settings.UI_DENSITY,
                Settings.UI_EXTENDED_WINDOW,
                Settings.UI_PALETTE_KEY,
                Settings.UI_TRAY,
                Settings.UI_CLOSE_TO_TRAY
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
        return null;
    }

    /** The open dialog's title, for a capture; empty while none is open. */
    String dialogTitle() {
        if (watchDialog != null && watchDialog.isShowing()) return watchDialog.title();
        if (searchDialog != null && searchDialog.isShowing()) return searchDialog.title();
        if (rssDialog != null && rssDialog.isShowing()) return rssDialog.title();
        if (ruleDialog != null && ruleDialog.isShowing()) return ruleDialog.title();
        return "";
    }

    /** Dismisses an open dialog, so the capture hook can exit outside its nested event loop. */
    void closeDialogsForCapture() {
        if (watchDialog != null && watchDialog.isShowing()) watchDialog.cancel();
        if (searchDialog != null && searchDialog.isShowing()) searchDialog.cancel();
        if (rssDialog != null && rssDialog.isShowing()) rssDialog.cancel();
        if (ruleDialog != null && ruleDialog.isShowing()) ruleDialog.cancel();
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

    private ChoiceBox<Theme> theme;
    private TextField downloadDir;
    private CheckBox incompleteEnabled;
    private TextField incompleteDir;
    private CheckBox partSuffix;
    private CheckBox preallocate;
    private CheckBox updateCheck;
    private CheckBox stateColors;
    private ChoiceBox<Density> density;
    private CheckBox extendedWindow;
    private ChoiceBox<PaletteKey> paletteKey;
    private CheckBox tray;
    private CheckBox closeToTray;
    private CheckBox addStartPaused;
    private CheckBox addSequential;
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
        VBox repository = section(page, "Repository");
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
                "Keep settings in sync",
                "Through a Git repository of yours, a private one on GitHub say. Every computer that connects to it"
                        + " gets the same settings: themes, columns, connection, protocol, filter and bans. Not this"
                        + " machine's folders, interface, clocks or proxy password.",
                syncEnabled,
                "git github repository share machines computers");
        syncUrl = new TextField();
        syncUrl.setPrefColumnCount(18);
        syncUrl.setPromptText("https://github.com/you/torrenta-settings.git");
        syncUrl.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setSyncUrl(now);
            settings.saveQuietly();
            if (sync != null) sync.settingsChanged();
        });
        Button create = new Button("Create on GitHub…");
        create.setOnAction(e -> createSyncRepository());
        row(
                Category.SYNC,
                repository,
                "Repository",
                "Its URL as git would clone it, HTTPS or SSH, without a password or token in it. Create on GitHub"
                        + " makes a private one through the GitHub CLI and fills this in.",
                new HBox(6, syncUrl, create),
                "url clone remote private gh");
        syncBranch = new TextField();
        syncBranch.setPrefColumnCount(10);
        syncBranch.setPromptText("main");
        syncBranch.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setSyncBranch(now);
            settings.saveQuietly();
        });
        row(Category.SYNC, repository, "Branch", "main unless the repository uses another.", syncBranch, "branch");
        syncInterval = new Spinner<>(1, 1440, 15, 5);
        syncInterval.setEditable(true);
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
                "Every, in minutes",
                "How often to look while the window is in front. A change here syncs half a minute after it"
                        + " anyway, and so does startup.",
                syncInterval,
                "interval automatic timer schedule");

        VBox status = section(page, "Status");
        syncStatus = new Label("Not connected.");
        syncStatus.setWrapText(true);
        syncStatus.getStyleClass().add("settings-row-description");
        syncNow = new Button("Sync now");
        syncNow.setOnAction(e -> {
            if (sync != null) sync.syncNow();
        });
        syncDisconnect = new Button("Disconnect");
        syncDisconnect.setOnAction(e -> disconnectSync());
        VBox statusBox = new VBox(8, syncStatus, new HBox(8, syncNow, syncDisconnect));
        status.getChildren().add(statusBox);
        rows.add(new Row(Category.SYNC, statusBox, "status last sync now disconnect account", status));
    }

    /** The switch went on: refuse a URL with a secret, show what connecting brings, then sync. */
    private void connectSync() {
        String url = syncUrl.getText().trim();
        if (!SyncEngine.isUsableUrl(url)) {
            syncEnabled.setSelected(false);
            syncStatus.setText("Enter the repository's URL first.");
            return;
        }
        if (SyncEngine.hasCredentials(url)) {
            syncEnabled.setSelected(false);
            syncStatus.setText("The URL carries a password or token; git signs in by itself, take it out.");
            return;
        }
        settings.setSyncUrl(url);
        settings.setSyncEnabled(true);
        settings.saveQuietly();
        if (sync == null) return;
        syncStatus.setText("Looking at the repository…");
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
                        "The repository already holds settings: "
                                + report.received().size()
                                + " would come to this machine"
                                + (report.conflicts().isEmpty()
                                        ? ""
                                        : ", and " + report.conflicts().size()
                                                + " that differ here keep this machine's value")
                                + ". Connect?");
                ask.setHeaderText("Connect to the repository");
                ask.initOwner(stage);
                Fonts.installUiFont(ask.getDialogPane());
                if (ask.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
                    settings.setSyncEnabled(false);
                    settings.saveQuietly();
                    reload();
                    syncStatus.setText("Not connected.");
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
        ask.setTitle("Create on GitHub");
        ask.setHeaderText("A private repository under the GitHub CLI's account");
        ask.setContentText("Name:");
        Fonts.installUiFont(ask.getDialogPane());
        ask.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (sync == null) return;
            syncStatus.setText("Creating " + name + " on GitHub…");
            sync.createOnGitHub(name, created -> {
                if (created.ok()) {
                    syncUrl.setText(created.url());
                    syncStatus.setText("Created. Switch sync on to connect.");
                } else {
                    syncStatus.setText("GitHub CLI: " + created.error());
                }
            });
        });
    }

    private void disconnectSync() {
        Alert ask = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Stop syncing, forget the repository and remove the local copy? The settings stay as they are.");
        ask.setHeaderText("Disconnect");
        ask.initOwner(stage);
        Fonts.installUiFont(ask.getDialogPane());
        if (ask.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        if (sync != null) sync.disconnect();
        reload();
    }

    /** FX thread: the Status section, from the service's state. */
    private void refreshSyncStatus() {
        if (syncStatus == null || sync == null) return;
        SettingsSync.State s = sync.state();
        String text =
                switch (s.phase()) {
                    case OFF -> "Not connected.";
                    case SYNCING -> "Syncing…";
                    case IDLE ->
                        s.report() == null
                                ? "Connected; the first sync is due."
                                : "Last sync " + at(s.at()) + ": " + s.report().summary() + ".";
                    case PROBLEM ->
                        "⚠ " + (s.report() == null ? "" : s.report().summary())
                                + (s.at() == null ? "" : " (" + at(s.at()) + ")");
                };
        if (!s.account().isEmpty()) text += "\nSigning in as " + s.account() + " through the GitHub CLI.";
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
        reload();
        stage.show();
    }

    // ---------------------------------------------------------------- shell

    private void build() {
        stage.setTitle(AppInfo.NAME + " Settings");
        for (Category category : Category.values()) pages.put(category, buildPage(category));

        search.setPromptText("Search settings");
        search.textProperty().addListener((o, old, value) -> filter(value));
        VBox.setMargin(search, new Insets(0, 0, 8, 0));

        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(150);
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.setCellFactory(list -> new SidebarCell());
        sidebar.getItems().setAll(sidebarItems());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item instanceof Category category) showPage(category);
        });

        pageHost.setPadding(new Insets(4, 0, 0, PAGE_GUTTER));
        pageHost.setAlignment(Pos.TOP_LEFT);

        Button reset = new Button("Reset to defaults");
        reset.setOnAction(e -> {
            settings.reset(OWNED_KEYS);
            settings.saveQuietly();
            reload();
            onAppearanceChanged.run();
        });
        Button close = new Button("Close");
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
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });
        stage.setScene(scene);
        selectFirstVisible();
    }

    private List<Object> sidebarItems() {
        List<Object> items = new ArrayList<>();
        Group current = null;
        for (Category category : Category.values()) {
            if (category.group != current) {
                current = category.group;
                items.add(current);
            }
            items.add(category);
        }
        return items;
    }

    /** Group headers are labels, not choices — they must not be selectable or clickable. */
    private final class SidebarCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("settings-group-header", "settings-sidebar-item", "settings-dimmed");
            if (empty || item == null) {
                setText(null);
                setDisable(false);
                setMouseTransparent(false);
                return;
            }
            if (item instanceof Group group) {
                setText(group.title().toUpperCase(Locale.ROOT));
                getStyleClass().add("settings-group-header");
                setMouseTransparent(true);
                setDisable(false);
            } else {
                Category category = (Category) item;
                setText("    " + category.title());
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

    // ---------------------------------------------------------------- search

    /** Case-insensitive substring over a row's own keywords. */
    static boolean matches(String query, String keywords) {
        if (query == null || query.isBlank()) return true;
        return keywords.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    /** The visible rows' titles, for a capture. */
    List<String> visibleRowsForCapture() {
        List<String> out = new ArrayList<>();
        for (Row row : rows) if (row.node().isManaged()) out.add(row.keywords().split(" \\| ")[0]);
        return out;
    }

    private void filter(String query) {
        hiddenCategories.clear();
        Set<Category> withHits = new LinkedHashSet<>();
        for (Row row : rows) {
            boolean visible = matches(query, row.keywords());
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
        rows.add(new Row(category, line, title + " | " + description + " " + keywords, section));
        return line;
    }

    // ---------------------------------------------------------------- pages

    private void buildGeneral(VBox page) {
        VBox appearance = section(page, "Appearance");
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
                "Theme",
                "Editora's Caret & Ink pair, and AtlantaFX's Nord and Cupertino, light and dark. Applied at once.",
                theme,
                "appearance colors colours dark light nord cupertino editora style");
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
                "Row density",
                "Comfortable rows are two lines: the name over a thin progress bar and what the torrent is"
                        + " doing. Compact rows are one line, with Progress and Status as columns. Each keeps"
                        + " its own columns.",
                density,
                "rows density comfortable compact height two lines list table columns");
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
                "Colour rows by state",
                "Downloading teal, seeding periwinkle, stalled amber, errors red: the glyph, the start of the"
                        + " status and the progress bar. Off, the glyphs and sentences still say it.",
                stateColors,
                "state status colours colors glyph progress bar stalled seeding downloading error accessibility");
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
                "Merge the menu bar into the title bar",
                "Experimental. Draws the window without a system title bar and puts the menus, the title"
                        + " and the window buttons on that one row. Not on macOS, where the menu belongs"
                        + " to the system bar. Applies after restarting Torrenta.",
                extendedWindow,
                "title bar titlebar menu bar menubar header extended window chrome decorations csd"
                        + " compact row space");

        VBox keyboard = section(page, "Keyboard");
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
                "Command palette",
                "The chord that opens the palette: Alt+X, Editora's M-x, or the Ctrl+Shift+P of the"
                        + " first releases. The View menu and the toolbar say the one in force. Applied"
                        + " at once.",
                paletteKey,
                "keyboard shortcut chord key binding keybinding palette command alt x m-x ctrl shift p");

        VBox trayBox = section(page, "System tray");
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
                "Show a tray icon",
                "The arrow in the panel: teal while something downloads, red when a torrent has an error."
                        + " Its tooltip and menu carry the rates. Where the desktop has a tray: KDE, GNOME with"
                        + " the AppIndicator extension, Windows and the macOS menu bar; on Windows and macOS"
                        + " it applies after restarting Torrenta.",
                tray,
                "tray icon panel notification area menu bar status item indicator appindicator dock");
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
                "Closing the window keeps Torrenta in the tray",
                "The torrents go on; the tray icon's Show Torrenta brings the window back, its Quit ends"
                        + " the program, as does Quit in the File menu. Without a tray, closing quits.",
                closeToTray,
                "close to tray hide minimize minimise background keep running quit exit window");

        VBox downloads = section(page, "Downloads");
        downloadDir = new TextField();
        downloadDir.setPrefColumnCount(28);
        downloadDir.textProperty().addListener((o, was, now) -> {
            if (loading || now.isBlank()) return;
            settings.downloadDir(Path.of(now.trim()));
            settings.saveQuietly();
        });
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Download folder");
            File current = new File(downloadDir.getText().trim());
            while (current != null && !current.isDirectory()) current = current.getParentFile();
            if (current != null) chooser.setInitialDirectory(current);
            File chosen = chooser.showDialog(stage);
            if (chosen != null) downloadDir.setText(chosen.getAbsolutePath());
        });
        row(
                Category.GENERAL,
                downloads,
                "Download folder",
                "Where new torrents save their files; the Add dialog starts here and can pick another.",
                new HBox(6, downloadDir, browse),
                "save path directory location");

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
        Button browseIncomplete = new Button("Browse…");
        browseIncomplete
                .disableProperty()
                .bind(incompleteEnabled.selectedProperty().not());
        browseIncomplete.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Incomplete folder");
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
                "Incomplete folder",
                "Downloads start here and are moved to their save path when complete; a file already there is never"
                        + " written over. This folder is this machine's and does not sync.",
                incompleteRow,
                "temporary partial in progress move finished");
        partSuffix = new CheckBox();
        partSuffix.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setPartSuffix(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                downloads,
                "Append .part to incomplete files",
                "Each file carries the suffix until it is complete, so a half-downloaded file is never mistaken for a"
                        + " whole one. Applies to torrents added from now on.",
                partSuffix,
                "suffix extension !qB !tr partial rename");
        preallocate = new CheckBox();
        preallocate.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setPreallocate(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                downloads,
                "Pre-allocate disk space for all files",
                "A file is written at its full size from its first piece, which keeps it in one piece on disk and"
                        + " fails early when the disk is full. Applies to torrents added from now on.",
                preallocate,
                "allocate sparse fragmentation full size");

        VBox updates = section(page, "Updates");
        updateCheck = new CheckBox();
        updateCheck.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setUpdateCheck(now);
            settings.saveQuietly();
        });
        row(
                Category.GENERAL,
                updates,
                "Check for updates",
                "Ask GitHub for a newer release after startup, at most once a day. Help → Check for Updates asks regardless.",
                updateCheck,
                "release version github automatic");

        VBox diagnostics = section(page, "Diagnostics");
        Button debugLog = new Button("Show debug log");
        debugLog.setOnAction(e -> showDebugLog.run());
        row(
                Category.GENERAL,
                diagnostics,
                "Debug log",
                "Warnings, errors and crashes from this session, also written to " + DebugLog.FILE_NAME
                        + " beside the settings. Copy it into a bug report.",
                debugLog,
                "debug log diagnostics errors warnings crash trace troubleshoot bug report session file");
    }

    /**
     * Categories (name and save path) and tags, as lists with a form under each. Not settings
     * keys: the lists live in {@code categories.json} and {@code tags.json}, through the store,
     * and the window re-files the torrents when one is deleted.
     */
    private void buildLabels(VBox page) {
        VBox categories = section(page, "Categories");
        categoryTable = new TableView<>();
        categoryTable.setPrefHeight(170);
        categoryTable.setPlaceholder(new Label("No categories yet"));
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<com.torrenta.engine.Category, String> name = new TableColumn<>("Name");
        name.setPrefWidth(160);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<com.torrenta.engine.Category, String> path = new TableColumn<>("Save path");
        path.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().savePath().isEmpty()
                        ? "(the download folder)"
                        : c.getValue().savePath()));
        categoryTable.getColumns().addAll(List.of(name, path));
        categoryName = new TextField();
        categoryName.setPromptText("name");
        categoryName.setPrefColumnCount(12);
        categoryPath = new TextField();
        categoryPath.setPromptText("save path, empty for the download folder");
        HBox.setHgrow(categoryPath, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Save path for the category");
            File current = new File(categoryPath.getText().trim());
            while (current != null && !current.isDirectory()) current = current.getParentFile();
            if (current != null) chooser.setInitialDirectory(current);
            File chosen = chooser.showDialog(stage);
            if (chosen != null) categoryPath.setText(chosen.getAbsolutePath());
        });
        Button save = new Button("Save");
        save.setDefaultButton(false);
        save.disableProperty().bind(categoryName.textProperty().map(String::isBlank));
        save.setOnAction(e -> saveCategory());
        categoryName.setOnAction(e -> saveCategory());
        categoryPath.setOnAction(e -> saveCategory());
        categoryDelete = new Button("Delete");
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
        Label hint = new Label("A category with a save path fills the Add dialog's folder when chosen: new torrents"
                + " filed under it download there. Changing the path moves nothing already added. Save with"
                + " an existing name changes that category's path; Delete unfiles its torrents.");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        VBox categoryBox = new VBox(8, hint, categoryTable, form);
        categories.getChildren().add(categoryBox);
        rows.add(new Row(
                Category.LABELS,
                categoryBox,
                "Categories | name save path folder download automatic torrent management delete",
                categories));

        VBox tags = section(page, "Tags");
        tagList = new ListView<>();
        tagList.setPrefHeight(130);
        tagList.setPlaceholder(new Label("No tags yet"));
        newTag = new TextField();
        newTag.setPromptText("new tags, comma-separated");
        HBox.setHgrow(newTag, Priority.ALWAYS);
        Button addTag = new Button("Add");
        addTag.disableProperty().bind(newTag.textProperty().map(String::isBlank));
        addTag.setOnAction(e -> addTags());
        newTag.setOnAction(e -> addTags());
        tagDelete = new Button("Delete");
        tagDelete
                .disableProperty()
                .bind(tagList.getSelectionModel().selectedItemProperty().isNull());
        tagDelete.setOnAction(e -> deleteTag());
        HBox tagForm = new HBox(6, newTag, addTag, tagDelete);
        tagForm.setAlignment(Pos.CENTER_LEFT);
        Label tagHint = new Label("Any number of tags per torrent (Torrent → Edit Tags…, Ctrl+T). A tag typed in a"
                + " dialog is added here by itself; Delete takes it off every torrent.");
        tagHint.setWrapText(true);
        tagHint.getStyleClass().add("settings-row-description");
        VBox tagBox = new VBox(8, tagHint, tagList, tagForm);
        tags.getChildren().add(tagBox);
        rows.add(new Row(Category.LABELS, tagBox, "Tags | labels add delete", tags));
    }

    private void saveCategory() {
        if (labelStore == null || categoryName.getText().isBlank()) return;
        try {
            labelStore.putCategory(new com.torrenta.engine.Category(categoryName.getText(), categoryPath.getText()));
        } catch (IOException | IllegalArgumentException e) {
            report("Could not save the category: " + e.getMessage());
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
        try {
            labelStore.removeCategory(selected.name());
        } catch (IOException e) {
            report("Could not delete the category: " + e.getMessage());
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
            report("Could not save the tags: " + e.getMessage());
            return;
        }
        newTag.clear();
        reloadLabels();
        labelsHost.labelsChanged();
    }

    private void deleteTag() {
        String selected = tagList.getSelectionModel().getSelectedItem();
        if (labelStore == null || selected == null) return;
        try {
            labelStore.removeTag(selected);
        } catch (IOException e) {
            report("Could not delete the tag: " + e.getMessage());
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
        alert.initOwner(stage);
        Fonts.installUiFont(alert.getDialogPane());
        alert.showAndWait();
    }

    private void buildWatch(VBox page) {
        VBox folders = section(page, "Watched folders");
        watchTable = new TableView<>();
        watchTable.setPrefHeight(190);
        watchTable.setPlaceholder(new Label("No folders watched yet"));
        watchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<WatchFolder, String> folder = new TableColumn<>("Folder");
        folder.setPrefWidth(200);
        folder.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().folder()));
        TableColumn<WatchFolder, String> saveTo = new TableColumn<>("Save to");
        saveTo.setPrefWidth(170);
        saveTo.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                switch (c.getValue().saveTo()) {
                    case DEFAULT -> "(the download folder)";
                    case WATCH_FOLDER -> "(the watch folder)";
                    case FOLDER -> c.getValue().savePath();
                }));
        TableColumn<WatchFolder, String> after = new TableColumn<>("After adding");
        after.setPrefWidth(110);
        after.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                switch (c.getValue().afterAdd()) {
                    case DELETE -> "Delete";
                    case RENAME -> "Rename";
                }));
        TableColumn<WatchFolder, String> category = new TableColumn<>("Category");
        category.setPrefWidth(100);
        category.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().category().isEmpty() ? "" : c.getValue().category()));
        TableColumn<WatchFolder, String> paused = new TableColumn<>("Paused");
        paused.setPrefWidth(60);
        paused.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().startPaused() ? "yes" : ""));
        watchTable.getColumns().addAll(List.of(folder, saveTo, after, category, paused));
        watchTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<WatchFolder> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) editWatchFolder();
            });
            return row;
        });
        Button add = new Button("Add…");
        add.setOnAction(e -> addWatchFolder());
        Button edit = new Button("Edit…");
        edit.disableProperty()
                .bind(watchTable.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> editWatchFolder());
        Button remove = new Button("Remove");
        remove.disableProperty()
                .bind(watchTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeWatchFolder());
        HBox buttons = new HBox(6, add, edit, remove);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("A .torrent or .magnet file (one magnet link per line) dropped into a watched folder is"
                + " added without a dialog, with the folder's choices, and then deleted or renamed .added."
                + " Watched folders are this machine's (watch-folders.json beside the settings) and never sync.");
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
                "Watch folders | folder torrent magnet automatic add delete rename save path category paused status",
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
        List<WatchFolder> list = new ArrayList<>(watchStore.entries());
        list.remove(selected);
        saveWatchFolders(list, null);
    }

    private void saveWatchFolders(List<WatchFolder> list, WatchFolder select) {
        try {
            watchStore.set(list);
        } catch (IOException e) {
            report("Could not save the watch folders: " + e.getMessage());
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
        VBox refresh = section(page, "Refreshing");
        rssMinutes = new Spinner<>(Feed.MIN_MINUTES, Feed.MAX_MINUTES, 30, 5);
        rssMinutes.setEditable(true);
        rssMinutes.setPrefWidth(90);
        rssMinutes.valueProperty().addListener((o, was, now) -> {
            if (loading || now == null) return;
            settings.setRssRefreshMinutes(now);
            settings.saveQuietly();
        });
        row(
                Category.RSS,
                refresh,
                "Refresh every (minutes)",
                "How often every feed is asked again, 5 to 1440; a feed can have its own interval. An unchanged"
                        + " feed costs one request (ETag and Last-Modified are sent back). Syncs.",
                rssMinutes,
                "rss feed interval refresh minutes schedule");
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
                "Download new articles' torrents automatically",
                "As each feed refreshes, the rules below run over its new articles and add the torrents they"
                        + " want, each once, said in the Event Log. Off, the rules run only on Run rules now. Syncs.",
                rssAutoDownload,
                "rss auto download automatic rules filter");

        VBox feeds = section(page, "Feeds");
        rssTable = new TableView<>();
        rssTable.setPrefHeight(190);
        rssTable.setPlaceholder(new Label("No feeds yet"));
        rssTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Feed, String> name = new TableColumn<>("Name");
        name.setPrefWidth(130);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().displayName()));
        TableColumn<Feed, String> url = new TableColumn<>("URL");
        url.setPrefWidth(190);
        url.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().url()));
        TableColumn<Feed, String> last = new TableColumn<>("Last refresh");
        last.setPrefWidth(110);
        last.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(rssLastRefresh(c.getValue())));
        TableColumn<Feed, String> unread = new TableColumn<>("Unread");
        unread.setPrefWidth(70);
        unread.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                rssService == null
                        ? ""
                        : Integer.toString(rssService.unread(c.getValue().id()))));
        TableColumn<Feed, String> status = new TableColumn<>("Status");
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
        Button add = new Button("Add…");
        add.setOnAction(e -> addRssFeed(""));
        Button edit = new Button("Edit…");
        edit.disableProperty()
                .bind(rssTable.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> editRssFeed());
        Button remove = new Button("Remove");
        remove.disableProperty()
                .bind(rssTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeRssFeed());
        Button refreshNow = new Button("Refresh");
        refreshNow.setOnAction(e -> {
            if (rssService == null) return;
            Feed selected = rssTable.getSelectionModel().getSelectedItem();
            if (selected == null) rssService.refreshAll();
            else rssService.refresh(selected.id());
        });
        HBox buttons = new HBox(6, add, edit, remove, refreshNow);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("The RSS tab (Ctrl+R) reads these. A feed is added by URL and fetched once to check it;"
                + " a blank name takes the feed's title. The feeds and their articles, read or not, are rss/feeds.json"
                + " and rss/articles/<id>.json beside the settings: this machine's, never synced.");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-row-description");
        rssStatusLabel = new Label();
        rssStatusLabel.setWrapText(true);
        rssStatusLabel.getStyleClass().add("settings-row-description");
        VBox box = new VBox(8, hint, rssTable, buttons, rssStatusLabel);
        feeds.getChildren().add(box);
        rows.add(new Row(
                Category.RSS, box, "Feeds | rss atom feed url add edit remove refresh unread articles status", feeds));

        VBox rulesSection = section(page, "Rules");
        rulesTable = new TableView<>();
        rulesTable.setPrefHeight(170);
        rulesTable.setPlaceholder(
                new Label("No rules yet: Add… writes one, or Create rule from title… in the RSS tab"));
        rulesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Rule, String> ruleName = new TableColumn<>("Name");
        ruleName.setPrefWidth(160);
        ruleName.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<Rule, String> ruleOn = new TableColumn<>("Enabled");
        ruleOn.setPrefWidth(70);
        ruleOn.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().enabled() ? "yes" : "no"));
        TableColumn<Rule, String> ruleFeeds = new TableColumn<>("Feeds");
        ruleFeeds.setPrefWidth(200);
        ruleFeeds.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                rssService == null ? "" : RuleText.feedsText(c.getValue(), rssService.namesById())));
        TableColumn<Rule, String> ruleLast = new TableColumn<>("Last match");
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
        Button addRule = new Button("Add…");
        addRule.setOnAction(e -> addRule("", ""));
        Button editRule = new Button("Edit…");
        editRule.disableProperty()
                .bind(rulesTable.getSelectionModel().selectedItemProperty().isNull());
        editRule.setOnAction(e -> editRule());
        Button removeRule = new Button("Remove");
        removeRule
                .disableProperty()
                .bind(rulesTable.getSelectionModel().selectedItemProperty().isNull());
        removeRule.setOnAction(e -> removeRule());
        Button duplicateRule = new Button("Duplicate");
        duplicateRule
                .disableProperty()
                .bind(rulesTable.getSelectionModel().selectedItemProperty().isNull());
        duplicateRule.setOnAction(e -> duplicateRule());
        Button runRules = new Button("Run rules now");
        runRules.setOnAction(e -> {
            if (rssService != null) rssService.runRulesNow();
        });
        HBox ruleButtons = new HBox(6, addRule, editRule, removeRule, duplicateRule, runRules);
        ruleButtons.setAlignment(Pos.CENTER_LEFT);
        Label rulesHint = new Label("A rule names the articles it wants (words the title must and must not contain, or"
                + " regular expressions; an episode filter such as 1x2-; which feeds; how old is too old) and what to"
                + " do with them (start paused or not, a category, a save path). The first rule that wants an"
                + " article takes it; the smart episode filter keeps a rule from taking an episode twice. Run rules"
                + " now goes over every unread article not yet downloaded. The rules are rss/rules.json beside the"
                + " settings: this machine's, never synced.");
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
                "Rules | rss auto download rule regex episode filter smart category save path must contain feeds"
                        + " add edit remove duplicate run",
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
        try {
            rssService.removeRule(selected.name());
        } catch (IOException e) {
            report("Could not remove the rule: " + e.getMessage());
        }
        reloadRules();
    }

    private void saveRule(String replacing, Rule rule) {
        try {
            rssService.putRule(replacing, rule);
        } catch (IOException e) {
            report("Could not save the rule: " + e.getMessage());
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
                ? "refreshing…"
                : s.refreshedEpochMs() <= 0 ? "never" : RssText.ago(s.refreshedEpochMs(), System.currentTimeMillis());
    }

    private String rssFeedStatus(Feed feed) {
        if (rssService == null) return "";
        RssService.Status s = rssService.status(feed.id());
        return s.failed() ? "last error: " + s.error() : s.refreshedEpochMs() <= 0 ? "" : "ok";
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
            report("Could not save the feed: " + e.getMessage());
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
            report("Could not save the feed: " + e.getMessage());
            return;
        }
        reloadRss();
        rssTable.getSelectionModel().select(edited.get());
    }

    private void removeRssFeed() {
        Feed selected = rssTable.getSelectionModel().getSelectedItem();
        if (rssService == null || selected == null) return;
        try {
            rssService.removeFeed(selected.id());
        } catch (IOException e) {
            report("Could not remove the feed: " + e.getMessage());
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
        rssStatusLabel.setText(
                feeds.isEmpty()
                        ? ""
                        : (feeds.size() == 1 ? "1 feed" : feeds.size() + " feeds") + ", "
                                + (unread == 1 ? "1 unread article" : unread + " unread articles"));
    }

    private void buildSearch(VBox page) {
        VBox providers = section(page, "Search providers");
        searchTable = new TableView<>();
        searchTable.setPrefHeight(190);
        searchTable.setPlaceholder(new Label("No providers yet: add a Prowlarr or Jackett server"));
        searchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<TorznabEndpoint, String> name = new TableColumn<>("Name");
        name.setPrefWidth(120);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<TorznabEndpoint, String> url = new TableColumn<>("URL");
        url.setPrefWidth(260);
        url.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().url()));
        TableColumn<TorznabEndpoint, String> key = new TableColumn<>("API key");
        key.setPrefWidth(80);
        key.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().maskedKey()));
        TableColumn<TorznabEndpoint, String> enabled = new TableColumn<>("Enabled");
        enabled.setPrefWidth(60);
        enabled.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().enabled() ? "yes" : ""));
        TableColumn<TorznabEndpoint, String> categories = new TableColumn<>("Categories");
        categories.setPrefWidth(90);
        categories.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().categories() < 0
                        ? "not tested"
                        : Integer.toString(c.getValue().categories())));
        searchTable.getColumns().addAll(List.of(name, url, key, enabled, categories));
        searchTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<TorznabEndpoint> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) editSearchProvider();
            });
            return row;
        });
        Button add = new Button("Add…");
        add.setOnAction(e -> addSearchProvider());
        Button edit = new Button("Edit…");
        edit.disableProperty()
                .bind(searchTable.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> editSearchProvider());
        Button remove = new Button("Remove");
        remove.disableProperty()
                .bind(searchTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeSearchProvider());
        Button test = new Button("Test");
        test.disableProperty()
                .bind(searchTable.getSelectionModel().selectedItemProperty().isNull());
        test.setOnAction(e -> testSearchProvider());
        HBox buttons = new HBox(6, add, edit, remove, test);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("A Torznab server, Prowlarr or Jackett, puts its indexers behind one URL and an API key;"
                + " every enabled one is asked when searching. Test asks the server who it is and how many"
                + " categories it knows. The list, keys included, is search-providers.json beside the settings:"
                + " this machine's, never synced.");
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
                "Search providers | search torznab prowlarr jackett indexer api key url add edit remove test"
                        + " enabled categories",
                providers));
        buildDefinitions(page);
    }

    /** The definition files: listed read-only, switched on or off, reloaded, the folder opened. */
    private void buildDefinitions(VBox page) {
        VBox section = section(page, "Definition files");
        definitionTable = new TableView<>();
        definitionTable.setPrefHeight(160);
        definitionTable.setPlaceholder(new Label("No definition files in the folder"));
        definitionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<DefinitionStore.Entry, String> name = new TableColumn<>("Name");
        name.setPrefWidth(140);
        name.setCellValueFactory(c ->
                new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().name()));
        TableColumn<DefinitionStore.Entry, String> file = new TableColumn<>("File");
        file.setPrefWidth(150);
        file.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().file().getFileName().toString()));
        TableColumn<DefinitionStore.Entry, String> type = new TableColumn<>("Type");
        type.setPrefWidth(50);
        type.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(definitionType(c.getValue())));
        TableColumn<DefinitionStore.Entry, String> on = new TableColumn<>("Enabled");
        on.setPrefWidth(60);
        on.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                !c.getValue().loaded()
                        ? ""
                        : settings.disabledSearchDefinitions()
                                        .contains(c.getValue().id())
                                ? ""
                                : "yes"));
        TableColumn<DefinitionStore.Entry, String> status = new TableColumn<>("Status");
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
        Button toggle = new Button("Enable / Disable");
        toggle.disableProperty()
                .bind(definitionTable.getSelectionModel().selectedItemProperty().isNull());
        toggle.setOnAction(e -> toggleDefinition());
        Button reload = new Button("Reload definitions");
        reload.setOnAction(e -> reloadDefinitionsPressed());
        Button open = new Button("Open folder");
        open.setOnAction(e -> openDefinitionFolder.run());
        HBox buttons = new HBox(6, toggle, reload, open);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("A site without an API is a definition file: search-providers/<id>.json in the config"
                + " folder says where to search and how to read the answer (JSON paths or CSS-like selectors;"
                + " docs/search-providers.md has the format). The shipped examples are copied in once and never"
                + " written over. Every file that loads is a provider chip in the Search tab unless switched off"
                + " here (search.disabled, which syncs); a file that does not load says why. The folder is"
                + " re-read when the Search tab opens, or on Reload.");
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
                "Definition files | search definition declarative provider json html selector folder reload"
                        + " example archive linuxtracker academic enable disable",
                section));
    }

    private static String definitionType(DefinitionStore.Entry e) {
        return e.loaded() ? e.definition().type().name().toLowerCase(Locale.ROOT) : "";
    }

    private static String definitionStatus(DefinitionStore.Entry e) {
        if (e.loaded()) return "loaded";
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
                            ? "Folder: " + definitionStore.dir()
                            : on + " of " + loaded + " enabled" + (broken == 0 ? "" : ", " + broken + " did not load")
                                    + " · " + definitionStore.dir());
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
        List<TorznabEndpoint> list = new ArrayList<>(searchStore.entries());
        list.remove(selected);
        saveSearchProviders(list, null);
    }

    /** Asks the selected server for its capabilities, off the FX thread, and writes the count back. */
    private void testSearchProvider() {
        TorznabEndpoint selected = searchTable.getSelectionModel().getSelectedItem();
        if (searchStore == null || selected == null) return;
        searchStatusLabel.setText("Testing " + selected.name() + "…");
        Thread.ofVirtual().name("torznab-test").start(() -> {
            String line;
            TorznabEndpoint tested = null;
            try {
                TorznabProvider.Capabilities caps = searchTester.test(selected);
                tested = selected.tested(caps.title(), caps.categories().size());
                String found = SearchProviderDialog.categoriesLine(
                        caps.title(), caps.categories().size());
                line = caps.title().equalsIgnoreCase(selected.name()) ? found : selected.name() + ": " + found;
            } catch (SearchException e) {
                line = selected.name() + ": " + e.getMessage();
            } catch (RuntimeException e) {
                line = selected.name() + ": failed: " + e;
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
            report("Could not save the search providers: " + e.getMessage());
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
                            : searchStore.enabled().size() + " of "
                                    + searchStore.entries().size() + " enabled");
    }

    private void buildAutomation(VBox page) {
        VBox run = section(page, "Run on event");
        onAdded = commandField(EngineEvent.Kind.ADDED);
        row(
                Category.AUTOMATION,
                run,
                "On added",
                "Runs when a torrent is added to the session, not for the ones restored at start.",
                onAdded,
                "run program command script execute added");
        onFinished = commandField(EngineEvent.Kind.FINISHED);
        row(
                Category.AUTOMATION,
                run,
                "On finished",
                "Runs when a download completes.",
                onFinished,
                "run program command script execute finished complete download");
        onSeedingDone = commandField(EngineEvent.Kind.SEEDING_DONE);
        row(
                Category.AUTOMATION,
                run,
                "On seeding done",
                "Runs when a torrent reaches its share limit (Queue and share limits, or its own) and is paused or"
                        + " removed.",
                onSeedingDone,
                "run program command script execute seeding done share ratio limit");
        Label legend = new Label("The line is split like a shell would (quotes keep a value together) but no shell"
                + " runs it; placeholders are filled in per argument, so a name with spaces stays one argument."
                + " %N name  %L category  %G tags  %F content path  %R root path  %D save path  %C file count"
                + "  %Z size in bytes  %T tracker  %I info-hash v1  %J info-hash v2  %K torrent id  %% percent."
                + " Output and exit code go to the Event Log; a program still running after 10 minutes is killed."
                + " These lines name programs on this machine and never sync.");
        legend.setWrapText(true);
        legend.getStyleClass().add("settings-row-description");
        run.getChildren().add(legend);
        rows.add(new Row(
                Category.AUTOMATION, legend, "Placeholders | %N %L %G %F %R %D %C %Z %T %I %J %K shell quotes", run));

        VBox notify = section(page, "Desktop notifications");
        notifyAdded = notifyBox(EngineEvent.Kind.ADDED);
        row(Category.AUTOMATION, notify, "Torrent added", "", notifyAdded, "notification notify desktop toast added");
        notifyFinished = notifyBox(EngineEvent.Kind.FINISHED);
        row(
                Category.AUTOMATION,
                notify,
                "Download finished",
                "",
                notifyFinished,
                "notification notify desktop toast finished complete");
        notifySeedingDone = notifyBox(EngineEvent.Kind.SEEDING_DONE);
        row(
                Category.AUTOMATION,
                notify,
                "Seeding done",
                "",
                notifySeedingDone,
                "notification notify desktop toast seeding share limit");
        notifyError = notifyBox(EngineEvent.Kind.ERROR);
        row(
                Category.AUTOMATION,
                notify,
                "Torrent or tracker error",
                "",
                notifyError,
                "notification notify desktop toast error tracker");
        Button test = new Button("Test notification");
        test.setOnAction(e -> testNotification.run());
        row(
                Category.AUTOMATION,
                notify,
                "Test",
                "Shows one now, through notify-send on Linux, osascript on macOS and a PowerShell toast on Windows;"
                        + " the Event Log says how it went.",
                test,
                "notification notify desktop toast test try");
    }

    private TextField commandField(EngineEvent.Kind kind) {
        TextField field = new TextField();
        field.setPrefColumnCount(30);
        field.setPromptText("none");
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
        VBox defaults = section(page, "Add dialog defaults");
        addStartPaused = new CheckBox();
        addStartPaused.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setAddStartPaused(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                "Start paused",
                "New torrents wait until resumed: Enter in the Add dialog adds paused, and Shift+Enter starts.",
                addStartPaused,
                "add stopped");
        addSequential = new CheckBox();
        addSequential.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setAddSequential(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                "Sequential download",
                "Fetch pieces in order rather than rarest first, for playing while downloading.",
                addSequential,
                "add order streaming preview");
        addWithoutAsking = new CheckBox();
        addWithoutAsking.selectedProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.setAddWithoutAsking(now);
            settings.saveQuietly();
        });
        row(
                Category.ADDING,
                defaults,
                "Add without asking",
                "Torrents and magnets are added at once with these defaults, into the download folder;"
                        + " the Add dialog does not open.",
                addWithoutAsking,
                "skip dialog confirm prompt silent automatic next time");
    }

    private void buildConnection(VBox page) {
        VBox listening = section(page, "Listening");
        port = intSpinner(1024, 65535, Settings.CONN_PORT);
        row(
                Category.CONNECTION,
                listening,
                "Port",
                "The TCP and uTP port other peers connect to. Forward it on the router, or let UPnP do it.",
                port,
                "listen incoming");
        randomPort = bool(Settings.CONN_RANDOM_PORT);
        port.disableProperty().bind(randomPort.selectedProperty());
        row(
                Category.CONNECTION,
                listening,
                "Random port",
                "Let the system pick a free port each start; the event log says which.",
                randomPort,
                "listen ephemeral");
        upnp = bool(Settings.CONN_UPNP);
        row(
                Category.CONNECTION,
                listening,
                "Map the port with UPnP / NAT-PMP",
                "Ask the router to forward the port. The event log reports the mapping or its failure.",
                upnp,
                "router forward nat");
        ipv6 = bool(Settings.CONN_IPV6);
        row(
                Category.CONNECTION,
                listening,
                "Listen on IPv6",
                "As well as IPv4, where the machine has it.",
                ipv6,
                "v6 address");
        listenInterface = text(Settings.CONN_INTERFACE);
        listenInterface.setPrefColumnCount(18);
        listenInterface.setPromptText("all interfaces");
        row(
                Category.CONNECTION,
                listening,
                "Bind to",
                "An address or interface name, for a VPN or one network card only. Blank means every interface.",
                listenInterface,
                "interface address vpn tun");

        VBox limits = section(page, "Limits");
        maxConnections = intSpinner(2, 10_000, Settings.CONN_MAX_CONNECTIONS);
        row(
                Category.CONNECTION,
                limits,
                "Connections",
                "Across every torrent.",
                maxConnections,
                "global peers maximum");
        maxPerTorrent = intSpinner(1, 10_000, Settings.CONN_MAX_PER_TORRENT);
        row(
                Category.CONNECTION,
                limits,
                "Connections per torrent",
                "Applied to each torrent as it is added.",
                maxPerTorrent,
                "peers maximum");
        uploadSlots = intSpinner(1, 1_000, Settings.CONN_UPLOAD_SLOTS);
        row(
                Category.CONNECTION,
                limits,
                "Upload slots",
                "How many peers are sent data at once.",
                uploadSlots,
                "unchoke");

        VBox proxy = section(page, "Proxy");
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
                "Type",
                "SOCKS5 or HTTP; trackers always go through it, peers when the box below is on.",
                proxyType,
                "socks http none");
        proxyHost = text(Settings.PROXY_HOST);
        proxyHost.setPrefColumnCount(18);
        row(
                Category.CONNECTION,
                proxy,
                "Host",
                "Host names are resolved through the proxy, so a lookup does not leak what it hides.",
                proxyHost,
                "server address");
        proxyPort = intSpinner(1, 65535, Settings.PROXY_PORT);
        row(Category.CONNECTION, proxy, "Proxy port", "", proxyPort, "");
        proxyUsername = text(Settings.PROXY_USERNAME);
        row(
                Category.CONNECTION,
                proxy,
                "Username",
                "Leave blank for a proxy without a login.",
                proxyUsername,
                "auth login");
        proxyPassword = new PasswordField();
        proxyPassword.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.PROXY_PASSWORD, now);
            changed();
        });
        row(
                Category.CONNECTION,
                proxy,
                "Password",
                "Kept in the settings file, which only you can read.",
                proxyPassword,
                "auth secret");
        proxyPeers = bool(Settings.PROXY_PEERS);
        row(
                Category.CONNECTION,
                proxy,
                "Use the proxy for peers",
                "Off, and only tracker requests go through it; your own address is still visible to peers.",
                proxyPeers,
                "connections privacy");
        for (var control : List.of(proxyHost, proxyPort, proxyUsername, proxyPassword, proxyPeers)) {
            control.disableProperty().bind(proxyType.valueProperty().isEqualTo(EngineSettings.ProxyType.NONE));
        }
    }

    private void buildProtocol(VBox page) {
        VBox discovery = section(page, "Finding peers");
        dht = bool(Settings.PROTO_DHT);
        row(
                Category.PROTOCOL,
                discovery,
                "DHT",
                "The distributed hash table. Magnet links need it; private torrents never use it.",
                dht,
                "distributed hash table trackerless");
        pex = bool(Settings.PROTO_PEX);
        row(
                Category.PROTOCOL,
                discovery,
                "Peer exchange (PEX)",
                "Swap peer lists with the peers you are connected to.",
                pex,
                "ut_pex");
        lsd = bool(Settings.PROTO_LSD);
        row(
                Category.PROTOCOL,
                discovery,
                "Local peer discovery",
                "Find peers on your own network, so a second machine at home fetches from the first.",
                lsd,
                "lsd lan multicast");

        VBox transport = section(page, "Transport");
        utp = bool(Settings.PROTO_UTP);
        row(
                Category.PROTOCOL,
                transport,
                "uTP",
                "The UDP transport that yields to other traffic, keeping the connection usable while downloading. TCP stays on.",
                utp,
                "udp congestion");
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
                "Encryption",
                "Require it and refuse plain peers; prefer it and take either; or never encrypt. It hides the traffic's shape from a watching network, not its endpoints.",
                encryption,
                "rc4 mse obfuscation require prefer");
        anonymous = bool(Settings.PROTO_ANONYMOUS);
        row(
                Category.PROTOCOL,
                transport,
                "Anonymous mode",
                "Send no client name or version and nothing a tracker could use to tell clients apart. Some trackers refuse such clients.",
                anonymous,
                "privacy identify fingerprint");

        VBox trackers = section(page, "Trackers");
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
                "Default trackers",
                "One per line. Added to every torrent as it is added, and to the ones already here.",
                defaultTrackers,
                "announce public list");
    }

    private void buildIpFilter(VBox page) {
        VBox list = section(page, "Blocklist");
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
                "Filter peers",
                "Refuse connections to and from the addresses below. Off, and the lists are kept but not used.",
                ipFilterEnabled,
                "enable block ban");
        ipFilterUrl = new TextField();
        ipFilterUrl.setPrefColumnCount(16);
        ipFilterUrl.setPromptText("https://…/list.p2p.gz");
        ipFilterUrl.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.IPFILTER_URL, now.trim());
            settings.saveQuietly();
        });
        Button refresh = new Button("Download now");
        refresh.setOnAction(e -> onIpFilterChanged.accept(true));
        row(
                Category.IP_FILTER,
                list,
                "Blocklist URL",
                "A .p2p or .dat list, plain or gzipped; downloaded on the schedule below and kept beside the settings.",
                new HBox(6, ipFilterUrl, refresh),
                "download p2p dat gz");
        ipFilterHours = intSpinner(1, 720, Settings.IPFILTER_REFRESH_HOURS);
        row(
                Category.IP_FILTER,
                list,
                "Refresh every (hours)",
                "Checked at startup and while running.",
                ipFilterHours,
                "schedule interval update");
        ipFilterFile = new TextField();
        ipFilterFile.setPrefColumnCount(16);
        ipFilterFile.setPromptText("none");
        ipFilterFile.textProperty().addListener((o, was, now) -> {
            if (loading) return;
            settings.set(Settings.IPFILTER_FILE, now.trim());
            settings.saveQuietly();
            onIpFilterChanged.accept(false);
        });
        Button browseFile = new Button("Browse…");
        browseFile.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Blocklist file");
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) ipFilterFile.setText(chosen.getAbsolutePath());
        });
        row(
                Category.IP_FILTER,
                list,
                "Local list",
                "A list on disk, read alongside the downloaded one.",
                new HBox(6, ipFilterFile, browseFile),
                "file import p2p dat");

        VBox bans = section(page, "Manual bans");
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
                "Banned addresses",
                "One per line: an address, a range, or a CIDR. Ban peer in the Peers tab adds here.",
                ipFilterBans,
                "ban address range cidr");
        ipFilterStatusLabel = new Label("");
        ipFilterStatusLabel.getStyleClass().add("settings-row-description");
        ipFilterStatusLabel.setWrapText(true);
        row(Category.IP_FILTER, bans, "Status", "", ipFilterStatusLabel, "loaded ranges blocked");
    }

    private void buildSpeed(VBox page) {
        VBox limits = section(page, "Limits (KiB/s, 0 for none)");
        speedDown = intSpinner(0, 10_000_000, Settings.SPEED_DOWNLOAD);
        row(
                Category.SPEED,
                limits,
                "Download",
                "The whole session, all torrents together.",
                speedDown,
                "rate limit cap kb");
        speedUp = intSpinner(0, 10_000_000, Settings.SPEED_UPLOAD);
        row(
                Category.SPEED,
                limits,
                "Upload",
                "A sensible upload cap keeps a home connection responsive.",
                speedUp,
                "rate limit cap kb");

        VBox alternative = section(page, "Alternative limits");
        altDown = intSpinner(0, 10_000_000, Settings.SPEED_ALT_DOWNLOAD);
        row(
                Category.SPEED,
                alternative,
                "Alternative download",
                "Used while the toggle below is on, or during the scheduled hours.",
                altDown,
                "alt rate night");
        altUp = intSpinner(0, 10_000_000, Settings.SPEED_ALT_UPLOAD);
        row(Category.SPEED, alternative, "Alternative upload", "", altUp, "alt rate night");
        altOn = bool(Settings.SPEED_ALT_ON);
        row(
                Category.SPEED,
                alternative,
                "Use alternative limits now",
                "Also toggled from the status bar.",
                altOn,
                "toggle turtle");

        VBox schedule = section(page, "Schedule");
        scheduleOn = bool(Settings.SPEED_SCHEDULE_ON);
        row(
                Category.SPEED,
                schedule,
                "Switch to the alternative limits on a schedule",
                "Checked every minute. A window that ends before it starts spans midnight.",
                scheduleOn,
                "time night hours");
        scheduleFrom = timeField(Settings.SPEED_SCHEDULE_FROM);
        row(Category.SPEED, schedule, "From", "24-hour clock, HH:mm.", scheduleFrom, "start time");
        scheduleTo = timeField(Settings.SPEED_SCHEDULE_TO);
        row(Category.SPEED, schedule, "To", "", scheduleTo, "end time");
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
        row(Category.SPEED, schedule, "Days", "", days, "weekdays weekend");

        VBox options = section(page, "Options");
        countOverhead = bool(Settings.SPEED_COUNT_OVERHEAD);
        row(
                Category.SPEED,
                options,
                "Count protocol overhead",
                "Rate-limit TCP/IP headers too, so the limit matches what the line carries rather than the payload alone.",
                countOverhead,
                "ip overhead headers");
    }

    private void buildQueue(VBox page) {
        VBox queue = section(page, "Queue (0 for no limit)");
        activeDownloads = intSpinner(0, 10_000, Settings.QUEUE_ACTIVE_DOWNLOADS);
        row(
                Category.QUEUE,
                queue,
                "Active downloads",
                "Torrents downloading at once; the rest wait their turn, in the order they were added. Force"
                        + " Resume starts one regardless.",
                activeDownloads,
                "queue slots maximum simultaneous");
        activeSeeds = intSpinner(0, 10_000, Settings.QUEUE_ACTIVE_SEEDS);
        row(
                Category.QUEUE,
                queue,
                "Active seeds",
                "Torrents seeding at once. A seed past its share limit gives up its slot first.",
                activeSeeds,
                "queue slots maximum simultaneous uploads");
        activeLimit = intSpinner(0, 10_000, Settings.QUEUE_ACTIVE_LIMIT);
        row(
                Category.QUEUE,
                queue,
                "Active torrents",
                "Downloading and seeding together.",
                activeLimit,
                "queue slots maximum total simultaneous");
        ignoreSlow = bool(Settings.QUEUE_IGNORE_SLOW);
        row(
                Category.QUEUE,
                queue,
                "Don't count slow torrents",
                "A torrent moving under a few KiB/s for a minute does not hold a slot, so a stalled one"
                        + " cannot block the queue.",
                ignoreSlow,
                "slow stalled threshold inactive");

        VBox share = section(page, "Share limits");
        shareRatio = ratioSpinner(Settings.SHARE_RATIO);
        row(
                Category.QUEUE,
                share,
                "Ratio limit",
                "Uploaded over downloaded at which a finished torrent is done seeding; 0 for none. A torrent"
                        + " whose files were there already is measured against its size.",
                shareRatio,
                "share seed upload");
        shareMinutes = intSpinner(0, 366 * 24 * 60, Settings.SHARE_SEED_MINUTES);
        row(
                Category.QUEUE,
                share,
                "Seeding time limit, in minutes",
                "Minutes spent finished, over the torrent's life; 0 for none. Either limit being reached is"
                        + " enough.",
                shareMinutes,
                "share seed time duration");
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
                "When a limit is reached",
                "Checked once a second. Pause keeps the torrent in the list, stopped; Remove takes it out and"
                        + " leaves the files. A torrent's own limits (Torrent → Limits…) replace these for it.",
                shareAction,
                "action pause remove stop");
    }

    /** A decimal spinner in tenths, which writes its key as a person would type the number. */
    private Spinner<Double> ratioSpinner(String key) {
        Spinner<Double> spinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1000, 0, 0.1));
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        spinner.focusedProperty().addListener((o, was, now) -> {
            if (!now) spinner.increment(0);
        });
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
        spinner.focusedProperty().addListener((o, was, now) -> {
            if (!now) spinner.increment(0);
        });
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
            reloadWatch();
            reloadDefinitions();
            reloadRss();
            rssMinutes.getValueFactory().setValue(settings.rssRefreshMinutes());
            rssAutoDownload.setSelected(settings.rssAutoDownload());
            theme.setValue(Theme.byId(settings.theme(), Theme.DEFAULT));
            downloadDir.setText(settings.downloadDir().toString());
            incompleteEnabled.setSelected(settings.incompleteEnabled());
            incompleteDir.setText(settings.incompleteDir().toString());
            partSuffix.setSelected(settings.partSuffix());
            preallocate.setSelected(settings.preallocate());
            updateCheck.setSelected(settings.updateCheck());
            stateColors.setSelected(settings.stateColors());
            density.setValue(Density.byId(settings.density(), Density.DEFAULT));
            extendedWindow.setSelected(settings.extendedWindow());
            paletteKey.setValue(PaletteKey.byId(settings.paletteKey(), PaletteKey.DEFAULT));
            tray.setSelected(settings.tray());
            closeToTray.setSelected(settings.closeToTray());
            syncEnabled.setSelected(settings.syncEnabled());
            syncUrl.setText(settings.syncUrl());
            syncBranch.setText(settings.syncBranch().equals("main") ? "" : settings.syncBranch());
            syncInterval.getValueFactory().setValue(settings.syncIntervalMinutes());
            addStartPaused.setSelected(settings.addStartPaused());
            addSequential.setSelected(settings.addSequential());
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
