package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;

import com.torrenta.engine.Category;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.store.DefinitionStore;
import com.torrenta.store.LabelStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.sync.QuietGit;
import com.torrenta.sync.SyncLabels;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sync page over the real {@link SettingsSync} and the real git, against a bare repository in
 * the test's folder: connecting asks when the repository already holds settings, a round brings
 * another machine's settings, category names and search servers, and Disconnect forgets it all.
 * Create on GitHub is only opened and cancelled: it would run the GitHub CLI.
 */
@ExtendWith(FxToolkit.class)
class SettingsSyncFxTest {

    @BeforeEach
    void git() {
        Assumptions.assumeTrue(QuietGit.available(), "git on the PATH");
    }

    /** What a sync told its window. */
    private static final class Host implements SettingsSync.Host {
        final List<String> told = new CopyOnWriteArrayList<>();
        Runnable onSettings = () -> {};

        @Override
        public void reportStatus(String text) {
            told.add("status " + text);
        }

        @Override
        public void settingsApplied() {
            told.add("settings");
            onSettings.run();
        }

        @Override
        public void labelsApplied(SyncLabels.Change change) {
            told.add("labels " + change.putCategories());
        }

        @Override
        public void searchApplied() {
            told.add("search");
        }

        @Override
        public boolean windowFocused() {
            return true;
        }
    }

    private static String bare(Path dir) throws Exception {
        Path remote = Files.createDirectories(dir.resolve("remote.git"));
        Process git = new ProcessBuilder("git", "init", "--bare", "--quiet")
                .directory(remote.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0, "git init --bare");
        return remote.toUri().toString();
    }

    /** Another machine that has pushed a theme, a category and a keyed search server to {@code url}. */
    private static void anotherMachinePushed(Path dir, String url) throws Exception {
        Settings settings = Fx.quietSettings(dir);
        settings.setTheme("nord-dark");
        settings.setSyncUrl(url);
        settings.setSyncEnabled(true);
        settings.saveQuietly();
        LabelStore labels = new LabelStore(dir);
        labels.load();
        labels.putCategory(new Category("Films", ""));
        labels.addTags(List.of("keep"));
        SearchProviderStore search = new SearchProviderStore(dir);
        search.load();
        search.set(List.of(new TorznabEndpoint("Indexer", "http://127.0.0.1:9696/1/api", "key")));
        DefinitionStore definitions = new DefinitionStore(dir);
        definitions.put("mine", "{ \"name\": \"Mine\" }");
        definitions.load();
        Host host = new Host();
        SettingsSync other = Fx.call(() -> new SettingsSync(settings, labels, search, definitions, dir, host));
        try {
            Fx.run(other::syncNow);
            Fx.waitUntil(
                    "the other machine's round",
                    () -> other.state().phase() == SettingsSync.Phase.IDLE
                            && other.state().report() != null);
            assertTrue(other.state().report().ok(), other.state().report().summary());
        } finally {
            Fx.run(other::shutdown);
        }
    }

    private record Synced(SettingsFixture f, SettingsSync sync, Host host) {}

    private static Synced open(Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        DefinitionStore definitions = new DefinitionStore(dir);
        definitions.load();
        Host host = new Host();
        host.onSettings = f.window::reloadFromSettings;
        SettingsSync sync = Fx.call(() -> new SettingsSync(f.settings, f.labels, f.search, definitions, dir, host));
        Fx.run(() -> f.window.setSync(sync));
        f.page(SettingsWindow.Category.SYNC);
        return new Synced(f, sync, host);
    }

    private static String state(SettingsFixture f) {
        return Fx.call(f.window::syncStateForCapture);
    }

    @Test
    void connectingAsksBeforeTakingWhatTheRepositoryHolds(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        anotherMachinePushed(dir.resolve("other"), url);
        Synced s = open(dir.resolve("here"));
        SettingsFixture f = s.f();
        try {
            assertTrue(state(f).startsWith("on=false connected=false url-field=disabled"), state(f));

            // On with no URL: the page asks for one.
            Fx.run(() -> f.window.syncSwitchForCapture(true));
            assertTrue(state(f).contains(tr("settings.sync.enterUrl")), state(f));
            assertTrue(state(f).contains("create=enabled"), state(f));

            // A URL with a token in it is refused.
            Fx.run(() -> f.window.syncUrlForCapture("https://me:ghp_secret@github.com/me/s.git"));
            assertTrue(state(f).contains(tr("settings.sync.urlHasSecret")), state(f));
            assertFalse(f.settings.syncEnabled());

            // A repository that holds settings: the question, answered no, leaves this machine as it was.
            Modals.later(() -> f.window.syncUrlForCapture(url));
            Modals.answer(tr("settings.sync.connectHeader"), false);
            assertFalse(f.settings.syncEnabled());
            assertTrue(state(f).contains(tr("settings.sync.notConnected")), state(f));
            assertEquals("", f.settings.theme(), "nothing arrived");

            // Answered yes: connected, and the round brings the other machine's settings.
            Modals.later(() -> f.window.syncUrlForCapture(url));
            Modals.answer(tr("settings.sync.connectHeader"), true);
            Fx.waitUntil(
                    "the first round",
                    () -> s.sync().state().phase() == SettingsSync.Phase.IDLE
                            && s.sync().state().report() != null);
            assertTrue(f.settings.syncEnabled());
            assertEquals("nord-dark", f.settings.theme());
            assertEquals(
                    List.of("Films"),
                    f.labels.categories().stream().map(Category::name).toList());
            assertEquals(List.of("keep"), f.labels.tags());
            assertTrue(
                    Files.exists(
                            dir.resolve("here").resolve(DefinitionStore.FOLDER).resolve("mine.json")),
                    "the definition file arrived");
            assertEquals(1, f.search.entries().size());
            assertTrue(f.search.entries().getFirst().keyNeeded(), "the key never travels");
            assertTrue(s.host().told.contains("settings"), s.host().told.toString());
            assertTrue(s.host().told.contains("search"), s.host().told.toString());
            assertTrue(
                    s.host().told.stream().anyMatch(t -> t.startsWith("status ")),
                    "the round's line, with the key it waits for: " + s.host().told);
            Fx.waitUntil("the status line", () -> f.window.syncStateForCapture().contains("Last sync"));
        } finally {
            Fx.run(s.sync()::shutdown);
        }
    }

