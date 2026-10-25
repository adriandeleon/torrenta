package com.torrenta.session;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
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
        Session session = new Session(settings, host);
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
        Session session = new Session(settings, host);
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

    private static final class RecordingHost implements Session.Host {
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
        return (Engine) Proxy.newProxyInstance(
                Engine.class.getClassLoader(),
                new Class<?>[] {Engine.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "torrents" -> torrents.get();
                    case "rates" -> Engine.Rates.ZERO;
                    case "capabilities" -> Engine.Capabilities.ALL;
                    case "refreshLimits" -> false;
                    case "pause" -> {
                        pauser.pause((String) args[0]);
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
