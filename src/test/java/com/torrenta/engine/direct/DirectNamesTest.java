package com.torrenta.engine.direct;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectNamesTest {

    @Test
    void aLinkIsNamedByItsLastSegmentDecoded() {
        assertEquals("a b.iso", DirectNames.fromUrl("https://x.org/iso/a%20b.iso?t=1#frag"));
        assertEquals("c++.tar.gz", DirectNames.fromUrl("https://x.org/c++.tar.gz"));
        assertEquals("release", DirectNames.fromUrl("https://x.org/release/"));
        assertEquals("x.org", DirectNames.fromUrl("https://x.org/"));
        assertEquals("x.org", DirectNames.fromUrl("https://x.org"));
        assertEquals("файл.zip", DirectNames.fromUrl("ftp://x.org/%D1%84%D0%B0%D0%B9%D0%BB.zip"));
        assertEquals(DirectNames.FALLBACK, DirectNames.fromUrl("not a link at all"));
    }

    @Test
    void namesAreMadeSafeForEveryFileSystem() {
        assertEquals("a_b_c.txt", DirectNames.safe("a/b\\c.txt"));
        assertEquals("what_.txt", DirectNames.safe("what?.txt"));
        assertEquals("hidden", DirectNames.safe("..hidden"));
        assertEquals("name", DirectNames.safe("name. . "));
        assertEquals("_CON.txt", DirectNames.safe("CON.txt"));
        assertEquals("_nul", DirectNames.safe("nul"));
        assertEquals("console.txt", DirectNames.safe("console.txt"));
        assertEquals("a_b", DirectNames.safe("a\u0000b"));
        assertEquals(DirectNames.FALLBACK, DirectNames.safe("..."));
        assertEquals(DirectNames.FALLBACK, DirectNames.safe(null));
        String longName = "x".repeat(300) + ".mkv";
        String cut = DirectNames.safe(longName);
        assertEquals(DirectNames.MAX_LENGTH, cut.length());
        assertTrue(cut.endsWith(".mkv"), "the extension is kept");
    }

    @Test
    void aTakenNameGetsANumberBeforeItsExtension() {
        Set<String> taken = Set.of("a.iso", "a (2).iso", "b.tar.gz", "README");
        assertEquals("a (3).iso", DirectNames.unused("a.iso", taken::contains));
        assertEquals("b (2).tar.gz", DirectNames.unused("b.tar.gz", taken::contains));
        assertEquals("README (2)", DirectNames.unused("README", taken::contains));
        assertEquals("free.iso", DirectNames.unused("free.iso", taken::contains));
    }

    @Test
    void theHostIsLowerCase() {
        assertEquals("cdn.example.org", DirectNames.host("https://CDN.Example.org/x"));
        assertEquals("", DirectNames.host("nonsense"));
    }
}
