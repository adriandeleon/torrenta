package com.torrenta.search;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generic provider against a server of ours serving recorded JSON and HTML answers. */
class DeclarativeProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> lastHeaders = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            requests.add(uri.toString());
            lastHeaders.set(exchange.getRequestHeaders());
            String path = uri.getPath();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            byte[] body;
            int status = 200;
            switch (path) {
                case "/advancedsearch.php" ->
                    body = HtmlSelectorTest.fixture("archive-org.json").getBytes(StandardCharsets.UTF_8);
                case "/torrents/" ->
                    body = query.contains("page=2")
                            ? "<table class=\"torrent-table\"><tbody></tbody></table>".getBytes(StandardCharsets.UTF_8)
                            : HtmlSelectorTest.fixture("linuxtracker.html").getBytes(StandardCharsets.UTF_8);
                case "/rss.xml" ->
                    body = HtmlSelectorTest.fixture("academictorrents.xml").getBytes(StandardCharsets.UTF_8);
                case "/messy" ->
                    body = HtmlSelectorTest.fixture("messy-table.html").getBytes(StandardCharsets.UTF_8);
                case "/limited" -> {
                    exchange.getResponseHeaders().add("Retry-After", "30");
                    status = 429;
                    body = new byte[0];
                }
                case "/forbidden" -> {
                    status = 403;
                    body = new byte[0];
                }
                case "/missing" -> {
                    status = 404;
                    body = new byte[0];
                }
                default -> body = "<p>nothing</p>".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** A shipped example pointed at the local server. */
    private DeclarativeProvider example(String name, String host) throws DefinitionException {
        String json = com.torrenta.store.DefinitionStore.example(name).replace(host, base);
        return new DeclarativeProvider(Definition.parse(json, name), TorznabProvider.defaultClient(), () -> NOW);
    }

    private DeclarativeProvider of(String json) throws DefinitionException {
        return new DeclarativeProvider(Definition.parse(json, "t.json"), TorznabProvider.defaultClient(), () -> NOW);
    }

    @Test
    void aJsonApiThroughTheInternetArchiveExample() throws Exception {
        DeclarativeProvider ia = example("archive-org.json", "https://archive.org");
        assertEquals("Internet Archive", ia.name());
        List<SearchResult> results = ia.search("debian", SearchCategory.SOFTWARE);
        assertEquals(1, requests.size());
        String asked = requests.get(0);
        assertTrue(
                asked.contains("q=debian+AND+mediatype%3Asoftware"),
                "the query and the category's value, encoded: " + asked);
        assertTrue(asked.contains("output=json"), asked);
        assertTrue(lastHeaders.get().getFirst("User-Agent").startsWith("Torrenta/"), "the app's User-Agent");
        assertEquals(5, results.size());
        SearchResult first = results.get(0);
        assertEquals("remastersys-debian-basix", first.name());
        assertEquals(1314439L, first.size());
        assertEquals(-1, first.seeds(), "the API says nothing of seeds");
        assertEquals(-1, first.leechers());
        assertEquals(Instant.parse("2022-10-03T12:35:22Z"), first.published());
        assertEquals(
                new SearchResult.Link.TorrentUrl(
                        base + "/download/remastersys-debian-basix/remastersys-debian-basix_archive.torrent"),
                first.link(),
                "the template filled from the identifier (the host swapped for the test server's)");
        assertEquals(base + "/details/remastersys-debian-basix", first.detailUrl());
        assertEquals("", first.infoHash());
        assertEquals("Internet Archive", first.provider());
        assertEquals(
                "Complete vGPU 16.4 package for Citrix XenServer 8 including supported guest drivers",
                results.get(2).name());
        assertEquals(1966299327L, results.get(2).size());

        requests.clear();
        ia.search("plan 9", SearchCategory.ALL);
        assertTrue(requests.get(0).contains("q=plan+9&fl"), "All sends the default, empty: " + requests.get(0));
    }

    @Test
    void anHtmlListingThroughTheLinuxTrackerExample() throws Exception {
        DeclarativeProvider lt = example("linuxtracker.json", "https://linuxtracker.org");
        List<SearchResult> results = lt.search("debian", SearchCategory.ALL);
        assertEquals(List.of("/torrents/?q=debian"), requests, "one page by default");
        assertEquals(4, results.size(), "the header row has no name link and is skipped");
        SearchResult first = results.get(0);
        assertEquals("debian-13.7.0-arm64-STICK16GB-1.iso", first.name());
        assertEquals("b7852b7fa31d85b83a4d705143ac077d00d5956d", first.infoHash(), "from the detail link, by regex");
        assertEquals(
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:b7852b7fa31d85b83a4d705143ac077d00d5956d"
                        + "&dn=debian-13.7.0-arm64-STICK16GB-1.iso"),
                first.link(),
                "no magnet or .torrent on the page: a magnet is made of the hash");
        assertEquals(
                base + "/torrents/b7852b7fa31d85b83a4d705143ac077d00d5956d/",
                first.detailUrl(),
                "resolved against the page");
        assertEquals(Math.round(14.9 * 1024 * 1024 * 1024), first.size());
        assertEquals(9, first.seeds());
        assertEquals(0, first.leechers());
        assertEquals(Instant.parse("2026-09-13T00:00:00Z"), first.published(), "through the definition's format");
        assertEquals("LinuxTracker", first.provider());
    }

    @Test
    void anRssFeedFilteredLocallyThroughTheAcademicTorrentsExample() throws Exception {
        DeclarativeProvider at = example("academictorrents.json", "https://academictorrents.com");
        List<SearchResult> all = at.search("e", SearchCategory.ALL);
        assertEquals(List.of("/rss.xml"), requests, "the query is not sent: the feed cannot be asked");
        assertEquals(5, all.size(), "every item has an 'e' in its title");
        SearchResult mit = at.search("MIT finance", SearchCategory.ALL).get(0);
        assertEquals(1, at.search("mit FINANCE", SearchCategory.ALL).size(), "every word, any case");
        assertEquals(
                "Topics in Mathematics with Applications in Finance (MIT 18.642) - Video lectures 2024", mit.name());
        assertEquals("e53507bbf0bc496e40ea17ebdf132eec35e560be", mit.infoHash());
        assertEquals(
                new SearchResult.Link.TorrentUrl(base + "/download/e53507bbf0bc496e40ea17ebdf132eec35e560be.torrent"),
                mit.link());
        assertEquals(3770679296L, mit.size());
        assertEquals(
                "https://academictorrents.com/details/e53507bbf0bc496e40ea17ebdf132eec35e560be",
                mit.detailUrl(),
                "RSS's <link> has content (and is absolute in the feed)");
        assertNull(mit.published());
        assertTrue(at.search("nothing like this", SearchCategory.ALL).isEmpty());
    }

    private static final String MESSY = """
            { "id": "messy", "name": "Messy", "type": "html",
              "search": { "url": "BASE/messy?q={query}&cat={category}", "page": "BASE/messy?q={query}&page={page}", "pages": 3, "delayMs": 10 },
              "headers": { "User-Agent": "Mozilla/5.0 (compatible)", "X-Test": "yes" },
              "categories": { "default": "all", "Software": "sw" },
              "fields": {
                "rows": "table.lista tr",
                "name": "td:nth-of-type(1) a:first-of-type",
                "size": "td:nth-of-type(2)",
                "seeds": "td:nth-of-type(3)",
                "leechers": "td:nth-of-type(4)",
                "detailUrl": { "selector": "td a", "attr": "href" },
                "torrentUrl": { "selector": "a[href*=download]", "attr": "href" },
                "magnet": { "selector": "a[href^=magnet]", "attr": "href" },
                "id": { "selector": "td a", "attr": "href", "regex": "id=(\\\\d+)" },
                "comments": { "template": "BASE/comments/{id}" }
              } }
            """;

    @Test
    void aMessyTableHeadersPagesAndMissingFields() throws Exception {
        DeclarativeProvider messy = of(MESSY.replace("BASE", base));
        List<SearchResult> results = messy.search("debian 13", SearchCategory.SOFTWARE);
        assertEquals(
                List.of("/messy?q=debian+13&cat=sw", "/messy?q=debian+13&page=2", "/messy?q=debian+13&page=3"),
                requests,
                "the first page's URL, then the page template; every page the same answer here");
        assertEquals(
                "Mozilla/5.0 (compatible)", lastHeaders.get().getFirst("User-Agent"), "the definition's own agent");
        assertEquals("yes", lastHeaders.get().getFirst("X-Test"));
        assertEquals(6, results.size(), "two rows a page (the header and the linkless row skipped), three pages");
        SearchResult debian = results.get(0);
        assertEquals("debian-13.0.0-amd64-netinst.iso", debian.name());
        assertEquals(
                new SearchResult.Link.TorrentUrl(base + "/download.php/1/debian.torrent"),
                debian.link(),
                "relative, resolved");
        assertEquals(Math.round(631.0 * 1024 * 1024), debian.size(), "through a non-breaking space");
        assertEquals(1204, debian.seeds());
        assertEquals(37, debian.leechers());
        assertEquals(base + "/details.php?id=1", debian.detailUrl());
        assertNull(debian.published(), "no date field: null, not a failure");
        SearchResult ubuntu = results.get(1);
        assertEquals("Ubuntu 24.04 LTS", ubuntu.name());
        assertEquals(
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01&dn=ubuntu"),
                ubuntu.link(),
                "a magnet wins over a .torrent");
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", ubuntu.infoHash(), "read off the magnet");
        assertEquals(-1, ubuntu.leechers(), "a dash is not a count");
    }

    @Test
    void pagingStopsAtAnEmptyPage() throws Exception {
        DeclarativeProvider lt = of("""
                { "id": "lt", "name": "LT", "type": "html",
                  "search": { "url": "BASE/torrents/?q={query}", "page": "BASE/torrents/?q={query}&page={page}", "pages": 5 },
                  "fields": { "rows": "table.torrent-table tbody tr", "name": "a.torrent-name",
                              "infoHash": { "selector": "a.torrent-name", "attr": "href", "regex": "([0-9a-f]{40})" } } }
                """.replace("BASE", base));
        assertEquals(4, lt.search("x", SearchCategory.ALL).size());
        assertEquals(
                List.of("/torrents/?q=x", "/torrents/?q=x&page=2"),
                requests,
                "page 2 is empty, so page 3 is not asked");
    }

    @Test
    void failuresAreSaidInWords() throws Exception {
        assertEquals("rate limited by the server; retry after 30 s", failure("/limited"));
        assertEquals("the server refused the request (403)", failure("/forbidden"));
        assertEquals("the server answered 404", failure("/missing"));
        assertEquals("could not connect", failure("http://127.0.0.1:1/x"));
        SearchException notJson = assertThrows(
                SearchException.class, () -> of("""
                { "id": "j", "name": "J", "type": "json", "search": { "url": "BASE/messy?q={query}" },
                  "fields": { "rows": "/items", "name": "/n", "magnet": "/m" } }
                """.replace("BASE", base)).search("q", SearchCategory.ALL));
        assertTrue(notJson.getMessage().startsWith("the answer is not JSON"), notJson.getMessage());
        assertTrue(
                of("""
                { "id": "h", "name": "H", "type": "html", "search": { "url": "BASE/anything?q={query}" },
                  "fields": { "rows": "table tr", "name": "a", "magnet": { "selector": "a", "attr": "href" } } }
                """.replace("BASE", base)).search("q", SearchCategory.ALL).isEmpty(),
                "no rows is no results, not a failure");
    }

    private String failure(String path) throws DefinitionException {
        String url = path.startsWith("http") ? path : base + path;
        DeclarativeProvider p = of("""
                { "id": "f", "name": "F", "type": "html", "search": { "url": "URL?q={query}" },
                  "fields": { "rows": "tr", "name": "a", "magnet": { "selector": "a", "attr": "href" } } }
                """.replace("URL", url));
        return assertThrows(SearchException.class, () -> p.search("q", SearchCategory.ALL))
                .getMessage();
    }

    @Test
    void theUrlAndTheExtractionArePure() throws Exception {
        Definition d = Definition.parse(MESSY.replace("BASE", "https://t.test"), "messy.json");
        assertEquals(
                URI.create("https://t.test/messy?q=a%26b+c&cat=all"),
                DeclarativeProvider.url(d, "a&b c", SearchCategory.ALL, 1));
        assertEquals(
                URI.create("https://t.test/messy?q=a&page=2"), DeclarativeProvider.url(d, "a", SearchCategory.ALL, 2));
        assertEquals(
                URI.create("https://t.test/messy?q=a&cat=sw"),
                DeclarativeProvider.url(d, "a", SearchCategory.SOFTWARE, 1));
        List<SearchResult> rows = DeclarativeProvider.extract(
                d, HtmlSelectorTest.fixture("messy-table.html"), URI.create("https://t.test/messy?q=x"), "x", NOW);
        assertEquals(2, rows.size());
        assertEquals("https://t.test/details.php?id=1", rows.get(0).detailUrl());

        assertEquals(
                "abcdef0123456789abcdef0123456789abcdef01",
                DeclarativeProvider.hash(" ABCDEF0123456789ABCDEF0123456789ABCDEF01 "));
        assertEquals("", DeclarativeProvider.hash("not-a-hash"));
        assertEquals("", DeclarativeProvider.hash(null));
        assertEquals(64, DeclarativeProvider.hash("a".repeat(64)).length(), "a v2 hash");
        assertEquals(
                "https://x/a b".replace(" ", "%20"), DeclarativeProvider.resolve("/a b", URI.create("https://x/p")));
        assertEquals("https://x/p/q", DeclarativeProvider.resolve("q", URI.create("https://x/p/")));
        assertEquals("https://y/z", DeclarativeProvider.resolve("https://y/z", URI.create("https://x/")));
        assertNull(DeclarativeProvider.resolve("ftp://y/z", URI.create("https://x/")));
        assertNull(
                DeclarativeProvider.resolve("magnet:?xt=1", URI.create("https://x/")),
                "a magnet is not a URL to resolve");
        assertNull(DeclarativeProvider.resolve("", URI.create("https://x/")));
        assertNull(DeclarativeProvider.resolve("rel", null));
    }

    @Test
    void aRowWithoutANameOrALinkIsSkippedAndAnInfoHashMakesAMagnet() throws Exception {
        Definition d = Definition.parse("""
                { "id": "j", "name": "J", "type": "json", "search": { "url": "https://j.test/?q={query}" },
                  "fields": { "rows": "/items", "name": "/n", "infoHash": "/h", "size": "/s", "date": "/d", "seeds": "/seeds" } }
                """, "j.json");
        String body = """
                { "items": [
                  { "n": "one", "h": "0123456789abcdef0123456789abcdef01234567", "s": "1.5 GiB", "d": "2 days ago", "seeds": 3 },
                  { "h": "0123456789abcdef0123456789abcdef01234567" },
                  { "n": "no hash" },
                  { "n": "bad hash", "h": "xyz" },
                  { "n": "four", "h": "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", "s": null, "seeds": "many" } ] }
                """;
        List<SearchResult> rows = DeclarativeProvider.extract(d, body, URI.create("https://j.test/?q=x"), "x", NOW);
        assertEquals(
                List.of("one", "four"), rows.stream().map(SearchResult::name).toList());
        assertEquals(
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=one"),
                rows.get(0).link());
        assertEquals(Math.round(1.5 * 1024 * 1024 * 1024), rows.get(0).size());
        assertEquals(NOW.minusSeconds(2 * 86_400), rows.get(0).published());
        assertEquals(3, rows.get(0).seeds());
        assertEquals("abcdefghijklmnopqrstuvwxyz234567", rows.get(1).infoHash(), "the result lower-cases every hash");
        assertEquals(
                "magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&dn=four",
                rows.get(1).link().value(),
                "the magnet keeps base32 upper-case");
        assertEquals(-1, rows.get(1).size());
        assertEquals(-1, rows.get(1).seeds(), "'many' is not a count");
        assertNull(rows.get(1).published());
    }

    @Test
    void templatesFillFromOtherFieldsAndTheQuery() throws Exception {
        Definition d = Definition.parse("""
                { "id": "j", "name": "J", "type": "json", "search": { "url": "https://j.test/?q={query}" },
                  "fields": { "rows": "", "name": "/n", "slug": "/slug",
                              "torrentUrl": { "template": "https://j.test/dl/{slug}.torrent?from={query}" },
                              "detailUrl": { "template": "https://j.test/t/{missing}" },
                              "missing": "/nowhere",
                              "site": { "value": "J" },
                              "infoHash": { "template": "{slug}-{site}", "regex": "^(.{40})-J$" } } }
                """, "j.json");
        List<SearchResult> rows = DeclarativeProvider.extract(
                d,
                "[{\"n\":\"a\",\"slug\":\"0123456789abcdef0123456789abcdef01234567\"}]",
                URI.create("https://j.test/"),
                "deb ian",
                NOW);
        assertEquals(1, rows.size(), "the rows path may be the root itself");
        assertEquals(
                new SearchResult.Link.TorrentUrl(
                        "https://j.test/dl/0123456789abcdef0123456789abcdef01234567.torrent?from=deb ian"),
                rows.get(0).link());
        assertEquals("", rows.get(0).detailUrl(), "a template with a missing field is left out, not half filled");
        assertEquals(
                "0123456789abcdef0123456789abcdef01234567",
                rows.get(0).infoHash(),
                "a regex applies after the template");
        List<SearchResult> none = new ArrayList<>(DeclarativeProvider.filter(rows, "zzz"));
        assertTrue(none.isEmpty());
    }
}
