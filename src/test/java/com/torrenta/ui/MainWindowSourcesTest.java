package com.torrenta.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.torrenta.engine.Connectivity;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.FakeEngine;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.store.ProfileStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where torrents come from besides an add: a search provider, a feed, another engine profile;
 * and the status bar's network facts. Every server is a stub on loopback, never the network.
 */
@ExtendWith(FxToolkit.class)
class MainWindowSourcesTest {

    private static final String HASH = "1111111111111111111111111111111111111111";
    private static final String MAGNET = "magnet:?xt=urn:btih:" + HASH + "&amp;dn=debian";

    /** A loopback server answering every request with what {@code answer} makes of its URI. */
    private static HttpServer serve(Function<HttpExchange, String> answer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = answer.apply(exchange).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String resource(String path) {
        try (InputStream in = MainWindowSourcesTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final String SEARCH_ANSWER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
              <channel>
                <title>stub</title>
                <item>
                  <title>Debian 13 netinst</title>
                  <guid>debian-13</guid>
                  <size>700000000</size>
                  <pubDate>Fri, 30 Aug 2024 10:11:12 +0000</pubDate>
                  <link>%s</link>
                  <torznab:attr name="magneturl" value="%s" />
                  <torznab:attr name="seeders" value="12" />
                  <torznab:attr name="peers" value="20" />
                </item>
              </channel>
            </rss>
            """.formatted(MAGNET, MAGNET);

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Stub feed</title>
                <link>https://feed.example.org/</link>
                <description>a feed</description>
                <item>
                  <title>Debian 13 netinst</title>
                  <link>%s</link>
                  <guid>debian-13</guid>
                  <pubDate>Fri, 30 Aug 2024 10:11:12 +0000</pubDate>
                </item>
                <item>
                  <title>Debian 12 netinst</title>
                  <link>magnet:?xt=urn:btih:2222222222222222222222222222222222222222&amp;dn=debian12</link>
                  <guid>debian-12</guid>
                  <pubDate>Thu, 29 Aug 2024 10:11:12 +0000</pubDate>
                </item>
              </channel>
            </rss>
            """.formatted(MAGNET);

    private static boolean anyCall(FakeEngine engine, String prefix) {
        return engine.calls().stream().anyMatch(c -> c.startsWith(prefix));
    }

    @Test
    void aSearchResultIsAddedThroughTheAddDialog(@TempDir Path dir) throws Exception {
        String caps = resource("/com/torrenta/search/indexer-caps.xml");
        HttpServer server = serve(x -> x.getRequestURI().toString().contains("t=caps") ? caps : SEARCH_ANSWER);
        new SearchProviderStore(dir)
                .set(List.of(new TorznabEndpoint("Stub", base(server) + "/api", "key", true, 0, false)));
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            // The shipped examples reach real sites: off for this test.
            Fx.run(() -> f.settings.setDisabledSearchDefinitions(
                    List.of("archive-org", "linuxtracker", "academictorrents")));
            f.action("search:debian");
            f.action("search-filter:debian");
            Fx.waitUntil("a result", () -> searchRows(f) > 0);
            f.action("search-keys:DOWN+ENTER");
            f.awaitWindow(tr("dialog.add.title.magnet"));
            f.cancelDialogs();
            Fx.run(() -> f.settings.setAddWithoutAsking(true));
            f.action("search-keys:DOWN+ENTER");
            Fx.waitUntil("the result added", () -> anyCall(engine, "add "));
            f.action("search-clear");
            f.action("search-keys:ESCAPE");
        } finally {
            server.stop(0);
        }
    }

    /** The RSS tab's article list once it has a row, else null. */
    private static javafx.scene.control.TableView<?> articles(WindowFixture f) {
        for (Node n : f.stage.getScene().getRoot().lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t
                    && !t.getItems().isEmpty()
                    && t.getItems().get(0) instanceof ArticleRow) return t;
        }
        return null;
    }

    /** The Search tab's table rows, read off its scene. */
    private static int searchRows(WindowFixture f) {
        for (Node n : f.stage.getScene().getRoot().lookupAll(".table-view")) {
            if (n instanceof javafx.scene.control.TableView<?> t
                    && !t.getItems().isEmpty()
                    && !(t.getItems().get(0) instanceof TorrentRow))
                return t.getItems().size();
        }
        return 0;
    }

    @Test
    void aFeedsArticlesAreAddedDownloadedAndMadeIntoRules(@TempDir Path dir) throws Exception {
        HttpServer server = serve(x -> FEED);
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("rss:" + base(server) + "/feed.xml");
            f.action("rss-feed:Stub feed");
            f.action("rss-keys:DOWN+ENTER");
            f.awaitWindow(tr("dialog.add.title.magnet"));
            f.cancelDialogs();
            f.action("rss-download");
            Fx.waitUntil("the article added", () -> anyCall(engine, "add "));
            f.action("rss-rule-from-title");
            f.cancelDialogs();
            f.action("rss-dialog:" + base(server) + "/other.xml");
            f.cancelDialogs();
            f.action("rss-keys:ESCAPE");
            f.action("rss-run-rules");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aResultOrAnArticleWhoseTorrentDoesNotReadIsSaid(@TempDir Path dir) throws Exception {
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        String caps = resource("/com/torrenta/search/indexer-caps.xml");
        HttpServer server = serve(x -> {
            String uri = x.getRequestURI().toString();
            asked.add(uri);
            if (uri.contains("t=caps")) return caps;
            if (uri.startsWith("/api"))
                return LINK_ANSWER.formatted(base(x.getLocalAddress()), base(x.getLocalAddress()));
            if (uri.startsWith("/feed")) return LINK_FEED.formatted(base(x.getLocalAddress()));
            return "not a torrent";
        });
        new SearchProviderStore(dir)
                .set(List.of(new TorznabEndpoint("Stub", base(server) + "/api", "key", true, 0, false)));
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.settings.setDisabledSearchDefinitions(
                    List.of("archive-org", "linuxtracker", "academictorrents")));
            f.action("search:debian");
            Fx.waitUntil("a result", () -> searchRows(f) > 0);
            f.action("search-keys:DOWN+ENTER");
            Fx.waitUntil("its torrent fetched", () -> asked.stream().anyMatch(u -> u.startsWith("/file")));
            Fx.waitUntil("the failure said", () -> !f.status().contains("debian.torrent"));
            f.action("rss:" + base(server) + "/feed.xml");
            // Once the feed is fetched, its article chosen in the list and Enter pressed there.
            Fx.waitUntil("the article listed", () -> articles(f) != null);
            Fx.run(() -> {
                javafx.scene.control.TableView<?> list = articles(f);
                list.requestFocus();
                list.getSelectionModel().select(0);
            });
            f.action("rss-keys:ENTER");
            Fx.waitUntil(
                    "its torrent fetched",
                    () -> asked.stream().filter(u -> u.startsWith("/file")).count() >= 2);
            f.action("rss-download");
            Fx.waitUntil(
                    "fetched once more",
                    () -> asked.stream().filter(u -> u.startsWith("/file")).count() >= 3);
            Fx.pulse();
            assertTrue(
                    engine.calls().stream().noneMatch(c -> c.startsWith("add ")),
                    engine.calls().toString());
        } finally {
            server.stop(0);
        }
    }

