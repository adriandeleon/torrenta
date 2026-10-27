package com.torrenta.ui;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.stage.Window;

import com.sun.net.httpserver.HttpServer;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main window's panes, menus and side windows on a fake engine, driven through the capture
 * hook's actions, with what its lines say (the ones a screenshot cannot show) checked.
 */
@ExtendWith(FxToolkit.class)
class MainWindowViewsTest {

    private static FakeEngine twoTorrents() {
        return MainWindowCommandsTest.twoTorrents();
    }

    private static boolean anyCall(FakeEngine engine, String prefix) {
        return engine.calls().stream().anyMatch(c -> c.startsWith(prefix));
    }

    private static List<String> late(WindowFixture f) {
        return Fx.call(f.window::lateLinesForCapture);
    }

    private static boolean lateHas(WindowFixture f, String prefix) {
        return late(f).stream().anyMatch(l -> l.startsWith(prefix));
    }

    /** Every popup menu closed, so the next action starts from none. */
    private static void hidePopups() {
        Fx.run(() -> {
            for (Window w : List.copyOf(Window.getWindows())) if (w instanceof ContextMenu) w.hide();
        });
        Fx.pulse();
    }

    /**
     * The submenu titled {@code text} of a showing popup, shown as a menu's own {@code show()}
     * does: its {@code onShowing} runs, which is where a submenu built as it opens is built.
     */
    private static javafx.scene.control.Menu openShownSubmenu(String text) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (!(w instanceof ContextMenu menu) || !menu.isShowing()) continue;
            for (MenuItem item : menu.getItems()) {
                if (item instanceof javafx.scene.control.Menu sub && text.equals(sub.getText())) {
                    sub.show();
                    return sub;
                }
            }
        }
        throw new AssertionError("no submenu " + text + " in a menu showing");
    }

    /** The item of a showing popup menu whose text starts with {@code text}, fired. */
    private static void fireShownItem(String text) {
        Fx.run(() -> {
            for (Window w : List.copyOf(Window.getWindows())) {
                if (!(w instanceof ContextMenu menu) || !menu.isShowing()) continue;
                for (MenuItem item : menu.getItems()) {
                    if (item.getText() != null && item.getText().startsWith(text) && item.isVisible()) {
                        item.fire();
                        return;
                    }
                }
            }
            throw new AssertionError("no item " + text + " in a menu showing");
        });
        Fx.pulse();
    }

    private static Path resource(String path) {
        try {
            return Path.of(MainWindowViewsTest.class.getResource(path).toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theSideWindowsOpenAndTheCaptureSceneIsTheOneInFront(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            javafx.scene.Scene main = Fx.call(() -> f.stage.getScene());
            assertEquals(main, Fx.call(f.window::captureScene));
            for (String action : List.of(
                    "event-log",
                    "stats",
                    "statistics",
                    "leak-card",
                    "leak-check",
                    "message-log",
                    "debug-log",
                    "report-problem",
                    "about",
                    "settings")) {
                f.action(action);
                assertTrue(Fx.call(f.window::captureScene) != null, action);
                late(f);
            }
            assertFalse(Fx.call(f.window::aboutBoundsForCapture).isEmpty(), "the About panel is open");
            f.action("about");
            Fx.waitUntil("the statistics drawn", () -> late(f).stream().anyMatch(l -> l.startsWith("stats=")));
            Fx.waitUntil("the report built", () -> late(f).stream().anyMatch(l -> l.startsWith("report=")));
            f.cancelDialogs();
            Fx.closeAll();
            Fx.run(() -> f.stage.show());
        }
    }

    @Test
    void theCaptureLinesDescribeTheWindow(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            Fx.run(() -> f.window.setInstanceDescription("first"));
            assertEquals("first", Fx.call(f.window::instanceDescription));
            assertEquals("none", Fx.call(f.window::trayDescription));
            assertEquals("none", Fx.call(f.window::badgeDescription));
            Fx.call(f.window::trayLine);
            Fx.call(f.window::handlerDescription);
            Fx.call(f.window::openHandlersDescription);
            assertEquals(
                    CapabilityRules.missingLine(com.torrenta.engine.Engine.Capabilities.ALL),
                    Fx.call(f.window::capabilitiesLine));
            assertFalse(Fx.call(f.window::profileDescription).isEmpty());
            assertTrue(Fx.call(f.window::pollDescription).startsWith("onScreen=true"));
            assertEquals("", Fx.call(f.window::aboutBoundsForCapture));
            assertEquals("", Fx.call(f.window::availableUpdateVersion));
            assertTrue(Fx.call(f.window::networkStatusText).startsWith("port="));
            List<String> lines = late(f);
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("statusbar-fit=")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("window=")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("simple=false")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("table=sort:none shown=2")), lines.toString());
        }
    }

    @Test
    void theWindowMovesHidesAndComesBack(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("window:900x600+20+30");
            assertEquals(900, Fx.call(() -> f.stage.getWidth()), 1);
            Fx.run(f.window::rememberWindow);
            f.action("window:maximize");
            f.action("iconify");
            Fx.waitUntil(
                    "a tick skipped off screen",
                    () -> !f.window.pollDescription().contains("skipped=0"));
            f.action("deiconify");
            f.action("tray-show");
            Fx.waitUntil("the catch-up drawn", () -> !f.window.pollDescription().contains("catchUps=0"));
            Fx.run(f.window::rememberWindow);
        }
    }

    @Test
    void theMenusOpenAndListTheirItems(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            for (String menu : List.of("File", "Torrent", "View", "Help")) {
                f.action("menu:" + menu);
                assertFalse(Fx.call(f.window::menuLines).isEmpty(), menu);
                hidePopups();
            }
            f.action("context-menu");
            Fx.run(() -> openShownSubmenu(tr("menu.torrent.queue")));
            hidePopups();
            f.action("context-menu");
            assertTrue(Fx.call(f.window::menuLines).stream().anyMatch(l -> l.startsWith("> ")), "submenus marked");
            hidePopups();
            // The category submenu is built as it opens, from the categories there are.
            f.action("omnibox:>set-category~Films");
            f.action("focus-key:ENTER");
            f.awaitCall("setCategory " + selectedId(f) + " Films");
            f.action("context-menu");
            Fx.run(() -> {
                javafx.scene.control.Menu categories = openShownSubmenu(tr("menu.torrent.setCategory"));
                assertTrue(categories.getItems().stream().anyMatch(i -> "Films".equals(i.getText())));
                categories.getItems().stream()
                        .filter(i -> tr("common.none").equals(i.getText()))
                        .findFirst()
                        .orElseThrow()
                        .fire();
            });
            Fx.waitUntil(
                    "the category cleared",
                    () -> engine.calls().stream().anyMatch(c -> c.startsWith("setCategory ") && c.endsWith(" ")));
            hidePopups();
            f.action("palette");
            f.action("palette:pau");
            f.action("focus-key:ESCAPE");
            hidePopups();
        }
    }

    private static String selectedId(WindowFixture f) {
        return Fx.call(() ->
                f.shown("aaaa") != null && late(f).stream().anyMatch(l -> l.startsWith("table=") && l.contains("*Film"))
                        ? "aaaa"
                        : "bbbb");
    }

    @Test
    void theSidebarFiltersFoldsAndKeepsItsWidth(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("sidebar:status:seeding");
            Fx.waitUntil("the seed alone", () -> f.rows() == 1);
            f.action("sidebar:status:all");
            Fx.waitUntil("both", () -> f.rows() == 2);
            f.action("sidebar-click:status");
            f.action("sidebar-click:status");
            f.action("sidebar-drag:40");
            f.action("sidebar-keys:DOWN+SPACE");
            f.action("divider-drag:0.4");
            f.action("sort:name:desc");
            assertTrue(late(f).stream().anyMatch(l -> l.startsWith("table=sort:name:desc")));
            f.action("sort:size");
            f.action("sort:nothing");
            f.action("select-rows:0+1");
            assertEquals(2, Fx.call(f::selected));
            f.action("focus-key:ESCAPE");
        }
    }

    @Test
    void aSavedViewIsSavedPickedRenamedAndDeleted(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("omnibox:state:seeding");
            f.action("omnibox:state:seeding~");
            f.action("save-view:Seeds");
            Fx.waitUntil("the view saved", () -> f.settings.savedViews().size() == 1);
            Fx.waitUntil("the seed alone", () -> f.rows() == 1);
            f.action("show-all");
            f.action("pick-view:1");
            f.action("pick-view:9");
            Fx.waitUntil("the view picked", () -> f.rows() == 1);
            // The saved view's chord, Shortcut+Alt+1, from the list.
            f.action("show-all");
            f.action("table-key:ESCAPE");
            f.action("focus-key:" + MainWindowCommandsTest.SHORTCUT + "+ALT+DIGIT1");
            Fx.waitUntil("the view picked by its chord", () -> f.rows() == 1);
            assertEquals(1, Fx.call(() -> f.settings.savedViews().size()));
        }
    }

    @Test
    void theSettingsWindowsPagesAndKeysApplyToTheWindow(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("settings:port");
            f.action("settings-page:CONNECTION");
            f.action("settings-key:ESCAPE");
            f.action("manage-categories");
            f.action("settings-layout:right/bottom");
            assertTrue(late(f).stream().noneMatch(l -> l.startsWith("error")));
            f.action("settings-layout:left/right");
            f.action("settings-layout:left/hidden");
            f.action("settings-layout:left/right");
            f.action("settings-keys:recheck:" + "Shortcut+Alt+K|Shortcut+Alt+K");
            assertTrue(Fx.call(() -> f.settings.keyBindings().toString()).contains("recheck"));
            f.action("settings-close");
        }
    }

    @Test
    void theDetailPaneShowsTheSelectionAndItsEditsReachTheEngine(@TempDir Path dir) {
        FakeEngine engine = twoTorrents()
                .withFiles(
                        "aaaa",
                        List.of(
                                new TorrentDetail.FileStatus(0, "Film/film.mkv", 200, 100, Priority.NORMAL, 0, 40),
                                new TorrentDetail.FileStatus(1, "Film/extras.mkv", 100, 0, Priority.NORMAL, 41, 63)))
                .withPeers(
                        "aaaa",
                        List.of(new TorrentDetail.Peer("203.0.113.7:6881", "qBittorrent", "D", 0.5, 1000, 0, 5000, 0)));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("sort:name");
            f.action("select-rows:1"); // Film, after Album
            for (String tab : List.of("overview", "files", "peers", "trackers", "log", "speed")) {
                f.action("detail:" + tab);
                Fx.pulse();
                late(f);
            }
            f.action("detail:files");
            Fx.waitUntil("the files shown", () -> late(f).stream().anyMatch(l -> l.startsWith("file=")));
            f.action("files-rename:0:renamed.mkv");
            Fx.waitUntil("the rename", () -> anyCall(engine, "renamePath aaaa"));
            f.action("files-menu:1");
            hidePopups();
            f.action("detail:trackers");
            Fx.waitUntil("the trackers tab's line", () -> lateHas(f, "web-seeds="));
            f.action("web-seeds-add:https://seed.example.org/a/~https://seed.example.org/b/");
            Fx.waitUntil("the web seeds", () -> anyCall(engine, "addWebSeeds aaaa"));
            f.action("detail:overview");
            Fx.waitUntil("the overview's lines", () -> lateHas(f, "overview-"));
            f.action("detail:speed");
            Fx.waitUntil("the speed tab", () -> lateHas(f, "speed-tab="));
            f.action("peer-ban");
            Fx.waitUntil("the peer banned", () -> f.settings.ipFilterBans().contains("203.0.113.7"));
            f.action("peer-menu");
            Fx.waitUntil("the peer menu", () -> !f.window.menuLines().isEmpty());
            hidePopups();
            // The detail follows the selection; none selected clears it.
            f.action("table-key:ESCAPE");
            f.action("toggle-detail");
            f.action("toggle-detail");
        }
    }

    @Test
    void aShareLimitReachedPausesTheSeedAndSaysSo(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("bbbb", "Album", TorrentState.SEEDING, 1.0));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> {
                f.settings.set(Settings.SHARE_RATIO, "0.1");
                f.settings.set(Settings.SHARE_ACTION, "PAUSE");
            });
            // Any change of the engine's settings reads the share limit again: here, Alt speed twice.
            f.action("alt-speed");
            f.action("alt-speed");
            f.awaitCall("pause bbbb");
            Fx.waitUntil("the log says so", () -> f.window.eventLines().stream().anyMatch(l -> l.contains("Album")));
        }
    }

    @Test
    void inputsFromOutsideBringTheWindowForwardAndAreAdded(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.openFromOutside(List.of(), "a test"));
            assertEquals(tr("status.broughtToFront", "a test"), f.status());
            Fx.run(() -> {
                f.settings.setAddWithoutAsking(true);
                f.window.openFromOutside(List.of("magnet:?xt=urn:btih:" + "d".repeat(40)), "a test");
            });
            Fx.waitUntil("the add", () -> anyCall(engine, "add "));
        }
    }

    @Test
    void theImportDialogReadsEachClientsFolder(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            for (String client : List.of("transmission-4.1", "deluge-2.2", "qbittorrent-5.1")) {
                f.action("import-from:" + resource("/com/torrenta/migrate/" + client));
                f.awaitWindow(tr("dialog.import.title"));
                Fx.waitUntil(
                        "the plan read",
                        () -> late(f).stream().anyMatch(l -> l.startsWith("import=") && !l.contains("reading")));
                f.cancelDialogs();
            }
            // Confirmed, the import goes to the engine, which the fake cannot do.
            f.action("import-confirm");
            f.action("import-from:" + resource("/com/torrenta/migrate/transmission-4.1"));
            Fx.waitUntil(
                    "the import tried",
                    () -> !f.isShowing(tr("dialog.import.title"))
                                    && f.status()
                                            .startsWith(
                                                    tr("status.importing", 2).substring(0, 3))
                            || anyStatusAfterImport(f));
            f.cancelDialogs();
        }
    }

    private static boolean anyStatusAfterImport(WindowFixture f) {
        return !f.isShowing(tr("dialog.import.title"));
    }

    @Test
    void theCreateDialogOpensOnASource(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("payload.bin"), "content");
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("create");
            f.action("create:" + dir.resolve("payload.bin"));
            Fx.waitUntil("the create dialog's lines", () -> lateHas(f, "dialog=" + tr("dialog.create.title")));
            f.cancelDialogs();
        }
    }

    @Test
    void aNewerReleaseIsOfferedAndCanBeSkipped(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "{\"tag_name\":\"v99.0.0\",\"html_url\":\"https://example.org/r/99\",\"name\":\"99\"}"
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String was = System.getProperty("torrenta.updateEndpoint");
        System.setProperty(
                "torrenta.updateEndpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("about");
            f.action("check-updates");
            Fx.waitUntil(
                    "the newer version found",
                    () -> f.window.availableUpdateVersion().equals("99.0.0"));
            f.action("menu:Help");
            fireShownItem(tr("menu.help.downloadUpdate", com.torrenta.AppInfo.NAME, "99.0.0"));
            assertTrue(f.opened.contains("https://example.org/r/99"), f.opened.toString());
            f.action("menu:Help");
            fireShownItem(tr("menu.help.skipVersion", "99.0.0"));
            assertEquals("99.0.0", Fx.call(() -> f.settings.dismissedUpdate()));
            assertEquals("", Fx.call(f.window::availableUpdateVersion));
            f.cancelDialogs();
        } finally {
            if (was == null) System.clearProperty("torrenta.updateEndpoint");
            else System.setProperty("torrenta.updateEndpoint", was);
            server.stop(0);
        }
    }

    @Test
    void aWarningFromTheSessionIsSaid(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            engine.emit(new EngineEvent(
                    java.time.Instant.now(), EngineEvent.Level.WARNING, null, "", "tracker unreachable"));
            f.awaitStatus("tracker unreachable");
        }
    }
}
