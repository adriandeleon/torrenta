package com.torrenta.search;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dialog's validation rule and the record's defaults; the window itself is driven by the capture hook. */
class TorznabEndpointTest {

    @Test
    void theUrlMustBeHttpOrHttpsWithAHost() {
        assertEquals(Optional.empty(), TorznabEndpoint.problem("http://localhost:9696/1/api"));
        assertEquals(Optional.empty(), TorznabEndpoint.problem(" https://prowlarr.example.net/1/api "));
        assertTrue(TorznabEndpoint.problem("").isPresent());
        assertTrue(TorznabEndpoint.problem("   ").isPresent());
        assertEquals(
                Optional.of("The URL must start with http:// or https://"),
                TorznabEndpoint.problem("localhost:9696/1/api"));
        assertEquals(
                Optional.of("The URL must start with http:// or https://"), TorznabEndpoint.problem("ftp://host/api"));
        assertEquals(Optional.of("The URL names no host"), TorznabEndpoint.problem("http:///api"));
        assertTrue(TorznabEndpoint.problem("http://host:port/api").isPresent(), "a port that is not a number");
        assertEquals(Optional.of("That is not a URL"), TorznabEndpoint.problem("http://ho st/api"));
    }

    @Test
    void theKeyMayBeBlankAndANamelessEntryTakesItsHost() {
        TorznabEndpoint jackett =
                new TorznabEndpoint(" ", " http://nas.local:9117/api/v2.0/indexers/all/results/torznab/api ", "  ");
        assertEquals("nas.local", jackett.name());
        assertEquals("http://nas.local:9117/api/v2.0/indexers/all/results/torznab/api", jackett.url());
        assertEquals("", jackett.apiKey());
        assertEquals("none", jackett.maskedKey());
        assertTrue(jackett.enabled());
        assertEquals(-1, jackett.categories());

        TorznabEndpoint prowlarr = new TorznabEndpoint("Prowlarr", "http://localhost:9696/1/api", "abc123", false, 7);
        assertEquals("••••••••", prowlarr.maskedKey(), "the key is never shown whole in a table");
        assertFalse(prowlarr.enabled());
        assertEquals(7, prowlarr.categories());
    }

    @Test
    void testedFillsInANameAndTheCount() {
        TorznabEndpoint nameless = new TorznabEndpoint("", "http://h/1/api", "k");
        TorznabEndpoint tested = nameless.tested("Prowlarr", 22);
        assertEquals("h", nameless.name(), "the host stands in for a blank name");
        assertEquals("h", tested.name(), "and stays: the host was the name by then");
        assertEquals(22, tested.categories());
        TorznabEndpoint named = new TorznabEndpoint("Mine", "http://h/1/api", "k").tested("Prowlarr", 3);
        assertEquals("Mine", named.name(), "a name given is kept");
    }

    @Test
    void categoriesCarryNewznabNumbers() {
        assertEquals(0, SearchCategory.ALL.newznab());
        assertEquals("", SearchCategory.ALL.queryValue());
        assertEquals(2000, SearchCategory.MOVIES.newznab());
        assertEquals(5000, SearchCategory.TV.newznab());
        assertEquals(3000, SearchCategory.MUSIC.newznab());
        assertEquals(1000, SearchCategory.GAMES.newznab());
        assertEquals(4000, SearchCategory.SOFTWARE.newznab());
        assertEquals(7000, SearchCategory.BOOKS.newznab());
        assertEquals(5070, SearchCategory.ANIME.newznab());
        assertEquals(8000, SearchCategory.OTHER.newznab());
        assertEquals("5070", SearchCategory.ANIME.queryValue());
        assertEquals("Movies", SearchCategory.MOVIES.label());
    }

    @Test
    void aResultsNewznabNumbersNameItsCategory() {
        assertEquals(SearchCategory.MOVIES, SearchCategory.ofNewznab(List.of(2040)));
        assertEquals(
                SearchCategory.TV, SearchCategory.ofNewznab(List.of(100_001, 5000)), "an indexer's own is skipped");
        assertEquals(SearchCategory.ANIME, SearchCategory.ofNewznab(List.of(5000, 5070)), "anime wins over TV");
        assertEquals(SearchCategory.GAMES, SearchCategory.ofNewznab(List.of(1000, 4000)), "else the first");
        assertEquals(SearchCategory.OTHER, SearchCategory.ofNewznab(List.of(8010)));
        assertNull(SearchCategory.ofNewznab(List.of(6000)), "a block with no chip");
        assertNull(SearchCategory.ofNewznab(List.of(0, 999, 9000)));
        assertNull(SearchCategory.ofNewznab(List.of()));

        assertEquals(SearchCategory.TV, SearchCategory.named("tv"));
        assertEquals(SearchCategory.BOOKS, SearchCategory.named("Books"));
        assertNull(SearchCategory.named("all"), "All is not a category a result has");
        assertNull(SearchCategory.named("podcasts"));
    }

    @Test
    void aResultNormalisesItsHashAndReadsOneOffAMagnet() {
        SearchResult.Link.Magnet magnet =
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:2AA4F5A7E209E54B32803D43670971C4C8CAAA05&dn=x");
        SearchResult fromMagnet = new SearchResult("x", -5, -3, -1, null, magnet, "", "P", null);
        assertEquals("2aa4f5a7e209e54b32803d43670971c4c8caaa05", fromMagnet.infoHash());
        assertEquals("hash:2aa4f5a7e209e54b32803d43670971c4c8caaa05", fromMagnet.identity());
        assertEquals(-1, fromMagnet.size());
        assertEquals(-1, fromMagnet.seeds());
        assertEquals("", fromMagnet.detailUrl());
        assertTrue(fromMagnet.publishedAt().isEmpty());

        SearchResult given = new SearchResult(
                "y", 1, 1, 1, null, new SearchResult.Link.TorrentUrl("https://h/y.torrent"), "ABCDEF", "P", "");
        assertEquals("abcdef", given.infoHash());
        assertThrows(IllegalArgumentException.class, () -> new SearchResult.Link.Magnet("https://not-a-magnet"));
        assertThrows(IllegalArgumentException.class, () -> new SearchResult.Link.TorrentUrl(" "));
        assertThrows(IllegalArgumentException.class, () -> new SearchResult("z", 1, 1, 1, null, null, "", "P", ""));
        assertNull(given.category(), "unknown unless given");
        assertNull(
                new SearchResult("y", 1, 1, 1, null, given.link(), "", "P", "", SearchCategory.ALL).category(),
                "All is no category of a result's");
    }
}
