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

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final class RecordingHost implements Session.Host {
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
