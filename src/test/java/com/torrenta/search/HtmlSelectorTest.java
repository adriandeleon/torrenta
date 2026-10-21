package com.torrenta.search;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parser and the selector engine of our own, on tidy markup and on the real-world kind. */
class HtmlSelectorTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = HtmlSelectorTest.class.getResourceAsStream("/com/torrenta/search/declarative/" + name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> texts(List<HtmlNode> nodes) {
        return nodes.stream().map(HtmlNode::text).toList();
    }

    @Test
    void tagsClassesIdsAttributesAndCombinators() {
        HtmlNode doc = HtmlParser.parse(
                "<div id=\"main\" class=\"wrap big\"><p class=\"x\">one</p><span><p class=\"x y\">two</p></span>"
                        + "<a href=\"https://a/b.torrent\" data-n='3'>link</a><a href=\"magnet:?xt=1\">m</a></div><p>outside</p>");
        assertEquals(List.of("one", "two", "outside"), texts(doc.select("p")));
        assertEquals(List.of("one", "two"), texts(doc.select("div p")), "descendant");
        assertEquals(List.of("one"), texts(doc.select("div > p")), "child");
        assertEquals(List.of("two"), texts(doc.select("span > p.x")));
        assertEquals(List.of("one", "two"), texts(doc.select(".x")));
        assertEquals(List.of("two"), texts(doc.select(".x.y")));
        assertEquals(List.of("one", "two"), texts(doc.select("#main .x")));
        assertEquals(List.of("one", "two"), texts(doc.select("div.wrap.big p")));
        assertEquals(List.of("link", "m"), texts(doc.select("a[href]")));
        assertEquals(List.of("link"), texts(doc.select("a[href$=.torrent]")));
        assertEquals(List.of("m"), texts(doc.select("a[href^=\"magnet:\"]")));
        assertEquals(List.of("link"), texts(doc.select("a[href*='//a/']")));
        assertEquals(List.of("link"), texts(doc.select("[data-n=3]")));
        assertEquals(List.of("one", "two"), texts(doc.select("div[class~=big] p")));
        assertEquals(List.of("one", "two"), texts(doc.select("*.x")));
        assertEquals(List.of("link"), texts(doc.select("a:contains(LINK)")), "case-insensitive");
        assertEquals("one", doc.selectFirst("p").orElseThrow().text());
        assertTrue(doc.selectFirst("table").isEmpty());
        assertEquals("https://a/b.torrent", doc.selectFirst("a").orElseThrow().attr("href"));
        assertEquals("", doc.selectFirst("a").orElseThrow().attr("nothing"));
    }

    @Test
    void positionalPseudoClasses() {
        HtmlNode doc = HtmlParser.parse("<tr><th>h</th><td>a</td><td>b</td><td>c</td><span>s</span></tr>");
        assertEquals(List.of("a"), texts(doc.select("td:nth-of-type(1)")));
        assertEquals(List.of("b"), texts(doc.select("td:nth-of-type(2)")));
        assertEquals(List.of("c"), texts(doc.select("td:last-of-type")));
        assertEquals(List.of("a"), texts(doc.select("td:first-of-type")));
        assertEquals(List.of("h"), texts(doc.select("tr > :first-child")));
        assertEquals(List.of("s"), texts(doc.select("tr > :last-child")));
        assertEquals(List.of("b"), texts(doc.select("tr > :nth-child(3)")));
        assertEquals(List.of(), texts(doc.select("td:nth-of-type(4)")));
        assertEquals(List.of("s"), texts(doc.select("span:nth-of-type(1)")));
    }

    @Test
    void theScopeIsTheElementAskedNotTheDocument() {
        HtmlNode doc = HtmlParser.parse("<table><tr id=r1><td>1</td></tr><tr id=r2><td>2</td></tr></table>");
        HtmlNode r2 = doc.selectFirst("#r2").orElseThrow();
        assertEquals(List.of("2"), texts(r2.select("td")));
        assertEquals(List.of("2"), texts(r2.select("tr > td")), "the row itself is the parent that matches");
        assertEquals(List.of(), texts(r2.select("table td")), "an ancestor above the scope does not match (as jsoup)…");
        assertEquals(List.of(), texts(r2.select("tr")), "…and the scope itself is never a result");
    }

    @Test
    void selectorsItDoesNotUnderstandSaySo() {
        for (String bad : List.of(
                "",
                "  ",
                "a, b",
                "a + b",
                "a ~ b",
                "td:nth-of-type(x)",
                "td:nth-of-type(0)",
                "td:nope",
                "a[",
                "a[href",
                "a[href=\"x]",
                ".",
                "#",
                "div >",
                "> p",
                "td:nth-of-type")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Selector.parse(bad), bad);
            assertTrue(e.getMessage().startsWith("selector '") || bad.isBlank(), e.getMessage());
        }
        assertEquals("td.x > a", Selector.parse("  td.x > a ").toString());
    }

    @Test
    void aColonInATagNameIsEscaped() {
        HtmlNode doc =
                HtmlParser.parse("<rss><item><torrent:infoHash>abc</torrent:infoHash><title>t</title></item></rss>");
        assertEquals(
                "abc",
                doc.selectFirst("item > torrent\\:infohash").orElseThrow().text());
        assertEquals("t", doc.selectFirst("item title").orElseThrow().text());
    }

    @Test
    void leniencyUnclosedCellsBareAttributesEntitiesAndJunk() throws IOException {
        HtmlNode doc = HtmlParser.parse(fixture("messy-table.html"));
        assertTrue(doc.select("script").isEmpty(), "script contents are dropped, fake rows and all");
        assertTrue(doc.select("style").isEmpty());
        assertEquals("Messy & Co", doc.selectFirst("title").orElseThrow().text());
        List<HtmlNode> rows = doc.select("table.lista tr");
        assertEquals(4, rows.size(), "the header row and three bodies, no tbody invented");
        assertEquals(List.of("Name", "Size", "S", "L"), texts(rows.get(0).select("td")), "unclosed header cells");
        HtmlNode debian = rows.get(1);
        assertEquals(4, debian.select("td").size(), "unclosed cells are closed by the next <td> and the next <tr>");
        assertEquals(
                "debian-13.0.0-amd64-netinst.iso",
                debian.selectFirst("td a").orElseThrow().text());
        assertEquals(
                "Debian \"trixie\"", debian.selectFirst("td a").orElseThrow().attr("title"), "entities in attributes");
        assertEquals(
                "631.0 MB",
                debian.selectFirst("td:nth-of-type(2)").orElseThrow().text(),
                "a non-breaking space stays");
        assertEquals(
                Math.round(631.0 * 1024 * 1024),
                SizeText.parse(
                        debian.selectFirst("td:nth-of-type(2)").orElseThrow().text()));
        assertEquals(
                "1,204", debian.selectFirst("td:nth-of-type(3)").orElseThrow().text());
        assertEquals(
                "/download.php/1/debian.torrent",
                debian.selectFirst("a[href*=download]").orElseThrow().attr("href"));
        assertEquals("Download", debian.selectFirst("img").orElseThrow().attr("alt"));
        assertEquals("dl.gif", debian.selectFirst("img").orElseThrow().attr("src"), "an unquoted value");
        assertEquals("100%", doc.selectFirst("table").orElseThrow().attr("width"));
        assertEquals("even", rows.get(2).attr("class"));
        assertEquals(
                "magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01&dn=ubuntu",
                rows.get(2).selectFirst("a[href^=magnet]").orElseThrow().attr("href"),
                "an unquoted magnet, &amp; decoded");
        assertEquals(
                "Ubuntu 24.04 LTS", rows.get(2).selectFirst("a").orElseThrow().text());
        assertEquals(List.of("one", "two", "three"), texts(doc.select("ul > li")), "unclosed list items");
        assertEquals(2, doc.select("body > p").size(), "a block closes an open paragraph");
        assertEquals("Unclosed paragraph", doc.select("body > p").get(0).text());
        assertEquals(
                "Page 1 of 3 » next", doc.select("body > p").get(1).text(), "entities and a space before the link");
        assertFalse(doc.text().contains("not a row"));
    }

    @Test
    void textCollapsesWhitespaceAndSeparatesBlocks() {
        HtmlNode doc = HtmlParser.parse(
                "<td>\n   9  \n <strong>   seeds </strong></td><td>0</td><p>a<br>b</p><span>x</span><span>y</span>");
        assertEquals("9 seeds", doc.selectFirst("td").orElseThrow().text());
        assertEquals("9 seeds 0 a b xy", doc.text(), "cells and a <br> are words apart; inline spans are not");
        assertEquals(
                "AB&bogus;&amp",
                HtmlParser.decode("&#x41;&#66;&bogus;&amp"),
                "numeric entities decode, unknown and unterminated ones stay");
    }

    @Test
    void endTagsWithNothingOpenAndEofMidTagAreHarmless() {
        HtmlNode doc = HtmlParser.parse("</div></p><td>x</td></br><b>bold<i>both</b>only-i-open?</i><a href='x");
        assertEquals("x", doc.selectFirst("td").orElseThrow().text());
        assertEquals("boldboth", doc.selectFirst("b").orElseThrow().text(), "</b> closes the <i> inside it too");
        assertEquals("x", doc.selectFirst("a").orElseThrow().attr("href"), "an unterminated quote runs to the end");
        assertEquals("", HtmlParser.parse("").text());
        assertEquals("", HtmlParser.parse(null).text());
        assertEquals("a < b", HtmlParser.parse("a < b").text(), "a lone < is text");
        assertEquals(
                "",
                HtmlParser.parse("<!doctype html><!-- c --><?xml ?><![CDATA[ ]]>")
                        .text()
                        .trim());
    }
}
