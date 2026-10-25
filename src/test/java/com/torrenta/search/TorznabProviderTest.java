package com.torrenta.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Torznab client against a server of ours serving recorded Jackett and Prowlarr answers. */
class TorznabProviderTest {

    private HttpServer server;
    private URI base;
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");
    private final AtomicReference<String> lastAgent = new AtomicReference<>("");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Prowlarr's shape: one endpoint, the key checked, caps or search by t=.
        server.createContext("/1/api", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            lastQuery.set(query == null ? "" : query);
            lastAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            if (!lastQuery.get().contains("apikey=secret")) {
                respond(exchange, 200, fixture("error.xml"));
            } else if (lastQuery.get().contains("t=caps")) {
                respond(exchange, 200, fixture("prowlarr-caps.xml"));
            } else {
                respond(exchange, 200, fixture("prowlarr-search.xml"));
            }
        });
        // Jackett's shape, without a key.
        server.createContext("/api/v2.0/indexers/all/results/torznab/api", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            respond(exchange, 200, fixture(query.contains("t=caps") ? "jackett-caps.xml" : "jackett-search.xml"));
        });
        server.createContext("/limited", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            respond(exchange, 429, "slow down".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/forbidden", exchange -> respond(exchange, 401, new byte[0]));
        server.createContext("/missing", exchange -> respond(exchange, 404, "no".getBytes(StandardCharsets.UTF_8)));
        server.createContext(
                "/garbage", exchange -> respond(exchange, 200, "this is not xml".getBytes(StandardCharsets.UTF_8)));
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = TorznabProviderTest.class.getResourceAsStream("/com/torrenta/search/" + name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return in.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private TorznabProvider prowlarr(String key) {
        return new TorznabProvider(
                new TorznabEndpoint("Prowlarr", base.resolve("/1/api").toString(), key));
    }

    private TorznabProvider jackett() {
        return new TorznabProvider(new TorznabEndpoint(
                "", base.resolve("/api/v2.0/indexers/all/results/torznab/api").toString(), ""));
    }

    @Test
    void aServerWhoseKeyIsOnAnotherMachineIsNotAsked() {
        TorznabProvider waiting = new TorznabProvider(
                new TorznabEndpoint("Prowlarr", base.resolve("/1/api").toString(), "", true, -1, true));
        lastQuery.set("untouched");
        SearchException e = assertThrows(SearchException.class, () -> waiting.search("debian", SearchCategory.ALL));
        assertEquals(com.torrenta.i18n.Messages.tr("search.error.keyNeeded"), e.getMessage());
        assertThrows(SearchException.class, waiting::capabilities);
        assertEquals("untouched", lastQuery.get(), "no request was made");
    }

    @Test
    void capsSayWhoTheServerIsAndWhatItKnows() throws SearchException {
        TorznabProvider.Capabilities caps = prowlarr("secret").capabilities();
        assertEquals("Prowlarr", caps.title());
        assertEquals(21, caps.categories().size(), "top-level categories and their subcategories");
        assertTrue(caps.categories().contains("Movies/UHD"));
        assertTrue(lastQuery.get().contains("t=caps"), lastQuery.get());
        assertTrue(lastQuery.get().contains("apikey=secret"), lastQuery.get());
        assertTrue(lastAgent.get().startsWith("Torrenta/"), "the app's User-Agent, not Java's: " + lastAgent.get());

        TorznabProvider.Capabilities jackett = jackett().capabilities();
        assertEquals("Jackett", jackett.title());
        assertEquals(6, jackett.categories().size());
        assertEquals("127.0.0.1", jackett().name(), "a nameless endpoint is called by its host");
    }

    @Test
    void aTorznabErrorIsItsDescription() {
        SearchException e =
                assertThrows(SearchException.class, () -> prowlarr("wrong").capabilities());
        assertEquals("Invalid API Key (error 100)", e.getMessage());
        SearchException search =
                assertThrows(SearchException.class, () -> prowlarr("wrong").search("debian", SearchCategory.ALL));
        assertEquals("Invalid API Key (error 100)", search.getMessage());
    }

    @Test
    void searchAsksWithQueryCategoryAndKeyAndReadsTheItems() throws SearchException {
        List<SearchResult> results = prowlarr("secret").search("debian 12.7", SearchCategory.SOFTWARE);
        assertTrue(lastQuery.get().contains("t=search"), lastQuery.get());
        assertTrue(lastQuery.get().contains("q=debian+12.7"), lastQuery.get());
        assertTrue(lastQuery.get().contains("cat=4000"), lastQuery.get());
        assertTrue(lastQuery.get().contains("apikey=secret"), lastQuery.get());

        assertEquals(2, results.size());
        SearchResult netinst = results.get(0);
        assertEquals("Debian 12.7.0 amd64 netinst", netinst.name());
        assertEquals(661651456L, netinst.size());
        assertEquals(210, netinst.seeds());
        assertEquals(12, netinst.leechers(), "leechers given outright wins over peers");
        assertEquals(Instant.parse("2024-08-31T12:00:00Z"), netinst.published());
        assertEquals(
                new SearchResult.Link.TorrentUrl(
                        "http://localhost:9696/5/download?apikey=abc&link=xyz&file=Debian+12.7.0"),
                netinst.link(),
                "no magnet: the enclosure is the .torrent");
        assertEquals("", netinst.infoHash());
        assertEquals("Prowlarr", netinst.provider());
        assertEquals("https://example.test/details/42", netinst.detailUrl());

        SearchResult dvd = results.get(1);
        assertEquals(
                new SearchResult.Link.Magnet(
                        "magnet:?xt=urn:btih:D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C8D9E0&dn=debian-12.7.0-arm64-DVD-1.iso"),
                dvd.link(),
                "a magneturl attribute wins over the enclosure");
        assertEquals("d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0", dvd.infoHash(), "lower-case hex");
        assertEquals(3989831680L, dvd.size(), "no size attribute or element: the enclosure's length");
        assertEquals(6, dvd.leechers(), "peers less seeders");
        assertEquals(Instant.parse("2024-08-31T13:30:00Z"), dvd.published(), "ISO dates are read too");
    }

    @Test
    void jackettItemsReadTheSameWay() throws SearchException {
        List<SearchResult> results = jackett().search("ubuntu", SearchCategory.ALL);
        assertEquals(2, results.size(), "the item with no link of any kind is skipped");
        SearchResult desktop = results.get(0);
        assertEquals("ubuntu-24.04.1-desktop-amd64.iso", desktop.name());
        assertEquals(6114656256L, desktop.size(), "the <size> element when there is no size attribute");
        assertEquals(1532, desktop.seeds());
        assertEquals(69, desktop.leechers());
        assertEquals("2aa4f5a7e209e54b32803d43670971c4c8caaa05", desktop.infoHash());
        assertTrue(desktop.link() instanceof SearchResult.Link.Magnet);
        assertEquals("https://example-indexer.test/torrent/123", desktop.detailUrl());
        assertEquals("127.0.0.1", desktop.provider());

        SearchResult server = results.get(1);
        assertTrue(server.link() instanceof SearchResult.Link.Magnet, "a <link> that is a magnet");
        assertEquals("4e3d2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e", server.infoHash(), "read off the magnet");
        assertEquals(2754981888L, server.size());
        assertEquals("", server.detailUrl(), "a guid that is the magnet itself is no detail page");
    }

    @Test
    void httpFailuresReadAsAStatusLine() {
        assertEquals(
                "rate limited by the server; retry after 30 s",
                assertThrows(SearchException.class, () -> provider("/limited").search("x", SearchCategory.ALL))
                        .getMessage());
        assertEquals(
                "the API key was refused (401)",
                assertThrows(SearchException.class, () -> provider("/forbidden").capabilities())
                        .getMessage());
        assertEquals(
                "the server answered 404",
                assertThrows(SearchException.class, () -> provider("/missing").search("x", SearchCategory.ALL))
                        .getMessage());
        assertEquals(
                "could not read the answer: not Torznab XML",
                assertThrows(SearchException.class, () -> provider("/garbage").search("x", SearchCategory.ALL))
                        .getMessage());
        TorznabProvider nobody = new TorznabProvider(new TorznabEndpoint("Nobody", "http://127.0.0.1:1/api", ""));
        assertEquals(
                "could not connect",
                assertThrows(SearchException.class, () -> nobody.search("x", SearchCategory.ALL))
                        .getMessage());
    }

    private TorznabProvider provider(String path) {
        return new TorznabProvider(new TorznabEndpoint("T", base.resolve(path).toString(), ""));
    }

    @Test
    void urlsAreBuiltFromTheBase() {
        assertEquals(
                "http://h:9696/1/api?t=caps&apikey=k%26y",
                TorznabProvider.capsUri("http://h:9696/1/api", "k&y").toString());
        assertEquals(
                "http://h/api?t=caps",
                TorznabProvider.capsUri("http://h/api ", "").toString());
        assertEquals(
                "http://h/api?t=search&q=ubuntu+24.04&cat=2000&apikey=k",
                TorznabProvider.searchUri("http://h/api", "k", " ubuntu 24.04 ", SearchCategory.MOVIES)
                        .toString());
        assertEquals(
                "http://h/api?x=1&t=search&q=a",
                TorznabProvider.searchUri("http://h/api?x=1", "", "a", SearchCategory.ALL)
                        .toString(),
                "a base with a query already gets & and no cat for All");
    }

    @Test
    void capsWithoutAServerTitleTakeTheIndexerElement() throws Exception {
        try (InputStream in = TorznabProviderTest.class.getResourceAsStream("/com/torrenta/search/indexer-caps.xml")) {
            TorznabProvider.Capabilities caps = TorznabProvider.parseCaps(in);
            assertEquals("Example Indexer", caps.title());
            assertEquals(List.of("PC"), caps.categories());
        }
    }

    @Test
    void datesAreForgiving() {
        assertEquals(
                Instant.parse("2024-08-30T10:11:12Z"), TorznabProvider.parseDate("Fri, 30 Aug 2024 10:11:12 +0000"));
        assertEquals(
                Instant.parse("2024-08-30T08:11:12Z"), TorznabProvider.parseDate("Fri, 30 Aug 2024 10:11:12 +0200"));
        assertEquals(Instant.parse("2024-08-30T10:11:12Z"), TorznabProvider.parseDate("2024-08-30T10:11:12Z"));
        assertNull(TorznabProvider.parseDate("yesterday"));
        assertNull(TorznabProvider.parseDate(""));
    }
}
