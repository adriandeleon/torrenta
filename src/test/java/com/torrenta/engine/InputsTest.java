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
        assertEquals(Inputs.Kind.FILE, Inputs.classify("/home/x/a.torrent"));
        assertEquals(Inputs.Kind.FILE, Inputs.classify("C:\\\\x\\\\a.torrent"));
    }

    @Test
    void clipboardCandidates() {
        assertTrue(Inputs.looksAddable("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        assertTrue(Inputs.looksAddable("https://example.org/dl/ubuntu.torrent?x=1"));
        assertFalse(Inputs.looksAddable("https://example.org/"), "a web page is not a torrent");
        assertFalse(Inputs.looksAddable("magnet:?"), "an empty magnet");
        assertFalse(Inputs.looksAddable("some text"));
        assertFalse(Inputs.looksAddable("magnet:?xt=urn:btih:abc\nsecond line"));
        assertFalse(Inputs.looksAddable(null));
        assertFalse(Inputs.looksAddable("   "));
    }
}
