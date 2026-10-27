package com.torrenta.engine;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Everything the detail pane shows about one torrent, read in one go: the general facts always,
 * the costly lists only as {@link #parts} says. A list of a part not asked for is empty and means
 * nothing; the pane keeps what it showed.
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
 * @param parts what was read besides the general facts; see {@link Part}
 * @param fileCount how many real files the torrent has, padding left out: {@code files().size()}
 *     when {@link Part#FILES} was read, else what the engine knows cheaply, {@code -1} for nothing
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
        String command,
        Set<Part> parts,
        int fileCount) {

    /**
     * The parts of a detail that cost the engine something per file, peer or piece. The general
     * facts are always read; a tab asks for the rest it shows ({@code ui.DetailParts}).
     */
    public enum Part {
        /** {@link #files}, and the archives found locked. */
        FILES,
        PEERS,
        TRACKERS,
        /** {@link #pieces} and {@link #availability}: a copy per piece. */
        PIECES,
        WEB_SEEDS;

        /** Every part: what a caller outside the pane gets from {@link Engine#detail(String)}. */
        public static final Set<Part> ALL = java.util.Collections.unmodifiableSet(EnumSet.allOf(Part.class));

        /** The general facts alone. */
        public static final Set<Part> NONE = java.util.Collections.unmodifiableSet(EnumSet.noneOf(Part.class));
    }

    public TorrentDetail {
        availability = availability == null ? new int[0] : availability;
        webSeeds = webSeeds == null ? List.of() : List.copyOf(webSeeds);
        encryptedArchives = encryptedArchives == null ? java.util.Set.of() : java.util.Set.copyOf(encryptedArchives);
        command = command == null ? "" : command;
        parts = parts == null || parts.isEmpty()
                ? Part.NONE
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(parts));
        if (parts.contains(Part.FILES)) fileCount = files.size();
    }

    /** Every part read. */
    public TorrentDetail(
            General general,
            List<FileStatus> files,
            List<Peer> peers,
            List<Tracker> trackers,
            boolean[] pieces,
            int[] availability,
            List<String> webSeeds,
            java.util.Set<Integer> encryptedArchives,
            String command) {
        this(general, files, peers, trackers, pieces, availability, webSeeds, encryptedArchives, command, Part.ALL, -1);
    }

    /** Whether {@code part} was read, so that its list says what the torrent has. */
    public boolean has(Part part) {
        return parts.contains(part);
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
            @Nullable FoundFile found) {

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
