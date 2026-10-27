package com.torrenta.stream;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.torrenta.engine.PieceMath;

/**
 * Which pieces a player will want soon, and by when: the read-ahead window. Pure.
 *
 * <p>From the playhead (the byte a player last asked for), the pieces over the next {@link
 * #WINDOW_SECONDS} of playback each get a deadline of when the player will reach them at the
 * playback rate, so they tighten towards the playhead; the file's own pieces only, never the
 * torrent's others, which keep downloading as they would. At the start, the file's first and last
 * pieces come first too: a container's index sits at either end, and a player reads both before
 * it shows anything.
 */
public final class Readahead {

    /** How far ahead of the playhead pieces are asked for, in seconds of playback. */
    public static final int WINDOW_SECONDS = 30;

    /** The playback rate before one is measured: 1 MiB/s, about a 1080p film. */
    public static final long DEFAULT_BITRATE = 1 << 20;

    private Readahead() {}

    /** A piece of the torrent, wanted within {@code millis}. */
    public record Deadline(int piece, int millis) {}

    /**
     * A file's place in the torrent's pieces.
     *
     * @param offset where it starts, in bytes into the torrent
     * @param size its size
     * @param pieceLength the torrent's
     */
    public record Geometry(long offset, long size, long pieceLength) {
        int firstPiece() {
            return PieceMath.span(offset, pieceLength, 0, 0).first();
        }

        int lastPiece() {
            return PieceMath.span(offset, pieceLength, size - 1, size - 1).first();
        }

        /** Where piece {@code p} starts, counted from the file's start; 0 for the piece it starts in. */
        long start(int p) {
            return Math.max(0, p * pieceLength - offset);
        }
    }

    /**
     * The deadlines for this look, nearest first.
     *
     * @param playhead the byte a player last asked for, from the file's start
     * @param have the torrent's pieces that are there, by index
     * @param bitrate bytes per second of playback (0 or less: {@link #DEFAULT_BITRATE})
     * @param starting the first look at this file: its first and last pieces, at once, too
     */
    public static List<Deadline> plan(long playhead, Geometry file, BitSet have, long bitrate, boolean starting) {
        List<Deadline> out = new ArrayList<>();
        if (file.size() <= 0) return out;
        long rate = bitrate > 0 ? bitrate : DEFAULT_BITRATE;
        long head = Math.min(Math.max(0, playhead), file.size() - 1);
        long until = Math.min(file.size() - 1, head + rate * WINDOW_SECONDS);
        PieceMath.Span window = PieceMath.span(file.offset(), file.pieceLength(), head, until);
        if (starting) {
            for (int edge : new int[] {file.firstPiece(), file.lastPiece()})
                if (!have.get(edge) && out.stream().noneMatch(d -> d.piece() == edge)) out.add(new Deadline(edge, 0));
        }
        for (int p = window.first(); p <= window.last(); p++) {
            if (have.get(p)) continue;
            int piece = p;
            if (out.stream().anyMatch(d -> d.piece() == piece)) continue;
            long ahead = Math.max(0, file.start(p) - head);
            out.add(new Deadline(p, (int) Math.min(Integer.MAX_VALUE, ahead * 1000 / rate)));
        }
        return out;
    }

    /** The pieces a plan covers, first to last; the strip's mark. */
    public static PieceMath.Span window(long playhead, Geometry file, long bitrate) {
        long rate = bitrate > 0 ? bitrate : DEFAULT_BITRATE;
        long head = Math.min(Math.max(0, playhead), file.size() - 1);
        long until = Math.min(file.size() - 1, head + rate * WINDOW_SECONDS);
        return PieceMath.span(file.offset(), file.pieceLength(), head, until);
    }
}
