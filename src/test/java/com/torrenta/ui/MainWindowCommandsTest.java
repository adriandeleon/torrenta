package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main window's commands on a fake engine, run by id as the menus, the palette and the keys
 * run them: what reached the engine, and what the window says.
 */
@ExtendWith(FxToolkit.class)
class MainWindowCommandsTest {

    /** The platform's Shortcut as the capture hook's focus-key names it. */
    static final String SHORTCUT =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac") ? "META" : "CTRL";

    @AfterEach
    void noAnswerLeftOver() {
        RemoveConfirm.answerForCapture = null;
    }

    static FakeEngine twoTorrents() {
        return new FakeEngine()
                .put(FakeEngine.torrent("aaaa", "Film"))
                .put(FakeEngine.torrent("bbbb", "Album", TorrentState.SEEDING, 1.0));
    }

    private static boolean anyCall(FakeEngine engine, String prefix) {
        return engine.calls().stream().anyMatch(c -> c.startsWith(prefix));
    }

    private static long count(FakeEngine engine, String prefix) {
        return engine.calls().stream().filter(c -> c.startsWith(prefix)).count();
    }

    @Test
    void theTorrentCommandsReachTheEngineForEveryTorrentSelected(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            for (String id : List.of(
                    "force-resume",
                    "recheck",
                    "reannounce",
                    "sequential",
                    "queue-top",
                    "queue-up",
                    "queue-down",
                    "queue-bottom")) {
                f.action(id);
            }
            for (String call : List.of(
                    "forceResume aaaa",
                    "forceResume bbbb",
                    "recheck aaaa",
                    "recheck bbbb",
                    "reannounce aaaa",
                    "setSequential aaaa true",
                    "queueMove aaaa TOP",
                    "queueMove bbbb BOTTOM")) {
                f.awaitCall(call);
            }
            // Super seeding is for seeds only.
            f.action("super-seed");
            f.awaitCall("setSuperSeeding bbbb true");
            assertFalse(anyCall(engine, "setSuperSeeding aaaa"), engine.calls().toString());
            f.action("select-first");
            f.action("pause-resume");
            Fx.waitUntil(
                    "a row shown paused",
                    () -> f.shown("aaaa").paused() || f.shown("bbbb").paused());
            f.action("pause-resume");
            Fx.waitUntil("a pause and a resume", () -> anyCall(engine, "pause ") && anyCall(engine, "resume "));
        }
    }

    @Test
    void superSeedingWithNoSeedSelectedSaysSo(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, new FakeEngine().put(FakeEngine.torrent("aaaa", "Film")))) {
            f.action("select-first");
            f.action("super-seed");
            assertEquals(tr("status.superSeed.none"), f.status());
        }
    }

    @Test
    void aRefusalIsSaidInTheStatusBar(@TempDir Path dir) {
        FakeEngine engine = twoTorrents().refuse("recheck", "magnetLink");
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            f.action("recheck");
            f.awaitStatus("refuses recheck");
            // A magnet the engine will not give is made from the id.
            f.action("copy-magnet");
            f.awaitStatus("2");
        }
    }

    @Test
    void theCopiesFillTheClipboardAndSayHowMany(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            f.action("copy-name");
            assertEquals(tr("status.copied.name", 2), f.status());
            assertEquals(
                    Fx.call(() -> javafx.scene.input.Clipboard.getSystemClipboard()
                                    .getString())
                            .lines()
                            .count(),
                    2);
            f.action("copy-hash");
            assertEquals(tr("status.copied.infoHash", 2), f.status());
            f.action("copy-magnet");
            f.awaitStatus(tr("status.copied.magnet", 2));
            assertTrue(Fx.call(() ->
                            javafx.scene.input.Clipboard.getSystemClipboard().getString())
                    .contains("magnet:?xt=urn:btih:aaaa"));
        }
    }

    @Test
    void exportWritesEveryTorrentIntoTheFolderUnderAnUnusedName(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        Files.writeString(out.resolve("Film.torrent"), "taken");
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            f.action("export-to:" + out);
            f.awaitCall("exportTorrent aaaa");
            f.awaitCall("exportTorrent bbbb");
            Fx.waitUntil("three files in the folder", () -> {
                try (var files = Files.list(out)) {
                    return files.count() == 3;
                } catch (java.io.IOException e) {
                    return false;
                }
            });
            engine.refuse("exportTorrent");
            f.action("export-to:" + out);
            f.awaitStatus("refuses exportTorrent");
        }
    }

    @Test
    void openFolderOpensTheSavePath(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("select-first");
            f.action("open-folder");
            assertEquals(1, f.opened.size(), f.opened.toString());
            assertTrue(f.opened.getFirst().startsWith("file:"), f.opened.toString());
        }
    }

    @Test
    void typedCommandsActOnTheSelection(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        String target = dir.resolve("moved").toString().replace(' ', '~');
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            for (String typed : List.of(
                    ">set-category~Films",
                    ">add-tag~keep",
                    ">remove-tag~keep",
                    ">limit-down~500k",
                    ">limit-up~1m",
                    ">sequential~on",
                    ">queue~top",
                    ">queue~up",
                    ">queue~down",
                    ">queue~bottom",
                    ">pause",
                    ">resume",
                    ">recheck",
                    ">reannounce",
                    ">set-location~" + target)) {
                f.action("omnibox:" + typed);
                f.action("focus-key:ENTER");
            }
            for (String call : List.of(
                    "setCategory aaaa Films",
                    "setTags aaaa [keep]",
                    "setTags aaaa []",
                    "setLimits aaaa",
                    "setSequential bbbb true",
                    "queueMove aaaa TOP",
                    "queueMove aaaa BOTTOM",
                    "pause aaaa",
                    "resume bbbb",
                    "recheck aaaa",
                    "reannounce bbbb",
                    "moveStorage aaaa")) {
                Fx.waitUntil("the engine asked " + call, () -> anyCall(engine, call));
            }
            Fx.waitUntil("two limits per torrent", () -> count(engine, "setLimits aaaa") == 2);
            // A typed command that does not read says why and does nothing.
            f.action("omnibox:>limit-down~fast");
            f.action("focus-key:ENTER");
            assertTrue(f.status().contains("limit-down"), f.status());
            // A typed removal always asks.
            RemoveConfirm.answerForCapture = true;
            f.action("omnibox:>remove");
            f.action("focus-key:ENTER");
            f.awaitCall("detach aaaa");
            Fx.waitUntil("the list empty", () -> f.rows() == 0);
        }
    }

    @Test
    void typedCommandsOnAChipActOnWhatMatchesAndSayWhenNothingDoes(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("omnibox:state:downloading");
            f.action("omnibox:state:downloading~");
            Fx.waitUntil("one row", () -> f.rows() == 1);
            f.action("omnibox:>pause");
            f.action("focus-key:ENTER");
            f.awaitCall("pause aaaa");
            assertFalse(anyCall(engine, "pause bbbb"));
            f.action("omnibox-key:ESCAPE");
            f.action("omnibox-key:ESCAPE");
            f.action("table-key:ESCAPE");
            f.action("omnibox:>recheck");
            f.action("focus-key:ENTER");
            assertEquals(tr("omni.nothing"), f.status());
        }
    }

    @Test
    void theListsKeysPauseRemoveAndClear(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("table-key:SPACE");
            Fx.waitUntil("a pause or a resume", () -> anyCall(engine, "pause ") || anyCall(engine, "resume "));
            // Ctrl+Shift+Home on the list is Move to Top, taken before the list's own binding.
            f.action("focus-key:" + SHORTCUT + "+SHIFT+HOME");
            Fx.waitUntil("a queue move", () -> anyCall(engine, "queueMove "));
            f.action("table-key:ESCAPE");
            assertEquals(
                    0,
                    Fx.call(() -> f.window.lateLinesForCapture().stream()
                            .filter(l -> l.startsWith("table=") && l.contains("*"))
                            .count()));
            f.action("select-first");
            RemoveConfirm.answerForCapture = true;
            f.action("table-key:DELETE");
            Fx.waitUntil("a detach", () -> anyCall(engine, "detach "));
            f.action("select-first");
            f.action("table-key:F2");
            f.cancelDialogs();
        }
    }

    @Test
    void theOmniboxChipsItsTermsAndSelectsWhatMatches(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("omnibox:state:seeding");
            f.action("omnibox:state:seeding~");
            Fx.waitUntil("the seed alone", () -> f.rows() == 1);
            // A chip that does not read is a name to look for, and drawn as not understood.
            f.action("omnibox:is:nonsense");
            f.action("omnibox:is:nonsense~");
            Fx.waitUntil("nothing matches both", () -> f.rows() == 0);
            f.action("omnibox-key:BACK_SPACE");
            f.action("omnibox:");
            f.action("omnibox-key:BACK_SPACE");
            f.action("omnibox:");
            f.action("omnibox-key:BACK_SPACE");
            Fx.waitUntil("both again", () -> f.rows() == 2);
            f.action("omnibox:alb");
            f.action("omnibox-key:ENTER");
            assertTrue(
                    Fx.call(() -> f.window.lateLinesForCapture().stream()
                            .anyMatch(l -> l.startsWith("table=") && l.contains("*Album"))),
                    "Enter selects the matches");
            f.action("omnibox-key:ESCAPE");
            f.action("omnibox-key:ESCAPE");
            f.action("filter");
            // A magnet typed and entered is added.
            f.action("omnibox:magnet:?xt=urn:btih:" + "e".repeat(40));
            f.action("omnibox-key:ENTER");
            f.awaitWindow(tr("dialog.add.title.magnet"));
            f.cancelDialogs();
        }
    }

    @Test
    void theViewsSwitchByCommandAndByKey(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("search");
            f.action("rss");
            f.action("transfers");
            f.action("view-next");
            f.action("view-previous");
            f.action("focus-key:CTRL+TAB");
            f.action("focus-key:CTRL+SHIFT+TAB");
            f.action("view:search");
            f.action("filter");
            f.action("view:rss");
            f.action("view:nowhere");
            f.action("view:transfers");
            assertTrue(Fx.call(() -> f.window.lateLinesForCapture().stream().anyMatch(l -> l.startsWith("table="))));
        }
    }

    @Test
    void theViewTogglesMoveThePanesAndRememberIt(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("select-first");
            for (String id : List.of(
                    "toggle-toolbar",
                    "toggle-toolbar",
                    "toggle-sidebar",
                    "focus-sidebar",
                    "sidebar-side",
                    "sidebar-side",
                    "toggle-detail",
                    "toggle-detail",
                    "detail-side",
                    "detail-side",
                    "toggle-density",
                    "toggle-density",
                    "show-all")) {
                f.action(id);
            }
            for (SidebarStatus status : SidebarStatus.values()) f.action("show-" + status.key());
            f.action("show-all");
            assertTrue(Fx.call(() -> f.settings.toolbar()));
            assertTrue(Fx.call(() -> f.settings.sidebar()));
            Fx.waitUntil("both rows", () -> f.rows() == 2);
        }
    }

    @Test
    void simpleModeTakesTheChromeAwayAndGivesItBack(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, twoTorrents())) {
            f.action("toggle-simple");
            assertEquals(tr("status.simple.on"), f.status());
            assertTrue(Fx.call(() -> f.settings.simple()));
            f.action("pick-view:1");
            f.action("settings");
            f.action("toggle-simple");
            assertEquals(tr("status.simple.off"), f.status());
            Fx.run(f.window::useSimpleForSession);
            f.action("toggle-simple");
            assertFalse(Fx.call(() -> f.settings.simple()));
        }
    }

    @Test
    void altSpeedFlipsTheSettingAndThePill(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("alt-speed");
            assertEquals(tr("status.altSpeedLimitsOn"), f.status());
            Fx.waitUntil("the settings applied", () -> anyCall(engine, "applySettings"));
            f.action("alt-speed");
            assertEquals(tr("status.altSpeedLimitsOff"), f.status());
            assertFalse(Fx.call(f.window::altSpeedText).isEmpty());
        }
    }

    @Test
    void theEnginesEventsReachTheLogAndTheStatusBar(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            engine.emit(EngineEvent.info("aaaa", "Film", "Film finished"));
            engine.emit(new EngineEvent(
                    java.time.Instant.now(), EngineEvent.Level.ERROR, "aaaa", "Film", "Film: disk full"));
            f.awaitStatus("disk full");
            Fx.waitUntil(
                    "both in the log",
                    () -> f.window.eventLines().stream().anyMatch(l -> l.contains("Film finished"))
                            && f.window.eventLines().stream().anyMatch(l -> l.contains("disk full")));
            f.action("event-log");
            f.action("message-log");
            f.action("message-log:disk");
            f.action("message-log");
        }
    }

    @Test
    void anEngineThatFailsShowsWhyAndRetryStartsItAgain(@TempDir Path dir) {
        FakeEngine engine = twoTorrents();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.engineFailed(new EngineException("the daemon is not there")));
            assertEquals("the daemon is not there", f.status());
            f.action("retry-engine");
            Fx.waitUntil("a second start", () -> count(engine, "start") == 2);
        }
    }
}
