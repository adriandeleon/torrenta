package com.torrenta.search;

import java.net.URI;
import java.util.List;

import com.torrenta.store.DefinitionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped examples: each parses, is named after its file, and reads the answer its site gave
 * when it was recorded (the fixtures beside the test), so a typo cannot ship and a site change
 * shows up as a fixture that no longer matches.
 */
class ExampleDefinitionsTest {

    @Test
    void everyShippedExampleParsesAndIsNamedAfterItsFile() throws Exception {
        List<String> names = DefinitionStore.exampleNames();
        assertEquals(List.of("archive-org.json", "linuxtracker.json", "academictorrents.json"), names);
        for (String name : names) {
            String text = DefinitionStore.example(name);
            assertNotNull(text, name + " is in the index but not shipped");
            Definition d = Definition.parse(text, name);
            assertEquals(name, d.id() + DefinitionStore.SUFFIX, "the id is the file's stem");
            assertFalse(d.homepage().isEmpty(), name + " names its homepage");
            assertTrue(d.search().url().startsWith("https://"), name + " searches over https");
            DeclarativeProvider.url(d, "debian 12", SearchCategory.SOFTWARE, 1);
            DeclarativeProvider.url(d, "debian 12", SearchCategory.ALL, 2);
        }
    }

    @Test
    void theInternetArchiveExampleReadsItsRecordedAnswer() throws Exception {
        Definition d = Definition.parse(DefinitionStore.example("archive-org.json"), "archive-org.json");
        URI asked = DeclarativeProvider.url(d, "debian", SearchCategory.SOFTWARE, 1);
        assertEquals(
                "https://archive.org/advancedsearch.php?q=debian+AND+mediatype%3Asoftware&fl%5B%5D=identifier"
                        + "&fl%5B%5D=title&fl%5B%5D=item_size&fl%5B%5D=publicdate&rows=50&page=1&output=json",
                asked.toString());
        List<SearchResult> results = DeclarativeProvider.extract(
                d, HtmlSelectorTest.fixture("archive-org.json"), asked, "debian", java.time.Instant.EPOCH);
        assertEquals(5, results.size());
        assertEquals("SquidServers 0.4.9", results.get(4).name());
        assertEquals(96504312L, results.get(4).size());
        assertTrue(results.get(4)
                .link()
                .value()
                .endsWith("/squidservers_0.4.9_amd64/squidservers_0.4.9_amd64_archive.torrent"));
    }

    @Test
    void theLinuxTrackerExampleReadsItsRecordedAnswer() throws Exception {
        Definition d = Definition.parse(DefinitionStore.example("linuxtracker.json"), "linuxtracker.json");
        URI asked = DeclarativeProvider.url(d, "debian", SearchCategory.ALL, 1);
        assertEquals("https://linuxtracker.org/torrents/?q=debian", asked.toString());
        assertEquals(
                "https://linuxtracker.org/torrents/?q=debian&page=2",
                DeclarativeProvider.url(d, "debian", SearchCategory.ALL, 2).toString());
        List<SearchResult> results = DeclarativeProvider.extract(
                d, HtmlSelectorTest.fixture("linuxtracker.html"), asked, "debian", java.time.Instant.EPOCH);
        assertEquals(4, results.size());
        for (SearchResult r : results) {
            assertEquals(40, r.infoHash().length(), r.name());
            assertTrue(r.detailUrl().startsWith("https://linuxtracker.org/torrents/"), r.detailUrl());
            assertTrue(r.size() > 0, r.name());
            assertTrue(r.seeds() >= 0, r.name());
            assertNotNull(r.published(), r.name());
        }
        assertEquals("debian-13.7.0-source-DVD-22.iso", results.get(2).name());
    }

    @Test
    void theAcademicTorrentsExampleReadsItsRecordedFeed() throws Exception {
        Definition d = Definition.parse(DefinitionStore.example("academictorrents.json"), "academictorrents.json");
        assertTrue(d.search().localFilter(), "the site asks not to be scraped; its feed is read and filtered here");
        URI asked = DeclarativeProvider.url(d, "anything", SearchCategory.ALL, 1);
        assertEquals("https://academictorrents.com/rss.xml", asked.toString());
        List<SearchResult> results = DeclarativeProvider.extract(
                d, HtmlSelectorTest.fixture("academictorrents.xml"), asked, "x", java.time.Instant.EPOCH);
        assertEquals(5, results.size());
        for (SearchResult r : results) {
            assertEquals(40, r.infoHash().length(), r.name());
            assertEquals(
                    "https://academictorrents.com/download/" + r.infoHash() + ".torrent",
                    r.link().value());
            assertEquals("https://academictorrents.com/details/" + r.infoHash(), r.detailUrl());
        }
    }
}
