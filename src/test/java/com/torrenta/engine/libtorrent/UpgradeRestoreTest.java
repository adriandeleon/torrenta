package com.torrenta.engine.libtorrent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentState;
import com.torrenta.engine.TorrentStorage;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A torrent store written by each released Torrenta comes back under this build as that release
 * left it, and again after this build has written it: the 1.0 criterion "resume data proven
 * across upgrades". The stores under {@code upgrade/<version>/} were written by the release itself
 * ({@code scripts/upgrade-fixture.sh}: the tag built, {@code scripts/upgrade-fixture/RecordStore.java}
 * run against it with no network), beside what that release showed of each torrent just before it
 * stopped. Only the save path is rewritten here, to this test's folder.
 *
 * <p>The torrents cover what has gone wrong at a restart before: a single file seeding; a folder
 * filed, tagged, given a name, a file renamed and one skipped, paused; a folder with a file missing,
 * paused; a magnet without its metadata; and two added with the {@code .part} suffix whose whole
 * files lost it again, which libtorrent records as renames relative to the root folder (0.4.0
 * downloaded such torrents again at every restart). No peer can be reached, so a file the restore
 * no longer finds leaves the torrent short of what the release recorded, and a paused torrent that
 * needs a check never runs one: either way the progress comparison fails.
 */
class UpgradeRestoreTest {

    /** Path, size, seed: the same table as {@code RecordStore.PAYLOADS}. */
    private static final Object[][] PAYLOADS = {
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

    private static final List<String> SCENARIOS =
            List.of("single", "multi", "partial", "magnet", "finished", "suffixed");

    @ParameterizedTest(name = "a store written by Torrenta {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0", "0.3.0", "0.4.0", "0.4.1"})
    void comesBackAsTheReleaseLeftIt(String version, @TempDir Path dir) throws Exception {
        Properties seen = fixture(version);
        Path downloads = dir.resolve("downloads");
        layOut(seen.getProperty("disk"), downloads);
        Map<String, Long> onDisk = listing(downloads);

        TorrentStore store = new TorrentStore(dir.resolve("store"));
        Files.createDirectories(store.dir());
        String recordedSave = seen.getProperty("savePath");
        Map<String, TorrentStorage> records = new TreeMap<>();
        for (String name : seen.getProperty("store").split(",")) {
            byte[] bytes = resource(version, "store/" + name);
            if (name.endsWith(TorrentStore.SUFFIX))
                bytes = withSavePath(
                        bytes, recordedSave, downloads.toAbsolutePath().toString());
            if (name.endsWith(TorrentStore.STORAGE_SUFFIX))
                records.put(
                        name.substring(0, name.length() - TorrentStore.STORAGE_SUFFIX.length()),
                        TorrentStorage.decode(bytes));
            Files.write(store.dir().resolve(name), bytes);
        }
        List<String> ids = SCENARIOS.stream().map(seen::getProperty).toList();

        // Once from the release's store, once from what this build wrote back at its stop.
        for (int start = 1; start <= 2; start++) {
            String when = "Torrenta " + version + "'s store, start " + start;
            try (LibtorrentEngine engine = new LibtorrentEngine(
                    () -> downloads, store, LibtorrentEngineTest.isolated(LibtorrentEngineTest.ANY_PORT))) {
                engine.start();
                await(when + ": every torrent back", () -> engine.torrents().size() == ids.size());
                for (String scenario : SCENARIOS) {
                    String id = seen.getProperty(scenario);
                    String what = when + ", " + scenario;
                    double progress = Double.parseDouble(seen.getProperty(id + ".progress"));
                    await(what + ": settled at the recorded " + progress, () -> {
                        Torrent t = torrent(engine, id);
                        return t.state() != TorrentState.CHECKING && Math.abs(t.progress() - progress) < 1e-3;
                    });
                    Torrent t = torrent(engine, id);
                    assertEquals(seen.getProperty(id + ".name"), t.name(), what);
                    assertEquals(Boolean.parseBoolean(seen.getProperty(id + ".paused")), t.paused(), what + ": paused");
                    assertEquals(seen.getProperty(id + ".category"), t.category(), what + ": category");
                    assertEquals(seen.getProperty(id + ".tags"), String.join(",", t.tags()), what + ": tags");
                    assertEquals(seen.getProperty(id + ".files"), files(engine.detail(id)), what + ": files");
                }
            }
            assertEquals(onDisk, listing(downloads), when + ": nothing on disk renamed, created or deleted");
            for (Map.Entry<String, TorrentStorage> r : records.entrySet()) {
                TorrentStorage now =
                        TorrentStorage.decode(store.loadStorage(r.getKey()).orElseThrow());
                assertEquals(r.getValue().suffixed(), now.suffixed(), when + ": the files still under .part");
                assertEquals(r.getValue().renamed(), now.renamed(), when + ": the names given");
            }
        }
    }

