package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.torrenta.rss.Article;
import com.torrenta.rss.Feed;
import com.torrenta.rss.FeedException;
import com.torrenta.rss.FeedSource;
import com.torrenta.rss.RssService;
import com.torrenta.rss.RssStore;
import com.torrenta.rss.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RSS page: feeds added through their dialog (which fetches a new URL first), edited, refreshed
 * and removed; download rules added with a live preview, edited, duplicated and removed. The feeds
 * come from a stub source, never the network.
 */
@ExtendWith(FxToolkit.class)
class SettingsRssFxTest {

    private static final String URL = "https://feeds.invalid/linux";
    private static final String OTHER = "https://feeds.invalid/other";
    private static final String BROKEN = "https://feeds.invalid/broken";

    /** Answers by URL; a URL it does not know fails as a server would. */
    private static final class Stub implements FeedSource {
        final Map<String, Result> answers = new ConcurrentHashMap<>();

        @Override
        public Result fetch(Feed feed, String etag, String lastModified) throws FeedException {
            Result r = answers.get(feed.url());
            if (r == null) throw new FeedException("the server answered 404");
            return r;
        }
    }

    private static Article item(String guid, String title) {
        return new Article(
                "",
                guid,
                title,
                "https://x/" + guid,
                "magnet:?xt=urn:btih:" + "a".repeat(39) + guid,
                Instant.now(),
                "",
                false);
    }

    private record Rss(SettingsFixture f, RssService service, Stub stub) {}

    private static Rss open(Path dir) {
        Stub stub = new Stub();
        stub.answers.put(
                URL,
                new FeedSource.Result(
                        true, "Linux ISOs", List.of(item("1", "Debian 13 netinst"), item("2", "Fedora 43")), "", ""));
        stub.answers.put(OTHER, new FeedSource.Result(true, "", List.of(), "", ""));
        RssStore store = new RssStore(dir);
        store.load();
        RssService service = new RssService(store, stub, () -> 30, Runnable::run, Platform::runLater);
        SettingsFixture f = SettingsFixture.open(dir);
        Fx.run(() -> f.window.setRss(service));
        f.page(SettingsWindow.Category.RSS);
        return new Rss(f, service, stub);
    }

    @SuppressWarnings("unchecked")
    private static <T> TableView<T> table(SettingsFixture f, int n) {
        return Fx.call(() ->
                (TableView<T>) Modals.all(f.root(), TableView.class, t -> true).get(n));
    }

    /** The {@code n}-th button labelled {@code tr(key)} on the page: the feeds' come before the rules'. */
    private static Button button(SettingsFixture f, String key, int n) {
        return Fx.call(() -> Modals.all(f.root(), Button.class, b -> b.getText().equals(tr(key)))
                .get(n));
    }

