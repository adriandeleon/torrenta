package com.torrenta.engine.libtorrent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PieceHasherTest {

    private static final int K = 1024;

    /** Everything a run produced, by index. */
    private static final class Collected implements PieceHasher.Sink {
        final Map<Integer, byte[]> v1 = new TreeMap<>();
        final Map<String, byte[]> v2 = new TreeMap<>();
        final List<int[]> progress = new ArrayList<>();
        int stopAfter = Integer.MAX_VALUE;

        @Override
        public void v1(int piece, byte[] sha1) {
            assertTrue(v1.put(piece, sha1) == null, "piece " + piece + " twice");
        }

        @Override
        public void v2(int file, int piece, byte[] sha256) {
            assertTrue(v2.put(file + ":" + piece, sha256) == null, "piece " + file + ":" + piece + " twice");
        }

        @Override
        public boolean progress(int done, int total) {
            progress.add(new int[] {done, total});
            return progress.size() < stopAfter;
        }
    }

    private static byte[] sha(String algorithm, byte[]... parts) throws Exception {
        MessageDigest d = MessageDigest.getInstance(algorithm);
        for (byte[] p : parts) d.update(p);
        return d.digest();
    }

    private static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void v1PiecesRunAcrossFilesAndPadFilesReadAsZeros(@TempDir Path dir) throws Exception {
        byte[] a = random(40 * K, 1);
        byte[] b = random(20 * K, 2);
        Files.write(dir.resolve("a"), a);
        Files.write(dir.resolve("b"), b);
        // a (40 KiB), a pad of 24 KiB to the 64 KiB boundary, b (20 KiB): 84 KiB in 32 KiB pieces.
        List<PieceHasher.Entry> layout = List.of(
                new PieceHasher.Entry(dir.resolve("a"), a.length, false),
                new PieceHasher.Entry(null, 24 * K, true),
                new PieceHasher.Entry(dir.resolve("b"), b.length, false));
        Collected out = new Collected();
        PieceHasher.hash(layout, 32 * K, true, false, 3, out);
        assertEquals(3, out.v1.size());
        assertArrayEquals(sha("SHA-1", Arrays.copyOfRange(a, 0, 32 * K)), out.v1.get(0));
        assertArrayEquals(
                sha("SHA-1", Arrays.copyOfRange(a, 32 * K, 40 * K), new byte[24 * K]),
                out.v1.get(1),
                "a's tail, then the pad");
        assertArrayEquals(sha("SHA-1", b), out.v1.get(2), "b alone, a short last piece");
        assertTrue(out.v2.isEmpty());
        assertEquals(List.of(1, 2, 3, 3), out.progress.stream().map(p -> p[0]).toList(), "each piece, then the total");
        assertTrue(out.progress.stream().allMatch(p -> p[1] == 3));
    }

    @Test
    void v2PieceLayersAreMerkleRootsOfTheBlocksPaddedWithZeroHashes(@TempDir Path dir) throws Exception {
        // 64 KiB pieces = 4 blocks. a: 100 KiB = a full piece and 2.25 blocks; b: 20 KiB = 2 blocks
        // (one piece, so its tree has 2 leaves, not 4); c: 3 KiB = 1 block (its root is the block).
        byte[] a = random(100 * K, 3);
        byte[] b = random(20 * K, 4);
        byte[] c = random(3 * K, 5);
        Files.write(dir.resolve("a"), a);
        Files.write(dir.resolve("b"), b);
        Files.write(dir.resolve("c"), c);
        List<PieceHasher.Entry> layout = List.of(
                new PieceHasher.Entry(dir.resolve("a"), a.length, false),
                new PieceHasher.Entry(null, 28 * K, true),
                new PieceHasher.Entry(dir.resolve("b"), b.length, false),
                new PieceHasher.Entry(null, 44 * K, true),
                new PieceHasher.Entry(dir.resolve("c"), c.length, false));
        Collected out = new Collected();
        PieceHasher.hash(layout, 64 * K, true, true, 4, out);

        byte[] zero = new byte[32];
        byte[][] ab = new byte[7][];
        for (int i = 0; i < 7; i++)
            ab[i] = sha("SHA-256", Arrays.copyOfRange(a, i * 16 * K, Math.min(a.length, (i + 1) * 16 * K)));
        byte[] a0 = sha("SHA-256", sha("SHA-256", ab[0], ab[1]), sha("SHA-256", ab[2], ab[3]));
        byte[] a1 = sha("SHA-256", sha("SHA-256", ab[4], ab[5]), sha("SHA-256", ab[6], zero));
        assertArrayEquals(a0, out.v2.get("0:0"));
        assertArrayEquals(a1, out.v2.get("0:1"), "the last piece's missing block is a zero hash");
        byte[] b0 = sha(
                "SHA-256",
                sha("SHA-256", Arrays.copyOfRange(b, 0, 16 * K)),
                sha("SHA-256", Arrays.copyOfRange(b, 16 * K, 20 * K)));
        assertArrayEquals(b0, out.v2.get("2:0"), "a one-piece file's tree is as wide as its blocks");
        assertArrayEquals(sha("SHA-256", c), out.v2.get("4:0"), "one block: the root is the block's hash");
        assertEquals(4, out.v2.size(), "pad files have no v2 hashes");
        assertEquals(4, out.v1.size(), "and the v1 pieces run over everything");
        assertArrayEquals(sha("SHA-1", Arrays.copyOfRange(a, 64 * K, 100 * K), new byte[28 * K]), out.v1.get(1));
    }

    @Test
    void v2OnlyCountsItsOwnPiecesAndACancelStops(@TempDir Path dir) throws Exception {
        byte[] a = random(100 * K, 6);
        Files.write(dir.resolve("a"), a);
        List<PieceHasher.Entry> layout = List.of(new PieceHasher.Entry(dir.resolve("a"), a.length, false));
        Collected out = new Collected();
        PieceHasher.hash(layout, 64 * K, false, true, 2, out);
        assertTrue(out.v1.isEmpty());
        assertEquals(2, out.v2.size());
        assertEquals(List.of(1, 2, 2), out.progress.stream().map(p -> p[0]).toList());

        Collected stopped = new Collected();
        stopped.stopAfter = 1;
        assertThrows(PieceHasher.Cancelled.class, () -> PieceHasher.hash(layout, 16 * K, true, false, 7, stopped));
        assertEquals(1, stopped.v1.size(), "stopped after the first piece");
    }

    @Test
    void merkleRootsAndBlockCounts() throws Exception {
        byte[] zero = new byte[32];
        byte[] x = sha("SHA-256", "x".getBytes());
        byte[] y = sha("SHA-256", "y".getBytes());
        assertArrayEquals(x, PieceHasher.merkleRoot(List.of(x), 1));
        assertArrayEquals(sha("SHA-256", x, y), PieceHasher.merkleRoot(List.of(x, y), 2));
        assertArrayEquals(sha("SHA-256", x, zero), PieceHasher.merkleRoot(List.of(x), 2));
        assertArrayEquals(
                sha("SHA-256", sha("SHA-256", x, y), sha("SHA-256", zero, zero)),
                PieceHasher.merkleRoot(List.of(x, y), 4));
        assertArrayEquals(
                sha("SHA-256", sha("SHA-256", x, y), sha("SHA-256", zero, zero)),
                PieceHasher.merkleRoot(List.of(x, y), 3),
                "a width that is not a power of two is rounded up");
        assertEquals(0, PieceHasher.blocks(0));
        assertEquals(1, PieceHasher.blocks(1));
        assertEquals(1, PieceHasher.blocks(16 * K));
        assertEquals(2, PieceHasher.blocks(16 * K + 1));
        assertEquals(1, PieceHasher.nextPowerOfTwo(1));
        assertEquals(4, PieceHasher.nextPowerOfTwo(3));
        assertEquals(8, PieceHasher.nextPowerOfTwo(8));
        assertThrows(
                IllegalArgumentException.class,
                () -> PieceHasher.hash(List.of(), 1000, true, true, 0, new Collected()));
    }
}
