package com.torrenta.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.sun.net.httpserver.HttpServer;
import com.torrenta.AppInfo;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.FakeEngine;
import com.torrenta.store.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window around its engine: an engine that does not come up and what waits for it, the
 * update check on its own schedule, the Settings window's dialogs as the capture hook opens them.
 */
@ExtendWith(FxToolkit.class)
class MainWindowLifecycleTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:" + "4".repeat(40) + "&dn=Four";

    private static boolean anyCall(FakeEngine engine, String prefix) {
        return engine.calls().stream().anyMatch(c -> c.startsWith(prefix));
    }

    @Test
    void anEngineThatCannotStartLeavesTheWindowUpAndInputsWaitForTheNext(@TempDir Path dir) throws Exception {
        EngineProfile nas = new EngineProfile(
                "nas", "NAS", EngineProfile.Kind.REMOTE, "http://127.0.0.1:9/transmission/rpc", "", "");
        new ProfileStore(dir).set(List.of(nas));
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.setEngineFactory(profile -> {
                if (!profile.isLocal()) throw new IllegalStateException("nothing listens there");
                return engine;
            }));
            f.action("switch-profile:nas");
            f.awaitStatus("nothing listens there");
            Fx.waitUntil("the list emptied", () -> f.rows() == 0);
            // With no engine: an input waits, a creation and an undo say the engine is starting.
            Fx.run(() -> {
                f.settings.setAddWithoutAsking(true);
                f.window.addInput(MAGNET);
            });
            f.action("create");
            assertEquals(tr("status.engineStarting"), f.status());
            f.action("undo");
            f.action("switch-profile:local");
            Fx.waitUntil("the engine back", () -> f.engine.description().equals(f.window.engineDescription()));
            Fx.waitUntil("the waiting input added", () -> anyCall(engine, "add "));
        }
    }

    @Test
    void theUpdateCheckRunsOnItsOwnWhenDueAndSaysUpToDateWhenAsked(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "{\"tag_name\":\"v0.0.1\",\"html_url\":\"https://example.org/r/1\",\"name\":\"old\"}"
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String was = System.getProperty("torrenta.updateEndpoint");
        System.setProperty(
                "torrenta.updateEndpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (WindowFixture f = WindowFixture.open(dir, new FakeEngine())) {
            Fx.run(() -> {
                f.settings.setUpdateCheck(true);
                f.settings.setLastUpdateCheck(0);
            });
            // Ready again: the check is due, and runs by itself.
            Fx.run(() -> f.window.engineReady(f.engine));
            Fx.waitUntil("the check recorded", () -> f.settings.lastUpdateCheck() > 0);
            f.action("check-updates");
            f.awaitStatus(tr("status.upToDate", AppInfo.NAME, AppInfo.VERSION));
        } finally {
            if (was == null) System.clearProperty("torrenta.updateEndpoint");
            else System.setProperty("torrenta.updateEndpoint", was);
            server.stop(0);
        }
    }

    @Test
    void theSettingsWindowsDialogsOpenFromTheirPages(@TempDir Path dir) throws Exception {
        try (WindowFixture f = WindowFixture.open(dir, new FakeEngine().put(FakeEngine.torrent("aaaa", "Film")))) {
            for (String action : List.of(
                    "settings-profile-add:http://127.0.0.1:9/transmission/rpc",
                    "settings-profile-dialog-test:http://127.0.0.1:9/transmission/rpc",
                    "settings-watch-add",
                    "settings-search-add:http://127.0.0.1:9/api",
                    "settings-search-dialog-test:http://127.0.0.1:9/api",
                    "settings-rss-add:http://127.0.0.1:9/feed.xml",
                    "settings-rss-rule-add:Debian",
                    "settings-profile-add",
                    "settings-search-add",
                    "settings-rss-add",
                    "settings-rss-rule-add")) {
                f.actionLater(action);
                Fx.waitUntil(
                        action + " to open a dialog",
                        () -> Window.getWindows().stream()
                                .anyMatch(w -> w.isShowing()
                                        && w instanceof Stage s
                                        && s.getModality() != javafx.stage.Modality.NONE));
                Fx.pulse();
                f.action("settings");
                assertFalse(Fx.call(f.window::lateLinesForCapture).isEmpty());
                f.cancelDialogs();
            }
            // The Create dialog with Publish ticked, and a creation the fake engine refuses.
            Files.writeString(dir.resolve("payload.bin"), "content");
            f.action("create-publish:mine");
            f.action("create-run:" + dir.resolve("payload.bin"));
            Fx.waitUntil(
                    "the creation over",
                    () -> f.window.lateLinesForCapture().stream()
                            .anyMatch(l -> l.startsWith("create=") && !l.startsWith("create=hashing")));
            f.cancelDialogs();
            f.action("settings-definitions-reload");
            Path zip = dir.resolve("definitions.zip");
            f.action("settings-definitions-export:" + zip);
            Fx.waitUntil("the definitions exported", () -> Files.exists(zip));
            // The General page's first switch is Simple mode; the window follows it.
            f.action("settings-page:GENERAL");
            Fx.run(() -> firstCheckBox(f).fire());
            assertTrue(Fx.call(() -> f.settings.simple()));
            Fx.run(() -> firstCheckBox(f).fire());
            assertFalse(Fx.call(() -> f.settings.simple()));
            f.action("settings-close");
        }
    }

    private static CheckBox firstCheckBox(WindowFixture f) {
        Stage settings = f.awaitWindow(tr("settings.title", AppInfo.NAME));
        for (Node n : settings.getScene().getRoot().lookupAll(".check-box")) {
            if (n instanceof CheckBox c && c.isVisible()) return c;
        }
        throw new AssertionError("no switch on the page");
    }

    @Test
    void aSubmenuOfTheMenuBarOpensFromTheKeyboard(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, MainWindowCommandsTest.twoTorrents())) {
            f.action("select-first");
            // The menu first, so its popup is up when the submenu is asked for.
            f.action("menu:Torrent");
            f.action("menu:Torrent>" + tr("menu.torrent.queue"));
            assertFalse(Fx.call(f.window::menuLines).isEmpty());
            Fx.run(() -> {
                for (Window w : List.copyOf(Window.getWindows())) if (w instanceof ContextMenu) w.hide();
            });
        }
    }

    @Test
    void theOverviewsCommandLineIsCopied(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, new FakeEngine().put(FakeEngine.torrent("aaaa", "Film")))) {
            f.action("select-first");
            f.action("detail:overview");
            Fx.run(() -> {
                for (Node n : f.stage.getScene().getRoot().lookupAll(".overview-copy")) {
                    // The command line's Copy sits under the command itself, set in a mono face.
                    if (n instanceof Hyperlink link
                            && link.getParent() instanceof javafx.scene.layout.VBox box
                            && box.getChildren().getFirst().getStyleClass().contains("mono")) link.fire();
                }
            });
            assertEquals(tr("status.copied.command"), f.status());
            f.action("remedy");
            f.action("web-seed-remove:0");
        }
    }
}