    private static void doubleClickFirstRow(TableView<?> table) {
        Fx.pulse();
        TableRow<?> row = Fx.call(
                () -> Modals.first(table, TableRow.class, r -> !r.isEmpty()).orElseThrow());
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

    private static void acknowledgeFailure() {
        Modals.answer(tr("settings.title", com.torrenta.AppInfo.NAME), true);
    }

    private static void breakFile(Path file) throws Exception {
        Files.deleteIfExists(file);
        Files.createDirectories(file);
        Files.writeString(file.resolve("keep"), "x");
    }

    @Test
    void aFeedIsFetchedBeforeItIsAddedAndCanBeEditedRefreshedAndRemoved(@TempDir Path dir) throws Exception {
        Rss r = open(dir);
        SettingsFixture f = r.f();

        // A URL that does not answer keeps the dialog open with the reason.
        Fx.run(() -> f.window.openRssDialogForCapture(BROKEN));
        Stage add = Modals.await(tr("dialog.feed.title.add"));
        Modals.press(add, tr("common.save"));
        Fx.waitUntil("the fetch to fail", () -> f.window.rssStatus().contains("404"));
        assertTrue(add.isShowing());
        assertEquals(tr("dialog.feed.title.add"), Fx.call(f.window::dialogTitle));
        assertTrue(Fx.call(() -> f.window.dialogScene() != null));
        Fx.run(() -> {
            Node root = add.getScene().getRoot();
            Modals.field(root, 0).setText(URL);
            Modals.first(root, CheckBox.class, c -> true).orElseThrow().setSelected(true);
        });
        Modals.press(add, tr("common.save"));
        Modals.gone(tr("dialog.feed.title.add"));
        assertEquals(1, r.service().feeds().size());
        Feed feed = r.service().feeds().getFirst();
        assertEquals("Linux ISOs", feed.name(), "a blank name takes the feed's title");
        assertTrue(feed.refreshMinutes() > 0, "its own interval");
        Fx.waitUntil("the status line", () -> !f.window.rssStatus().isEmpty());
        TableView<Feed> feeds = table(f, 0);
        Fx.waitUntil("two unread in the table", () -> Modals.texts(feeds).contains("2"));

        // Edited with the URL unchanged: saved at once, no fetch.
        doubleClickFirstRow(table(f, 0));
        Stage edit = Modals.await(tr("dialog.feed.title.edit"));
        Fx.run(() -> Modals.field(edit.getScene().getRoot(), 1).setText("ISOs"));
        Modals.press(edit, tr("common.save"));
        Modals.gone(tr("dialog.feed.title.edit"));
        assertEquals("ISOs", r.service().feeds().getFirst().name());

        // Edited to a new URL, through the Edit button: fetched first.
        Modals.later(() -> button(f, "common.edit", 0).fire());
        Stage moved = Modals.await(tr("dialog.feed.title.edit"));
        Fx.run(() -> Modals.field(moved.getScene().getRoot(), 0).setText(OTHER));
        Modals.press(moved, tr("common.save"));
        Modals.gone(tr("dialog.feed.title.edit"));
        assertEquals(OTHER, r.service().feeds().getFirst().url());

        // Refresh: the selected feed, then every feed.
        Fx.run(() -> button(f, "settings.rss.refresh", 0).fire());
        Fx.run(() -> table(f, 0).getSelectionModel().clearSelection());
        Fx.run(() -> button(f, "settings.rss.refresh", 0).fire());
        Fx.pulse();

        // A cancelled add changes nothing; a removal asks.
        Modals.later(() -> button(f, "settings.table.add", 0).fire());
        Stage cancelled = Modals.await(tr("dialog.feed.title.add"));
        Modals.press(cancelled, tr("common.cancel"));
        Modals.gone(tr("dialog.feed.title.add"));
        Fx.run(() -> table(f, 0).getSelectionModel().select(0));
        Modals.later(() -> button(f, "common.remove", 0).fire());
        Modals.answer(RemoveConfirm.header(RemoveConfirm.Kind.FEED), false);
        assertEquals(1, r.service().feeds().size());
        Modals.later(() -> button(f, "common.remove", 0).fire());
        Modals.answer(RemoveConfirm.header(RemoveConfirm.Kind.FEED), true);
        Fx.pulse();
        assertEquals(List.of(), r.service().feeds());
    }

    @Test
    void aFeedThatCannotBeSavedSaysSo(@TempDir Path dir) throws Exception {
        Rss r = open(dir);
        SettingsFixture f = r.f();
        Fx.run(() -> f.window.openRssDialogForCapture(URL));
        Modals.press(Modals.await(tr("dialog.feed.title.add")), tr("common.save"));
        Modals.gone(tr("dialog.feed.title.add"));
        Path feeds = dir.resolve("rss").resolve(RssStore.FEEDS_FILE);
        assertTrue(Files.exists(feeds), feeds + " is where the list is kept");
        breakFile(feeds);

        Fx.run(() -> table(f, 0).getSelectionModel().select(0));
        Modals.later(() -> button(f, "common.edit", 0).fire());
        Stage edit = Modals.await(tr("dialog.feed.title.edit"));
        Fx.run(() -> Modals.field(edit.getScene().getRoot(), 1).setText("Renamed"));
        Modals.press(edit, tr("common.save"));
        acknowledgeFailure();
        Modals.later(() -> button(f, "common.remove", 0).fire());
        Modals.answer(RemoveConfirm.header(RemoveConfirm.Kind.FEED), true);
        acknowledgeFailure();
        Fx.run(() -> f.window.openRssDialogForCapture(OTHER));
        Modals.press(Modals.await(tr("dialog.feed.title.add")), tr("common.save"));
        acknowledgeFailure();
    }

    @Test
    void rulesAreAddedWithAPreviewEditedDuplicatedAndRemoved(@TempDir Path dir) throws Exception {
        Rss r = open(dir);
        SettingsFixture f = r.f();
        r.service().addFeed(Feed.of(URL, "", 0));
        Fx.pulse();

        Fx.run(() -> f.window.openRuleDialogForCapture("Debian"));
        Stage add = Modals.await(tr("dialog.rule.title.add"));
        Fx.waitUntil("the preview", () -> f.window.ruleMatchesForCapture().contains("Debian 13 netinst"));
        assertEquals("", Fx.call(f.window::ruleProblemForCapture));
        assertEquals(tr("dialog.rule.title.add"), Fx.call(f.window::dialogTitle));
        assertTrue(Fx.call(() -> f.window.dialogScene() != null));
        Fx.run(() -> {
            Node root = add.getScene().getRoot();
            for (CheckBox box : Modals.all(root, CheckBox.class, c -> true)) box.setSelected(!box.isSelected());
            Modals.field(root, 3).setText("1x1-");
        });
        Fx.pulse();
        Fx.run(() -> {
            Node root = add.getScene().getRoot();
            for (CheckBox box : Modals.all(root, CheckBox.class, c -> true)) box.setSelected(!box.isSelected());
            Modals.field(root, 3).setText("");
        });
        Modals.press(add, tr("common.save"));
        Modals.gone(tr("dialog.rule.title.add"));
        assertEquals(
                List.of("Debian"), r.service().rules().stream().map(Rule::name).toList());
        List<String> lines = Fx.call(f.window::rulesForCapture);
        assertTrue(lines.getFirst().startsWith("Debian | on"), lines.toString());

        // From an article's title: a fresh name, since "Debian" is taken.
        Fx.run(() -> f.window.createRuleFromTitle("Debian 13 netinst"));
        Stage fromTitle = Modals.await(tr("dialog.rule.title.add"));
        Fx.waitUntil("its preview", () -> !f.window.ruleMatchesForCapture().isEmpty());
        Modals.press(fromTitle, tr("common.save"));
        Modals.gone(tr("dialog.rule.title.add"));
        assertEquals(2, r.service().rules().size());

        // A name another rule has is refused in the dialog.
        Modals.later(() -> button(f, "settings.table.add", 1).fire());
        Stage taken = Modals.await(tr("dialog.rule.title.add"));
        Fx.run(() -> Modals.field(taken.getScene().getRoot(), 0).setText("Debian"));
        assertEquals(tr("dialog.rule.problem.nameTaken", "Debian"), Fx.call(f.window::ruleProblemForCapture));
        Modals.press(taken, tr("common.cancel"));
        Modals.gone(tr("dialog.rule.title.add"));

        TableView<Rule> rules = table(f, 1);
        Fx.run(() -> rules.getSelectionModel().select(0));
        Fx.run(() -> button(f, "settings.rss.duplicate", 0).fire());
        assertEquals(3, r.service().rules().size());
        Fx.run(() -> button(f, "settings.rss.runRules", 0).fire());

        doubleClickFirstRow(rules);
        Stage edit = Modals.await(tr("dialog.rule.title.edit"));
        Fx.run(() -> Modals.field(edit.getScene().getRoot(), 2).setText("Fedora"));
        Modals.press(edit, tr("common.save"));
        Modals.gone(tr("dialog.rule.title.edit"));
        assertTrue(r.service().rules().stream()
                .anyMatch(rule -> rule.mustNotContain().equals("Fedora")));
        Fx.run(() -> rules.getSelectionModel().select(0));
        Modals.later(() -> button(f, "common.edit", 1).fire());
        Stage cancelled = Modals.await(tr("dialog.rule.title.edit"));
        Modals.press(cancelled, tr("common.cancel"));
        Modals.gone(tr("dialog.rule.title.edit"));

        // The global switch is the rules' status line.
        CheckBox auto = f.control("settings.rss.autoDownload", CheckBox.class);
        Fx.run(() -> auto.setSelected(!auto.isSelected()));
        assertEquals(Fx.call(auto::isSelected), f.settings.rssAutoDownload());

        Fx.run(() -> rules.getSelectionModel().select(0));
        Modals.later(() -> button(f, "common.remove", 1).fire());
        Modals.answer(RemoveConfirm.header(RemoveConfirm.Kind.RULE), true);
        Fx.pulse();
        assertEquals(2, r.service().rules().size());

        // A rules file that cannot be written: each change says so.
        breakFile(dir.resolve("rss").resolve(RssStore.RULES_FILE));
        Fx.run(() -> rules.getSelectionModel().select(0));
        Modals.later(() -> button(f, "settings.rss.duplicate", 0).fire());
        acknowledgeFailure();
        Fx.run(() -> rules.getSelectionModel().select(0));
        Modals.later(() -> button(f, "common.remove", 1).fire());
        Modals.answer(RemoveConfirm.header(RemoveConfirm.Kind.RULE), true);
        acknowledgeFailure();
        assertFalse(Fx.call(f.window::rulesForCapture).isEmpty());
    }

    @Test
    void aRuleDialogLeftOpenIsClosedByTheCapture(@TempDir Path dir) {
        Rss r = open(dir);
        SettingsFixture f = r.f();
        Fx.run(() -> f.window.openRuleDialogForCapture(""));
        Modals.await(tr("dialog.rule.title.add"));
        Fx.run(() -> f.window.openRuleDialogForCapture("again"));
        Fx.pulse();
        Fx.run(f.window::closeDialogsForCapture);
        Modals.gone(tr("dialog.rule.title.add"));
        assertEquals(List.of(), r.service().rules());
        assertEquals("", Fx.call(f.window::ruleMatchesForCapture), "nothing open, nothing to say");
        assertEquals("", Fx.call(f.window::ruleProblemForCapture));
    }
}