    @Test
    void theSavePathIsReplacedWithItsLengthAndNothingElse() throws IOException {
        byte[] resume = "d4:name3:abc9:save_path8:/old/dl/5:totali3ee".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(
                "d4:name3:abc9:save_path14:C:\\new\\fold€5:totali3ee".getBytes(StandardCharsets.UTF_8),
                withSavePath(resume, "/old/dl/", "C:\\new\\fold€"));
        assertThrows(IOException.class, () -> withSavePath(resume, "/elsewhere", "/x"));
    }

    /**
     * The resume data with its {@code save_path} string swapped for {@code to}, the length prefix
     * counted in bytes. Byte for byte otherwise: a re-encode would be a second writer of the
     * release's data.
     */
    static byte[] withSavePath(byte[] resume, String from, String to) throws IOException {
        byte[] old = bencoded("save_path", from);
        int at = indexOf(resume, old);
        if (at < 0 || indexOf(Arrays.copyOfRange(resume, at + 1, resume.length), old) >= 0)
            throw new IOException("save_path " + from + " not found exactly once");
        byte[] now = bencoded("save_path", to);
        byte[] out = new byte[resume.length - old.length + now.length];
        System.arraycopy(resume, 0, out, 0, at);
        System.arraycopy(now, 0, out, at, now.length);
        System.arraycopy(resume, at + old.length, out, at + now.length, resume.length - at - old.length);
        return out;
    }

    private static byte[] bencoded(String key, String value) {
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        return (key.length() + ":" + key + v.length + ":" + value).getBytes(StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Each file the release left on disk, written again from the payload it held. */
    private static void layOut(String disk, Path downloads) throws IOException {
        for (String entry : disk.split("\\|")) {
            String[] parts = entry.split("=", 2);
            Object[] payload = Arrays.stream(PAYLOADS)
                    .filter(p -> p[0].equals(parts[1]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no payload for " + entry));
            Path file = downloads.resolve(parts[0]);
            Files.createDirectories(file.getParent());
            byte[] bytes = new byte[(int) payload[1]];
            new Random((int) payload[2]).nextBytes(bytes);
            Files.write(file, bytes);
        }
    }

    private static Map<String, Long> listing(Path root) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(Files::isRegularFile).toList())
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.size(p));
        }
        return out;
    }

    private static String files(TorrentDetail detail) {
        return String.join(
                "|",
                detail.files().stream()
                        .map(f -> f.path() + ":" + f.priority().name())
                        .toList());
    }

    private static Torrent torrent(LibtorrentEngine engine, String id) {
        return engine.torrents().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not in the list"));
    }

    private static Properties fixture(String version) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new java.io.ByteArrayInputStream(resource(version, "fixture.properties"))) {
            p.load(in);
        }
        return p;
    }

    private static byte[] resource(String version, String name) throws IOException {
        String path = "upgrade/" + version + "/" + name;
        try (InputStream in = Objects.requireNonNull(
                UpgradeRestoreTest.class.getResourceAsStream(path), () -> "no test resource " + path)) {
            return in.readAllBytes();
        }
    }

    private interface Condition {
        boolean test() throws Exception;
    }

    /** Bounded, never sleep-and-hope; long enough for a Windows recheck of a few hundred KiB. */
    private static void await(String what, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.test()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(25);
        }
    }
}
