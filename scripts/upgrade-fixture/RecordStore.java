import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.store.TorrentStore;
import org.libtorrent4j.TorrentBuilder;

/**
 * Writes a torrent store with a released build of Torrenta, for {@code UpgradeRestoreTest}.
 * Compiled and run against that build's own classes by {@code scripts/upgrade-fixture.sh}, so it
 * uses only the engine calls every release since 0.1.0 has had.
 *
 * <p>Six torrents: a single file seeding; a folder seeding, filed, tagged, given a name, one
 * file renamed and one skipped, paused; a folder with one of its three files missing, paused;
 * a magnet whose metadata never came; and two added with the {@code .part} suffix over files
 * already there under it, so the check finds them whole and the suffix comes off (the shape
 * 0.4.0 downloaded again at restart): one finished and seeding, one with a file missing, paused.
 * The payloads are {@link #PAYLOADS}, made from fixed seeds, which the test makes again: only the
 * store, what this build showed of it and the names on disk at the end are kept.
 *
 * <p>Arguments: the output folder, the version.
 */
public final class RecordStore {

    /** Path, size, seed. Kept in step with {@code UpgradeRestoreTest.PAYLOADS}. */
    static final Object[][] PAYLOADS = {
        {"single.bin", 96 * 1024, 1},
        {"multi/a.bin", 64 * 1024, 2},
        {"multi/b.bin", 64 * 1024, 3},
        {"multi/sub/c.bin", 32 * 1024, 4},
        {"partial/a.bin", 64 * 1024, 5},
        {"partial/b.bin", 64 * 1024, 6},
        {"partial/c.bin", 64 * 1024, 7},
        {"finished/a.bin", 64 * 1024, 8},
        {"finished/b.bin", 32 * 1024, 9},
        {"suffixed/a.bin", 64 * 1024, 10},
        {"suffixed/b.bin", 64 * 1024, 11},
        {"suffixed/c.bin", 64 * 1024, 12},
    };

