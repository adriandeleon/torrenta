package com.torrenta.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputsTest {

    @Test
    void threeKinds() {
        assertEquals(Inputs.Kind.MAGNET, Inputs.classify("magnet:?xt=urn:btih:abc"));
        assertEquals(Inputs.Kind.MAGNET, Inputs.classify("  MAGNET:?xt=urn:btih:abc"));
        assertEquals(Inputs.Kind.URL, Inputs.classify("https://example.org/x.torrent"));
        assertEquals(Inputs.Kind.URL, Inputs.classify("http://example.org/x"));
        assertEquals(Inputs.Kind.URL, Inputs.classify("FTP://example.org/x.iso"));
        assertEquals(Inputs.Kind.URL, Inputs.classify("sftp://example.org/x.iso"));
        assertEquals(Inputs.Kind.FILE, Inputs.classify("file:///etc/passwd"));
        assertEquals(Inputs.Kind.FILE, Inputs.classify("/home/x/a.torrent"));
        assertEquals(Inputs.Kind.FILE, Inputs.classify("C:\\\\x\\\\a.torrent"));
    }

    @Test
    void clipboardCandidates() {
        assertTrue(Inputs.looksAddable("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        assertTrue(Inputs.looksAddable("https://example.org/dl/ubuntu.torrent?x=1"));
        assertFalse(Inputs.looksAddable("https://example.org/"), "a web page is not a torrent");
        assertTrue(Inputs.looksAddable("https://example.org/debian-13.iso"), "a link to a file");
        assertTrue(Inputs.looksAddable("ftp://example.org/pub/a.tar.gz"));
        assertFalse(Inputs.looksAddable("https://example.org/news/article.html"), "a page");
        assertFalse(Inputs.looksAddable("https://example.org/watch?v=x"), "no file named");
        assertFalse(Inputs.looksAddable("https://example.org/v1.2/"), "a folder");
        assertFalse(Inputs.looksAddable("magnet:?"), "an empty magnet");
        assertFalse(Inputs.looksAddable("some text"));
        assertFalse(Inputs.looksAddable("magnet:?xt=urn:btih:abc\nsecond line"));
        assertFalse(Inputs.looksAddable(null));
        assertFalse(Inputs.looksAddable("   "));
    }

    @Test
    void aLinkIsATorrentsOrDownloadedAsItIs() {
        assertTrue(Inputs.isTorrentLink("https://example.org/dl/ubuntu.torrent?x=1"));
        assertFalse(Inputs.isDirectLink("https://example.org/dl/ubuntu.torrent?x=1"));
        assertTrue(Inputs.isDirectLink("https://example.org/download?id=3"));
        assertTrue(Inputs.isDirectLink("ftp://example.org/pub/a.iso"));
        assertFalse(Inputs.isTorrentLink("ftp://example.org/pub/a.torrent"), "only the web's are fetched as torrents");
        assertFalse(Inputs.isDirectLink("magnet:?xt=urn:btih:abc"));
        assertFalse(Inputs.isDirectLink("/home/x/a.iso"));
        assertFalse(Inputs.isDirectLink("https://"));
        assertFalse(Inputs.isDirectLink(null));
    }
}
