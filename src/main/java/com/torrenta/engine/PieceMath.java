package com.torrenta.engine;

/** Where a file's bytes lie in a torrent's pieces. Pure. */
public final class PieceMath {

    private PieceMath() {}

    /** The pieces from {@code first} to {@code last}, inclusive. */
    public record Span(int first, int last) {
        public int count() {
            return last - first + 1;
        }
    }

    /**
     * The pieces bytes {@code from} to {@code to} (inclusive, counted from the file's start) of a
     * file need: the file starts {@code fileOffset} bytes into the torrent (v1's layout, a hybrid's
     * padding included, so a v2 file starts on a piece of its own).
     */
    public static Span span(long fileOffset, long pieceLength, long from, long to) {
        if (pieceLength <= 0 || from < 0 || to < from) throw new IllegalArgumentException("no such range");
        return new Span((int) ((fileOffset + from) / pieceLength), (int) ((fileOffset + to) / pieceLength));
    }

    /**
     * The last byte of the file, counted from its start, that piece {@code piece} holds: what can
     * be sent once that piece is there. A piece past the file's end ends at the file's end.
     */
    public static long lastByteOf(int piece, long fileOffset, long fileSize, long pieceLength) {
        long end = (piece + 1L) * pieceLength - 1 - fileOffset;
        return Math.min(end, fileSize - 1);
    }
}
