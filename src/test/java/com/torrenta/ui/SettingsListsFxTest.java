package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.torrenta.engine.Category;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.WatchFolder;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.store.DefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings pages that edit a list through a store: categories and tags, watch folders,
 * engine profiles, search providers and definition files. Each add, edit and remove goes through
 * its dialog or question as a hand would, and lands in the store.
 */
@ExtendWith(FxToolkit.class)
class SettingsListsFxTest {

    private static final String CAPS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <caps>
              <server title="Stub Indexer"/>
              <categories>
                <category id="2000" name="Movies"/>
                <category id="5000" name="TV"/>
              </categories>
            </caps>
            """;

    private static final String DAEMON =
            "{\"arguments\":{\"version\":\"4.1.0\",\"rpc-version\":18,\"download-dir\":\"/srv\"},\"result\":\"success\"}";

    private static String removeTitle(RemoveConfirm.Kind kind) {
        return RemoveConfirm.header(kind);
    }

    /** The window's warning for a write that failed, answered. */
    private static void acknowledgeFailure() {
        Modals.answer(tr("settings.title", com.torrenta.AppInfo.NAME), true);
    }

    /** Puts a non-empty folder where a store keeps its file, so the next write fails. */
    private static void breakFile(Path file) throws Exception {
        Files.deleteIfExists(file);
        Files.createDirectories(file);
        Files.writeString(file.resolve("keep"), "x");
    }

    /** A double click on the first filled row of {@code table}, as a mouse makes it. */
    private static void doubleClickFirstRow(TableView<?> table) {
        doubleClickRow(table, 0);
    }

    /** A double click on row {@code index} of {@code table}. */
    private static void doubleClickRow(TableView<?> table, int index) {
        Fx.pulse();
        TableRow<?> row = Fx.call(() -> Modals.first(table, TableRow.class, r -> !r.isEmpty() && r.getIndex() == index)
                .orElseThrow());
        MouseEvent click = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1,
                1,
                1,
                1,
                MouseButton.PRIMARY,
                2,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
        Modals.later(() -> row.fireEvent(click));
    }

    // ---------------------------------------------------------------- categories and tags

    @Test
    void categoriesAreSavedSelectedAndRemoved(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.LABELS);
        List<TextField> fields = f.fields();
        TextField name = fields.get(0);
        TextField path = fields.get(1);
        Fx.run(() -> {
            name.setText("Films");
            path.setText(dir.resolve("films").toString());
            name.fireEvent(new javafx.event.ActionEvent());
        });
        assertEquals(List.of(new Category("Films", dir.resolve("films").toString())), f.labels.categories());
        assertEquals(List.of("labels"), f.told);
        Fx.run(() -> {
            name.setText("Books");
            path.setText("");
            path.fireEvent(new javafx.event.ActionEvent());
        });
        TableView<Category> table = f.table();
        Fx.pulse();
        Fx.run(() -> table.getSelectionModel().select(1));
        assertEquals("Films", Fx.call(name::getText), "choosing a row fills the form");

        Button remove = Fx.call(() -> Modals.all(
                        f.root(), Button.class, b -> b.getText().equals(tr("common.remove")))
                .getFirst());
        Modals.later(remove::fire);
        Modals.answer(removeTitle(RemoveConfirm.Kind.CATEGORY), false);
        assertEquals(2, f.labels.categories().size(), "a no keeps it");
        Modals.later(remove::fire);
        Modals.answer(removeTitle(RemoveConfirm.Kind.CATEGORY), true);
        Fx.pulse();
        assertEquals(
                List.of("Books"),
                f.labels.categories().stream().map(Category::name).toList());
        assertTrue(f.told.contains("category-deleted Films"), f.told.toString());
        assertEquals("", Fx.call(name::getText), "the form empties");

        // A write that fails says so and leaves the list as it was.
        breakFile(dir.resolve("categories.json"));
        Fx.run(() -> name.setText("Music"));
        Modals.later(() -> name.fireEvent(new javafx.event.ActionEvent()));
        acknowledgeFailure();
        Fx.run(() -> table.getSelectionModel().select(0));
        Modals.later(remove::fire);
        Modals.answer(removeTitle(RemoveConfirm.Kind.CATEGORY), true);
        acknowledgeFailure();
    }

    @Test
    void tagsAreAddedAndRemoved(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.LABELS);
        TextField tag = f.fields().get(2);
        Fx.run(() -> {
            tag.setText("keep");
            tag.fireEvent(new javafx.event.ActionEvent());
            tag.setText("seen");
        });
        Fx.run(() -> f.button("common.add").fire());
        assertEquals(List.of("keep", "seen"), f.labels.tags());
        @SuppressWarnings("unchecked")
        ListView<String> list = Fx.call(() -> Modals.first(
                        f.root(), ListView.class, l -> l != null && l.getItems().contains("keep"))
                .orElseThrow());
        Fx.run(() -> list.getSelectionModel().select("keep"));
        Button remove = Fx.call(() -> Modals.all(
                        f.root(), Button.class, b -> b.getText().equals(tr("common.remove")))
                .getLast());
        Modals.later(remove::fire);
        Modals.answer(removeTitle(RemoveConfirm.Kind.TAG), false);
        Modals.later(remove::fire);
        Modals.answer(removeTitle(RemoveConfirm.Kind.TAG), true);
        Fx.pulse();
        assertEquals(List.of("seen"), f.labels.tags());
        assertTrue(f.told.contains("tag-deleted keep"), f.told.toString());

        breakFile(dir.resolve("tags.json"));
        Fx.run(() -> tag.setText("more"));
        Modals.later(() -> tag.fireEvent(new javafx.event.ActionEvent()));
        acknowledgeFailure();
        Fx.run(() -> list.getSelectionModel().select("seen"));
        Modals.later(remove::fire);
        Modals.answer(removeTitle(RemoveConfirm.Kind.TAG), true);
        acknowledgeFailure();
    }

    // ---------------------------------------------------------------- watch folders

    @Test
    void watchFoldersAreAddedEditedAndRemovedThroughTheirDialog(@TempDir Path dir) throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.WATCH);

        Modals.later(() -> f.button("settings.table.add").fire());
        Stage add = Modals.await(tr("dialog.watchFolder.title.add"));
        Fx.run(() -> Modals.field(add.getScene().getRoot(), 0).setText(inbox.toString()));
        Modals.press(add, tr("common.save"));
        Modals.gone(tr("dialog.watchFolder.title.add"));
        assertEquals(1, f.watch.entries().size());
        assertEquals(inbox.toString(), f.watch.entries().getFirst().folder());
        assertEquals(List.of("watch"), f.told);
        assertTrue(f.says("watching 1"), "the status line asks the host");

        // Edit by double click: save into a folder of its own, delete the file after adding.
        doubleClickFirstRow(f.table());
        Stage edit = Modals.await(tr("dialog.watchFolder.title.edit"));
        Fx.run(() -> {
            Node root = edit.getScene().getRoot();
            @SuppressWarnings("unchecked")
            List<javafx.scene.control.ChoiceBox<Object>> choices = (List<javafx.scene.control.ChoiceBox<Object>>)
                    (List<?>) Modals.all(root, javafx.scene.control.ChoiceBox.class, c -> true);
            choices.get(0).setValue(WatchFolder.SaveTo.FOLDER);
            Modals.field(root, 1).setText(dir.resolve("saved").toString());
            choices.get(1).setValue(WatchFolder.AfterAdd.DELETE);
            Modals.first(root, javafx.scene.control.CheckBox.class, c -> true)
                    .orElseThrow()
                    .setSelected(true);
        });
        Modals.press(edit, tr("common.save"));
        Modals.gone(tr("dialog.watchFolder.title.edit"));
        WatchFolder edited = f.watch.entries().getFirst();
        assertEquals(WatchFolder.SaveTo.FOLDER, edited.saveTo());
        assertEquals(WatchFolder.AfterAdd.DELETE, edited.afterAdd());
        assertTrue(edited.startPaused());
        Fx.pulse();

        // Cancelled, nothing changes; Escape is a cancel.
        Modals.later(() -> f.button("common.edit").fire());
        Stage again = Modals.await(tr("dialog.watchFolder.title.edit"));
        Modals.press(again, tr("common.cancel"));
        Modals.later(() -> f.button("settings.table.add").fire());
        Stage escaped = Modals.await(tr("dialog.watchFolder.title.add"));
        Fx.run(() -> escaped.getScene()
                .getRoot()
                .fireEvent(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.ESCAPE,
                        false,
                        false,
                        false,
                        false)));
        Modals.gone(tr("dialog.watchFolder.title.add"));
        assertEquals(edited, f.watch.entries().getFirst());

        Modals.later(() -> f.button("common.remove").fire());
        Modals.answer(removeTitle(RemoveConfirm.Kind.WATCH_FOLDER), true);
        Fx.pulse();
        assertEquals(List.of(), f.watch.entries());

        breakFile(dir.resolve("watch-folders.json"));
        Modals.later(() -> f.button("settings.table.add").fire());
        Stage failing = Modals.await(tr("dialog.watchFolder.title.add"));
        Fx.run(() -> Modals.field(failing.getScene().getRoot(), 0).setText(inbox.toString()));
        Modals.press(failing, tr("common.save"));
        acknowledgeFailure();
    }

    @Test
    void theWatchTableSaysWhereEachFolderSavesAndWhatItDoesAfter(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Path c = Files.createDirectories(dir.resolve("c"));
        SettingsFixture f = SettingsFixture.open(dir);
        f.watch.set(List.of(
                new WatchFolder(a.toString(), WatchFolder.AfterAdd.RENAME, WatchFolder.SaveTo.DEFAULT, "", "", false),
                new WatchFolder(
                        b.toString(), WatchFolder.AfterAdd.DELETE, WatchFolder.SaveTo.WATCH_FOLDER, "", "Films", true),
                new WatchFolder(
                        c.toString(),
                        WatchFolder.AfterAdd.RENAME,
                        WatchFolder.SaveTo.FOLDER,
                        dir.resolve("out").toString(),
                        "",
                        false)));
        Fx.run(() -> f.window.reloadFromSettings());
        Fx.run(f.window::refreshWatchStatus);
        f.page(SettingsWindow.Category.WATCH);
        TableView<WatchFolder> table = f.table();
        Fx.waitUntil("the three rows drawn", () -> Modals.texts(table).contains("Films"));
        List<String> cells = Fx.call(() -> Modals.texts(table));
        assertTrue(cells.contains(tr("settings.watchFolders.saveTo.downloadFolder")), cells.toString());
        assertTrue(cells.contains(tr("settings.watchFolders.saveTo.watchFolder")), cells.toString());
        assertTrue(cells.contains(dir.resolve("out").toString()), cells.toString());
        assertTrue(cells.contains(tr("common.delete")), cells.toString());
        assertTrue(cells.contains("Films"), cells.toString());
        assertTrue(cells.contains(tr("settings.table.yes")), cells.toString());
        assertTrue(f.says("watching 3"));
    }

    // ---------------------------------------------------------------- engine profiles

    @Test
    void profilesAreAddedTestedChosenAndRemoved(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.ENGINE_PROFILES);
        assertTrue(Fx.call(() -> Modals.texts(f.root()).stream().anyMatch(t -> t.contains("fake engine"))));

        try (LoopbackHttp daemon = new LoopbackHttp(200, "application/json", DAEMON)) {
            Modals.later(() -> f.button("settings.table.add").fire());
            Stage add = Modals.await(tr("dialog.profile.title.add"));
            Fx.run(() -> {
                Node root = add.getScene().getRoot();
                Modals.field(root, 0).setText("Seedbox");
                Modals.field(root, 1).setText(daemon.url());
                Modals.field(root, 2).setText("me");
                Modals.field(root, 3).setText("secret");
            });
            Modals.press(add, tr("common.save"));
            Modals.gone(tr("dialog.profile.title.add"));
            assertEquals(1, f.profiles.entries().size());
            EngineProfile seedbox = f.profiles.entries().getFirst();
            assertEquals("Seedbox", seedbox.name());
            assertEquals("secret", seedbox.password());

            // The page's Test asks the daemon who it is.
            Fx.pulse();
            Fx.run(() -> f.button("settings.engine.test").fire());
            Fx.waitUntil("the test line", () -> Modals.texts(f.root()).stream().anyMatch(t -> t.contains("4.1.0")));
        }
        Fx.run(() -> f.button("settings.engine.test").fire());
        Fx.waitUntil(
                "the failed test line",
                () -> Modals.texts(f.root()).stream()
                        .anyMatch(t -> t.startsWith(tr("settings.engine.testFailed", "Seedbox", "")
                                        .substring(0, 8))
                                && !t.contains("4.1.0")));

        Fx.run(() -> f.button("settings.engine.useNext").fire());
        String id = f.profiles.entries().getFirst().id();
        assertEquals(id, f.settings.engineProfile());
        Fx.run(() -> f.button("settings.engine.switchNow").fire());
        assertTrue(f.told.contains("switch Seedbox"), f.told.toString());

        // Edit by double click, then cancel; edit by the button and save a new name.
        doubleClickRow(f.table(), 1);
        Stage dbl = Modals.await(tr("dialog.profile.title.edit"));
        Modals.press(dbl, tr("common.cancel"));
        Modals.gone(tr("dialog.profile.title.edit"));
        TableView<EngineProfile> table = f.table();
        Fx.run(() -> table.getSelectionModel().select(1));
        Modals.later(() -> f.button("common.edit").fire());
        Stage edit = Modals.await(tr("dialog.profile.title.edit"));
        Fx.run(() -> Modals.field(edit.getScene().getRoot(), 0).setText("Box"));
        Modals.press(edit, tr("common.save"));
        Modals.gone(tr("dialog.profile.title.edit"));
        assertEquals("Box", f.profiles.entries().getFirst().name());

        Modals.later(() -> f.button("common.remove").fire());
        Modals.answer(removeTitle(RemoveConfirm.Kind.ENGINE_PROFILE), true);
        Fx.pulse();
        assertEquals(List.of(), f.profiles.entries());
        assertEquals(EngineProfile.LOCAL_ID, f.settings.engineProfile(), "the next start goes back to local");

        breakFile(dir.resolve("profiles.json"));
        Modals.later(() -> f.button("settings.table.add").fire());
        Stage failing = Modals.await(tr("dialog.profile.title.add"));
        Fx.run(() -> Modals.field(failing.getScene().getRoot(), 1).setText("http://127.0.0.1:9091"));
        Modals.press(failing, tr("common.save"));
        acknowledgeFailure();
    }

    @Test
    void theCaptureOpensTheProfileDialogWithATest(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        try (LoopbackHttp daemon = new LoopbackHttp(200, "application/json", DAEMON)) {
            Fx.run(() -> f.window.openProfileDialogForCapture(daemon.url(), true));
            Modals.await(tr("dialog.profile.title.add"));
            Fx.waitUntil(
                    "the dialog's test line",
                    () -> f.window.profileDialogStatusForCapture().contains("4.1.0"));
            assertEquals(tr("dialog.profile.title.add"), Fx.call(f.window::dialogTitle));
            assertTrue(Fx.call(() -> f.window.dialogScene() != null));
            Stage dialog = Modals.await(tr("dialog.profile.title.add"));
            assertEquals(
                    "127.0.0.1",
                    Fx.call(() -> Modals.field(dialog.getScene().getRoot(), 0).getText())
                            .replaceAll(":.*", ""),
                    "a blank name takes the host");
            Modals.press(dialog, tr("common.save"));
            Modals.gone(tr("dialog.profile.title.add"));
        }
        assertEquals(1, f.profiles.entries().size());
        Fx.run(() -> f.window.openProfileDialogForCapture("", false));
        Modals.await(tr("dialog.profile.title.add"));
        Fx.run(f.window::closeDialogsForCapture);
        Modals.gone(tr("dialog.profile.title.add"));
        assertEquals(1, f.profiles.entries().size());
        Fx.run(f.window::refreshProfiles);
    }

    // ---------------------------------------------------------------- search providers

    @Test
    void searchProvidersAreAddedTestedEditedAndRemoved(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        f.page(SettingsWindow.Category.SEARCH);
        try (LoopbackHttp server = new LoopbackHttp(200, "application/xml", CAPS)) {
            String url = server.url() + "/api";
            Modals.later(() -> f.button("settings.table.add").fire());
            Stage add = Modals.await(tr("dialog.provider.title.add"));
            Fx.run(() -> {
                Node root = add.getScene().getRoot();
                Modals.field(root, 1).setText(url);
                Modals.field(root, 2).setText("key");
            });
            Modals.press(add, tr("dialog.provider.test"));
            Fx.waitUntil(
                    "the dialog's test",
                    () -> Modals.texts(add.getScene().getRoot()).stream().anyMatch(t -> t.contains("2 categories")));
            assertEquals(
                    "Stub Indexer",
                    Fx.call(() -> Modals.field(add.getScene().getRoot(), 0).getText()));
            Modals.press(add, tr("common.save"));
            Modals.gone(tr("dialog.provider.title.add"));
            assertEquals(1, f.search.entries().size());
            assertEquals(2, f.search.entries().getFirst().categories());
            assertEquals(List.of("search"), f.told);

            // The page's Test on a server named otherwise: the count and the line are written back.
            f.search.set(List.of(new TorznabEndpoint("Mine", url, "key")));
            Fx.run(f.window::reloadSearchPage);
            Fx.run(f.window::testSearchProviderForCapture);
            Fx.waitUntil("the page's test", () -> f.window.searchStatus().contains("2 categories"));
            Fx.waitUntil("the count saved", () -> f.search.entries().getFirst().categories() == 2);
            Fx.run(f.window::testSearchProviderForCapture);
            Fx.waitUntil("the second test", () -> f.window.searchStatus().contains("Stub Indexer"));
        }
        Fx.run(() -> f.button("dialog.provider.test").fire());
        Fx.waitUntil("a failed test", () -> !f.window.searchStatus().contains(tr("settings.search.testing", "Mine")));

        doubleClickFirstRow(f.table());
        Stage edit = Modals.await(tr("dialog.provider.title.edit"));
        Fx.run(() -> Modals.field(edit.getScene().getRoot(), 0).setText("Renamed"));
        Modals.press(edit, tr("common.save"));
        Modals.gone(tr("dialog.provider.title.edit"));
        assertEquals("Renamed", f.search.entries().getFirst().name());

        Modals.later(() -> f.button("common.remove").fire());
        Modals.answer(removeTitle(RemoveConfirm.Kind.SEARCH_PROVIDER), true);
        Fx.pulse();
        assertEquals(List.of(), f.search.entries());

        breakFile(dir.resolve("search-providers.json"));
        Fx.run(() -> f.window.openSearchDialogForCapture("http://127.0.0.1:9/api", false));
        Stage failing = Modals.await(tr("dialog.provider.title.add"));
        assertTrue(Fx.call(() -> f.window.dialogScene() != null));
        assertEquals(tr("dialog.provider.title.add"), Fx.call(f.window::dialogTitle));
        Modals.press(failing, tr("common.save"));
        acknowledgeFailure();
    }

    @Test
    void theSearchDialogFromTheCaptureTestsAndIsClosedByIt(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        try (LoopbackHttp server = new LoopbackHttp(200, "application/xml", CAPS)) {
            Fx.run(() -> f.window.openSearchDialogForCapture(server.url() + "/api", true));
            Modals.await(tr("dialog.provider.title.add"));
            Fx.waitUntil("the dialog's test", () -> f.window.searchStatus().contains("2 categories"));
        }
        Fx.run(f.window::closeDialogsForCapture);
        Modals.gone(tr("dialog.provider.title.add"));
        assertEquals(List.of(), f.search.entries());
        Fx.run(() -> f.window.showSearchProviders(f.owner));
        assertEquals("query=\"\" page=SEARCH", Fx.call(f.window::searchStateForCapture));
    }

    // ---------------------------------------------------------------- definition files

    @Test
    void definitionFilesAreSwitchedReloadedOpenedAndExported(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        DefinitionStore store = new DefinitionStore(dir);
        store.installExamples();
        store.load();
        Fx.run(() -> f.window.setSearchDefinitions(store, () -> f.told.add("open-folder")));
        f.page(SettingsWindow.Category.SEARCH);
        List<String> rows = Fx.call(f.window::definitionsForCapture);
        assertFalse(rows.isEmpty(), "the examples are listed");
        assertTrue(rows.getFirst().endsWith("| on"), rows.toString());

        TableView<DefinitionStore.Entry> table = Fx.call(() -> Modals.all(f.root(), TableView.class, t -> true).stream()
                .map(t -> (TableView<DefinitionStore.Entry>) t)
                .filter(t -> !t.getItems().isEmpty() && t.getItems().getFirst() instanceof DefinitionStore.Entry)
                .findFirst()
                .orElseThrow());
        Fx.run(() -> table.getSelectionModel().select(0));
        Fx.run(() -> f.button("settings.search.toggle").fire());
        assertTrue(Fx.call(f.window::definitionsForCapture).getFirst().endsWith("| off"));
        assertEquals(1, f.settings.disabledSearchDefinitions().size());
        // Space on the table toggles too.
        Fx.run(() -> table.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED,
                " ",
                " ",
                javafx.scene.input.KeyCode.SPACE,
                false,
                false,
                false,
                false)));
        assertTrue(f.settings.disabledSearchDefinitions().isEmpty());
        doubleClickFirstRow(table);
        Fx.waitUntil(
                "the double click toggles",
                () -> !f.settings.disabledSearchDefinitions().isEmpty());

        // A file that does not parse is listed with its problem.
        Files.writeString(store.dir().resolve("broken.json"), "{ not json");
        Fx.run(f.window::reloadDefinitionsForCapture);
        assertTrue(Fx.call(f.window::definitionsForCapture).stream().anyMatch(r -> r.contains("broken.json")));
        Fx.run(() -> f.button("settings.search.reload").fire());
        Fx.run(() -> f.button("settings.search.openFolder").fire());
        assertTrue(f.told.contains("open-folder"));

        Path zip = dir.resolve("defs.zip");
        Fx.run(() -> f.window.exportDefinitions(zip));
        assertTrue(Files.size(zip) > 0);
        assertTrue(Fx.call(f.window::definitionStatusForCapture).contains("defs.zip"));
        Fx.run(() -> f.window.exportDefinitions(dir.resolve("missing").resolve("defs.zip")));
        assertTrue(Fx.call(f.window::definitionStatusForCapture).contains("missing"));
    }
}
