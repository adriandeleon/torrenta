package com.torrenta.engine;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableSourceTest {

    private static final String KEY = "8543d3e6115f0f98c944077a4493dcd543e49c739fd998550a1f614ab36ed63e";

    @Test
    void anUpdatableAddressIsReadWithItsSaltAndWrittenBackCanonically() {
        MutableSource s = MutableSource.parse("  magnet:?xs=urn:btpk:" + KEY.toUpperCase() + "&s=6e616d65  ")
                .orElseThrow();
        assertEquals(32, s.publicKey().length);
        assertArrayEquals("name".getBytes(StandardCharsets.US_ASCII), s.salt());
        assertEquals("magnet:?xs=urn:btpk:" + KEY + "&s=6e616d65", s.uri(), "lower-case hex");
        assertEquals(s, MutableSource.parse(s.uri()).orElseThrow());
        assertEquals(s.hashCode(), MutableSource.parse(s.uri()).orElseThrow().hashCode());

        MutableSource bare =
                MutableSource.parse("magnet:?xs=urn%3Abtpk%3A" + KEY).orElseThrow();
        assertEquals(0, bare.salt().length, "no salt, and the value percent-decoded");
        assertEquals("magnet:?xs=urn:btpk:" + KEY, bare.uri());
    }

    @Test
    void anythingElseIsNotOne() {
        assertEquals(Optional.empty(), MutableSource.parse(null));
        assertEquals(Optional.empty(), MutableSource.parse("magnet:?xt=urn:btih:" + KEY.substring(0, 40)));
        assertEquals(Optional.empty(), MutableSource.parse("magnet:?xs=urn:btpk:" + KEY.substring(2)), "short key");
        assertEquals(Optional.empty(), MutableSource.parse("magnet:?xs=urn:btpk:" + KEY + "&s=abc"), "odd salt");
        assertEquals(Optional.empty(), MutableSource.parse("magnet:?xs=urn:btpk:" + KEY + "&s=zz"), "not hex");
        assertEquals(Optional.empty(), MutableSource.parse("https://example.com/?xs=urn:btpk:" + KEY));
        assertThrows(IllegalArgumentException.class, () -> new MutableSource(new byte[31], null));
    }

    /** BEP 46's value: {@code ih}, 20 bytes for v1 (or a hybrid's v1), 32 for v2; nothing else counts. */
    @Test
    void theItemNamesItsTorrentByItsInfoHash() {
        byte[] v1 = new byte[20];
        v1[0] = (byte) 0xab;
        byte[] v2 = new byte[32];
        v2[31] = 1;
        assertEquals(Optional.of("ab" + "00".repeat(19)), MutableSource.infoHash(Map.of("ih", v1)));
        assertEquals(Optional.of("00".repeat(31) + "01"), MutableSource.infoHash(Map.of("ih", v2)));
        assertEquals(Optional.empty(), MutableSource.infoHash(Map.of("ih", new byte[16])));
        assertEquals(Optional.empty(), MutableSource.infoHash(Map.of("ih", "text")));
        assertEquals(Optional.empty(), MutableSource.infoHash(Map.of()));
        assertEquals(Optional.empty(), MutableSource.infoHash(new byte[20]));
        assertEquals(Optional.empty(), MutableSource.infoHash(null));
    }

    @Test
    void theVersionIsAddedAsAMagnetOfItsKind() {
        String v1 = "ab".repeat(20);
        String v2 = "cd".repeat(32);
        assertEquals("magnet:?xt=urn:btih:" + v1, MutableSource.magnetOf(v1));
        assertEquals("magnet:?xt=urn:btmh:1220" + v2, MutableSource.magnetOf(v2), "a v2 multihash");
        assertTrue(Inputs.classify(MutableSource.magnetOf(v2)) == Inputs.Kind.MAGNET);
    }
}
