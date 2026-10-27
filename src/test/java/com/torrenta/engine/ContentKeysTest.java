package com.torrenta.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentKeysTest {

    private static final long P = 16 * 1024;

    @Test
    void aFileOwnsItsPiecesWhenItStartsAndEndsOnBoundaries() {
        assertTrue(ContentKeys.ownPieces(0, 100, P, 100), "a single file: its last piece is short but its own");
        assertTrue(ContentKeys.ownPieces(P, 2 * P, P, 10 * P), "aligned both ends, padding counted in");
        assertTrue(ContentKeys.ownPieces(2 * P, 5, P, 2 * P + 5), "the last file, ending the torrent");
        assertFalse(ContentKeys.ownPieces(0, P + 1, P, 3 * P), "its last piece holds the next file's start");
        assertFalse(ContentKeys.ownPieces(1000, P, P, 3 * P), "its first piece holds the previous file's end");
        assertFalse(ContentKeys.ownPieces(0, 0, P, 0), "an empty file says nothing");
    }

    @Test
    void thePiecesAFileSpans() {
        assertArrayEquals(new int[] {0, 0}, ContentKeys.pieces(0, 100, P));
        assertArrayEquals(new int[] {1, 2}, ContentKeys.pieces(P, 2 * P, P));
        assertArrayEquals(new int[] {2, 3}, ContentKeys.pieces(2 * P, P + 1, P));
    }

    @Test
    void theKeyNamesPieceLengthSizeAndHashes() {
        List<String> hashes = List.of("aa".repeat(20), "bb".repeat(20));
        String key = ContentKeys.v1(P, 2 * P, hashes);
        assertTrue(ContentKeys.isV1(key), key);
        assertTrue(key.startsWith("v1:"));
        assertEquals(key, ContentKeys.v1(P, 2 * P, List.of("AA".repeat(20), "BB".repeat(20))), "hex case aside");
        assertNotEquals(key, ContentKeys.v1(2 * P, 2 * P, hashes), "another piece length");
        assertNotEquals(key, ContentKeys.v1(P, 2 * P - 1, hashes), "another size");
        assertNotEquals(key, ContentKeys.v1(P, 2 * P, List.of(hashes.get(1), hashes.get(0))), "another order");
        assertFalse(ContentKeys.isV1("1".repeat(64)), "a v2 root is not a v1 key");
        assertTrue(ContentIndex.isKey(key) && ContentIndex.isKey("1".repeat(64)));
        assertFalse(ContentKeys.isV1("v1:xyz"));
        assertFalse(ContentKeys.isV1(null));
    }

    /** A hybrid's file is in the index under both keys, found by either, counted once. */
    @Test
    void theIndexFindsAFileByEitherKey() {
        String v1 = ContentKeys.v1(P, 10, List.of("cc".repeat(20)));
        ContentIndex index = new ContentIndex();
        java.nio.file.Path path = java.nio.file.Path.of("/d/a.bin").toAbsolutePath();
        index.put(
                "t",
                List.of(
                        new ContentIndex.Entry("1".repeat(64), "t", 0, path, 10),
                        new ContentIndex.Entry(v1, "t", 0, path, 10)));
        assertEquals(1, index.find(v1).size());
        assertEquals(1, index.find("1".repeat(64)).size());
        assertEquals(1, index.of("t").size());
        assertEquals(1, index.size());
    }
}
