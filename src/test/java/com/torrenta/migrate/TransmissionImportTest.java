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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What a real Transmission 4.1 left in its config folder, converted to libtorrent's resume format
 * and adopted by a real libtorrent session: the seed whole, the pack's wanted file whole with its
 * unwanted one skipped under the name Transmission gave it, the magnet stopped; no check, a
 * truncated file checked and short.
 */
class TransmissionImportTest {

    static Path fixture() {
        try {
            return Path.of(TransmissionImportTest.class
                    .getResource("/com/torrenta/migrate/transmission-4.1")
                    .toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /** The same payloads as qBittorrent's fixture: the same two .torrent files went into both clients. */
    private static Path payload(Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Path qb = QbittorrentImportTest.fixture().resolve("payload");
        Files.copy(qb.resolve("Seed.bin"), downloads.resolve("Seed.bin"));
        Files.createDirectories(downloads.resolve("Pack"));
        Files.copy(qb.resolve("Pack/a.bin"), downloads.resolve("Pack/a.bin"));
        return downloads;
    }

    private static List<ImportCandidate> candidates() {
        return TransmissionReader.read(fixture()).candidates();
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

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("timed out waiting: " + what);
    }

    private static void flipAByte(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        bytes[100] ^= 0x5a;
        Files.write(file, bytes);
    }

    @Test
    void transmissionsTorrentsComeOverAtTheirProgressWithoutACheck(@TempDir Path dir) throws Exception {
        Path downloads = payload(dir);
        // Trusted, a changed byte goes unseen; a check would fail its piece.
        flipAByte(downloads.resolve("Seed.bin"));
        flipAByte(downloads.resolve("Pack/a.bin"));
        List<ImportCandidate> all = candidates();
        ImportCandidate seed = all.stream()
                .filter(c -> c.name().equals("Seed.bin"))
                .findFirst()
                .orElseThrow();
        ImportCandidate pack =
                all.stream().filter(c -> c.name().equals("Pack")).findFirst().orElseThrow();
        ImportCandidate magnet =
                all.stream().filter(c -> c.metainfo() == null).findFirst().orElseThrow();
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
                    () -> byId(engine, seed.id()).progress() == 1.0
                            && byId(engine, pack.id()).progress() == 1.0);
            Thread.sleep(1000);
            assertEquals(1.0, byId(engine, seed.id()).progress(), 1e-9, "still whole: nothing checked");
            Torrent s = byId(engine, seed.id());
            assertEquals("tv", s.category(), "Transmission's category: label");
            assertEquals(List.of("keep"), s.tags());
            Torrent p = byId(engine, pack.id());
            assertTrue(p.paused(), "stopped in Transmission, stopped here");
            assertEquals(65536, p.size(), "only the wanted file counts");
            TorrentDetail d = engine.detail(pack.id());
            assertEquals(Priority.IGNORE, d.files().get(1).priority(), "unwanted there, skipped here");
            assertEquals("Pack/bee.bin", d.files().get(1).path(), "the name Transmission gave it");
            assertTrue(byId(engine, magnet.id()).paused());
            assertEquals(List.of(), finished, "nothing finished now: they were complete already");
        }
        try (LibtorrentEngine again = QuietEngine.open(downloads, store)) {
            again.start();
            await(
                    "back as they were",
                    () -> byId(again, seed.id()) != null
                            && byId(again, seed.id()).progress() == 1.0
                            && byId(again, pack.id()).progress() == 1.0);
            assertEquals(
                    "Pack/bee.bin", again.detail(pack.id()).files().get(1).path(), "the rename survives a restart");
        }
    }

    @Test
    void aTruncatedFileComesUpShortNeverWrong(@TempDir Path dir) throws Exception {
        Path downloads = payload(dir);
        byte[] whole = Files.readAllBytes(downloads.resolve("Seed.bin"));
        Files.write(downloads.resolve("Seed.bin"), java.util.Arrays.copyOf(whole, 20_000));
        ImportCandidate seed = candidates().stream()
                .filter(c -> c.name().equals("Seed.bin"))
                .findFirst()
                .orElseThrow();
        try (LibtorrentEngine engine = QuietEngine.open(downloads, new TorrentStore(dir.resolve("store")))) {
            engine.start();
            engine.adopt(adoption(seed, downloads));
            await("checked and short", () -> {
                Torrent t = byId(engine, seed.id());
                return t != null && t.progress() < 1.0 && t.progress() > 0;
            });
        }
    }

    @Test
    void theReaderTakesWhatTransmissionKept() {
        TransmissionReader.Found found = TransmissionReader.read(fixture());
        assertEquals(List.of(), found.unreadable());
        assertEquals(3, found.candidates().size());
        ImportCandidate seed = found.candidates().stream()
                .filter(c -> c.name().equals("Seed.bin"))
                .findFirst()
                .orElseThrow();
        assertEquals("7d434420c3804d11c60483ef340365e3766d8e42", seed.id());
        assertEquals("/srv/transmission/dl", seed.savePath());
        assertEquals(3, seed.piecesHave());
        assertEquals(-2.0, seed.ratioLimit(), 1e-9, "ratio-mode 0: the global limit");
        ImportCandidate pack = found.candidates().stream()
                .filter(c -> c.name().equals("Pack"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                4,
                pack.piecesHave(),
                "from its blocks, f0: the first four of eight; its 'pieces: all' is the check state");
        assertEquals(8, pack.pieces());
        assertEquals(Map.of(1, "Pack/bee.bin"), pack.renamed());
        assertEquals(
                List.of(
                        new ImportCandidate.FileEntry("Pack/a.bin", 65536, true),
                        new ImportCandidate.FileEntry("Pack/bee.bin", 65536, false)),
                pack.files(),
                "the plan looks for the file under the name it has on disk");
        assertTrue(pack.stopped());
        ImportCandidate magnet = found.candidates().stream()
                .filter(c -> c.metainfo() == null)
                .findFirst()
                .orElseThrow();
        assertEquals("233640c560f1c7062595a16289846e079d074172", magnet.id(), "from the .magnet file");
        assertNull(magnet.metainfo());
        assertTrue(TransmissionReader.isConfigFolder(fixture()));
    }
}
