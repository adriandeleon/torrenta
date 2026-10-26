package com.torrenta.migrate;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.Engine;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real libtorrent session adopting what a real qBittorrent 5.1.0 left in its {@code BT_backup}:
 * a complete seed, a half-downloaded two-file torrent stopped and renamed, a magnet without
 * metadata. They must reach their progress without a check, keep it across a restart, and a
 * truncated file must come up short.
 */
class QbittorrentImportTest {

    static Path fixture() {
        try {
            return Path.of(QbittorrentImportTest.class
                    .getResource("/com/torrenta/migrate/qbittorrent-5.1")
                    .toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /** The fixture's data where the import will say it is. */
    private static Path payload(Path dir) throws Exception {
        Path downloads = Files.createDirectories(dir.resolve("downloads"));
        Files.copy(fixture().resolve("payload/Seed.bin"), downloads.resolve("Seed.bin"));
        Files.createDirectories(downloads.resolve("Pack"));
        Files.copy(fixture().resolve("payload/Pack/a.bin"), downloads.resolve("Pack/a.bin"));
        return downloads;
    }

    private static List<ImportCandidate> candidates() {
        Map<String, String> env = new HashMap<>();
        return QbittorrentReader.read(fixture(), Path.of("/nonexistent-home"), env)
                .candidates();
    }

    /** As the import does it: the plan looks at the disk, and a torrent that does not match is checked. */
    private static Engine.Adoption adoption(ImportCandidate c, Path savePath) {
        ImportCandidate here = c.withSavePath(savePath.toString());
        ImportPlan.Item item = ImportPlan.of(List.of(here), java.util.Set.of(), ImportPlan::sizeOnDisk, false)
                .getFirst();
        return new Engine.Adoption(
                c.resume(),
                c.metainfo(),
                savePath.toString(),
                new TorrentLabels(c.category(), c.tags(), c.rename()),
                c.shownName(),
                item.verdict() == ImportPlan.Verdict.CHECK);
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

    @Test
    void qbittorrentsTorrentsComeOverAtTheirProgressWithoutACheck(@TempDir Path dir) throws Exception {
        Path downloads = payload(dir);
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        List<ImportCandidate> candidates = candidates();
        ImportCandidate seed = candidates.stream()
                .filter(ImportCandidate::complete)
                .findFirst()
                .orElseThrow();
        ImportCandidate pack = candidates.stream()
                .filter(c -> c.rename().equals("Pack Renamed"))
                .findFirst()
                .orElseThrow();
        ImportCandidate magnet = candidates.stream()
                .filter(c -> c.metainfo() == null)
                .findFirst()
                .orElseThrow();
        // The proof that nothing was checked: a byte changed in each file, its size kept. Trusted, the
        // resume data's progress stands; checked, that piece would fail and the torrent drop below it.
        flipAByte(downloads.resolve("Seed.bin"));
        flipAByte(downloads.resolve("Pack/a.bin"));
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, store)) {
            engine.start();
            assertTrue(engine.capabilities().has(Engine.Capability.IMPORT_RESUME));
            for (ImportCandidate c : candidates) assertEquals(c.id(), engine.adopt(adoption(c, downloads)), c.name());

            await(
                    "the seed whole and the pack half done, from the resume data",
                    () -> progress(engine, seed) == 1.0 && progress(engine, pack) == 0.5);
            Thread.sleep(1000);
            assertEquals(1.0, progress(engine, seed), 1e-9, "still whole: no check found the changed byte");
            assertEquals(0.5, progress(engine, pack), 1e-9, "still half");
            Torrent s = byId(engine, seed.id());
            assertTrue(
                    s.state() == TorrentState.SEEDING || s.state() == TorrentState.FINISHED,
                    s.state().name());
            assertEquals("tv", s.category());
            assertEquals(List.of("keep"), s.tags());
            Torrent p = byId(engine, pack.id());
            assertTrue(p.paused(), "stopped in qBittorrent, stopped here");
            assertEquals("Pack Renamed", p.name(), "the name a person gave it");
            Torrent m = byId(engine, magnet.id());
            assertEquals("Nobody.Serves.This", m.name());
            assertTrue(m.paused());
            assertTrue(engine.adopt(adoption(seed, downloads)) == null, "unreachable");
        } catch (com.torrenta.engine.EngineException expected) {
            assertTrue(
                    expected.getMessage().contains("Seed.bin"),
                    "a second import of the same torrent is refused by name");
        }

        // Written as this engine's own: a restart brings them back as they were, still unchecked.
        try (LibtorrentEngine again = new LibtorrentEngine(downloads, store)) {
            again.start();
            assertEquals(3, again.torrents().size());
            await("back as they were", () -> progress(again, seed) == 1.0 && progress(again, pack) == 0.5);
            assertEquals("Pack Renamed", byId(again, pack.id()).name());
            assertEquals("tv", byId(again, seed.id()).category());
        }
    }

    private static double progress(Engine engine, ImportCandidate c) {
        Torrent t = byId(engine, c.id());
        return t == null ? -1 : t.progress();
    }

    /** One byte of the file's first piece changed, its size kept: only a check can tell. */
    private static void flipAByte(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        bytes[100] ^= 0x5a;
        Files.write(file, bytes);
    }

    /** The resume data says whole; the disk says otherwise: libtorrent checks, and it comes up short. */
    @Test
    void aTruncatedFileComesUpShortNeverWrong(@TempDir Path dir) throws Exception {
        Path downloads = payload(dir);
        byte[] whole = Files.readAllBytes(downloads.resolve("Seed.bin"));
        Files.write(downloads.resolve("Seed.bin"), java.util.Arrays.copyOf(whole, 20_000));
        ImportCandidate seed = candidates().stream()
                .filter(ImportCandidate::complete)
                .findFirst()
                .orElseThrow();
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, new TorrentStore(dir.resolve("store")))) {
            engine.start();
            engine.adopt(adoption(seed, downloads));
            await("checked and short", () -> {
                Torrent t = byId(engine, seed.id());
                return t != null && t.state() != TorrentState.CHECKING && t.progress() < 1.0 && t.progress() > 0;
            });
        }
    }
}