    static final String MAGNET_ID = "5d1c0ffee5d1c0ffee5d1c0ffee5d1c0ffee5d1c";
    static final String MAGNET = "magnet:?xt=urn:btih:" + MAGNET_ID + "&dn=Upgrade%20magnet";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]).toAbsolutePath();
        String version = args[1];
        Path work = Files.createTempDirectory("torrenta-upgrade-");
        Path downloads = work.resolve("downloads");
        for (Object[] p : PAYLOADS) {
            Path file = downloads.resolve((String) p[0]);
            Files.createDirectories(file.getParent());
            Files.write(file, payload(p));
        }
        TorrentStore store = new TorrentStore(work.resolve("store"));
        Properties seen = new Properties();
        seen.setProperty("version", version);
        seen.setProperty("savePath", downloads.toString());
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, store)) {
            engine.start(); // loads the native, which TorrentBuilder needs too
            Path single = torrent(work, downloads.resolve("single.bin"));
            Path multi = torrent(work, downloads.resolve("multi"));
            Path partial = torrent(work, downloads.resolve("partial"));
            Path finished = torrent(work, downloads.resolve("finished"));
            Path suffixed = torrent(work, downloads.resolve("suffixed"));
            Files.delete(downloads.resolve("partial/c.bin"));
            Files.delete(downloads.resolve("suffixed/c.bin"));
            // The suffixed ones' data "arrives" later, under the .part names the engine gives them.
            Path aside = work.resolve("aside");
            List<String> arriving = List.of("finished/a.bin", "finished/b.bin", "suffixed/a.bin", "suffixed/b.bin");
            for (String name : arriving) {
                Files.createDirectories(aside.resolve(name).getParent());
                Files.move(downloads.resolve(name), aside.resolve(name));
            }

            String singleId = engine.add(single.toString()).id();
            await("single seeding", () -> state(engine, singleId) == TorrentState.SEEDING);

            String multiId = engine.add(multi.toString()).id();
            await("multi seeding", () -> state(engine, multiId) == TorrentState.SEEDING);
            engine.setCategory(multiId, "Films");
            engine.setTags(multiId, List.of("hd", "keep"));
            engine.rename(multiId, "My multi");
            engine.renamePath(multiId, "multi/b.bin", "b-renamed.bin");
            await("renamed on disk", () -> Files.isRegularFile(downloads.resolve("multi/b-renamed.bin")));
            int c = engine.detail(multiId).files().stream()
                    .filter(f -> f.path().endsWith("/c.bin"))
                    .findFirst()
                    .orElseThrow()
                    .index();
            engine.setFilePriority(multiId, c, Priority.IGNORE);
            engine.pause(multiId);

            String partialId = engine.add(partial.toString()).id();
            await(
                    "partial checked",
                    () -> state(engine, partialId) == TorrentState.DOWNLOADING
                            && torrentOf(engine, partialId).progress() > 0.5);
            engine.pause(partialId);

            engine.add(MAGNET);

            String finishedId = engine.add(suffixed(finished, downloads)).id();
            String suffixedId = engine.add(suffixed(suffixed, downloads)).id();
            for (String id : List.of(finishedId, suffixedId))
                await("the suffix on " + id, () -> suffixedNow(store, id));
            for (String name : arriving) {
                Path part = downloads.resolve(name + ".part");
                // The engine takes away a folder its renames emptied, on its own thread: again until it stays.
                for (int attempt = 0; ; attempt++) {
                    try {
                        Files.createDirectories(part.getParent());
                        Files.move(aside.resolve(name), part);
                        break;
                    } catch (java.nio.file.NoSuchFileException e) {
                        if (attempt == 50) throw e;
                        Thread.sleep(100);
                    }
                }
            }
            engine.recheck(finishedId);
            engine.recheck(suffixedId);
            await(
                    "finished seeding, the suffix off",
                    () -> state(engine, finishedId) == TorrentState.SEEDING
                            && Files.isRegularFile(downloads.resolve("finished/a.bin"))
                            && Files.isRegularFile(downloads.resolve("finished/b.bin")));
            await(
                    "suffixed checked, its whole files' suffix off",
                    () -> state(engine, suffixedId) == TorrentState.DOWNLOADING
                            && torrentOf(engine, suffixedId).progress() > 0.5
                            && Files.isRegularFile(downloads.resolve("suffixed/a.bin"))
                            && Files.isRegularFile(downloads.resolve("suffixed/b.bin")));
            engine.pause(suffixedId);

            await(
                    "every torrent settled",
                    () -> engine.torrents().stream()
                            .noneMatch(t -> t.state() == TorrentState.CHECKING || t.state() == TorrentState.UNKNOWN));
            seen.setProperty("single", singleId);
            seen.setProperty("multi", multiId);
            seen.setProperty("partial", partialId);
            seen.setProperty("magnet", MAGNET_ID);
            seen.setProperty("finished", finishedId);
            seen.setProperty("suffixed", suffixedId);
            for (Torrent t : engine.torrents()) describe(engine, t, seen);
        }

        Path storeOut = out.resolve("store");
        if (Files.exists(storeOut)) {
            try (Stream<Path> old = Files.list(storeOut)) {
                for (Path p : old.toList()) Files.delete(p);
            }
        }
        Files.createDirectories(storeOut);
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(store.dir())) {
            for (Path p : files.sorted().toList()) {
                Files.copy(p, storeOut.resolve(p.getFileName()));
                names.add(p.getFileName().toString());
            }
        }
        seen.setProperty("store", String.join(",", names));
        // Each file left on disk, by the payload it holds, so the test can lay the folder out again.
        List<String> disk = new ArrayList<>();
        try (Stream<Path> files = Files.walk(downloads)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(p);
                String holds = "unknown:" + bytes.length;
                for (Object[] payload : PAYLOADS)
                    if (java.util.Arrays.equals(bytes, payload((Object[]) payload))) holds = (String) payload[0];
                disk.add(downloads.relativize(p).toString().replace('\\', '/') + "=" + holds);
            }
        }
        seen.setProperty("disk", String.join("|", disk));
        try (OutputStream o = Files.newOutputStream(out.resolve("fixture.properties"))) {
            seen.store(o, "Written by Torrenta " + version + " through scripts/upgrade-fixture.sh");
        }
        System.out.println("recorded " + version + ": " + names);
        try (Stream<Path> all = Files.walk(work)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static byte[] payload(Object[] p) {
        byte[] bytes = new byte[(int) p[1]];
        new Random((int) p[2]).nextBytes(bytes);
        return bytes;
    }

    /** What this build showed of a torrent just before it stopped, as the test will compare it. */
    private static void describe(LibtorrentEngine engine, Torrent t, Properties seen) throws Exception {
        String k = t.id() + ".";
        seen.setProperty(k + "name", t.name());
        seen.setProperty(k + "paused", Boolean.toString(t.paused()));
        seen.setProperty(k + "progress", String.format(java.util.Locale.ROOT, "%.4f", t.progress()));
        seen.setProperty(k + "category", t.category());
        seen.setProperty(k + "tags", String.join(",", t.tags()));
        List<String> files = new ArrayList<>();
        for (TorrentDetail.FileStatus f : engine.detail(t.id()).files())
            files.add(f.path() + ":" + f.priority().name());
        seen.setProperty(k + "files", String.join("|", files));
    }

    /** Whether the storage record has files under the suffix (written once the add's check named them). */
    private static boolean suffixedNow(TorrentStore store, String id) {
        try {
            return store.loadStorage(id)
                    .map(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8))
                    .filter(text -> text.contains("\"suffixed\":[") && !text.contains("\"suffixed\":[]"))
                    .isPresent();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** The torrent added as the window adds it with "Keep incomplete files as .part" ticked. */
    private static AddRequest suffixed(Path torrent, Path downloads) throws Exception {
        return AddRequest.defaults(new AddRequest.TorrentFile(Files.readAllBytes(torrent), torrent.toString()), downloads)
                .withStorage(new AddRequest.Storage(null, true, false));
    }

    private static Path torrent(Path work, Path source) throws Exception {
        Path file = work.resolve(source.getFileName() + ".torrent");
        Files.write(
                file,
                new TorrentBuilder()
                        .path(source.toFile())
                        .pieceSize(16 * 1024)
                        .generate()
                        .entry()
                        .bencode());
        return file;
    }

    private static Torrent torrentOf(LibtorrentEngine engine, String id) {
        return engine.torrents().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static TorrentState state(LibtorrentEngine engine, String id) {
        return torrentOf(engine, id).state();
    }

    private static void await(String what, BooleanSupplier done) throws InterruptedException {
        long end = System.nanoTime() + 60_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > end) throw new IllegalStateException("timed out: " + what);
            Thread.sleep(50);
        }
    }
}
