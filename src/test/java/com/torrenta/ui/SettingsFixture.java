package com.torrenta.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.torrenta.engine.EngineProfile;
import com.torrenta.store.LabelStore;
import com.torrenta.store.ProfileStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.store.WatchFolderStore;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Settings window shown on a stage of its own, with {@link Fx#quietSettings} and every store
 * in a test's folder, wired as the main window wires it but with hooks that only say they were
 * called ({@link #told}). A row's control is found by the row's title, as a person finds it.
 */
final class SettingsFixture {

    final Path dir;
    final Settings settings;
    final SettingsWindow window;
    final Stage owner;
    final LabelStore labels;
    final WatchFolderStore watch;
    final ProfileStore profiles;
    final SearchProviderStore search;
    /** What the window told its hooks, in order: "appearance", "engine", "watch", … */
    final List<String> told = new CopyOnWriteArrayList<>();

    EngineProfile inUse = EngineProfile.LOCAL;

    private SettingsFixture(Path dir) {
        this.dir = dir;
        this.settings = Fx.quietSettings(dir);
        this.labels = new LabelStore(dir);
        labels.load();
        this.watch = new WatchFolderStore(dir);
        watch.load();
        this.profiles = new ProfileStore(dir);
        profiles.load();
        this.search = new SearchProviderStore(dir);
        search.load();
        this.owner = Fx.call(() -> {
            Stage s = new Stage();
            s.show();
            return s;
        });
        this.window = Fx.call(() -> new SettingsWindow(settings));
    }

    /** Built, wired and shown; on the first page. */
    static SettingsFixture open(Path dir) {
        SettingsFixture f = new SettingsFixture(dir);
        Fx.run(f::wire);
        Fx.run(() -> f.window.show(f.owner));
        Fx.pulse();
        return f;
    }

    private void wire() {
        window.setOnEngineSettingsChanged(() -> told.add("engine"));
        window.setOnAppearanceChanged(() -> told.add("appearance"));
        window.setOnSimpleChanged(() -> told.add("simple"));
        window.setOnTrayChanged(() -> told.add("tray"));
        window.setOnHandlerChanged(() -> told.add("handler"));
        window.setOnBadgeChanged(() -> told.add("badge"));
        window.setOnShowDebugLog(() -> told.add("debug-log"));
        window.setOnTestNotification(() -> told.add("test-notification"));
        window.setOnIpFilterChanged(force -> told.add("ip-filter " + force), () -> "filter status");
        window.setLabels(labels, new SettingsWindow.LabelsHost() {
            @Override
            public void labelsChanged() {
                told.add("labels");
            }

            @Override
            public void categoryDeleted(String name) {
                told.add("category-deleted " + name);
            }

            @Override
            public void tagDeleted(String name) {
                told.add("tag-deleted " + name);
            }
        });
        window.setWatchFolders(watch, new SettingsWindow.WatchHost() {
            @Override
            public void watchFoldersChanged() {
                told.add("watch");
            }

            @Override
            public String watchStatus() {
                return "watching " + watch.entries().size();
            }
        });
        window.setProfiles(profiles, new SettingsWindow.ProfilesHost() {
            @Override
            public void switchTo(EngineProfile profile) {
                told.add("switch " + profile.displayName());
            }

            @Override
            public EngineProfile inUse() {
                return inUse;
            }

            @Override
            public String engineLine() {
                return "fake engine";
            }
        });
        window.setSearchProviders(search, () -> told.add("search"));
    }

    /** Shows {@code page} and waits a pulse, so its controls are in the scene. */
    void page(SettingsWindow.Category page) {
        Fx.run(() -> window.selectPageForCapture(page.name()));
        Fx.pulse();
    }

    Node root() {
        return Fx.call(() -> window.scene().getRoot());
    }

    /** The control of the row titled {@code tr(titleKey)} on the page shown, or its first node of {@code type}. */
    <T> T control(String titleKey, Class<T> type) {
        String title = tr(titleKey);
        return Fx.call(() -> {
            for (HBox row : Modals.all(
                    window.scene().getRoot(), HBox.class, h -> h.getStyleClass().contains("settings-row"))) {
                if (!(row.getChildren().getFirst() instanceof VBox text)
                        || !(text.getChildren().getFirst() instanceof Label name)
                        || !title.equals(name.getText())) continue;
                Node control = row.getChildren().get(1);
                if (type.isInstance(control)) return type.cast(control);
                return Modals.first(control, type, n -> true)
                        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in row " + title));
            }
            throw new AssertionError("no row titled \"" + title + "\" on the page shown");
        });
    }

    /** The button labelled {@code tr(key)} on the page shown; the first of several. */
    Button button(String key) {
        return Fx.call(() -> Modals.button(window.scene().getRoot(), tr(key)));
    }

    /** The text fields on the page shown, in order; the search box above every page left out. */
    List<javafx.scene.control.TextField> fields() {
        String prompt = tr("settings.searchBox.prompt");
        return Fx.call(() -> Modals.all(
                window.scene().getRoot(),
                javafx.scene.control.TextField.class,
                t -> !prompt.equals(t.getPromptText())));
    }

    /** The first table on the page shown. */
    @SuppressWarnings("unchecked")
    <T> TableView<T> table() {
        return Fx.call(() -> (TableView<T>) Modals.first(window.scene().getRoot(), TableView.class, t -> true)
                .orElseThrow());
    }

    /** Whether a label on the page shown reads {@code text}. */
    boolean says(String text) {
        return Fx.call(() -> Modals.texts(window.scene().getRoot()).contains(text));
    }

    /** The settings file's entries as saved, comments left out. */
    Map<String, String> saved() {
        Map<String, String> out = new TreeMap<>();
        try {
            Path file = dir.resolve(Settings.FILE_NAME);
            if (!Files.exists(file)) return out;
            java.util.Properties p = new java.util.Properties();
            try (var in = Files.newInputStream(file)) {
                p.load(in);
            }
            for (String key : p.stringPropertyNames()) out.put(key, p.getProperty(key));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out;
    }
}
