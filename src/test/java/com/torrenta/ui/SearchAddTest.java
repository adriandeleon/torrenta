package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import com.torrenta.engine.AddRequest;
import com.torrenta.search.SearchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchAddTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:2aa4f5a7e209e54b32803d43670971c4c8caaa05&dn=debian.iso";

    private static SearchResult result(SearchResult.Link link) {
        return new SearchResult("Debian 12.7.0", 1, 5, 0, null, link, "", "Prowlarr", "");
    }

    @Test
    void aMagnetIsTheMagnetSourceAndFetchesNothing() throws IOException {
        List<URI> fetched = new java.util.ArrayList<>();
        AddRequest.Source source = SearchAdd.sourceOf(result(new SearchResult.Link.Magnet(MAGNET)), url -> {
            fetched.add(url);
            return new byte[0];
        });
        assertEquals(new AddRequest.Magnet(MAGNET), source);
        assertEquals(List.of(), fetched);
        assertEquals("", SearchAdd.fetchingText(result(new SearchResult.Link.Magnet(MAGNET))));
        assertEquals("Copy Magnet Link", SearchAdd.copyLabel(result(new SearchResult.Link.Magnet(MAGNET))));
    }

    @Test
    void aTorrentLinkIsFetchedIntoTheFileSourceWithTheUrlAsItsOrigin() throws IOException {
        String url = "http://host:9696/5/download?apikey=abc&link=xyz";
        byte[] bytes = "d8:announce0:e".getBytes();
        SearchResult r = result(new SearchResult.Link.TorrentUrl(url));
        AddRequest.Source source = SearchAdd.sourceOf(r, u -> {
            assertEquals(URI.create(url), u);
            return bytes;
        });
        AddRequest.TorrentFile file = assertInstanceOf(AddRequest.TorrentFile.class, source);
        assertArrayEquals(bytes, file.bytes());
        assertEquals(url, file.origin());
        assertEquals("Fetching Debian 12.7.0…", SearchAdd.fetchingText(r));
        assertEquals("Copy Link", SearchAdd.copyLabel(r));
    }

    @Test
    void aFetchThatFailsIsTheExceptionNotASource() {
        SearchResult r = result(new SearchResult.Link.TorrentUrl("http://host/x.torrent"));
        IOException failure = assertThrows(
                IOException.class,
                () -> SearchAdd.sourceOf(r, u -> {
                    throw new IOException("the server answered 404");
                }));
        assertEquals("the server answered 404", failure.getMessage());
        SearchResult odd = result(new SearchResult.Link.TorrentUrl("http://host/a b.torrent"));
        assertThrows(IOException.class, () -> SearchAdd.sourceOf(odd, u -> new byte[0]), "not a URI");
    }
}
