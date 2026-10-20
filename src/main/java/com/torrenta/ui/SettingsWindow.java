package com.torrenta.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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

import com.torrenta.AppInfo;
import com.torrenta.store.Settings;

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
        ADDING(Group.APPLICATION, "Adding torrents");

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
    private static final String[] OWNED_KEYS = {
        Settings.DOWNLOAD_DIR, Settings.UPDATE_CHECK, Settings.ADD_START_PAUSED, Settings.ADD_SEQUENTIAL
    };

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

    private TextField downloadDir;
    private CheckBox updateCheck;
    private CheckBox addStartPaused;
    private CheckBox addSequential;

    SettingsWindow(Settings settings) {
        this.settings = settings;
        build();
    }

    /** Types into the search box as the user would, so a capture can reach any row. */
    void searchForCapture(String query) {
        search.setText(query);
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
                "New torrents wait until resumed. The box in the Add dialog starts in this position.",
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
    }

    /** Puts the stored values into the controls, with the listeners held off. */
    private void reload() {
        loading = true;
        try {
            downloadDir.setText(settings.downloadDir().toString());
            updateCheck.setSelected(settings.updateCheck());
            addStartPaused.setSelected(settings.addStartPaused());
            addSequential.setSelected(settings.addSequential());
        } finally {
            loading = false;
        }
    }
}