    @Test
    void anEmptyRepositoryConnectsAtOnceAndDisconnectForgetsIt(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        Synced s = open(dir.resolve("here"));
        SettingsFixture f = s.f();
        try {
            Fx.run(() -> f.window.syncSwitchForCapture(true));
            TextField urlField = f.control("settings.sync.url", TextField.class);
            Fx.run(() -> urlField.setText(url));
            // Leaving the field connects, as Enter does.
            Fx.run(urlField::requestFocus);
            Fx.pulse();
            Fx.run(() -> f.window.scene().getRoot().requestFocus());
            Fx.pulse();
            if (!f.settings.syncEnabled()) Fx.run(() -> f.window.syncUrlForCapture(url));
            Fx.waitUntil(
                    "the first round",
                    () -> s.sync().state().report() != null && s.sync().state().phase() == SettingsSync.Phase.IDLE);
            assertTrue(f.settings.syncEnabled());
            assertEquals(url, f.settings.syncUrl());

            // The branch and the interval write through.
            TextField branch = f.control("settings.sync.branch", TextField.class);
            Fx.run(() -> branch.setText("main"));
            assertEquals("main", f.settings.syncBranch());
            @SuppressWarnings("unchecked")
            Spinner<Integer> interval = f.control("settings.sync.interval", Spinner.class);
            Fx.run(() -> interval.getValueFactory().setValue(45));
            assertEquals(45, f.settings.syncIntervalMinutes());

            // Sync now runs another round, and a second press while it runs one more after it.
            Fx.run(() -> {
                f.button("settings.sync.syncNow").fire();
                s.sync().syncNow();
            });
            Fx.waitUntil("the second round", () -> s.sync().state().phase() == SettingsSync.Phase.IDLE);
            Fx.run(() -> {
                f.settings.setTheme("nord-light");
                f.settings.saveQuietly();
                s.sync().settingsSaved();
                s.sync().markDirty();
            });

            // A repository gone: the round fails and the page says so.
            deleteTree(dir.resolve("remote.git"));
            Fx.run(() -> f.button("settings.sync.syncNow").fire());
            Fx.waitUntil("the failed round", () -> s.sync().state().phase() == SettingsSync.Phase.PROBLEM);
            Fx.waitUntil(
                    "the problem line", () -> f.window.syncStateForCapture().contains("⚠"));

            Button disconnect = f.button("settings.sync.disconnect");
            Modals.later(disconnect::fire);
            Modals.answer(tr("settings.sync.disconnect"), false);
            assertTrue(f.settings.syncEnabled());
            Modals.later(disconnect::fire);
            Modals.answer(tr("settings.sync.disconnect"), true);
            Fx.pulse();
            assertFalse(f.settings.syncEnabled());
            assertEquals("", f.settings.syncUrl());
            assertEquals(SettingsSync.Phase.OFF, s.sync().state().phase());
            assertTrue(state(f).startsWith("on=false"), state(f));
        } finally {
            Fx.run(s.sync()::shutdown);
        }
    }

    @Test
    void aRepositoryThatCannotBeReadIsSaidAndTheSwitchOffSaysSo(@TempDir Path dir) throws Exception {
        Synced s = open(dir);
        SettingsFixture f = s.f();
        try {
            Fx.run(() -> f.window.syncSwitchForCapture(true));
            String missing = dir.resolve("nowhere.git").toUri().toString();
            Fx.run(() -> f.window.syncUrlForCapture(missing));
            Fx.waitUntil(
                    "the preview to fail", () -> !f.window.syncStateForCapture().contains(tr("settings.sync.looking")));
            assertFalse(f.settings.syncEnabled(), "kept only once a connect worked");
            assertTrue(state(f).contains("on=true"), "the switch stays on so the URL can be put right");

            // Sync now while nothing is connected: the window is told.
            Fx.run(s.sync()::syncNow);
            assertTrue(
                    s.host().told.contains("status " + tr("settings.sync.notSetUp")),
                    s.host().told.toString());

            Fx.run(() -> f.window.syncSwitchForCapture(false));
            assertFalse(f.settings.syncEnabled());
        } finally {
            Fx.run(s.sync()::shutdown);
        }
    }

    @Test
    void createOnGitHubWithNoNameDoesNothing(@TempDir Path dir) {
        Synced s = open(dir);
        SettingsFixture f = s.f();
        try {
            Fx.run(() -> f.window.syncSwitchForCapture(true));
            Fx.run(() -> f.window.createSyncRepositoryForCapture(" "));
            Fx.waitUntil("the prompt to come and go", () -> !Modals.alertShowing(tr("settings.sync.create.title")));
            Fx.pulse();
            assertTrue(state(f).contains("create=enabled"), "nothing is being created: " + state(f));
            // Cancelled from the button: the same.
            Modals.later(() -> f.button("settings.sync.createOnGitHub").fire());
            Modals.answer(tr("settings.sync.create.title"), false);
            assertEquals("", f.settings.syncUrl());
        } finally {
            Fx.run(s.sync()::shutdown);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
