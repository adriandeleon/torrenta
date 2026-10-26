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
 * @param webSeeds the web seeds (BEP 19 HTTP sources), as {@link Engine#webSeeds} lists them
 * @param encryptedArchives the indices of the archives whose first bytes say they ask for a
 *     password ({@link ArchiveProbe}); empty until read, and always for an engine that cannot
 * @param command a download from a link: the command line that fetches the same file by hand
 *     ({@code Curl.shellCommand}); empty for a torrent
 */
public record TorrentDetail(
        General general,
        List<FileStatus> files,
        List<Peer> peers,
        List<Tracker> trackers,
        boolean[] pieces,
        int[] availability,
        List<String> webSeeds,
        java.util.Set<Integer> encryptedArchives,
        String command) {

    public TorrentDetail {
        availability = availability == null ? new int[0] : availability;
        webSeeds = webSeeds == null ? List.of() : List.copyOf(webSeeds);
        encryptedArchives = encryptedArchives == null ? java.util.Set.of() : java.util.Set.copyOf(encryptedArchives);
        command = command == null ? "" : command;
    }

    public TorrentDetail(
            General general,
            List<FileStatus> files,
            List<Peer> peers,
            List<Tracker> trackers,
            boolean[] pieces,
            int[] availability,
            List<String> webSeeds,
            java.util.Set<Integer> encryptedArchives) {
        this(general, files, peers, trackers, pieces, availability, webSeeds, encryptedArchives, "");
    }

    public TorrentDetail(
            General general,
            List<FileStatus> files,
            List<Peer> peers,
            List<Tracker> trackers,
            boolean[] pieces,
            int[] availability,
            List<String> webSeeds) {
        this(general, files, peers, trackers, pieces, availability, webSeeds, java.util.Set.of());
    }

    /** A detail with no web seeds. */
    public TorrentDetail(
            General general,
            List<FileStatus> files,
            List<Peer> peers,
            List<Tracker> trackers,
            boolean[] pieces,
            int[] availability) {
        this(general, files, peers, trackers, pieces, availability, List.of());
    }

    /** A detail with no availability counts and no web seeds: what most tests want. */
    public TorrentDetail(
            General general, List<FileStatus> files, List<Peer> peers, List<Tracker> trackers, boolean[] pieces) {
        this(general, files, peers, trackers, pieces, new int[0], List.of());
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
            int index,
            String path,
            long size,
            long done,
            Priority priority,
            int firstPiece,
            int lastPiece,
            FoundFile found) {

        public FileStatus(
                int index, String path, long size, long done, Priority priority, int firstPiece, int lastPiece) {
            this(index, path, size, done, priority, firstPiece, lastPiece, null);
        }

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
