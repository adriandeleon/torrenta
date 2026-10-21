package com.torrenta.search;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionTest {

    static final String HTML = """
            {
              "id": "tracker",
              "name": "A Tracker",
              "homepage": "https://tracker.test",
              "type": "html",
              "search": {
                "url": "https://tracker.test/search?q={query}&cat={category}",
                "page": "https://tracker.test/search?q={query}&cat={category}&page={page}",
                "pages": 2,
                "delayMs": 500
              },
              "headers": { "User-Agent": "Mozilla/5.0", "Accept-Language": "en" },
              "categories": { "default": "0", "Movies": "201", "software": "300" },
              "fields": {
                "rows": "table.lista tr",
                "name": "td:nth-of-type(1) a",
                "size": "td:nth-of-type(2)",
                "seeds": { "selector": "td:nth-of-type(3)" },
                "leechers": "td:nth-of-type(4)",
                "detailUrl": { "selector": "td:nth-of-type(1) a", "attr": "href" },
                "torrentUrl": { "selector": "a[href*=download]", "attr": "href" },
                "magnet": { "selector": "a[href^=magnet]", "attr": "href" },
                "infoHash": { "selector": "a[href^=magnet]", "attr": "href", "regex": "btih:([0-9A-Fa-f]{40})" },
                "date": { "selector": "td:nth-of-type(5)", "format": "dd.MM.uuuu" },
                "site": { "value": "tracker" },
                "id": { "selector": "td a", "attr": "href", "regex": "id=(\\\\d+)" },
                "comments": { "template": "https://tracker.test/comments?id={id}&from={site}&q={query}" }
              }
            }
            """;

    static final String JSON = """
            { "id": "api", "name": "An API", "type": "json",
              "search": { "url": "https://api.test/q?s={query}" },
              "fields": { "rows": "/data/items", "name": "/title", "torrentUrl": "/links/torrent" } }
            """;

    @Test
    void aGoodDefinitionReadsBack() throws DefinitionException {
        Definition d = Definition.parse(HTML, "tracker.json");
        assertEquals("tracker", d.id());
        assertEquals("A Tracker", d.name());
        assertEquals("https://tracker.test", d.homepage());
        assertEquals(Definition.Type.HTML, d.type());
        assertEquals(
                "https://tracker.test/search?q={query}&cat={category}",
                d.search().url());
        assertEquals(2, d.search().pages());
        assertEquals(500, d.search().delayMs());
        assertTrue(!d.search().localFilter());
        assertEquals(Map.of("User-Agent", "Mozilla/5.0", "Accept-Language", "en"), d.headers());
        assertEquals("201", d.categoryValue(SearchCategory.MOVIES));
        assertEquals("300", d.categoryValue(SearchCategory.SOFTWARE), "names match whatever the case");
        assertEquals("0", d.categoryValue(SearchCategory.TV), "the default for the rest");
        assertEquals("0", d.categoryValue(null));
        assertEquals("table.lista tr", d.rows().path());
        assertEquals(Definition.Field.of("td:nth-of-type(1) a"), d.fields().get("name"));
        assertEquals("href", d.fields().get("detailUrl").attr());
        assertEquals("btih:([0-9A-Fa-f]{40})", d.fields().get("infoHash").regex());
        assertEquals("dd.MM.uuuu", d.fields().get("date").format());
        assertEquals("tracker", d.fields().get("site").value());
        assertTrue(d.fields().get("site").isConstant());
        assertTrue(d.fields().get("comments").isTemplate());
        assertEquals(13, d.fields().size(), "auxiliary fields are kept beside the known ones");

        Definition j = Definition.parse(JSON, "api.json");
        assertEquals(Definition.Type.JSON, j.type());
        assertEquals("", j.homepage());
        assertEquals(1, j.search().pages());
        assertEquals(0, j.search().delayMs());
        assertEquals("", j.categoryValue(SearchCategory.MOVIES), "no categories: empty");
        assertEquals("/data/items", j.rows().path());
    }

    private static String problem(String json) {
        DefinitionException e = assertThrows(DefinitionException.class, () -> Definition.parse(json, "f.json"));
        assertEquals("f.json", e.file());
        assertTrue(e.getMessage().startsWith("f.json: "), e.getMessage());
        return e.field() + ": "
                + e.getMessage()
                        .substring("f.json: ".length()
                                + (e.field().isEmpty() ? 0 : e.field().length() + 2));
    }

    private static String without(String key) {
        return JSON.replace("\"" + key + "\":", "\"" + key + "_gone\":");
    }

    @Test
    void whatIsMissingOrWrongIsNamedWithTheFile() {
        assertTrue(problem("{ not json").startsWith(": not JSON"), "unreadable JSON");
        assertTrue(problem("[]").startsWith(": must be an object"));
        assertTrue(
                problem(without("id")).contains("id_gone: is not a key here"),
                "a typo in a key is caught, not ignored");
        assertEquals("type: must be \"json\" or \"html\", not \"xml\"", problem(JSON.replace("\"json\"", "\"xml\"")));
        assertEquals(
                "id: must be lower-case letters, digits, '.', '_' or '-'",
                problem(JSON.replace("\"api\"", "\"An API\"")));
        assertEquals("name: is empty", problem(JSON.replace("\"An API\"", "\"\"")));
        assertEquals(
                "search.url: must contain {query} (or set search.localFilter)", problem(JSON.replace("{query}", "x")));
        assertEquals(
                "search.url: must start with http:// or https://", problem(JSON.replace("https://api", "ftp://api")));
        assertEquals(
                "search.pages: must be between 1 and 10",
                problem(JSON.replace("\"search\": { ", "\"search\": { \"pages\": 11, ")));
        assertEquals(
                "search.page: is needed when search.pages is more than 1",
                problem(JSON.replace("\"search\": { ", "\"search\": { \"pages\": 2, ")));
        assertEquals(
                "search.page: must contain {page}",
                problem(JSON.replace("\"search\": { ", "\"search\": { \"page\": \"https://api.test/p\", ")));
        assertEquals(
                "search.delayMs: must be between 0 and 10000",
                problem(JSON.replace("\"search\": { ", "\"search\": { \"delayMs\": -1, ")));
        assertEquals(
                "search.delayMs: must be a whole number",
                problem(JSON.replace("\"search\": { ", "\"search\": { \"delayMs\": \"1s\", ")));
        assertEquals(
                "search.localFilter: must be true or false",
                problem(JSON.replace("\"search\": { ", "\"search\": { \"localFilter\": \"yes\", ")));
        assertEquals(
                "fields.rows: is missing",
                problem(JSON.replace("\"rows\"", "\"rowz\"").replace("\"rowz\": \"/data/items\",", "")));
        assertEquals("fields.name: is missing", problem(JSON.replace("\"name\": \"/title\",", "")));
        assertEquals(
                "fields: needs one of magnet, torrentUrl or infoHash",
                problem(JSON.replace("\"torrentUrl\"", "\"detailUrl\"")));
        assertEquals(
                "fields.torrentUrl.attr: is for html definitions",
                problem(JSON.replace(
                        "\"torrentUrl\": \"/links/torrent\"",
                        "\"torrentUrl\": { \"path\": \"/l\", \"attr\": \"href\" }")));
        assertEquals(
                "fields.torrentUrl: needs a path, a template or a value",
                problem(JSON.replace("\"torrentUrl\": \"/links/torrent\"", "\"torrentUrl\": { \"regex\": \"x\" }")));
        assertEquals("fields.torrentUrl: is empty", problem(JSON.replace("\"/links/torrent\"", "\" \"")));
        assertTrue(problem(JSON.replace(
                        "\"torrentUrl\": \"/links/torrent\"", "\"torrentUrl\": { \"path\": \"/l\", \"regex\": \"(\" }"))
                .startsWith("fields.torrentUrl.regex: does not compile"));
        assertEquals(
                "fields.torrentUrl.template: {nothing} names no field",
                problem(JSON.replace(
                        "\"torrentUrl\": \"/links/torrent\"",
                        "\"torrentUrl\": { \"template\": \"https://x/{nothing}\" }")));
        assertEquals(
                "fields.torrentUrl: has both a template and a path",
                problem(JSON.replace(
                        "\"torrentUrl\": \"/links/torrent\"",
                        "\"torrentUrl\": { \"path\": \"/l\", \"template\": \"x\" }")));
        assertEquals(
                "fields.name.format: is for the date field",
                problem(JSON.replace("\"name\": \"/title\"", "\"name\": { \"path\": \"/t\", \"format\": \"uuuu\" }")));
        assertEquals(
                "fields.weird name: a field's name is letters, digits and '_'",
                problem(JSON.replace("\"name\": \"/title\"", "\"name\": \"/title\", \"weird name\": \"/x\"")));
        assertEquals(
                "fields.rows: is a selector or path alone",
                problem(JSON.replace("\"rows\": \"/data/items\"", "\"rows\": { \"path\": \"/d\", \"regex\": \"x\" }")));
        assertEquals(
                "categories.Films: is not a category; the categories are All, Movies, TV, Music, Games, Software, Books, Anime, Other",
                problem(JSON.replace("\"fields\"", "\"categories\": { \"Films\": \"1\" }, \"fields\"")));
        assertEquals(
                "headers.X: must be a string",
                problem(JSON.replace("\"fields\"", "\"headers\": { \"X\": 1 }, \"fields\"")));
        assertTrue(
                problem(JSON.replace("\"fields\"", "\"extra\": 1, \"fields\"")).startsWith("extra: is not a key here"));
        assertTrue(problem(JSON.replace("\"search\": { ", "\"search\": { \"method\": \"POST\", "))
                .startsWith("search.method: is not a key here"));
    }

    @Test
    void htmlSelectorsAreCheckedWhenParsed() {
        String bad = HTML.replace("\"size\": \"td:nth-of-type(2)\"", "\"size\": \"td, span\"");
        DefinitionException e = assertThrows(DefinitionException.class, () -> Definition.parse(bad, "tracker.json"));
        assertEquals("fields.size", e.field());
        assertTrue(e.getMessage().contains("selector lists"), e.getMessage());
        String badDate = HTML.replace("\"dd.MM.uuuu\"", "\"not [a pattern\"");
        assertEquals(
                "fields.date.format",
                assertThrows(DefinitionException.class, () -> Definition.parse(badDate, "t.json"))
                        .field());
        String nested =
                HTML.replace("\"site\": { \"value\": \"tracker\" }", "\"site\": { \"template\": \"{comments}\" }");
        assertEquals(
                "fields.site.template",
                assertThrows(DefinitionException.class, () -> Definition.parse(nested, "t.json"))
                        .field(),
                "a template that names a template");
    }

    @Test
    void aLocalFilterFeedNeedsNoQueryInItsUrl() throws DefinitionException {
        Definition d = Definition.parse(
                JSON.replace("https://api.test/q?s={query}", "https://api.test/latest.json")
                        .replace("\"search\": { ", "\"search\": { \"localFilter\": true, "),
                "api.json");
        assertTrue(d.search().localFilter());
        assertEquals("https://api.test/latest.json", d.search().url());
    }
}
