package com.torrenta.engine;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSeedsTest {

    @Test
    void aWebSeedIsAnHttpOrHttpsUrlWithAHost() {
        assertTrue(WebSeeds.valid("http://mirror.example.org/pub/"));
        assertTrue(WebSeeds.valid("https://mirror.example.org:8443/pub/file.iso"));
        assertTrue(WebSeeds.valid("  HTTPS://Mirror.example.org/  "), "trimmed, scheme in any case");
        assertFalse(WebSeeds.valid("ftp://mirror.example.org/pub/"), "libtorrent fetches over HTTP only");
        assertFalse(WebSeeds.valid("udp://tracker.example.org:6969/announce"), "a tracker is no web seed");
        assertFalse(WebSeeds.valid("mirror.example.org/pub/"), "no scheme");
        assertFalse(WebSeeds.valid("http:///pub/"), "no host");
        assertFalse(WebSeeds.valid("http://mirror example.org/"), "not a URL at all");
        assertFalse(WebSeeds.valid(""));
        assertFalse(WebSeeds.valid("   "));
        assertFalse(WebSeeds.valid(null));
    }

    @Test
    void invalidNamesWhatWouldBeRefusedAndSkipsBlanks() {
        assertEquals(
                List.of("ftp://x.example.org/", "nonsense"),
                WebSeeds.invalid(List.of("http://a.example.org/", " ftp://x.example.org/ ", "", "  ", "nonsense")));
        assertEquals(List.of(), WebSeeds.invalid(Arrays.asList("http://a.example.org/", null)));
    }

    @Test
    void freshKeepsOrderAndDropsDuplicatesBlanksAndInvalidOnes() {
        assertEquals(
                List.of("http://b.example.org/", "https://c.example.org/"),
                WebSeeds.fresh(
                        List.of("http://a.example.org/"),
                        List.of(
                                "http://a.example.org/",
                                " http://b.example.org/ ",
                                "",
                                "ftp://x.example.org/",
                                "https://c.example.org/",
                                "http://b.example.org/")));
        assertEquals(
                List.of("http://A.example.org/"),
                WebSeeds.fresh(List.of("http://a.example.org/"), List.of("http://A.example.org/")),
                "compared as written: libtorrent keeps both");
        assertEquals(List.of(), WebSeeds.fresh(List.of(), List.of()));
    }
}
