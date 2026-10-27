package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisTest {

    private static final long STALLED = RowState.STALL_AFTER_SECONDS + 5;

    /** Ten pieces of 1 KiB: the film in 0–5, the poster in 6, the commentary in 7–9. */
    private static List<TorrentDetail.FileStatus> files(Priority commentary) {
        return List.of(
                new TorrentDetail.FileStatus(0, "film.mkv", 6 * 1024, 0, Priority.NORMAL, 0, 5),
                new TorrentDetail.FileStatus(1, "poster.jpg", 1024, 0, Priority.NORMAL, 6, 6),
                new TorrentDetail.FileStatus(2, "extras/commentary.mkv", 3 * 1024, 0, commentary, 7, 9));
    }

    private static TorrentDetail.General general(double availability) {
        return new TorrentDetail.General(
                "id", "/data", false, 10, 1024, 6, availability, 0, 0, 0, 0, 0, 0, 1, "", "", "");
    }

    private static TorrentDetail.Peer peer(String flags, long down) {
        return new TorrentDetail.Peer("10.0.0.1", "qB", flags, 0.5, down, 0, 0, 0);
    }

    private static TorrentDetail detail(
            List<TorrentDetail.FileStatus> files,
            List<TorrentDetail.Peer> peers,
            List<TorrentDetail.Tracker> trackers,
            boolean[] have,
            int[] availability) {
        return new TorrentDetail(general(0.97), files, peers, trackers, have, availability);
    }

    private static boolean[] have(int... missing) {
        boolean[] out = new boolean[10];
        java.util.Arrays.fill(out, true);
        for (int i : missing) out[i] = false;
        return out;
    }

    private static int[] availability(int... none) {
        int[] out = new int[10];
        java.util.Arrays.fill(out, 3);
        for (int i : none) out[i] = 0;
        return out;
    }

    private static Diagnosis stalled(TorrentDetail d) {
        Torrent t = T.downloading().progress(0.9).peers(d.peers().size(), 0).build();
        return Diagnosis.of(t, RowState.of(t, STALLED), d, STALLED).orElseThrow();
    }

    @Test
    void aMovingOrFinishedTorrentHasNothingToExplain() {
        TorrentDetail d = detail(files(Priority.NORMAL), List.of(), List.of(), have(), new int[0]);
        for (T t :
                List.of(T.downloading().down(100), T.seeding(), T.downloading().paused())) {
            Torrent torrent = t.build();
            assertTrue(Diagnosis.of(torrent, RowState.of(torrent, 0), d, 0).isEmpty());
        }
    }

    /** The commentary (file 2) found asking for a password; downloading or stalled alike. */
    private static TorrentDetail locked(Priority commentary) {
        TorrentDetail d =
                detail(files(commentary), List.of(peer("dI", 0)), List.of(), have(7, 8, 9), availability(8, 9));
        return new TorrentDetail(
                d.general(), d.files(), d.peers(), d.trackers(), d.pieces(), d.availability(), d.webSeeds(), Set.of(2));
    }

    @Test
    void anArchiveThatAsksForAPasswordIsSaidWhetherOrNotTheTorrentMoves() {
        for (Torrent t : List.of(
                T.downloading().down(100).build(),
                T.downloading().progress(0.9).peers(1, 0).build())) {
            Diagnosis d = Diagnosis.of(t, RowState.of(t, STALLED), locked(Priority.NORMAL), STALLED)
                    .orElseThrow();
            assertEquals(Diagnosis.Title.ENCRYPTED, d.title());
            assertEquals("commentary.mkv", d.file());
            assertEquals(2, d.fileIndex());
            assertEquals(List.of(Diagnosis.Remedy.SKIP_FILE, Diagnosis.Remedy.REMOVE), d.remedies());
            assertTrue(d.text().startsWith("commentary.mkv asks for a password before it opens."), d.text());
        }
    }

    @Test
    void aSkippedLockedArchiveIsNoLongerSaidAndAnErrorComesFirst() {
        Torrent stalled = T.downloading().progress(0.9).peers(1, 0).build();
        Diagnosis skipped = Diagnosis.of(stalled, RowState.of(stalled, STALLED), locked(Priority.IGNORE), STALLED)
                .orElseThrow();
        assertEquals(Diagnosis.Title.STALLED, skipped.title(), "back to why it is stalled");
        Torrent moving = T.downloading().down(100).build();
        assertTrue(Diagnosis.of(moving, RowState.of(moving, 0), locked(Priority.IGNORE), 0)
                .isEmpty());
        Torrent failed = T.downloading().error("disk full").build();
        assertEquals(
                Diagnosis.Title.ERROR,
                Diagnosis.of(failed, RowState.of(failed, 0), locked(Priority.NORMAL), 0)
                        .orElseThrow()
                        .title());
        assertEquals(Diagnosis.Remedy.REMOVE.command(), java.util.Optional.of("remove"));
    }

    @Test
    void piecesNobodyHasInOneFileOfferToSkipIt() {
        TorrentDetail d =
                detail(files(Priority.NORMAL), List.of(peer("dI", 0)), List.of(), have(7, 8, 9), availability(8, 9));
        Diagnosis diagnosis = stalled(d);
        assertEquals(Diagnosis.Title.STALLED, diagnosis.title());
        assertEquals(
                "2 pieces (2.0 KiB) are on no peer in the swarm: availability is 0.97."
                        + " They all belong to one file, extras/commentary.mkv.",
                diagnosis.text());
        assertEquals("extras/commentary.mkv", diagnosis.file());
        assertEquals(2, diagnosis.fileIndex());
        assertEquals(List.of(Diagnosis.Remedy.SKIP_FILE, Diagnosis.Remedy.ADD_TRACKERS), diagnosis.remedies());
        assertTrue(diagnosis.body().contains(Diagnosis.MARK));
    }

    @Test
    void oneMissingPieceIsSaidInTheSingular() {
        TorrentDetail d = detail(files(Priority.NORMAL), List.of(peer("dI", 0)), List.of(), have(9), availability(9));
        assertEquals(
                "1 piece (1.0 KiB) is on no peer in the swarm: availability is 0.97."
                        + " It belongs to one file, extras/commentary.mkv.",
                stalled(d).text());
    }

    @Test
    void piecesAcrossFilesOfferTrackersOnly() {
        TorrentDetail d =
                detail(files(Priority.NORMAL), List.of(peer("dI", 0)), List.of(), have(0, 9), availability(0, 9));
        Diagnosis diagnosis = stalled(d);
        assertEquals(List.of(Diagnosis.Remedy.ADD_TRACKERS), diagnosis.remedies());
        assertEquals("", diagnosis.file());
        assertEquals(-1, diagnosis.fileIndex());
    }

    @Test
    void aPieceOfASkippedFileIsNotMissed() {
        // The commentary is skipped: its pieces will never come, and nobody needs them to.
        TorrentDetail d = detail(
                files(Priority.IGNORE),
                List.of(peer("dI", 0), peer("dI", 0)),
                List.of(),
                have(8, 9),
                availability(8, 9));
        assertEquals(
                "2 peers are connected, none sending to you yet."
                        + " They send when a slot frees for you; there is nothing to do but wait.",
                stalled(d).text());
    }

    @Test
    void skippingIsNotOfferedWhenTheFileIsTheOnlyOneWanted() {
        List<TorrentDetail.FileStatus> one =
                List.of(new TorrentDetail.FileStatus(0, "only.iso", 10 * 1024, 0, Priority.NORMAL, 0, 9));
        TorrentDetail d = detail(one, List.of(peer("dI", 0)), List.of(), have(9), availability(9));
        assertEquals(List.of(Diagnosis.Remedy.ADD_TRACKERS), stalled(d).remedies());
    }

    @Test
    void withNoPeersTheTrackersAreTheStory() {
        List<TorrentDetail.Tracker> failing = List.of(
                new TorrentDetail.Tracker("http://a/announce", 0, "error", "timed out", 3),
                new TorrentDetail.Tracker("udp://b:80", 1, "error", "", 1));
        // Nobody connected: every missing piece is "on no peer", and that is not the news.
        Diagnosis d = stalled(detail(files(Priority.NORMAL), List.of(), failing, have(9), availability(9)));
        assertEquals("No peer is connected, and none of its 2 trackers answers.", d.text());
        assertEquals(List.of(Diagnosis.Remedy.ADD_TRACKERS, Diagnosis.Remedy.REANNOUNCE), d.remedies());

        Diagnosis one = stalled(detail(files(Priority.NORMAL), List.of(), failing.subList(0, 1), have(9), null));
        assertEquals("No peer is connected, and its tracker does not answer.", one.text());

        // An unreachable tracker is skipped and stays "not contacted": not answering all the same.
        List<TorrentDetail.Tracker> unreached =
                List.of(new TorrentDetail.Tracker("http://127.0.0.1:1/announce", 0, "not contacted", "", 0));
        assertEquals(
                "No peer is connected, and its tracker does not answer.",
                stalled(detail(files(Priority.NORMAL), List.of(), unreached, have(9), null))
                        .text());

        List<TorrentDetail.Tracker> working = new ArrayList<>(failing);
        working.set(1, new TorrentDetail.Tracker("udp://b:80", 1, "working", "", 0));
        assertEquals(
                "No peer is connected: the trackers answer, but nobody else is sharing it right now.",
                stalled(detail(files(Priority.NORMAL), List.of(), working, have(9), null))
                        .text());

        Diagnosis none = stalled(detail(files(Priority.NORMAL), List.of(), List.of(), have(9), null));
        assertEquals(
                "No peer is connected, and the torrent has no trackers: peers can come only from DHT and PEX.",
                none.text());
        assertEquals(List.of(Diagnosis.Remedy.ADD_TRACKERS), none.remedies());
    }

    @Test
    void peersThatChokeMeanWaiting() {
        Diagnosis d = stalled(detail(files(Priority.NORMAL), List.of(peer("dIE", 0)), List.of(), have(9), null));
        assertEquals(
                "1 peer is connected but not sending to you yet."
                        + " They send when a slot frees for you; there is nothing to do but wait.",
                d.text());
        assertTrue(d.remedies().isEmpty());
    }

    @Test
    void aPeerThatSendsLeavesOnlyTheQuiet() {
        Diagnosis d = stalled(detail(files(Priority.NORMAL), List.of(peer("DI", 0)), List.of(), have(9), null));
        assertEquals("Nothing has arrived for 1 min, though peers are connected.", d.text());
        assertEquals(List.of(Diagnosis.Remedy.REANNOUNCE), d.remedies());
    }

    @Test
    void anErrorSaysWhatLibtorrentSaid() {
        Torrent t = T.downloading().error("Permission denied: /data/films").build();
        Diagnosis d = Diagnosis.of(
                        t, RowState.of(t, 0), detail(files(Priority.NORMAL), List.of(), List.of(), have(), null), 0)
                .orElseThrow();
        assertEquals(Diagnosis.Title.ERROR, d.title());
        assertEquals("libtorrent stopped it: Permission denied: /data/films", d.text());
        assertEquals(List.of(Diagnosis.Remedy.RETRY, Diagnosis.Remedy.OPEN_FOLDER), d.remedies());
    }

    @Test
    void aQueuedDownloadSaysItsPlace() {
        TorrentDetail d = detail(files(Priority.NORMAL), List.of(), List.of(), have(9), null);
        Torrent queued = T.downloading().queued(2).build();
        Diagnosis diagnosis = Diagnosis.of(queued, RowState.of(queued, 0), d, 0).orElseThrow();
        assertEquals(Diagnosis.Title.QUEUED, diagnosis.title());
        assertEquals(
                "It is #3 in the download queue and starts when an active download finishes or is paused.",
                diagnosis.text());
        assertEquals(List.of(Diagnosis.Remedy.FORCE_RESUME), diagnosis.remedies());
        assertTrue(Diagnosis.of(T.seeding().queued(0).build(), RowState.QUEUED, d, 0)
                .isEmpty());
    }

    @Test
    void remediesThatAreCommandsNameThem() {
        assertEquals("reannounce", Diagnosis.Remedy.REANNOUNCE.command().orElseThrow());
        assertEquals("force-resume", Diagnosis.Remedy.RETRY.command().orElseThrow());
        assertTrue(Diagnosis.Remedy.SKIP_FILE.command().isEmpty());
        assertTrue(Diagnosis.Remedy.ADD_TRACKERS.command().isEmpty());
    }

    /**
     * Files that share pieces (a v1 torrent's boundaries fall inside pieces): a missing piece on no
     * peer counts once, and every wanted file it holds bytes of is named; a piece only skipped
     * files touch is not counted, even when a wanted file's neighbour shares the next one.
     */
    @Test
    void sharedPiecesCountOnceAndNameEveryWantedFileInThem() {
        List<TorrentDetail.FileStatus> files = List.of(
                new TorrentDetail.FileStatus(0, "a", 2500, 0, Priority.NORMAL, 0, 2),
                new TorrentDetail.FileStatus(1, "b", 1000, 0, Priority.NORMAL, 2, 3),
                new TorrentDetail.FileStatus(2, "empty", 0, 0, Priority.NORMAL, -1, -1),
                new TorrentDetail.FileStatus(3, "c", 2000, 0, Priority.IGNORE, 3, 5),
                new TorrentDetail.FileStatus(4, "d", 3000, 0, Priority.NORMAL, 6, 9));
        // 2 (a and b), 4 (c alone, skipped), 9 (d): missing and on nobody. 7 is missing but held.
        boolean[] have = have(2, 4, 7, 9);
        int[] availability = availability(2, 4, 9);
        Diagnosis.Unavailable u =
                Diagnosis.unavailable(detail(files, List.of(peer("dI", 0)), List.of(), have, availability));
        assertEquals(2, u.pieces(), "2 and 9; 4 is skipped files' only");
        assertEquals(List.of(0, 1, 4), List.copyOf(u.files()));
        assertEquals(2 * 1024L, u.bytes());

        // Only the shared piece: both its files, so skipping one is no remedy.
        Diagnosis.Unavailable shared =
                Diagnosis.unavailable(detail(files, List.of(peer("dI", 0)), List.of(), have(2), availability(2)));
        assertEquals(1, shared.pieces());
        assertEquals(List.of(0, 1), List.copyOf(shared.files()));
    }

    /**
     * 100,000 pieces under 50,000 wanted files of two pieces each, every other piece on no peer:
     * a search of every file per piece is five billion steps, the sweep a few hundred thousand.
     * The bound is a sanity check only, generous for a slow CI machine.
     */
    @Test
    void aLargeTorrentIsOneSweep() {
        int pieces = 100_000;
        int fileCount = 50_000;
        List<TorrentDetail.FileStatus> files = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++)
            files.add(new TorrentDetail.FileStatus(i, "f" + i, 2048, 0, Priority.NORMAL, 2 * i, 2 * i + 1));
        boolean[] have = new boolean[pieces];
        int[] availability = new int[pieces];
        for (int i = 0; i < pieces; i++) {
            have[i] = i % 4 == 0;
            availability[i] = i % 2 == 0 ? 3 : 0;
        }
        TorrentDetail d = new TorrentDetail(
                new TorrentDetail.General("id", "/data", false, pieces, 1024, 0, 0.5, 0, 0, 0, 0, 0, 0, 1, "", "", ""),
                files,
                List.of(peer("dI", 0)),
                List.of(),
                have,
                availability);
        Diagnosis.Unavailable u = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> Diagnosis.unavailable(d));
        assertEquals(pieces / 2, u.pieces(), "every odd piece");
        assertEquals(fileCount, u.files().size(), "each file has an odd piece");
    }

    @Test
    void anAvailabilityCountOfAnotherLengthIsIgnored() {
        TorrentDetail d = detail(files(Priority.NORMAL), List.of(peer("dI", 0)), List.of(), have(9), new int[3]);
        assertEquals(0, Diagnosis.unavailable(d).pieces());
    }

    /** With its file list in and its files still to choose, the card says so and offers the command. */
    @Test
    void aTorrentWaitingForItsFilesToBeChosenOffersTheChoice() {
        com.torrenta.engine.Torrent t = T.downloading().paused().build();
        TorrentDetail d = detail(files(Priority.NORMAL), List.of(), List.of(), have(), availability());
        Diagnosis card = Diagnosis.of(t, RowState.of(t, 0), d, 0, true).orElseThrow();
        assertEquals(Diagnosis.Title.CHOOSE, card.title());
        assertEquals(List.of(Diagnosis.Remedy.CHOOSE_FILES), card.remedies());
        assertEquals(java.util.Optional.of("choose-files"), Diagnosis.Remedy.CHOOSE_FILES.command());
        assertTrue(card.body().startsWith("Its file list is in: " + d.files().size() + " files."), card.body());
        assertTrue(Diagnosis.of(t, RowState.of(t, 0), d, 0, false).isEmpty(), "unmarked, a pause is no story");
        assertTrue(
                Diagnosis.of(t, RowState.of(t, 0), null, 0, true).isEmpty(),
                "no file list yet: nothing to choose from");
    }
}
