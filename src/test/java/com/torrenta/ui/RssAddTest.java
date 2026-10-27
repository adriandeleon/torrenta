package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import com.torrenta.engine.AddRequest;
import com.torrenta.rss.Article;
import com.torrenta.session.RuleAdd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RssAddTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:2aa4f5a7e209e54b32803d43670971c4c8caaa05&dn=debian.iso";

    private static Article article(String torrent) {
        return new Article("f", "g", "Debian 12.7.0", "https://x/page", torrent, null, "", false);
    }

    @Test
    void aMagnetIsTheMagnetSourceAndFetchesNothing() throws IOException {
        List<URI> fetched = new java.util.ArrayList<>();
        AddRequest.Source source = RuleAdd.sourceOf(article(MAGNET), url -> {
            fetched.add(url);
            return new byte[0];
        });
        assertEquals(new AddRequest.Magnet(MAGNET), source);
        assertEquals(List.of(), fetched);
        assertEquals("", RssAdd.fetchingText(article(MAGNET)));
        assertEquals("Copy Magnet Link", RssAdd.copyLabel(article(MAGNET)));
        assertEquals(MAGNET, RssAdd.copyText(article(MAGNET)));
    }

    @Test
    void aTorrentLinkIsFetchedIntoTheFileSourceWithTheUrlAsItsOrigin() throws IOException {
        String url = "https://tracker.example/download/42.torrent?passkey=abc";
        byte[] bytes = "d8:announce0:e".getBytes();
        AddRequest.Source source = RuleAdd.sourceOf(article(url), u -> {
            assertEquals(URI.create(url), u);
            return bytes;
        });
        AddRequest.TorrentFile file = assertInstanceOf(AddRequest.TorrentFile.class, source);
        assertArrayEquals(bytes, file.bytes());
        assertEquals(url, file.origin());
        assertEquals("Fetching Debian 12.7.0…", RssAdd.fetchingText(article(url)));
        assertEquals("Copy Torrent Link", RssAdd.copyLabel(article(url)));
    }

    @Test
    void anArticleWithoutATorrentIsRefusedAndCopiesItsPage() {
        Article none = article("");
        IOException e = assertThrows(IOException.class, () -> RuleAdd.sourceOf(none, u -> new byte[0]));
        assertEquals("No torrent in Debian 12.7.0", e.getMessage());
        assertEquals("", RssAdd.fetchingText(none));
        assertEquals("Copy Link", RssAdd.copyLabel(none));
        assertEquals("https://x/page", RssAdd.copyText(none));
        IOException failure = assertThrows(
                IOException.class,
                () -> RuleAdd.sourceOf(article("https://x/a.torrent"), u -> {
                    throw new IOException("the server answered 404");
                }));
        assertEquals("the server answered 404", failure.getMessage());
    }
}
