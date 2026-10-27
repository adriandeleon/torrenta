package com.torrenta.engine.libtorrent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Hashes the files of a torrent the way libtorrent's own {@code set_piece_hashes} would, in Java:
 * SHA-1 per piece over the files laid end to end (v1), and per file the SHA-256 merkle root of
 * each piece's 16 KiB blocks (v2, BEP 52), handed to a {@link Sink} as they come.
 *
 * <p>Why not libtorrent's: its hashing goes through its memory-mapped disk I/O, which installs a
 * SIGSEGV/SIGBUS guard of its own in place of the JVM's handler once a file is big enough to be
 * mapped, and the JVM's next safepoint fault kills the process. Hashing here keeps the engine's
 * native code out of it, reports each piece, and stops when asked. Pure apart from the reads, so
 * the tests compare it against libtorrent on files too small to be mapped.
 */
final class PieceHasher {

    /** BEP 52's block: the leaf of the v2 merkle tree. */
    static final int BLOCK = 16 * 1024;

    private static final byte[] ZEROS = new byte[BLOCK];
    private static final byte[] ZERO_HASH = new byte[32];

    private PieceHasher() {}

    /**
     * One file of the layout, in torrent order. A pad file has no path and reads as zeros; it
     * takes part in the v1 pieces and is skipped by v2, as libtorrent's canonical layout has it.
     */
    /** {@code path} is null for a padding file, which is zeros and on no disk. */
    record Entry(@Nullable Path path, long size, boolean pad) {}

    /** Where the hashes go. All calls on the hashing thread, in order. */
    interface Sink {
        void v1(int piece, byte[] sha1);

        /** {@code piece} counts from the file's first piece; the tree's leaves are the blocks. */
        void v2(int file, int piece, byte[] sha256);

        /** After each v1 piece (or each v2 piece of a v2-only torrent); false stops the hashing. */
        boolean progress(int done, int total);
    }

    /** Thrown when the sink asked to stop. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("Cancelled");
        }
    }

    /**
     * @param pieceLength bytes per piece, a multiple of {@link #BLOCK} (libtorrent refuses an odd one)
     * @param v1 compute the SHA-1 pieces
     * @param v2 compute the SHA-256 piece layers
     * @param totalPieces what {@code progress} reports against
     */
    static void hash(List<Entry> files, int pieceLength, boolean v1, boolean v2, int totalPieces, Sink sink)
            throws IOException {
        if (pieceLength < BLOCK || pieceLength % BLOCK != 0)
            throw new IllegalArgumentException("piece length must be a multiple of 16 KiB: " + pieceLength);
        MessageDigest sha1 = digest("SHA-1");
        MessageDigest sha256 = digest("SHA-256");
        int blocksPerPiece = pieceLength / BLOCK;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(pieceLength, 1024 * 1024));
        int piece = 0;
        long inPiece = 0;
        int done = 0;
        for (int f = 0; f < files.size(); f++) {
            Entry entry = files.get(f);
            boolean wantV2 = v2 && !entry.pad() && entry.size() > 0;
            int leaves = wantV2 ? Math.min(blocksPerPiece, nextPowerOfTwo(blocks(entry.size()))) : 0;
            List<byte[]> blockHashes = new ArrayList<>();
            int filePiece = 0;
            long inBlock = 0;
            long left = entry.size();
            try (FileChannel channel =
                    entry.pad() || left == 0 ? null : FileChannel.open(entry.path(), StandardOpenOption.READ)) {
                while (left > 0) {
                    byte[] bytes;
                    int offset;
                    int length;
                    if (channel == null) {
                        bytes = ZEROS;
                        offset = 0;
                        length = (int) Math.min(ZEROS.length, left);
                    } else {
                        buffer.clear();
                        buffer.limit((int) Math.min(buffer.capacity(), left));
                        int read = channel.read(buffer);
                        if (read <= 0) throw new IOException("Unexpected end of " + entry.path());
                        buffer.flip();
                        bytes = new byte[read];
                        buffer.get(bytes);
                        offset = 0;
                        length = read;
                    }
                    left -= length;
                    // v1: the bytes split at piece boundaries.
                    if (v1) {
                        int at = offset;
                        int end = offset + length;
                        while (at < end) {
                            int take = (int) Math.min(end - at, pieceLength - inPiece);
                            sha1.update(bytes, at, take);
                            at += take;
                            inPiece += take;
                            if (inPiece == pieceLength) {
                                sink.v1(piece++, sha1.digest());
                                inPiece = 0;
                                if (!sink.progress(++done, totalPieces)) throw new Cancelled();
                            }
                        }
                    }
                    // v2: the bytes split at block boundaries, a piece every blocksPerPiece blocks.
                    if (wantV2) {
                        int at = offset;
                        int end = offset + length;
                        while (at < end) {
                            int take = (int) Math.min(end - at, BLOCK - inBlock);
                            sha256.update(bytes, at, take);
                            at += take;
                            inBlock += take;
                            boolean lastOfFile = left == 0 && at == end;
                            if (inBlock == BLOCK || lastOfFile) {
                                blockHashes.add(sha256.digest());
                                inBlock = 0;
                                if (blockHashes.size() == blocksPerPiece || lastOfFile) {
                                    sink.v2(f, filePiece++, merkleRoot(blockHashes, leaves));
                                    blockHashes.clear();
                                    if (!v1 && !sink.progress(++done, totalPieces)) throw new Cancelled();
                                }
                            }
                        }
                    }
                }
            }
        }
        if (v1 && inPiece > 0) {
            sink.v1(piece, sha1.digest());
            if (!sink.progress(++done, totalPieces)) throw new Cancelled();
        }
        sink.progress(totalPieces, totalPieces);
    }

    /** 16 KiB blocks in a file; the last may be short. */
    static int blocks(long size) {
        return (int) ((size + BLOCK - 1) / BLOCK);
    }

    static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /**
     * The merkle root of {@code leaves} padded with zero hashes to {@code width} leaves (a power
     * of two, at least the number given): BEP 52's rule for the hashes beyond a file's end.
     */
    static byte[] merkleRoot(List<byte[]> leaves, int width) {
        width = nextPowerOfTwo(Math.max(width, leaves.size()));
        List<byte[]> layer = new ArrayList<>(leaves);
        while (layer.size() < width) layer.add(ZERO_HASH);
        MessageDigest sha256 = digest("SHA-256");
        while (layer.size() > 1) {
            List<byte[]> next = new ArrayList<>(layer.size() / 2);
            for (int i = 0; i < layer.size(); i += 2) {
                sha256.update(layer.get(i));
                sha256.update(layer.get(i + 1));
                next.add(sha256.digest());
            }
            layer = next;
        }
        return layer.get(0);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
