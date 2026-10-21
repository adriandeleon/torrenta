package com.torrenta.engine;

import java.util.List;

/**
 * The piece-size rule of the Create Torrent dialog, pure.
 *
 * <p>"Automatic" aims at about {@value #TARGET_PIECES} pieces, as qBittorrent does: the smallest
 * power of two from 16 KiB to 16 MiB that keeps the count under the target, so a small torrent gets
 * small pieces and a large one does not grow a huge piece list. The dialog offers the same ladder
 * up to 32 MiB by hand.
 */
public final class PieceSize {

    public static final int MIN = 16 * 1024;
    /** The largest the rule chooses by itself. */
    public static final int MAX_AUTOMATIC = 16 * 1024 * 1024;
    /** The largest the dialog offers. */
    public static final int MAX = 32 * 1024 * 1024;

    static final int TARGET_PIECES = 1500;

    private PieceSize() {}

    /** The sizes the dialog offers, ascending: 16 KiB … 32 MiB, powers of two. */
    public static List<Integer> choices() {
        List<Integer> out = new java.util.ArrayList<>();
        for (int size = MIN; size <= MAX; size *= 2) out.add(size);
        return List.copyOf(out);
    }

    /** The automatic piece size for a torrent of {@code totalSize} bytes. */
    public static int suggest(long totalSize) {
        for (int size = MIN; size < MAX_AUTOMATIC; size *= 2) {
            if (count(totalSize, size) <= TARGET_PIECES) return size;
        }
        return MAX_AUTOMATIC;
    }

    /** Pieces of a torrent whose files are packed back to back: v1 without padding. */
    public static long count(long totalSize, int pieceSize) {
        if (totalSize <= 0) return 0;
        return (totalSize + pieceSize - 1) / pieceSize;
    }

    /**
     * Pieces of a torrent laid out with every file starting on a piece boundary (v2, a hybrid, or
     * v1 with padding): each file's own pieces, summed.
     */
    public static long count(List<Long> fileSizes, int pieceSize) {
        long pieces = 0;
        for (long size : fileSizes) pieces += count(size, pieceSize);
        return pieces;
    }

    /** The count the dialog shows: aligned or packed, as the format and the flag decide. */
    public static long count(List<Long> fileSizes, int pieceSize, boolean aligned) {
        if (aligned) return count(fileSizes, pieceSize);
        return count(fileSizes.stream().mapToLong(Long::longValue).sum(), pieceSize);
    }

    /** {@code 16 KiB}, {@code 1 MiB}: the ladder's labels, whole units. */
    public static String label(int pieceSize) {
        if (pieceSize >= 1024 * 1024) return (pieceSize / (1024 * 1024)) + " MiB";
        return (pieceSize / 1024) + " KiB";
    }
}
