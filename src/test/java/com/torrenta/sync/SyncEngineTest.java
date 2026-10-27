package com.torrenta.sync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.torrenta.search.TorznabEndpoint;
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

    private static SyncEngine engine(Path dir, String name, String url, SyncTarget target, SyncTarget labels) {
        return new SyncEngine(
                new QuietGit(dir.resolve(name).resolve("sync").resolve("repo"), false),
                url,
                "main",
                target,
                labels,
                name);
    }

    private static Map<String, String> labels(List<String> categories, List<String> tags) {
        return SyncLabels.entries(categories, tags);
    }

    @Test
    void categoryNamesAndTagsTravelBesideTheSettings(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of("ui.theme", "x"));
        MapTarget al = new MapTarget(labels(List.of("Movies"), List.of("hd")));
        MapTarget b = new MapTarget(Map.of());
        MapTarget bl = new MapTarget(labels(List.of("TV"), List.of()));
        assertTrue(engine(dir, "a", url, a, al).run().ok());
        SyncReport second = engine(dir, "b", url, b, bl).run();
        assertTrue(second.ok(), second.summary());
        assertEquals(List.of("ui.theme", "category.movies", "tag.hd"), second.received());
        assertEquals(labels(List.of("Movies", "TV"), List.of("hd")), bl.live, "a first sync only adds");
        assertEquals(Map.of("ui.theme", "x"), b.live);
        assertTrue(engine(dir, "a", url, a, al).run().ok());
        assertEquals(labels(List.of("Movies", "TV"), List.of("hd")), al.live);

        bl.live.remove("tag.hd");
        bl.live.put("category.movies", "MOVIES");
        assertTrue(engine(dir, "b", url, b, bl).run().ok());
        SyncReport back = engine(dir, "a", url, a, al).run();
        assertEquals(List.of("category.movies", "tag.hd"), back.received());
        assertEquals(labels(List.of("MOVIES", "TV"), List.of()), al.live, "removed and respelled on A");
    }

    @Test
    void aMachineThatSyncedWithoutLabelsDeletesNoneWhenItStarts(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of());
        MapTarget al = new MapTarget(labels(List.of("Movies"), List.of()));
        assertTrue(engine(dir, "a", url, a, al).run().ok());
        // B runs a build without labels: its base is a commit that holds A's labels file.
        MapTarget b = new MapTarget(Map.of("ui.theme", "y"));
        assertTrue(engine(dir, "b", url, b).run().ok());
        assertTrue(engine(dir, "a", url, a, al).run().ok());
        assertEquals(Map.of("ui.theme", "y"), a.live);
        assertEquals(labels(List.of("Movies"), List.of()), al.live, "the old build kept the labels file");

        // B upgrades. Its own list never held Movies, and that is not a deletion.
        MapTarget bl = new MapTarget(labels(List.of("TV"), List.of()));
        SyncReport upgraded = engine(dir, "b", url, b, bl).run();
        assertTrue(upgraded.ok(), upgraded.summary());
        assertEquals(labels(List.of("Movies", "TV"), List.of()), bl.live);
        assertTrue(engine(dir, "a", url, a, al).run().ok());
        assertEquals(labels(List.of("Movies", "TV"), List.of()), al.live);
    }

    /** A machine's search providers, applied the way the window's target applies them. */
    static final class SearchTarget implements SyncTarget {
        static final Map<String, String> SHIPPED = Map.of("example", "{ shipped }\n");
        final List<TorznabEndpoint> endpoints = new ArrayList<>();
        final Map<String, String> definitions = new TreeMap<>(SHIPPED);

        @Override
        public Map<String, String> read() {
            return SyncSearch.entries(endpoints, definitions, SHIPPED);
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> merged) {
            if (!read().equals(expected)) return false;
            try {
                List<TorznabEndpoint> after = SyncSearch.endpoints(endpoints, merged);
                endpoints.clear();
                endpoints.addAll(after);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            SyncSearch.DefinitionChange change = SyncSearch.definitionChange(expected, merged);
            definitions.putAll(change.put());
            for (String id : change.removed()) {
                if (SHIPPED.containsKey(id)) definitions.put(id, SHIPPED.get(id));
                else definitions.remove(id);
            }
            return true;
        }
    }

    private static SyncEngine engine(Path dir, String name, String url, SyncTarget target, SearchTarget search) {
        return new SyncEngine(
                new QuietGit(dir.resolve(name).resolve("sync").resolve("repo"), false),
                url,
                "main",
                target,
                null,
                search,
                name);
    }

    private static final String PROWLARR = "http://nas:9696/1/api";

    @Test
    void searchProvidersTravelAndTheirKeysStayHome(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of());
        SearchTarget as = new SearchTarget();
        as.endpoints.add(new TorznabEndpoint("Prowlarr", PROWLARR, "s3cret-key", true, 22));
        as.definitions.put("mine", "{ \"id\": \"mine\" }\n");
        as.definitions.put("example", "{ edited on A }\n");
        MapTarget b = new MapTarget(Map.of());
        SearchTarget bs = new SearchTarget();

        assertTrue(engine(dir, "a", url, a, as).run().ok());
        SyncReport second = engine(dir, "b", url, b, bs).run();
        assertTrue(second.ok(), second.summary());
        assertEquals(
                List.of(new TorznabEndpoint("Prowlarr", PROWLARR, "", true, -1, true)),
                bs.endpoints,
                "B has the server, waiting for its key");
        assertEquals(as.definitions, bs.definitions, "A's file and A's edit of the example, over B's unchanged copy");

        Path clone = dir.resolve("b").resolve("sync").resolve("repo");
        try (var files = Files.walk(clone)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (f.startsWith(clone.resolve(".git"))) continue; // compressed; the log below reads it
                assertFalse(Files.readString(f).contains("s3cret-key"), "the key is in " + f);
            }
        }
        Proc.Result log = Proc.run(clone, Duration.ofSeconds(30), List.of("git", "log", "-p", "--all"), Map.of());
        assertFalse(log.out().contains("s3cret-key"), "nor in the history");
        assertTrue(Files.isRegularFile(clone.resolve("search-providers/mine.json")));

        // B types the key in: nothing to send, the server already said it takes one.
        bs.endpoints.set(0, new TorznabEndpoint("Prowlarr", PROWLARR, "s3cret-key", true, -1));
        SyncReport typed = engine(dir, "b", url, b, bs).run();
        assertTrue(typed.ok() && !typed.changedAnything(), typed.summary());

        // A renames it, turns the example back and deletes its own file.
        as.endpoints.set(0, new TorznabEndpoint("Home", PROWLARR, "s3cret-key", true, 22));
        as.definitions.put("example", SearchTarget.SHIPPED.get("example"));
        as.definitions.remove("mine");
        assertTrue(engine(dir, "a", url, a, as).run().ok());
        SyncReport back = engine(dir, "b", url, b, bs).run();
        assertTrue(back.ok(), back.summary());
        assertEquals(List.of(new TorznabEndpoint("Home", PROWLARR, "s3cret-key", true, -1)), bs.endpoints);
        assertEquals(SearchTarget.SHIPPED, bs.definitions, "the example back to its shipped text, not deleted");
        assertFalse(Files.exists(clone.resolve("search-providers/mine.json")));

        as.endpoints.clear();
        assertTrue(engine(dir, "a", url, a, as).run().ok());
        assertTrue(engine(dir, "b", url, b, bs).run().ok());
        assertTrue(bs.endpoints.isEmpty(), "removed on A, removed on B");
        assertFalse(Files.exists(clone.resolve(SyncSearch.TORZNAB_FILE)));
    }

    @Test
    void aMachineThatSyncedWithoutSearchRemovesNoProviderWhenItStarts(@TempDir Path dir) throws Exception {
        String url = bare(dir);
        MapTarget a = new MapTarget(Map.of());
        SearchTarget as = new SearchTarget();
        as.endpoints.add(new TorznabEndpoint("Prowlarr", PROWLARR, "k"));
        assertTrue(engine(dir, "a", url, a, as).run().ok());
        // B's build syncs no search providers; its base is a commit that holds A's.
        MapTarget b = new MapTarget(Map.of("ui.theme", "y"));
        assertTrue(engine(dir, "b", url, b).run().ok());
        assertTrue(engine(dir, "a", url, a, as).run().ok());
        assertEquals(1, as.endpoints.size(), "the old build kept the file");

        SearchTarget bs = new SearchTarget();
        bs.endpoints.add(new TorznabEndpoint("Jackett", "http://nas:9117/api", ""));
        assertTrue(engine(dir, "b", url, b, bs).run().ok());
        assertEquals(2, bs.endpoints.size(), "B's upgrade only adds");
        assertTrue(engine(dir, "a", url, a, as).run().ok());
        assertEquals(2, as.endpoints.size());
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
