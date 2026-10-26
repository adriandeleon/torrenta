package com.torrenta.engine.libtorrent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.ContentIndex;
import com.torrenta.engine.DedupFiles;
import com.torrenta.engine.DedupPlan;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.FoundFile;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libtorrent4j.TorrentBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The engine keeps the content index from its own alerts: a hybrid torrent's complete files are
 * there once it is checked, under the name a rename gave, again after a restart (built from the
 * restored torrents' checks, never stored), and gone when it is removed; a new torrent sharing one
 * of their files gets its plan from it.
 */
class ContentIndexEngineTest {

    /** Loopback, any port, nothing that finds peers: the test is about the disk. */
    private static EngineSettings quiet() {
        EngineSettings d = EngineSettings.defaults();
        EngineSettings.Protocol p = d.protocol();
        return new EngineSettings(
                0,
                false,
                "127.0.0.1",
                d.ipv6(),
                false,
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                "",
                1080,
                "",
                "",
                false,
                new EngineSettings.Protocol(false, false, false, false, p.encryption(), false, List.of()),
                d.speed(),
                d.queue());
    }

    private static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] torrentOf(Path folder) throws Exception {
        NativeLibrary.ensureAvailable(); // TorrentBuilder is native too
        return new TorrentBuilder().path(folder.toFile()).generate().entry().bencode();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("timed out waiting: " + what);
    }

    private static Path pathOf(ContentIndex index, String id, String root) {
        return index.of(id).stream()
                .filter(e -> e.root().equals(root))
                .map(ContentIndex.Entry::path)
                .findFirst()
                .orElse(null);
    }

    @Test
    void theIndexFollowsTheTorrentsAndAPlanIsMadeFromIt(@TempDir Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Path album = downloads.resolve("Album");
        Files.createDirectories(album.resolve("sub"));
        byte[] shared = random(100 * 1024, 1);
        Files.write(album.resolve("a.bin"), shared);
        Files.write(album.resolve("sub/b.bin"), random(50 * 1024, 2));
        byte[] albumTorrent = torrentOf(album);

        // Another torrent with a.bin's bytes under another name, made elsewhere.
        Path made = Files.createDirectories(dir.resolve("made/Other"));
        Files.write(made.resolve("copy.bin"), shared);
        Files.write(made.resolve("new.bin"), random(30 * 1024, 3));
        byte[] otherTorrent = torrentOf(made);

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String id;
        String rootA;
        try (LibtorrentEngine engine = new LibtorrentEngine(() -> downloads, store, quiet())) {
            engine.start();
            TorrentPreview preview = engine.inspect(albumTorrent);
            assertTrue(preview.v2(), "TorrentBuilder makes hybrids");
            rootA = preview.files().stream()
                    .filter(f -> f.path().endsWith("a.bin"))
                    .findFirst()
                    .orElseThrow()
                    .root();
            assertTrue(ContentIndex.isRoot(rootA), "a v2 file's root, from the preview");

            Torrent added = engine.add(
                    AddRequest.defaults(new AddRequest.TorrentFile(albumTorrent, "Album.torrent"), downloads));
            id = added.id();
            ContentIndex index = engine.contentIndex();
            await("both files indexed at the check", () -> index.of(id).size() == 2);
            assertEquals(album.resolve("a.bin").toAbsolutePath().normalize(), pathOf(index, id, rootA));
            for (TorrentPreview.FileEntry f : preview.files())
                assertEquals(1, index.find(f.root()).size(), "every file by the root its preview has: " + f.path());

            engine.renamePath(id, "Album/a.bin", "renamed.bin");
            Path renamed = album.resolve("renamed.bin").toAbsolutePath().normalize();
            await("the rename followed", () -> renamed.equals(pathOf(index, id, rootA)));

            // The new torrent's copy.bin is a.bin's bytes: planned from disk, linked, nothing else.
            TorrentPreview other = engine.inspect(otherTorrent);
            Path start = Files.createDirectories(dir.resolve("next"));
            DedupPlan plan =
                    DedupPlan.of(other, index, Set.of(), start, FileRenames.Layout.ORIGINAL, DedupPlan.Disk.SYSTEM);
            assertEquals(1, plan.matches().size());
            DedupPlan.Match m = plan.matches().getFirst();
            assertEquals("Other/copy.bin", m.path());
            assertEquals(renamed, m.source().path(), "found by its bytes, under the name it has now");
            assertEquals(start.resolve("Other/copy.bin").toAbsolutePath().normalize(), m.target());
            assertEquals(DedupPlan.Method.LINK, m.method(), "one temporary folder, one volume");
            assertEquals(shared.length, plan.bytes());
        }

        // Never stored: built again from the restored torrent's check, under the renamed name.
        try (LibtorrentEngine again = new LibtorrentEngine(() -> downloads, store, quiet())) {
            again.start();
            ContentIndex index = again.contentIndex();
            Path renamed = album.resolve("renamed.bin").toAbsolutePath().normalize();
            await("rebuilt at start", () -> index.of(id).size() == 2 && renamed.equals(pathOf(index, id, rootA)));
            again.remove(id, false);
            assertTrue(index.of(id).isEmpty(), "a removed torrent's files are not offered");
            assertTrue(index.find(rootA).isEmpty());
        }
    }

    /**
     * Item 5's promise, end to end: a file linked into place before the add is verified by the
     * add's own check and counts as downloaded, with no peer anywhere; it is the hybrid's second
     * file, so its v1 pieces are its own only thanks to the padding. The Files tab's record of it
     * survives a restart.
     */
    @Test
    void aFileFoundOnDiskIsLinkedAndPassesTheAddsOwnCheck(@TempDir Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Path album = Files.createDirectories(downloads.resolve("Album"));
        byte[] shared = random(100 * 1024 + 123, 7);
        Files.write(album.resolve("track.bin"), shared);
        byte[] albumTorrent = torrentOf(album);

        Path made = Files.createDirectories(dir.resolve("made/Other"));
        Files.write(made.resolve("a-new.bin"), random(30 * 1024 + 7, 8));
        Files.write(made.resolve("z-shared.bin"), shared);
        byte[] otherTorrent = torrentOf(made);

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        Path start = Files.createDirectories(dir.resolve("next"));
        String otherId;
        try (LibtorrentEngine engine = new LibtorrentEngine(() -> downloads, store, quiet())) {
            engine.start();
            String albumId = engine.add(
                            AddRequest.defaults(new AddRequest.TorrentFile(albumTorrent, "Album.torrent"), downloads))
                    .id();
            await("the album indexed", () -> engine.contentIndex().of(albumId).size() == 1);

            TorrentPreview other = engine.inspect(otherTorrent);
            AddRequest request = AddRequest.defaults(new AddRequest.TorrentFile(otherTorrent, "Other.torrent"), start);
            DedupPlan plan = DedupPlan.of(
                    other,
                    engine.contentIndex(),
                    Set.of(),
                    request.storage().start(request.savePath()),
                    request.storage().layout(),
                    DedupPlan.Disk.SYSTEM);
            DedupFiles.Placed placed = DedupFiles.place(plan, true, true, DedupFiles.Cloner.NONE, id -> "Album");
            assertEquals(1, placed.found().size());
            Path target = start.resolve("Other/z-shared.bin");
            assertTrue(Files.isSameFile(album.resolve("track.bin"), target), "linked, not copied");

            otherId = engine.add(request.withStorage(request.storage().withFound(placed.found())))
                    .id();
            String id = otherId;
            int sharedIndex = placed.found().keySet().iterator().next();
            await("the linked file whole by the add's own check", () -> {
                TorrentDetail.FileStatus f = fileOf(engine, id, sharedIndex);
                return f != null && f.done() == f.size();
            });
            TorrentDetail.FileStatus f = fileOf(engine, id, sharedIndex);
            assertEquals(new FoundFile(FoundFile.How.LINK, "Album"), f.found());
            // A hybrid's padding sits between the two, so the other file is found by its name.
            TorrentDetail.FileStatus fresh = engine.detail(id).files().stream()
                    .filter(x -> x.path().endsWith("a-new.bin"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(0, fresh.done(), "the other file still to download");
            assertEquals(null, fresh.found());
        }
        try (LibtorrentEngine again = new LibtorrentEngine(() -> downloads, store, quiet())) {
            again.start();
            String id = otherId;
            await("back", () -> again.torrents().stream().anyMatch(t -> t.id().equals(id)));
            assertTrue(
                    again.detail(id).files().stream()
                            .anyMatch(x -> x.path().endsWith("z-shared.bin")
                                    && x.found() != null
                                    && x.found().how() == FoundFile.How.LINK),
                    "the Files tab's record survives a restart");
        }
    }

    private static TorrentDetail.FileStatus fileOf(LibtorrentEngine engine, String id, int index) {
        try {
            return engine.detail(id).files().stream()
                    .filter(f -> f.index() == index)
                    .findFirst()
                    .orElse(null);
        } catch (com.torrenta.engine.EngineException e) {
            return null;
        }
    }

    @Test
    void aV1OnlyTorrentIsIndexedByItsKeyAndAnIncompleteFileIsNot(@TempDir Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Path pack = Files.createDirectories(downloads.resolve("Pack"));
        Files.write(pack.resolve("whole.bin"), random(64 * 1024, 4));
        Files.write(pack.resolve("short.bin"), random(64 * 1024, 5));
        byte[] hybrid = torrentOf(pack);
        // The second file loses its last block before the add: checked, it is incomplete.
        byte[] full = Files.readAllBytes(pack.resolve("short.bin"));
        Files.write(pack.resolve("short.bin"), java.util.Arrays.copyOf(full, 40 * 1024));

        Path single = Files.createDirectories(dir.resolve("v1"));
        Files.write(single.resolve("old.bin"), random(64 * 1024, 6));
        NativeLibrary.ensureAvailable();
        byte[] v1 = new TorrentBuilder()
                .path(single.resolve("old.bin").toFile())
                .flags(TorrentBuilder.V1_ONLY)
                .generate()
                .entry()
                .bencode();

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        String packId;
        try (LibtorrentEngine engine = new LibtorrentEngine(() -> downloads, store, quiet())) {
            engine.start();
            packId = engine.add(AddRequest.defaults(new AddRequest.TorrentFile(hybrid, "Pack.torrent"), downloads))
                    .id();
            String v1Id = engine.add(AddRequest.defaults(new AddRequest.TorrentFile(v1, "old.torrent"), single))
                    .id();
            ContentIndex index = engine.contentIndex();
            await("the pack checked", () -> index.of(packId).size() == 1);
            assertTrue(index.of(packId).getFirst().path().endsWith("whole.bin"), "only the complete file");
            await(
                    "the v1 torrent seeding",
                    () -> engine.torrents().stream().anyMatch(t -> t.id().equals(v1Id) && t.progress() == 1.0));
            await("the v1 file by its key", () -> index.of(v1Id).size() == 1);
            assertTrue(
                    com.torrenta.engine.ContentKeys.isV1(
                            index.of(v1Id).getFirst().root()),
                    "no root without v2: its key, a single file's pieces being its own");
        }
        // A torrent part done is not finished at a start: its complete file is indexed as it loads.
        try (LibtorrentEngine again = new LibtorrentEngine(() -> downloads, store, quiet())) {
            again.start();
            ContentIndex index = again.contentIndex();
            await(
                    "the part-done pack's whole file, at start",
                    () -> index.of(packId).size() == 1);
            // Damaged on disk, found by a recheck: no longer offered. Only the check can say so;
            // nothing else is posted for a file that stopped being complete. Not paused: libtorrent
            // never runs a paused torrent's check (and with no peers nothing is in flight).
            byte[] whole = Files.readAllBytes(pack.resolve("whole.bin"));
            whole[10] ^= 0x5a;
            Files.write(pack.resolve("whole.bin"), whole);
            again.recheck(packId);
            await("the damaged file left out", () -> index.of(packId).isEmpty());
        }
    }

    /**
     * A v1-only torrent's file, found by its key in another v1-only torrent of the same piece
     * length, is linked into place and passes the add's own check; a v1 file that shares a piece
     * with its neighbour has no key and is downloaded as before.
     */
    @Test
    void aV1FileWhosePiecesAreItsOwnIsFoundAndLinked(@TempDir Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        byte[] film = random(300 * 1024 + 11, 9);
        Files.write(downloads.resolve("film.mkv"), film);
        byte[] seeded = v1Only(downloads.resolve("film.mkv"));

        // The same bytes under another name, single-file too, and inside a pack after an odd-sized file.
        Path made = Files.createDirectories(dir.resolve("made"));
        Files.write(made.resolve("Film (2024).mkv"), film);
        byte[] renamed = v1Only(made.resolve("Film (2024).mkv"));
        Path pack = Files.createDirectories(made.resolve("Pack"));
        Files.write(pack.resolve("a.nfo"), random(1000, 10));
        Files.write(pack.resolve("b.mkv"), film);
        byte[] packed = v1Only(pack);

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        Path start = Files.createDirectories(dir.resolve("next"));
        try (LibtorrentEngine engine = new LibtorrentEngine(() -> downloads, store, quiet())) {
            engine.start();
            String seedId = engine.add(
                            AddRequest.defaults(new AddRequest.TorrentFile(seeded, "film.torrent"), downloads))
                    .id();
            await("the film indexed", () -> engine.contentIndex().of(seedId).size() == 1);

            TorrentPreview inPack = engine.inspect(packed);
            assertTrue(inPack.files().stream()
                    .allMatch(f -> f.v1Key().isEmpty() || f.path().endsWith("a.nfo")));
            assertTrue(
                    DedupPlan.of(
                                    inPack,
                                    engine.contentIndex(),
                                    Set.of(),
                                    start,
                                    FileRenames.Layout.ORIGINAL,
                                    DedupPlan.Disk.SYSTEM)
                            .isEmpty(),
                    "b.mkv starts inside a.nfo's piece: no key, no match");

            TorrentPreview other = engine.inspect(renamed);
            assertTrue(com.torrenta.engine.ContentKeys.isV1(
                    other.files().getFirst().v1Key()));
            AddRequest request = AddRequest.defaults(new AddRequest.TorrentFile(renamed, "renamed.torrent"), start);
            DedupPlan plan = DedupPlan.of(
                    other,
                    engine.contentIndex(),
                    Set.of(),
                    request.storage().start(request.savePath()),
                    request.storage().layout(),
                    DedupPlan.Disk.SYSTEM);
            assertEquals(1, plan.matches().size());
            DedupFiles.Placed placed = DedupFiles.place(plan, true, true, DedupFiles.Cloner.NONE, id -> "film");
            Path target = start.resolve("Film (2024).mkv");
            assertTrue(Files.isSameFile(downloads.resolve("film.mkv"), target), "linked, not copied");
            String id = engine.add(request.withStorage(request.storage().withFound(placed.found())))
                    .id();
            await(
                    "whole by the add's own check",
                    () -> engine.torrents().stream().anyMatch(t -> t.id().equals(id) && t.progress() == 1.0));
        }
    }

    /** A v1-only torrent in 16 KiB pieces, so two of them cut the same bytes the same way. */
    private static byte[] v1Only(Path path) throws Exception {
        NativeLibrary.ensureAvailable();
        return new TorrentBuilder()
                .path(path.toFile())
                .pieceSize(16 * 1024)
                .flags(TorrentBuilder.V1_ONLY)
                .generate()
                .entry()
                .bencode();
    }
}
