package com.torrenta.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.Following;
import com.torrenta.engine.MutableSource;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session driven against {@link FakeEngine}, for what the proxy engines of {@link SessionTest}
 * do not reach: each share-limit action, the trash, a daemon's own blocklist, an updatable address.
 */
class SessionOnFakeEngineTest {

    private static final String SEED = "aa".repeat(20);

    @TempDir
    Path dir;

    private Session session;

    @AfterEach
    void close() {
        if (session != null) session.close();
    }

    /** What the owner heard, kept for the test to read. */
    private static final class Heard implements Session.Host {
        final List<EngineEvent> seedingDone = new CopyOnWriteArrayList<>();
        final List<EngineEvent> reports = new CopyOnWriteArrayList<>();
        final List<EngineEvent> problems = new CopyOnWriteArrayList<>();
        final List<EngineEvent> events = new CopyOnWriteArrayList<>();
        final List<String> ipFilter = new CopyOnWriteArrayList<>();
        final List<Engine> ready = new CopyOnWriteArrayList<>();

        @Override
        public void seedingDone(EngineEvent event, Torrent torrent, boolean removed) {
            seedingDone.add(event);
        }

        @Override
        public void limitsRefreshed(boolean alternative, SpeedMode.Reason reason, SpeedMode.Reason paused) {}

        @Override
        public void problem(EngineEvent event) {
            problems.add(event);
        }

        @Override
        public void report(EngineEvent event) {
            reports.add(event);
        }

        @Override
        public void event(EngineEvent event) {
            events.add(event);
        }

        @Override
        public void ipFilterReport(String text) {
            ipFilter.add(text);
        }

        @Override
        public void engineReady(Engine engine) {
            ready.add(engine);
        }
    }

    private Session session(Settings settings, Heard host) {
        session = new Session(
                settings,
                dir,
                new com.torrenta.store.LabelStore(dir),
                new com.torrenta.store.WatchFolderStore(dir),
                new com.torrenta.rss.RssStore(dir),
                Runnable::run,
                host);
        return session;
    }

    private Settings settings() {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.DOWNLOAD_DIR, dir.resolve("dl").toString());
        return settings;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** A seed at a quarter's ratio, past a limit of a fifth: each action, and one the engine refuses. */
    @ParameterizedTest
    @ValueSource(strings = {"REMOVE", "REMOVE_DATA", "SUPER_SEED", "REMOVE_REFUSED"})
    void eachShareLimitActionIsTakenAndTheOwnerHears(String action) throws Exception {
        Settings settings = settings();
        settings.set(Settings.SHARE_RATIO, "0.2");
        boolean refused = action.endsWith("_REFUSED");
        settings.set(Settings.SHARE_ACTION, refused ? "REMOVE" : action);
        settings.set(Settings.REMOVE_TO_TRASH, "false");
        FakeEngine fake = new FakeEngine().put(FakeEngine.torrent(SEED, "Seed", TorrentState.SEEDING, 1.0));
        if (refused) fake.refuse("remove");
        Heard host = new Heard();
        session(settings, host).start(fake);
        await("the limit", () -> !host.seedingDone.isEmpty());
        EngineEvent done = host.seedingDone.getFirst();
        assertEquals(EngineEvent.Kind.SEEDING_DONE, done.kind());
        assertTrue(done.message().contains("Seed"), done.message());
        switch (action) {
            case "REMOVE" ->
                assertTrue(
                        fake.calls().contains("remove " + SEED + " false"),
                        fake.calls().toString());
            case "REMOVE_DATA" ->
                assertTrue(
                        fake.calls().contains("remove " + SEED + " true"),
                        fake.calls().toString());
            case "SUPER_SEED" -> assertTrue(fake.calls().contains("setSuperSeeding " + SEED + " true"));
            default -> assertTrue(fake.torrents().stream().anyMatch(t -> t.id().equals(SEED)), "still there");
        }
    }

    /** An engine whose files are on this machine, in the test's folder. */
    private static final class FilesHere extends FakeEngine {
        private final Engine.OnDisk onDisk;

        FilesHere(Engine.OnDisk onDisk) {
            this.onDisk = onDisk;
        }

