package com.torrenta.migrate;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What a real Deluge 2.2.0 (libtorrent 2.0.13) left in its config folder, adopted by a real
 * libtorrent session: the seed whole with its label, the pack's wanted file whole with its unwanted
 * one skipped under the name Deluge gave it, which survives a restart, the magnet stopped; no
 * check, and a truncated file checked and short.
 */
class DelugeImportTest {

    static Path fixture() {
        try {
            return Path.of(DelugeImportTest.class
                    .getResource("/com/torrenta/migrate/deluge-2.2")
                    .toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /** The same payloads and .torrent files as the other two clients' fixtures. */
    private static Path payload(Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Path qb = QbittorrentImportTest.fixture().resolve("payload");
        Files.copy(qb.resolve("Seed.bin"), downloads.resolve("Seed.bin"));
        Files.createDirectories(downloads.resolve("Pack"));
        Files.copy(qb.resolve("Pack/a.bin"), downloads.resolve("Pack/a.bin"));
        return downloads;
    }

    private static List<ImportCandidate> candidates() {
        return DelugeReader.read(fixture()).candidates();
    }

    private static ImportCandidate named(List<ImportCandidate> all, String name) {
        return all.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static Engine.Adoption adoption(ImportCandidate c, Path savePath) {
        ImportCandidate here = c.withSavePath(savePath.toString());
        ImportPlan.Item item = ImportPlan.of(List.of(here), Set.of(), ImportPlan::sizeOnDisk, false)
                .getFirst();
        return new Engine.Adoption(
                c.resume(),
                c.metainfo(),
                savePath.toString(),
                new TorrentLabels(c.category(), c.tags(), c.rename()),
                c.shownName(),
                item.verdict().checkedOnTheAdd(),
                c.renamed());
    }

    private static Torrent byId(Engine engine, String id) {
        return engine.torrents().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static double progress(Engine engine, ImportCandidate c) {
        Torrent t = byId(engine, c.id());
        return t == null ? -1 : t.progress();
    }

    private static void await(String what, BooleanSupplier condition, Engine engine) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("timed out waiting: " + what + " "
                + engine.torrents().stream()
                        .map(t -> t.name() + " " + t.state() + " " + t.progress())
                        .toList());
    }

    private static void flipAByte(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        bytes[100] ^= 0x5a;
        Files.write(file, bytes);
    }

    @Test
    void delugesTorrentsComeOverAtTheirProgressWithoutACheck(@TempDir Path dir) throws Exception {
        Path downloads = payload(dir);
        // Trusted, a changed byte goes unseen; a check would fail its piece.
        flipAByte(downloads.resolve("Seed.bin"));
        flipAByte(downloads.resolve("Pack/a.bin"));
        List<ImportCandidate> all = candidates();
        ImportCandidate seed = named(all, "Seed.bin");
        ImportCandidate pack = named(all, "Pack");
        ImportCandidate magnet = named(all, "Nobody.Serves.This");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        List<EngineEvent> finished = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (LibtorrentEngine engine = QuietEngine.open(downloads, store)) {
            engine.addListener(e -> {
                if (e.kind() == EngineEvent.Kind.FINISHED) finished.add(e);
            });
            engine.start();
            for (ImportCandidate c : all) assertEquals(c.id(), engine.adopt(adoption(c, downloads)), c.name());
            await(
                    "the seed whole, the pack's wanted file whole",
                    () -> progress(engine, seed) == 1.0 && progress(engine, pack) == 1.0,
                    engine);
            Thread.sleep(1000);
            assertEquals(1.0, progress(engine, seed), 1e-9, "still whole: nothing checked");
            assertEquals(1.0, progress(engine, pack), 1e-9, "still whole");
            Torrent s = byId(engine, seed.id());
            assertEquals("tv", s.category(), "the Label plugin's label");
            assertFalse(s.paused());
            Torrent p = byId(engine, pack.id());
            assertTrue(p.paused(), "paused in Deluge, paused here");
            assertEquals(65536, p.size(), "only the wanted file counts");
            TorrentDetail d = engine.detail(pack.id());
            assertEquals(Priority.IGNORE, d.files().get(1).priority(), "unwanted there, skipped here");
            assertEquals("Pack/bee.bin", d.files().get(1).path(), "the name Deluge gave it");
            Torrent m = byId(engine, magnet.id());
            assertEquals("Nobody.Serves.This", m.name());
            assertTrue(m.paused());
            assertEquals(List.of(), finished, "nothing finished now: they were complete already");
        }
        try (LibtorrentEngine again = QuietEngine.open(downloads, store)) {
            again.start();
            await("back as they were", () -> progress(again, seed) == 1.0 && progress(again, pack) == 1.0, again);
            assertEquals(
                    "Pack/bee.bin", again.detail(pack.id()).files().get(1).path(), "the rename survives a restart");
        }
    }

    @Test
    void aTruncatedFileComesUpShortNeverWrong(@TempDir Path dir) throws Exception {
        Path downloads = payload(dir);
        byte[] whole = Files.readAllBytes(downloads.resolve("Seed.bin"));
        Files.write(downloads.resolve("Seed.bin"), java.util.Arrays.copyOf(whole, 20_000));
        ImportCandidate seed = named(candidates(), "Seed.bin");
        try (LibtorrentEngine engine = QuietEngine.open(downloads, new TorrentStore(dir.resolve("store")))) {
            engine.start();
            engine.adopt(adoption(seed, downloads));
            await(
                    "checked and short",
                    () -> {
                        Torrent t = byId(engine, seed.id());
                        return t != null && t.progress() < 1.0 && t.progress() > 0;
                    },
                    engine);
        }
    }

    @Test
    void theReaderTakesWhatDelugeKept() throws Exception {
        DelugeReader.Found found = DelugeReader.read(fixture());
        assertEquals(List.of(), found.unreadable());
        assertEquals(3, found.candidates().size());
        ImportCandidate seed = named(found.candidates(), "Seed.bin");
        assertEquals("7d434420c3804d11c60483ef340365e3766d8e42", seed.id());
        assertEquals(DelugeReader.CLIENT, seed.client());
        assertEquals("/downloads", seed.savePath());
        assertEquals("tv", seed.category());
        assertEquals(List.of(), seed.tags(), "Deluge has one label, no tags");
        assertEquals(3, seed.piecesHave());
        assertEquals(2.5, seed.ratioLimit(), 1e-9, "stop at ratio 2.5, its own");
        assertFalse(seed.stopped());
        ImportCandidate pack = named(found.candidates(), "Pack");
        assertEquals(4, pack.piecesHave());
        assertEquals(8, pack.pieces());
        assertEquals(-1.0, pack.ratioLimit(), 1e-9, "no stop at ratio: none of its own");
        assertEquals(Map.of(1, "Pack/bee.bin"), pack.renamed(), "from the resume data's mapped_files");
        assertEquals(
                List.of(
                        new ImportCandidate.FileEntry("Pack/a.bin", 65536, true),
                        new ImportCandidate.FileEntry("Pack/bee.bin", 65536, false)),
                pack.files(),
                "the plan looks for the file under the name it has on disk");
        assertTrue(pack.stopped());
        ImportCandidate magnet = named(found.candidates(), "Nobody.Serves.This");
        assertEquals("233640c560f1c7062595a16289846e079d074172", magnet.id(), "no resume data: from its link");
        assertNull(magnet.metainfo());
        assertTrue(magnet.stopped());
        assertTrue(DelugeReader.isConfigFolder(fixture()));
        assertFalse(DelugeReader.isConfigFolder(TransmissionImportTest.fixture()));
        assertEquals(58846, DelugeReader.daemonPort(Files.readString(fixture().resolve("core.conf"))));
        assertEquals(
                Map.of("7d434420c3804d11c60483ef340365e3766d8e42", "tv"),
                DelugeReader.labels(Files.readString(fixture().resolve("label.conf"))));
    }

    @Test
    void knownLocationsFollowThePlatform() {
        Path home = Path.of("/home/someone");
        assertEquals(
                List.of(
                        home.resolve(".config/deluge"),
                        home.resolve(".var/app/org.deluge_torrent.deluge/config/deluge"),
                        Path.of("/var/lib/deluged/config")),
                DelugeReader.knownLocations("Linux", home, Map.of()));
        assertEquals(List.of(home.resolve(".config/deluge")), DelugeReader.knownLocations("Mac OS X", home, Map.of()));
        assertEquals(
                List.of(Path.of("C:/Users/someone/AppData/Roaming", "deluge")),
                DelugeReader.knownLocations("Windows 11", home, Map.of("APPDATA", "C:/Users/someone/AppData/Roaming")));
    }
}
