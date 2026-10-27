package com.torrenta.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentLabelsTest {

    @Test
    void normalisedOnConstruction() {
        TorrentLabels l = new TorrentLabels("  Movies ", List.of(" b", "a, c ,", "", "A", null == null ? "b" : ""));
        assertEquals("Movies", l.category());
        assertEquals(List.of("a", "b", "c"), l.tags(), "split on commas, trimmed, distinct ignoring case, sorted");
        assertFalse(l.isEmpty());
        assertTrue(TorrentLabels.NONE.isEmpty());
        assertTrue(new TorrentLabels(null, null).isEmpty());
        assertEquals(List.of(), TorrentLabels.normalizeTags(List.of(" , ,")));
    }

    @Test
    void withersKeepTheRest() {
        TorrentLabels l = new TorrentLabels("Movies", List.of("hd"));
        assertEquals(new TorrentLabels("", List.of("hd")), l.withCategory(""));
        assertEquals(new TorrentLabels("Movies", List.of("4k", "hd")), l.withTags(List.of("hd", "4k")));
        assertEquals(new TorrentLabels("Movies", List.of()), l.withTags(null));
    }

    /** A name a person gave goes along with the filing, and only when there is one. */
    @Test
    void theGivenNameRoundTripsAndKeepsTheRest() throws IOException {
        TorrentLabels l = new TorrentLabels("Movies", List.of("hd")).withName("  My film ");
        assertEquals("My film", l.name());
        assertEquals(l, TorrentLabels.decode(l.encode()));
        assertEquals("My film", l.withCategory("").withTags(List.of()).name());
        assertTrue(!TorrentLabels.NONE.withName("x").isEmpty(), "a name alone is worth a sidecar");
        assertEquals(
                "{\"category\":\"\",\"tags\":[]}",
                new String(TorrentLabels.NONE.encode(), StandardCharsets.UTF_8),
                "no name, no field: older sidecars and newer ones read the same");
    }

    @Test
    void encodeDecodeRoundTrip() throws IOException {
        TorrentLabels l = new TorrentLabels("Linux \"ISOs\"", List.of("ubuntu", "ünïcödé"));
        byte[] bytes = l.encode();
        assertEquals(
                "{\"category\":\"Linux \\\"ISOs\\\"\",\"tags\":[\"ubuntu\",\"ünïcödé\"]}",
                new String(bytes, StandardCharsets.UTF_8));
        assertEquals(l, TorrentLabels.decode(bytes));
        assertEquals(TorrentLabels.NONE, TorrentLabels.decode(TorrentLabels.NONE.encode()));
    }

    @Test
    void decodeIsLenientAboutExtrasAndStrictAboutShape() throws IOException {
        assertEquals(
                new TorrentLabels("x", List.of("t")),
                TorrentLabels.decode("{\"future\":{\"a\":[1]},\"category\":\"x\",\"tags\":[\"t\",3,{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(TorrentLabels.NONE, TorrentLabels.decode("{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> TorrentLabels.decode("[]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> TorrentLabels.decode("not json".getBytes(StandardCharsets.UTF_8)));
    }

    /** An updatable torrent's address, version and the one it replaces travel with the filing. */
    @Test
    void theFollowingRoundTripsAndKeepsTheRest() throws IOException {
        Following f = new Following(" magnet:?xs=urn:btpk:" + "00".repeat(32) + " ", 7, "ab".repeat(20));
        TorrentLabels l =
                new TorrentLabels("Movies", List.of("hd")).withName("Film").withFollowing(f);
        assertEquals("magnet:?xs=urn:btpk:" + "00".repeat(32), l.following().source());
        assertEquals(l, TorrentLabels.decode(l.encode()));
        assertEquals(f, l.withCategory("TV").withTags(List.of("x")).withName("").following(), "the withers keep it");
        TorrentLabels done = l.withFollowing(f.withReplaces(""));
        assertEquals(done, TorrentLabels.decode(done.encode()), "with nothing left to replace");
        assertFalse(TorrentLabels.NONE.withFollowing(f).isEmpty(), "a following alone is worth a sidecar");
        assertTrue(TorrentLabels.NONE.withFollowing(new Following("", 1, "")).isEmpty(), "no address, no following");
        assertEquals(null, l.withFollowing(null).following());
    }
}
