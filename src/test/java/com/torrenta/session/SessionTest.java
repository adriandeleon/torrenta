package com.torrenta.session;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "torrenta", "session");

    /**
     * The session is what a headless run and the HTTP API will drive, with no toolkit: a JavaFX
     * name anywhere under it, an import or a qualified use, fails here, as a literal in a text
     * position fails {@code UiStringSweepTest}.
     */
    @Test
    void nothingUnderSessionTouchesJavaFx() throws Exception {
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) continue;
                    if (line.contains("javafx.")) found.add(file.getFileName() + ":" + (i + 1) + " " + line);
                }
            }
        }
        assertTrue(found.isEmpty(), "JavaFX under com.torrenta.session: " + found);
    }

    /** A seed past its limit is paused once, and the owner hears it with the snapshot. */
    @Test
    void aSeedPastItsShareLimitIsPausedOnceAndTheOwnerHears(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.SHARE_RATIO, "1.0");
        settings.set(Settings.SHARE_ACTION, "PAUSE");
        AtomicBoolean paused = new AtomicBoolean();
        AtomicInteger pauses = new AtomicInteger();
        Engine engine = engine(() -> List.of(seed("s", 2000, paused.get())), id -> {
            pauses.incrementAndGet();
            paused.set(true);
        });
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        AtomicInteger polls = new AtomicInteger();
        session.addListener(p -> polls.incrementAndGet());
        session.start(engine);
        try {
            await(() -> polls.get() >= 3);
        } finally {
            session.stop();
        }
        assertEquals(1, pauses.get(), "done once: a paused seed no longer qualifies");
        assertEquals(1, host.seedingDone.size());
        EngineEvent event = host.seedingDone.get(0);
        assertEquals(EngineEvent.Kind.SEEDING_DONE, event.kind());
        assertEquals("s", event.torrentId());
        assertTrue(event.message().contains("name"), event.message());
    }

    @Test
    void theSpeedScheduleIsLookedAtOnStartAndNothingIsPolledOnceStopped(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        AtomicInteger polls = new AtomicInteger();
        List<String> threads = new CopyOnWriteArrayList<>();
        session.addListener(p -> {
            throw new IllegalStateException("a listener that fails");
        });
        session.addListener(p -> {
            threads.add(Thread.currentThread().getName());
            polls.incrementAndGet();
        });
        Engine engine = engine(List::of, id -> {});
        session.start(engine);
        await(() -> polls.get() >= 2 && !host.limits.isEmpty());
        assertEquals(List.of(false), host.limits.subList(0, 1));
        assertEquals("engine-poll", threads.get(0), "listeners hear on the poll thread");
        // A scheduled task that throws is never run again; the failing listener must not stop the poll.
        assertTrue(session.execute(() -> {}), "the poll thread takes work while polling");
        session.stop();
        int after = polls.get();
        Thread.sleep(Session.POLL_MS * 2);
        assertEquals(after, polls.get(), "stopped means no poll");
        assertEquals(null, session.engine());
        assertFalse(session.execute(() -> {}), "and no work");
        session.stop(); // idempotent
    }

    /** The IP filters set on any proxy engine, in order. */
    private static final List<List<?>> IP_FILTERS = new CopyOnWriteArrayList<>();

    private static Session session(Path dir, Settings settings, Session.Host host) {
        return new Session(
                settings,
                dir,
                new com.torrenta.store.LabelStore(dir),
                new com.torrenta.store.WatchFolderStore(dir),
                new com.torrenta.rss.RssStore(dir),
                Runnable::run,
                host);
    }

    /**
     * Starting takes the engine's IP filter (from disk, here a ban, before any download runs) and
     * the watch folders with the poll; stopping gives both back.
     */
    @Test
    void startAppliesTheIpFilterAndWatchesStopLetsBothGo(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.IPFILTER_ENABLED, "true");
        settings.setIpFilterBans(List.of("203.0.113.9"));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        IP_FILTERS.clear();
        session.start(engine(List::of, id -> {}));
        try {
            await(() -> !IP_FILTERS.isEmpty() && host.watchStatus.get() > 0);
            assertEquals(1, IP_FILTERS.get(0).size(), "the ban, as one range: " + IP_FILTERS);
            assertTrue(session.watcher() != null, "watching while an engine is up");
        } finally {
            session.close();
        }
        assertEquals(null, session.watcher(), "and not after");
    }

    private static final EngineProfile OTHER =
            new EngineProfile("nas", "NAS", EngineProfile.Kind.REMOTE, "http://nas:9091/transmission/rpc", "", "");

    /**
     * The session builds the profile's engine, tells the owner before starting it (to listen from
     * its first event), starts it off the caller's thread and polls it; a switch stops the poll and
     * the old engine and polls the new one; quitting stops the engine in use.
     */
    @Test
    void theSessionStartsSwitchesAndStopsTheEngine(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        AtomicInteger localStops = new AtomicInteger();
        AtomicInteger otherStops = new AtomicInteger();
        Engine local = engine(List::of, id -> {}, () -> {}, localStops);
        Engine other = engine(List::of, id -> {}, () -> {}, otherStops);
        session.setEngineFactory(profile -> profile.isLocal() ? local : other);
        List<Engine> polled = new CopyOnWriteArrayList<>();
        session.addListener(p -> polled.add(p.engine()));

        session.startEngine(EngineProfile.LOCAL);
        await(() -> host.ready.contains(local) && polled.contains(local));
        assertEquals(List.of(local), host.created, "heard of before it started");
        assertEquals(local, session.engine());

        session.switchTo(OTHER);
        await(() -> host.ready.contains(other) && polled.contains(other) && localStops.get() == 1);
        assertEquals(OTHER, session.profile());
        int beforeQuit = polled.size();
        session.stopEngine();
        assertEquals(1, otherStops.get(), "stopped at quit, on the caller's thread");
        Thread.sleep(Session.POLL_MS * 2);
        assertEquals(beforeQuit, polled.size(), "and no longer polled");
        assertTrue(host.failed.isEmpty(), host.failed.toString());
    }

    /** An engine that cannot be built, or cannot start, is the owner's to hear of; nothing is polled. */
    @Test
    void anEngineThatCannotStartIsSaidAndNotPolled(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        session.setEngineFactory(profile -> engine(
                List::of,
                id -> {},
                () -> {
                    throw new IllegalStateException("no native library");
                },
                new AtomicInteger()));
        session.startEngine(EngineProfile.LOCAL);
        await(() -> !host.failed.isEmpty());
        assertEquals("no native library", host.failed.get(0).getMessage());
        assertEquals(null, session.engine());

        session.setEngineFactory(profile -> {
            throw new IllegalArgumentException("bad profile");
        });
        session.startEngine(OTHER);
        assertEquals("bad profile", host.failed.get(1).getMessage(), "said at once, on the caller's thread");
    }

    /**
     * A switch while the first engine is still connecting: that one, once up, is stopped rather
     * than polled beside the next, and says nothing.
     */
    @Test
    void anEngineOvertakenByASwitchIsStoppedNeverPolled(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        CountDownLatch slow = new CountDownLatch(1);
        AtomicInteger slowStops = new AtomicInteger();
        Engine connecting = engine(
                List::of,
                id -> {},
                () -> {
                    try {
                        slow.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                slowStops);
        Engine next = engine(List::of, id -> {}, () -> {}, new AtomicInteger());
        session.setEngineFactory(profile -> profile.isLocal() ? connecting : next);
        List<Engine> polled = new CopyOnWriteArrayList<>();
        session.addListener(p -> polled.add(p.engine()));
        session.startEngine(EngineProfile.LOCAL);
        session.switchTo(OTHER);
        await(() -> polled.contains(next));
        slow.countDown();
        await(() -> slowStops.get() == 1);
        Thread.sleep(Session.POLL_MS * 2);
        assertFalse(polled.contains(connecting), "never polled");
        assertEquals(List.of(next), host.ready, "only the newer one is said to be ready");
        session.stopEngine();
    }

    /** A film and a program named like one beside it: the content check flags the second. */
    private static final TorrentPreview FILM = new TorrentPreview(
            "film",
            "Film.2026",
            1010,
            List.of(
                    new TorrentPreview.FileEntry(0, "Film.2026/film.mkv", 1000),
                    new TorrentPreview.FileEntry(1, "Film.2026/film.mkv.exe", 10)),
            false,
            List.of(),
            "",
            "",
            0,
            true,
            false);

    /** What an add engine was asked and did, for the pipeline's tests. */
    private static final class Adds {
        final List<AddRequest> added = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile List<Torrent> torrents = List.of();
        volatile List<TorrentDetail.FileStatus> files = List.of();
        volatile TorrentPreview preview = FILM;
        volatile com.torrenta.engine.ContentIndex index = com.torrenta.engine.ContentIndex.EMPTY;

        Engine engine() {
            return (Engine) Proxy.newProxyInstance(
                    Engine.class.getClassLoader(),
                    new Class<?>[] {Engine.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "torrents" -> torrents;
                        case "rates" -> Engine.Rates.ZERO;
                        case "capabilities" -> Engine.Capabilities.ALL;
                        case "refreshLimits" -> false;
                        case "setIpFilter", "start", "stop", "addListener" -> null;
                        case "inspect" -> preview;
                        case "contentIndex" -> index;
                        case "add" -> {
                            added.add((AddRequest) args[0]);
                            yield seed("film", 0, true);
                        }
                        case "detail" ->
                            new TorrentDetail(
                                    null,
                                    files,
                                    List.of(),
                                    List.of(),
                                    new boolean[0],
                                    new int[0],
                                    List.of(),
                                    java.util.Set.of(),
                                    "");
                        case "setFilePriority", "resume", "remove" -> {
                            calls.add(method.getName() + " " + java.util.Arrays.toString(args));
                            yield null;
                        }
                        case "toString" -> "add engine";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /**
     * A {@code .torrent} added without a dialog is checked before it is added: under the default
     * (skip), the program named like a film is left out and the log hears why.
     */
    @Test
    void aTorrentFilesFlaggedFileIsSkippedOnAnAddWithoutADialog(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        Adds adds = new Adds();
        AddRequest request =
                AddDefaults.from(settings).withoutAsking(new AddRequest.TorrentFile(new byte[1], "film.torrent"));
        session.add(adds.engine(), request);
        assertEquals(1, adds.added.size());
        assertEquals(
                java.util.Map.of(1, com.torrenta.engine.Priority.IGNORE),
                adds.added.get(0).filePriorities(),
                "the program left out, the film kept");
        assertEquals(1, host.reports.size(), "what was found is said");
        assertTrue(
                host.reports.get(0).message().contains("film.mkv.exe"),
                host.reports.get(0).message());
    }

    /**
     * An add without a dialog uses what is on disk as the setting says: the film, found complete
     * in another torrent on the same volume, is linked into place before the add and recorded for
     * the Files tab; the flagged program beside it is skipped and so never looked for. Off, the
     * film is downloaded as ever.
     */
    @Test
    void anAddWithoutADialogLinksWhatIsOnDiskAsTheSettingSays(@TempDir Path dir) throws Exception {
        String root = "6".repeat(64);
        Path old = dir.resolve("seeds/Old/old.mkv");
        Files.createDirectories(old.getParent());
        Files.write(old, new byte[1000]);
        com.torrenta.engine.ContentIndex index = new com.torrenta.engine.ContentIndex();
        index.put("old", List.of(new com.torrenta.engine.ContentIndex.Entry(root, "old", 0, old, 1000)));
        Torrent oldTorrent = seed("old", 0, false);

        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.DOWNLOAD_DIR, dir.resolve("dl").toString());
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        Adds adds = new Adds();
        adds.torrents = List.of(oldTorrent);
        adds.index = index;
        adds.preview = new TorrentPreview(
                "film",
                "Film.2026",
                1010,
                List.of(
                        new TorrentPreview.FileEntry(0, "Film.2026/film.mkv", 1000, root),
                        new TorrentPreview.FileEntry(1, "Film.2026/film.mkv.exe", 10, root.replace('6', '7'))),
                false,
                List.of(),
                "",
                "",
                0,
                true,
                true);
        AddRequest request =
                AddDefaults.from(settings).withoutAsking(new AddRequest.TorrentFile(new byte[1], "film.torrent"));
        session.add(adds.engine(), request);
        AddRequest added = adds.added.getFirst();
        assertEquals(
                java.util.Map.of(0, new com.torrenta.engine.FoundFile(com.torrenta.engine.FoundFile.How.LINK, "name")),
                added.storage().found(),
                "linked without asking, from the torrent of that name");
        Path target = dir.resolve("dl/Film.2026/film.mkv");
        assertTrue(Files.isSameFile(old, target));
        assertTrue(
                host.reports.stream().anyMatch(e -> e.message().startsWith("One file of “Film.2026” already on disk")),
                host.reports.toString());

        // Off: nothing is looked for.
        Files.delete(target);
        settings.setAddDedup(com.torrenta.engine.DedupMode.OFF.name());
        session.add(adds.engine(), request);
        assertTrue(adds.added.get(1).storage().found().isEmpty());
        assertFalse(Files.exists(target));
    }

    /**
     * A magnet has no files when it is added: it is added to stop at its metadata and marked; the
     * poll checks it once its files are known, skips the flagged one, starts it, and clears the mark.
     */
    @Test
    void aMagnetIsStoppedAtItsMetadataAndCheckedOnceItsFilesAreKnown(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        Adds adds = new Adds();
        Engine engine = adds.engine();
        session.add(
                engine, AddDefaults.from(settings).withoutAsking(new AddRequest.Magnet("magnet:?xt=urn:btih:film")));
        assertEquals(
                AddRequest.StopCondition.METADATA_RECEIVED, adds.added.get(0).stopCondition());
        assertTrue(settings.unsafeContentPending().containsKey("film"), "marked for the poll");

        // The metadata is in: the torrent stopped, its files known.
        adds.torrents = List.of(seed("film", 0, true));
        adds.files = List.of(
                new TorrentDetail.FileStatus(0, "Film.2026/film.mkv", 1000, 0, com.torrenta.engine.Priority.NORMAL),
                new TorrentDetail.FileStatus(1, "Film.2026/film.mkv.exe", 10, 0, com.torrenta.engine.Priority.NORMAL));
        session.setEngineFactory(profile -> engine);
        session.startEngine(EngineProfile.LOCAL);
        try {
            await(() -> settings.unsafeContentPending().isEmpty());
        } finally {
            session.stopEngine();
        }
        assertEquals(List.of("setFilePriority [film, 1, IGNORE]", "resume [film]"), adds.calls);
        assertTrue(
                new Settings(dir.resolve(Settings.FILE_NAME))
                        .unsafeContentPending()
                        .isEmpty(),
                "and saved so");
    }

    /** The listeners and adds of the plain proxy engines, for the tests that look at them. */
    private static final List<java.util.function.Consumer<EngineEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    private static final List<Object> ADDED = new CopyOnWriteArrayList<>();

    /** The engine's events reach the owner from the first, through the session's own listener. */
    @Test
    void theEnginesEventsReachTheOwner(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        LISTENERS.clear();
        session.setEngineFactory(profile -> engine(List::of, id -> {}));
        session.startEngine(EngineProfile.LOCAL);
        try {
            await(() -> !host.ready.isEmpty());
            assertEquals(1, LISTENERS.size(), "attached before the start");
            EngineEvent added = EngineEvent.info("a", "name", "Added name");
            LISTENERS.get(0).accept(added);
            assertEquals(List.of(added), host.events);
        } finally {
            session.stopEngine();
        }
    }

    /**
     * An input with no engine up waits, and is handed on once one is, in order; one that comes
     * while an engine is up is handed on at once.
     */
    @Test
    void anInputWaitsForAnEngineAndIsHandedOnInOrder(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        RecordingHost host = new RecordingHost();
        Session session = session(dir, settings, host);
        List<String> handed = new CopyOnWriteArrayList<>();
        session.setInputHandler(handed::add);
        session.input("magnet:?xt=urn:btih:one");
        session.input("/tmp/two.torrent");
        assertTrue(handed.isEmpty(), "no engine yet");
        session.setEngineFactory(profile -> engine(List::of, id -> {}));
        session.startEngine(EngineProfile.LOCAL);
        try {
            await(() -> handed.size() == 2);
            assertEquals(List.of("magnet:?xt=urn:btih:one", "/tmp/two.torrent"), handed);
            session.input("magnet:?xt=urn:btih:three");
            assertEquals("magnet:?xt=urn:btih:three", handed.get(2), "at once while one is up");
        } finally {
            session.stopEngine();
        }
    }

    /** "Add without asking" of a magnet: the defaults' request, through the session's own add. */
    @Test
    void anInputAddedWithoutAskingIsTheDefaultsRequest(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.ADD_UNSAFE_CONTENT, "ADD");
        Session session = session(dir, settings, new RecordingHost());
        assertThrows(
                com.torrenta.engine.EngineException.class, () -> session.addWithoutAsking("magnet:?xt=urn:btih:x"));
        ADDED.clear();
        session.setEngineFactory(profile -> engine(List::of, id -> {}));
        session.startEngine(EngineProfile.LOCAL);
        try {
            await(() -> session.engine() != null);
            session.addWithoutAsking("  magnet:?xt=urn:btih:x  ");
            AddRequest request = (AddRequest) ADDED.get(0);
            assertEquals(new AddRequest.Magnet("magnet:?xt=urn:btih:x"), request.source());
            assertEquals(settings.downloadDir(), request.savePath());
            assertEquals(
                    com.torrenta.engine.LinkRequest.of("https://example.org/file.iso"),
                    addLink(session, "https://example.org/file.iso"),
                    "a link that is not a torrent goes to curl");
        } finally {
            session.stopEngine();
        }
    }

    private static Object addLink(Session session, String url) throws Exception {
        ADDED.clear();
        session.addWithoutAsking(url);
        return ADDED.get(0);
    }

    /** The session keeps its rates and every torrent's; a followed torrent's history grows from then. */
    @Test
    void theHistoriesAreKeptAndAFollowedTorrentsGrows(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        Session session = session(dir, settings, new RecordingHost());
        session.setEngineFactory(profile -> engine(() -> List.of(seed("s", 0, false)), id -> {}));
        session.startEngine(EngineProfile.LOCAL);
        try {
            await(() -> !session.history().isEmpty());
            assertEquals(null, session.followed("s"), "not followed yet");
            session.follow("s");
            SpeedHistory followed = session.followed("s");
            assertTrue(followed != null && !followed.isEmpty(), "starts from the recent one");
        } finally {
            session.stopEngine();
        }
    }

    private static final class RecordingHost implements Session.Host {
        final List<EngineEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void event(EngineEvent event) {
            events.add(event);
        }

        final List<EngineEvent> reports = new CopyOnWriteArrayList<>();

        @Override
        public void report(EngineEvent event) {
            reports.add(event);
        }

        final AtomicInteger watchStatus = new AtomicInteger();
        final List<Engine> created = new CopyOnWriteArrayList<>();
        final List<Engine> ready = new CopyOnWriteArrayList<>();
        final List<com.torrenta.engine.EngineException> failed = new CopyOnWriteArrayList<>();

        @Override
        public void engineCreated(Engine engine) {
            created.add(engine);
        }

        @Override
        public void engineReady(Engine engine) {
            ready.add(engine);
        }

        @Override
        public void engineFailed(com.torrenta.engine.EngineException failure) {
            failed.add(failure);
        }

        @Override
        public void watchStatusChanged() {
            watchStatus.incrementAndGet();
        }

        final List<EngineEvent> seedingDone = new CopyOnWriteArrayList<>();
        final List<Boolean> limits = new CopyOnWriteArrayList<>();

        @Override
        public void seedingDone(EngineEvent event, Torrent torrent, boolean removed) {
            seedingDone.add(event);
        }

        @Override
        public void limitsRefreshed(boolean alternative) {
            limits.add(alternative);
        }

        @Override
        public void problem(EngineEvent event) {
            throw new AssertionError(event.message());
        }
    }

    private interface Pauser {
        void pause(String id);
    }

    /** The few calls a session makes, answered; anything else is a test that needs more. */
    private static Engine engine(java.util.function.Supplier<List<Torrent>> torrents, Pauser pauser) {
        return engine(torrents, pauser, () -> {}, new AtomicInteger());
    }

    /** {@code starting} runs inside {@code start()} (a wait, or a throw); {@code stops} counts {@code stop()}. */
    private static Engine engine(
            java.util.function.Supplier<List<Torrent>> torrents,
            Pauser pauser,
            Runnable starting,
            AtomicInteger stops) {
        return (Engine) Proxy.newProxyInstance(
                Engine.class.getClassLoader(),
                new Class<?>[] {Engine.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "torrents" -> torrents.get();
                    case "rates" -> Engine.Rates.ZERO;
                    case "capabilities" -> Engine.Capabilities.ALL;
                    case "refreshLimits" -> false;
                    case "setIpFilter" -> {
                        IP_FILTERS.add(List.copyOf((List<?>) args[0]));
                        yield null;
                    }
                    case "pause" -> {
                        pauser.pause((String) args[0]);
                        yield null;
                    }
                    case "start" -> {
                        starting.run();
                        yield null;
                    }
                    case "stop" -> {
                        stops.incrementAndGet();
                        yield null;
                    }
                    case "addListener" -> {
                        LISTENERS.add((java.util.function.Consumer<EngineEvent>) args[0]);
                        yield null;
                    }
                    case "addLink", "add" -> {
                        ADDED.add(args[0]);
                        yield seed("added", 0, false);
                    }
                    case "toString" -> "test engine";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A finished torrent of 1000 bytes that has sent {@code uploaded}. */
    private static Torrent seed(String id, long uploaded, boolean paused) {
        return new Torrent(
                id,
                "name",
                1000,
                1000,
                1,
                TorrentState.SEEDING,
                0,
                0,
                0,
                0,
                uploaded,
                1000,
                0,
                0,
                "",
                "/data",
                paused,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false,
                false,
                -1,
                false,
                false);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }
}
