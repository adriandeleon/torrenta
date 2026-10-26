package com.torrenta.engine;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The key a v1 file is found by in the {@link ContentIndex}, where a v2 file has its Merkle root:
 * {@code v1:} and SHA-256 over the piece length, the file's size and the SHA-1 of every piece it
 * spans. Only a file whose pieces are its own has one ({@link #ownPieces}): a single-file torrent's
 * file, or one a hybrid's (or BEP 47's) padding aligns. A piece shared with a neighbouring file
 * says nothing about this file alone, and a file found on disk by it would be rewritten in place
 * if the neighbour's bytes made the piece fail; so those files have no key and are downloaded.
 *
 * <p>Two files with one key have the same bytes as surely as their pieces' SHA-1s say so, the
 * add's check then verifying them; files cut into pieces of different lengths never share one.
 */
public final class ContentKeys {

    /** What a v1 key starts with, apart from a v2 root's 64 bare hex digits. */
    public static final String V1 = "v1:";

    private ContentKeys() {}

    /**
     * Whether every piece holding a byte of the file holds only bytes of it: it starts on a piece
     * boundary and ends on one, or at the end of the torrent. The caller counts the padding file
     * after it, if any, in {@code size}: padding is zeros, the same in every torrent that has it.
     */
    public static boolean ownPieces(long offset, long size, long pieceLength, long total) {
        if (size <= 0 || pieceLength <= 0 || offset < 0) return false;
        long end = offset + size;
        return offset % pieceLength == 0 && (end % pieceLength == 0 || end == total);
    }

    /** The first and last piece the file spans; call only for a file with {@link #ownPieces}. */
    public static int[] pieces(long offset, long size, long pieceLength) {
        return new int[] {(int) (offset / pieceLength), (int) ((offset + size - 1) / pieceLength)};
    }

    /** The key for a file of {@code size} bytes in pieces of {@code pieceLength}, from their SHA-1s in hex. */
    public static String v1(long pieceLength, long size, List<String> pieceHashes) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        sha256.update(ByteBuffer.allocate(16).putLong(pieceLength).putLong(size).array());
        HexFormat hex = HexFormat.of();
        for (String h : pieceHashes) sha256.update(hex.parseHex(h));
        return V1 + hex.formatHex(sha256.digest());
    }

    /** Whether {@code key} is a v1 key as {@link #v1} makes them. */
    public static boolean isV1(String key) {
        if (key == null) return false;
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (!k.startsWith(V1) || k.length() != V1.length() + 64) return false;
        return k.substring(V1.length()).chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