    private static String base(InetSocketAddress address) {
        return "http://127.0.0.1:" + address.getPort();
    }

    private static final String LINK_ANSWER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
              <channel>
                <title>stub</title>
                <item>
                  <title>Debian 13 netinst</title>
                  <guid>debian-13</guid>
                  <size>700000000</size>
                  <link>%s/file/debian.torrent</link>
                  <enclosure url="%s/file/debian.torrent" length="700000000" type="application/x-bittorrent" />
                  <torznab:attr name="seeders" value="3" />
                </item>
              </channel>
            </rss>
            """;

    private static final String LINK_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Link feed</title>
                <link>https://feed.example.org/</link>
                <description>a feed</description>
                <item>
                  <title>Debian 13 netinst</title>
                  <link>%s/file/debian.torrent</link>
                  <guid>debian-13</guid>
                </item>
              </channel>
            </rss>
            """;

    @Test
    void anotherProfileIsSwitchedToAndBack(@TempDir Path dir) throws Exception {
        EngineProfile nas = new EngineProfile(
                "nas", "NAS", EngineProfile.Kind.REMOTE, "http://127.0.0.1:9/transmission/rpc", "", "");
        new ProfileStore(dir).set(List.of(nas));
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            // The same profile again says it is in use.
            f.actionLater("switch-profile");
            f.pressDefault(tr("dialog.switchProfile.title"));
            assertEquals(tr("status.profileInUse", EngineProfile.LOCAL.displayName()), f.status());
            f.actionLater("switch-profile");
            Stage choice = f.awaitWindow(tr("dialog.switchProfile.title"));
            Fx.run(() -> {
                ComboBox<?> box = (ComboBox<?>) choice.getScene().getRoot().lookup(".combo-box");
                box.getSelectionModel().select(1);
            });
            f.pressDefault(tr("dialog.switchProfile.title"));
            Fx.waitUntil(
                    "the remote profile in the title", () -> f.stage.getTitle().contains("NAS"));
            Fx.waitUntil("the engine back up", () -> f.engine.description().equals(f.window.engineDescription()));
            assertEquals("nas", Fx.call(() -> f.window.profile().id()));
            f.action("switch-profile:local");
            Fx.waitUntil("the local profile again", () -> f.window.profile().isLocal());
        }
    }

    @Test
    void theStatusBarSaysThePortAndTheExitCheckAndItsEntriesOpenWhatTheyName(@TempDir Path dir) throws Exception {
        HttpServer exit = serve(x -> x.getRemoteAddress().getAddress().getHostAddress());
        FakeEngine engine = new FakeEngine()
                .put(FakeEngine.torrent("aaaa", "Film"))
                .withConnectivity(new Connectivity(
                        6881, true, false, Connectivity.Mapping.MAPPED, true, true, true, "127.0.0.1"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> {
                f.settings.set(Settings.CONN_INTERFACE, "127.0.0.1");
                f.settings.set(Settings.CONN_EXIT_CHECK, "true");
                f.settings.set(Settings.CONN_EXIT_CHECK_URL, base(exit) + "/ip");
            });
            Fx.waitUntil("the port said", () -> f.window.networkStatusText().contains("6881"));
            Fx.waitUntil("the leak pill", () -> !f.window.networkStatusText().contains("leak=\"\""));
            f.action("leak-check");
            f.action("leak-card");
            // A click on each entry of the bar does what it says.
            for (String style : List.of(
                    "network-status", "alt-speed", "status-message", "error-badge", "update-badge", "leak-pill")) {
                Fx.run(() -> {
                    for (Node n : f.stage.getScene().getRoot().lookupAll("." + style)) {
                        if (n.getOnMouseClicked() != null) n.getOnMouseClicked().handle(null);
                    }
                });
                Fx.pulse();
            }
            assertTrue(f.opened.stream().anyMatch(u -> u.startsWith("file:")), f.opened.toString());
            f.action("settings-close");
        } finally {
            exit.stop(0);
        }
    }

    @Test
    void theSceneChordsRunTheirActions(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            // The window's own scene chords; the menus' are the scene's too, and Quit would end the toolkit.
            List<String> ids = new java.util.ArrayList<>(List.of(
                    "filter-find",
                    "filter",
                    "palette",
                    "event-log",
                    "statistics",
                    "message-log",
                    "add-clipboard",
                    "set-category"));
            for (View v : View.values()) ids.add(Chords.viewName(v));
            for (String id : ids) {
                Runnable chord =
                        Fx.call(() -> f.stage.getScene().getAccelerators().get(Chords.of(id)));
                assertTrue(chord != null, id);
                // Posted: a chord may open a dialog that waits for an answer.
                javafx.application.Platform.runLater(chord);
                Fx.pulse();
                f.cancelDialogs();
                Fx.run(() -> f.window.runForCapture("transfers"));
            }
            // Shortcut+Z, the undo, is a scene filter that stands aside in a text field.
            f.action("table-key:ESCAPE");
            f.action("focus-key:" + MainWindowCommandsTest.SHORTCUT + "+Z");
            assertEquals(tr("status.undo.nothing"), f.status());
        }
    }
}
