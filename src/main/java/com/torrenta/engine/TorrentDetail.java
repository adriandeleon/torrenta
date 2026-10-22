package com.torrenta.engine;

import java.util.List;

/**
 * Everything the detail pane shows about one torrent, read in one go.
 *
 * @param general the facts for the General tab
 * @param files per-file progress and priority, in index order (padding files left out)
 * @param peers the connected peers
 * @param trackers the trackers, in tier order
 * @param pieces which pieces are complete, by index
 * @param availability how many connected peers have each piece, by index; empty when libtorrent
 *     keeps no count (a seed, a paused torrent, one without metadata)
 */
public record TorrentDetail(
        General general,
        List<FileStatus> files,
        List<Peer> peers,
        List<Tracker> trackers,
        boolean[] pieces,
        int[] availability) {

    public TorrentDetail {
        availability = availability == null ? new int[0] : availability;
    }

    /** A detail with no availability counts: what most tests want. */
    public TorrentDetail(
            General general, List<FileStatus> files, List<Peer> peers, List<Tracker> trackers, boolean[] pieces) {
        this(general, files, peers, trackers, pieces, new int[0]);
    }

    /**
     * The General tab. Durations and the next announce are seconds; times are epoch seconds. The
     * info-hashes are hex, each empty when the torrent has none of that version.
     */
    public record General(
            String id,
            String savePath,
            boolean isPrivate,
            int pieceCount,
            int pieceLength,
            int piecesHave,
            double availability,
            long downloaded,
            long uploaded,
            long wasted,
            long activeSeconds,
            long seedingSeconds,
            long nextAnnounceSeconds,
            int connections,
            String currentTracker,
            String infoHashV1,
            String infoHashV2) {

        public General {
            infoHashV1 = infoHashV1 == null ? "" : infoHashV1;
            infoHashV2 = infoHashV2 == null ? "" : infoHashV2;
        }
    }

    /**
     * One file: the torrent's index, its path, its size, how much of it is on disk, and the pieces
     * it touches, first and last inclusive ({@code -1} for an empty file or when unknown).
     */
    public record FileStatus(
            int index, String path, long size, long done, Priority priority, int firstPiece, int lastPiece) {

        public FileStatus(int index, String path, long size, long done, Priority priority) {
            this(index, path, size, done, priority, -1, -1);
        }

        /** Whether piece {@code piece} holds some of this file's bytes. */
        public boolean touches(int piece) {
            return firstPiece >= 0 && piece >= firstPiece && piece <= lastPiece;
        }

        public double progress() {
            return size == 0 ? 1.0 : (double) done / size;
        }
    }

    /**
     * One peer. {@code flags} is the compact letter string the list shows; see {@code PeerFlags}.
     */
    public record Peer(
            String address,
            String client,
            String flags,
            double progress,
            long downRate,
            long upRate,
            long downloaded,
            long uploaded) {}

    /** One tracker. {@code status} is one of working, updating, not contacted, error. */
    public record Tracker(String url, int tier, String status, String message, int fails) {}
}