        @Override
        public Optional<Engine.OnDisk> onDisk(String id) {
            return Optional.of(onDisk);
        }
    }

    @Test
    void removeWithFilesMovesThemToTheTrashAndDeletesTheEnginesLeftovers() throws Exception {
        Path save = Files.createDirectories(dir.resolve("dl"));
        Path file = Files.writeString(save.resolve("film.mkv"), "x");
        Path scratch = Files.writeString(save.resolve(".parts"), "y");
        FilesHere engine = new FilesHere(new Engine.OnDisk(save, List.of(file), List.of(scratch)));
        engine.put(FakeEngine.torrent(SEED, "Film"));
        Heard host = new Heard();
        Session s = session(settings(), host);
        List<Path> trashed = new CopyOnWriteArrayList<>();
        s.useTrash(trashed::add);
        assertTrue(s.removesToTrash(engine));
        s.removeWithFiles(engine, FakeEngine.torrent(SEED, "Film"));
        assertTrue(engine.calls().contains("remove " + SEED + " false"), "the engine keeps its hands off the files");
        await("the trash", () -> !host.reports.isEmpty());
        assertEquals(List.of(file), trashed);
        assertFalse(Files.exists(scratch), "the part file is clutter");
        assertTrue(host.reports.getFirst().message().contains("Film"));
    }

    @Test
    void aFileTheTrashRefusesStaysAndIsSaid() {
        Heard host = new Heard();
        Session s = session(settings(), host);
        Path file = dir.resolve("kept.bin");
        // Interrupted, the retries end at the first refusal rather than five seconds later.
        Thread.currentThread().interrupt();
        try {
            s.moveToTrash(
                    p -> {
                        throw new IOException("in use");
                    },
                    FakeEngine.torrent(SEED, "Kept"),
                    new Engine.OnDisk(dir, List.of(file), List.of(dir.resolve("no-such-part"))));
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, host.reports.size());
        assertEquals(EngineEvent.Level.WARNING, host.reports.getFirst().level());
        assertTrue(
                host.reports.getFirst().message().contains("in use"),
                host.reports.getFirst().message());
    }

    /** A daemon that fetches its own blocklist: the URL and the switch, and what it said. */
    private static final class Daemon extends FakeEngine {
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        Daemon() {
            withCapabilities(Engine.Capabilities.ALL.without(Engine.Capability.IP_FILTER));
        }

        @Override
        public long setBlocklist(String url, boolean enabled) throws EngineException {
            asked.add(url + " " + enabled);
            if (fail) throw new EngineException("the list did not parse");
            return 1234;
        }
    }

    @Test
    void aDaemonIsToldItsBlocklistAndWhatItSaysIsShown() throws Exception {
        Settings settings = settings();
        settings.set(Settings.IPFILTER_ENABLED, "true");
        settings.set(Settings.IPFILTER_URL, "https://lists.example.org/level1.gz");
        Heard host = new Heard();
        Daemon daemon = new Daemon();
        Session s = session(settings, host);
        s.start(daemon);
        await("the first answer", () -> host.ipFilter.size() == 1);
        assertEquals(List.of("https://lists.example.org/level1.gz true"), daemon.asked);
        assertEquals(host.ipFilter.getFirst(), s.blocklistStatus());
        assertTrue(s.blocklistStatus().contains("1"), s.blocklistStatus());

        daemon.fail = true;
        s.refreshIpFilter(true);
        await("the failure", () -> host.ipFilter.size() == 2);
        assertTrue(host.ipFilter.get(1).contains("did not parse"), host.ipFilter.get(1));

        daemon.fail = false;
        settings.set(Settings.IPFILTER_ENABLED, "false");
        s.refreshIpFilter(false);
        await("off", () -> host.ipFilter.size() == 3);
        assertFalse(host.ipFilter.get(2).equals(host.ipFilter.getFirst()), "off reads as off");
    }

    @Test
    void anEngineWithItsOwnFilterIsRefreshedAndNoEngineIsNothing() throws Exception {
        Heard host = new Heard();
        Session s = session(settings(), host);
        s.refreshIpFilter(true); // no engine: nothing to do, nothing said
        FakeEngine fake = new FakeEngine();
        s.start(fake);
        s.refreshIpFilter(true);
        s.applySettings();
        await("the settings", () -> fake.calls().contains("applySettings"));
        assertTrue(host.problems.isEmpty(), host.problems.toString());
    }

