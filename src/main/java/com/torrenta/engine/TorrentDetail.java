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
 */
public record TorrentDetail(
        General general, List<FileStatus> files, List<Peer> peers, List<Tracker> trackers, boolean[] pieces) {

    /** The General tab. Durations and the next announce are seconds; times are epoch seconds. */
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
            String currentTracker) {}

    /** One file: the torrent's index, its path, its size, how much of it is on disk. */
    public record FileStatus(int index, String path, long size, long done, Priority priority) {
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
