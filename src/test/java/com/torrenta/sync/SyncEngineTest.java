package com.torrenta.sync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two machines and a bare repository, with the real git. */
class SyncEngineTest {

    /** A machine's live settings: a map, applied only if it still holds what the engine read. */
    static final class MapTarget implements SyncTarget {
        final Map<String, String> live = new TreeMap<>();
        boolean busy;

        MapTarget(Map<String, String> initial) {
            live.putAll(initial);
        }

        @Override
        public Map<String, String> read() {
            return new TreeMap<>(live);
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> merged) {
            if (busy || !live.equals(expected)) return false;
            live.clear();
            live.putAll(merged);
            return true;
        }
    }

    @BeforeAll
    static void needsGit() {
        Assumptions.assumeTrue(QuietGit.available(), "git on the PATH");
    }

    private static String bare(Path dir) throws Exception {
        Path remote = dir.resolve("remote.git");
        Files.createDirectories(remote);
        Proc.Result init =
                Proc.run(remote, Duration.ofSeconds(30), List.of("git", "init", "--bare", "--quiet"), Map.of());
        assertTrue(init.ok(), init.transcript());
        return remote.toUri().toString();
    }

    private static SyncEngine engine(Path dir, String name, String url, SyncTarget target) {
        return new SyncEngine(
                new QuietGit(dir.resolve(name).resolve("sync").resolve("repo"), false), url, "main", target, name);
    }

    @Test
    void twoMachinesConvergeOverRounds(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of("ui.theme", "nord-dark", "connection.port", "6881"));
        MapTarget b = new MapTarget(Map.of("connection.port", "7000"));
        SyncEngine ea = engine(dir, "a", url, a);
        SyncEngine eb = engine(dir, "b", url, b);

        SyncReport first = ea.run();
        assertTrue(first.ok(), first.summary());
        assertEquals(List.of("connection.port", "ui.theme"), first.sent());

        SyncReport second = eb.run();
        assertTrue(second.ok(), second.summary());
        assertEquals(List.of("ui.theme"), second.received(), "B gets A's theme");
        assertEquals(List.of("connection.port"), second.conflicts(), "a first sync keeps B's port");
        assertEquals(Map.of("ui.theme", "nord-dark", "connection.port", "7000"), b.live);

        SyncReport third = ea.run();
        assertTrue(third.ok(), third.summary());
        assertEquals(List.of("connection.port"), third.received(), "A did not touch the port; B's wins");
        assertEquals("7000", a.live.get("connection.port"));

        SyncReport quiet = ea.run();
        assertTrue(quiet.ok() && !quiet.changedAnything(), quiet.summary());

        a.live.remove("ui.theme");
        a.live.put("add.sequential", "true");
        SyncReport removal = ea.run();
        assertEquals(List.of("add.sequential", "ui.theme"), removal.sent());
        SyncReport received = eb.run();
        assertEquals(List.of("add.sequential", "ui.theme"), received.received());
        assertEquals(Map.of("connection.port", "7000", "add.sequential", "true"), b.live);
    }

    @Test
    void previewWritesNothingAndLocalBusyKeepsTheBase(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of("ui.theme", "nord-dark"));
        assertTrue(engine(dir, "a", url, a).run().ok());
        MapTarget b = new MapTarget(Map.of("ui.theme", "editora-light"));
        SyncEngine eb = engine(dir, "b", url, b);
        SyncReport preview = eb.preview();
        assertTrue(preview.ok());
        assertEquals(List.of("ui.theme"), preview.conflicts(), "a first sync would keep B's");
        assertEquals(Map.of("ui.theme", "editora-light"), b.live, "nothing written");

        b.live.clear();
        b.busy = true;
        SyncReport busy = eb.run();
        assertEquals(SyncReport.Status.LOCAL_BUSY, busy.status());
        b.busy = false;
        SyncReport again = eb.run();
        assertTrue(again.ok(), again.summary());
        assertEquals(Map.of("ui.theme", "nord-dark"), b.live, "received once B was free");
    }

    @Test
    void aRepositoryFromANewerBuildIsLeftAlone(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of("ui.theme", "x"));
        assertTrue(engine(dir, "a", url, a).run().ok());
        // Another machine running a newer build rewrites the format file.
        Path c = dir.resolve("c");
        Proc.Result clone = Proc.run(
                dir,
                Duration.ofSeconds(30),
                List.of("git", "clone", "--quiet", "--branch", "main", url, c.toString()),
                Map.of());
        assertTrue(clone.ok(), clone.transcript());
        Files.writeString(c.resolve(SyncEngine.FORMAT_FILE), "formatVersion=99\n");
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME",
                "t",
                "GIT_AUTHOR_EMAIL",
                "t@x",
                "GIT_COMMITTER_NAME",
                "t",
                "GIT_COMMITTER_EMAIL",
                "t@x");
        Proc.Result commit = Proc.run(
                c,
                Duration.ofSeconds(30),
                List.of("git", "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify", "-am", "newer"),
                env);
        assertTrue(commit.ok(), commit.transcript());
        Proc.Result push = Proc.run(c, Duration.ofSeconds(30), List.of("git", "push", "--quiet"), Map.of());
        assertTrue(push.ok(), push.transcript());
        SyncReport report = engine(dir, "a", url, a).run();
        assertEquals(SyncReport.Status.NEWER_FORMAT, report.status());
        assertEquals(Map.of("ui.theme", "x"), a.live);
    }

    @Test
    void changingTheRepositoryStartsOver(@TempDir Path dir) throws Exception {
        String url1 = bare(dir.resolve("one"));
        String url2 = bare(dir.resolve("two"));
        MapTarget a = new MapTarget(new HashMap<>(Map.of("ui.theme", "x")));
        assertTrue(engine(dir, "a", url1, a).run().ok());
        SyncReport moved = engine(dir, "a", url2, a).run();
        assertTrue(moved.ok(), moved.summary());
        assertEquals(List.of("ui.theme"), moved.sent(), "a first sync into the new repository");
    }

    @Test
    void badInputsAreReportsNotExceptions(@TempDir Path dir) {
        MapTarget a = new MapTarget(Map.of());
        assertEquals(SyncReport.Status.FAILED, engine(dir, "a", "", a).run().status());
        assertEquals(
                SyncReport.Status.FAILED,
                engine(dir, "a", "--upload-pack=evil", a).run().status());
        assertEquals(
                SyncReport.Status.FETCH_FAILED,
                engine(dir, "a", dir.resolve("missing").toUri().toString(), a)
                        .run()
                        .status());
    }

    @Test
    void credentialsInAUrlAreSeen() {
        assertTrue(SyncEngine.hasCredentials("https://me:ghp_abc@github.com/me/r.git"));
        assertTrue(SyncEngine.hasCredentials("https://ghp_abcdefghijklmnop@github.com/me/r.git"));
        assertFalse(SyncEngine.hasCredentials("https://github.com/me/r.git"));
        assertFalse(SyncEngine.hasCredentials("git@github.com:me/r.git"));
        assertFalse(SyncEngine.hasCredentials("ssh://git@github.com/me/r.git"));
        assertTrue(SyncEngine.isUsableBranch("main"));
        assertTrue(SyncEngine.isUsableBranch("sync/settings"));
        assertFalse(SyncEngine.isUsableBranch("-x"));
        assertFalse(SyncEngine.isUsableBranch("a..b"));
        assertFalse(SyncEngine.isUsableBranch("x.lock"));
    }
}