    /** An engine that finds one version under any updatable address and follows what it is told. */
    private static final class Dht extends FakeEngine {
        final String infoHash;
        final List<String> followed = new CopyOnWriteArrayList<>();
        volatile boolean refuseFollowing;

        Dht(String infoHash) {
            this.infoHash = infoHash;
        }

        @Override
        public Optional<Engine.MutableItem> lookupMutable(MutableSource source, java.time.Duration timeout) {
            if (infoHash.isEmpty()) return Optional.empty();
            return Optional.of(
                    new Engine.MutableItem(3, Map.of("ih", HexFormat.of().parseHex(infoHash))));
        }

        @Override
        public void setFollowing(String id, Following following) throws EngineException {
            if (refuseFollowing) throw new EngineException("no sidecar here");
            followed.add(id + " " + following.seq());
        }
    }

    private static final String KEY = "0f".repeat(32);
    private static final String ADDRESS = "magnet:?xs=urn:btpk:" + KEY;

    private Session started(Engine engine, Heard host) throws InterruptedException {
        Session s = session(settings(), host);
        s.setEngineFactory(profile -> engine);
        s.startEngine(EngineProfile.LOCAL);
        await("the engine", () -> host.ready.contains(engine));
        return s;
    }

    @Test
    void anUpdatableAddressIsItsCurrentVersionFollowedOnceAdded() throws Exception {
        String hash = "cd".repeat(20);
        Dht dht = new Dht(hash);
        Heard host = new Heard();
        Session s = started(dht, host);

        AddRequest.Source source = s.resolve(ADDRESS);
        assertEquals(new AddRequest.Magnet(MutableSource.magnetOf(hash)), source);
        assertEquals(source, s.resolve(new AddRequest.Magnet(" " + ADDRESS + " ")), "a watch folder's line too");
        Torrent other = dht.add("magnet:?xt=urn:btih:" + "ee".repeat(20));
        s.attachFollowing(dht, other);
        assertEquals(List.of(), dht.followed, "a torrent the address does not name is left alone");

        Torrent added = new FakeEngine().add(MutableSource.magnetOf(hash));
        s.attachFollowing(dht, FakeEngine.torrent(FollowRules.idOf(hash), added.name()));
        assertEquals(List.of(FollowRules.idOf(hash) + " 3"), dht.followed);
        assertTrue(host.events.stream().anyMatch(e -> e.message().contains(added.name())));

        dht.refuseFollowing = true;
        s.resolve(ADDRESS);
        s.attachFollowing(dht, FakeEngine.torrent(FollowRules.idOf(hash), "Again"));
        assertEquals(1, host.problems.size(), "a follow the engine refuses is a problem");

        AddRequest.TorrentFile file = new AddRequest.TorrentFile(new byte[] {1}, "x.torrent");
        assertEquals(file, s.resolve(file), "any other source as it is");
    }

    @Test
    void anAddressNothingAnswersForIsRefusedAndSoIsAnEngineWithoutTheDht() throws Exception {
        Heard host = new Heard();
        Session s = started(new Dht(""), host);
        assertThrows(EngineException.class, () -> s.resolve(ADDRESS));
        s.stopEngine();

        Heard second = new Heard();
        FakeEngine noDht =
                new FakeEngine().withCapabilities(Engine.Capabilities.ALL.without(Engine.Capability.MUTABLE));
        Session other = started(noDht, second);
        assertThrows(EngineException.class, () -> other.resolve(ADDRESS));
        other.stopEngine();
        assertThrows(EngineException.class, () -> other.resolve(ADDRESS), "no engine running");
        assertThrows(
                EngineException.class,
                () -> other.resolve(dir.resolve("none.torrent").toString()));
        Path real = Files.write(dir.resolve("real.torrent"), "d4:infod4:name1:xee".getBytes(StandardCharsets.US_ASCII));
        assertTrue(other.resolve(real.toString()) instanceof AddRequest.TorrentFile);
    }
}
