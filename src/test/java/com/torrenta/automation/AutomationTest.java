package com.torrenta.automation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libtorrent4j.TorrentBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationTest {

    private static final AutomationSettings ALL_ON =
            new AutomationSettings("add %N", "fin %N", "seed %N", true, true, true, true);

    private static EngineEvent event(EngineEvent.Kind kind, String name, String message) {
        return new EngineEvent(Instant.EPOCH, EngineEvent.Level.INFO, "abc", name, message, kind);
    }

    @Test
    void thePlanFollowsTheSettings() {
        Automation.Plan finished = Automation.plan(event(EngineEvent.Kind.FINISHED, "pair", "pair finished"), ALL_ON);
        assertEquals(List.of("fin", "%N"), finished.command(), "tokens, not yet substituted");
        assertEquals(Optional.of("pair finished"), finished.notification());

        Automation.Plan added = Automation.plan(event(EngineEvent.Kind.ADDED, "pair", "Added pair"), ALL_ON);
        assertEquals(List.of("add", "%N"), added.command());
        assertEquals(Optional.of("pair added"), added.notification());

        Automation.Plan seeded = Automation.plan(event(EngineEvent.Kind.SEEDING_DONE, "pair", "…"), ALL_ON);
        assertEquals(List.of("seed", "%N"), seeded.command());
        assertEquals(Optional.of("pair finished seeding"), seeded.notification());

        Automation.Plan error = Automation.plan(event(EngineEvent.Kind.ERROR, "pair", "pair: disk full"), ALL_ON);
        assertEquals(List.of(), error.command(), "nothing runs on an error");
        assertEquals(Optional.of("pair: disk full"), error.notification(), "the message already names the torrent");
        assertEquals(
                Optional.of("pair: tracker down"),
                Automation.plan(event(EngineEvent.Kind.ERROR, "pair", "tracker down"), ALL_ON)
                        .notification());

        assertTrue(Automation.plan(event(EngineEvent.Kind.OTHER, "pair", "pair checked"), ALL_ON)
                .isEmpty());
        assertTrue(Automation.plan(event(EngineEvent.Kind.ADDED, "pair", "Added pair"), AutomationSettings.DEFAULTS)
                .isEmpty());
        assertEquals(
                Optional.of("pair finished"),
                Automation.plan(event(EngineEvent.Kind.FINISHED, "pair", "pair finished"), AutomationSettings.DEFAULTS)
                        .notification(),
                "finished notifies by default");
        assertTrue(AutomationSettings.DEFAULTS.active(), "finished notifies by default");
        assertFalse(new AutomationSettings("", "", "", false, false, false, false).active());
        assertEquals(
                "x", new AutomationSettings("  x  ", null, null, false, false, false, false).onAdded(), "stripped");
    }

    /** A host that remembers what it was told. */
    private static final class Reports implements Automation.Host {
        final List<EngineEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void report(EngineEvent event) {
            events.add(event);
        }

        List<String> messages() {
            return events.stream().map(EngineEvent::message).toList();
        }
    }

    /** A notifier that counts, and fails when told to. */
    private static final class CountingNotifier implements Notifier {
        final AtomicInteger shown = new AtomicInteger();
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile String problem;

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Optional<String> show(String title, String message) {
            shown.incrementAndGet();
            messages.add(title + ": " + message);
            return Optional.ofNullable(problem);
        }
    }

    @Test
    void aFinishedTorrentRunsTheCommandWithItsFactsAsArguments(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Path out = dir.resolve("ran.txt");
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, new TorrentStore(dir.resolve("store")))) {
            engine.start(); // the native is loaded here; TorrentBuilder needs it too
            byte[] torrent = twoFileTorrent(downloads, "pair");
            AutomationSettings settings = new AutomationSettings(
                    "",
                    ArgsToFile.line(out, "%N %C %Z \"%F\" %D %R %I %J %K \"%L\" \"%G\" %% %T"),
                    "",
                    false,
                    false,
                    false,
                    false);
            Reports reports = new Reports();
            CountingNotifier notifier = new CountingNotifier();
            Automation automation = new Automation(engine, () -> settings, notifier, reports);
            engine.addListener(automation::handle);

            Torrent t = engine.add(new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    new TorrentLabels("shows", List.of("b", "a"))));
            await(
                    "the command to run",
                    () -> Files.exists(out) && reports.messages().stream().anyMatch(m -> m.contains("exited")));
            assertTrue(automation.awaitIdle(Duration.ofSeconds(10)));

            List<String> args = Files.readAllLines(out, StandardCharsets.UTF_8);
            assertEquals("pair", args.get(0), "%N");
            assertEquals("2", args.get(1), "%C counts the files, padding left out");
            assertEquals(String.valueOf(150 * 1024), args.get(2), "%Z");
            assertEquals(downloads.resolve("pair").toString(), args.get(3), "%F is the root folder");
            assertEquals(downloads.toString(), args.get(4), "%D");
            assertEquals(downloads.resolve("pair").toString(), args.get(5), "%R");
            // TorrentBuilder makes a hybrid: the id is the v2 hash cut to 20 bytes, %I the v1 one.
            assertEquals(40, args.get(6).length(), "%I is the v1 hash: " + args.get(6));
            assertEquals(64, args.get(7).length(), "%J is the full v2 hash: " + args.get(7));
            assertEquals(t.id(), args.get(7).substring(0, 40), "the id is the best hash, truncated");
            assertEquals(t.id(), args.get(8), "%K");
            assertEquals("shows", args.get(9), "%L");
            assertEquals("a,b", args.get(10), "%G, sorted as the labels are");
            assertEquals("%", args.get(11), "%% is a percent");
            assertEquals(13, args.size(), "%T is one argument even when empty: " + args);

            List<String> messages = reports.messages();
            String exit = messages.stream()
                    .filter(m -> m.contains("exited"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(exit.startsWith("On finished for pair: "), exit);
            assertTrue(exit.endsWith(" exited 0"), exit);
            assertTrue(messages.contains("  hello from ArgsToFile"), messages.toString());
            assertTrue(messages.contains("  third line"), "three lines of output reach the log");
            assertFalse(messages.contains("  fourth line, not logged"), "no more than three");
            assertEquals(0, notifier.shown.get(), "notifications were off");
            EngineEvent logged = reports.events.stream()
                    .filter(e -> e.message().contains("exited"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(t.id(), logged.torrentId());
            assertEquals(EngineEvent.Level.INFO, logged.level());
        }
    }

    @Test
    void addingNotifiesAndRunsAndTheRestoredOnesAreNotAdded(@TempDir Path dir) throws Exception {
        Path downloads = dir.resolve("downloads");
        Path out = dir.resolve("added.txt");
        TorrentStore store = new TorrentStore(dir.resolve("store"));
        AutomationSettings settings =
                new AutomationSettings(ArgsToFile.line(out, "added %N"), "", "", true, false, false, false);
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, store)) {
            engine.start();
            byte[] torrent = twoFileTorrent(downloads, "pair");
            Reports reports = new Reports();
            CountingNotifier notifier = new CountingNotifier();
            Automation automation = new Automation(engine, () -> settings, notifier, reports);
            engine.addListener(automation::handle);
            engine.add(new AddRequest(
                    new AddRequest.TorrentFile(torrent, "pair.torrent"),
                    downloads,
                    false,
                    false,
                    false,
                    AddRequest.StopCondition.NONE,
                    Map.of(),
                    TorrentLabels.NONE));
            await("the added command", () -> Files.exists(out));
            assertTrue(automation.awaitIdle(Duration.ofSeconds(10)));
            assertEquals(List.of("added", "pair"), Files.readAllLines(out, StandardCharsets.UTF_8));
            assertEquals(List.of("Torrenta: pair added"), notifier.messages);
            engine.flush();
        }
        Files.delete(out);
        try (LibtorrentEngine engine = new LibtorrentEngine(downloads, store)) {
            Reports reports = new Reports();
            CountingNotifier notifier = new CountingNotifier();
            Automation automation = new Automation(engine, () -> settings, notifier, reports);
            engine.addListener(automation::handle);
            engine.start();
            await("the torrent to be restored", () -> engine.torrents().size() == 1);
            assertTrue(automation.awaitIdle(Duration.ofSeconds(10)));
            assertFalse(Files.exists(out), "a restored torrent is not an added one");
            assertEquals(0, notifier.shown.get());
        }
    }

    @Test
    void aNotifierThatIsUnavailableIsSaidOnceAndTheTestButtonAlwaysReports() throws Exception {
        Reports reports = new Reports();
        CountingNotifier notifier = new CountingNotifier();
        notifier.problem = "notify-send could not be run: not found";
        Engine engine = null; // never touched: no command is configured
        Automation automation = new Automation(engine, () -> ALL_ON, notifier, reports);
        Torrent gone = new Torrent(
                "abc",
                "pair",
                0,
                0,
                1,
                com.torrenta.engine.TorrentState.SEEDING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                false,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false);
        AutomationSettings notifyOnly = new AutomationSettings("", "", "", true, true, true, true);
        Automation quiet = new Automation(engine, () -> notifyOnly, notifier, reports);
        quiet.handle(event(EngineEvent.Kind.FINISHED, "pair", "pair finished"), gone);
        quiet.handle(event(EngineEvent.Kind.ERROR, "pair", "pair: disk full"), gone);
        quiet.handle(event(EngineEvent.Kind.ERROR, "pair", "pair: disk full"), gone);
        quiet.handle(event(EngineEvent.Kind.ERROR, "pair", "pair: disk full"), gone);
        assertTrue(quiet.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(2, notifier.shown.get(), "every event still tries, but the same error again does not");
        quiet.handle(event(EngineEvent.Kind.ERROR, "pair", "pair: tracker gone"), gone);
        quiet.handle(event(EngineEvent.Kind.ERROR, "pair", "pair: disk full"), gone);
        assertTrue(quiet.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(4, notifier.shown.get(), "a different error, and the first one back, both show");
        assertEquals(
                List.of("Desktop notifications are unavailable: notify-send could not be run: not found"),
                reports.messages(),
                "but the log hears about it once");
        assertEquals(EngineEvent.Level.WARNING, reports.events.get(0).level());

        quiet.testNotification();
        assertTrue(quiet.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(
                "Desktop notification failed: notify-send could not be run: not found",
                reports.messages().get(1));
        notifier.problem = null;
        quiet.testNotification();
        assertTrue(quiet.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(
                "Test notification shown through counting", reports.messages().get(2));
        assertEquals("Torrenta: Test notification", notifier.messages.get(notifier.messages.size() - 1));
        assertTrue(automation.awaitIdle(Duration.ofSeconds(1)));
    }

    @Test
    void aCommandThatCannotRunIsAnErrorInTheLog(@TempDir Path dir) throws Exception {
        Reports reports = new Reports();
        Torrent gone = new Torrent(
                "abc",
                "pair",
                0,
                0,
                1,
                com.torrenta.engine.TorrentState.SEEDING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                dir.toString(),
                false,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false);
        AutomationSettings settings = new AutomationSettings(
                "", "", "torrenta-no-such-program-" + System.nanoTime() + " %N", false, false, false, false);
        Automation automation = new Automation(null, () -> settings, new CountingNotifier(), reports);
        automation.handle(event(EngineEvent.Kind.SEEDING_DONE, "pair", "Share limit reached: removed pair"), gone);
        assertTrue(automation.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(1, reports.events.size(), reports.messages().toString());
        assertEquals(EngineEvent.Level.ERROR, reports.events.get(0).level());
        assertTrue(reports.messages().get(0).startsWith("On seeding done for pair: torrenta-no-such-program-"));
        assertTrue(
                reports.messages().get(0).contains(" could not be run: "),
                reports.messages().get(0));
    }

    /** A two-file torrent whose data is already in {@code downloads/<name>/}; after the engine started. */
    private static byte[] twoFileTorrent(Path downloads, String name) throws Exception {
        Path root = downloads.resolve(name);
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("a.bin"), new byte[100 * 1024]);
        Files.write(root.resolve("sub").resolve("b.bin"), new byte[50 * 1024]);
        return new TorrentBuilder()
                .path(root.toFile())
                .addTracker("udp://tracker.example.org:6969/announce")
                .generate()
                .entry()
                .bencode();
    }

    private interface Condition {
        boolean test() throws Exception;
    }

    private static void await(String what, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.test()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(25);
        }
    }
}
