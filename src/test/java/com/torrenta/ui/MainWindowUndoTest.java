package com.torrenta.ui;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pause and resume, a queue move and a file's priority, each taken back by Shortcut+Z on a fake
 * engine: what the undo asks of the engine, and that it leaves alone what the action did not change.
 */
@ExtendWith(FxToolkit.class)
class MainWindowUndoTest {

    @Test
    void aPauseIsUndoneByResumingOnlyWhatItPaused(@TempDir Path dir) throws Exception {
        FakeEngine engine =
                new FakeEngine().put(FakeEngine.torrent("aaaa", "Alpha")).put(FakeEngine.torrent("bbbb", "Beta"));
        engine.pause("bbbb"); // a person's pause from before: not this action's to take back
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            f.action("pause-resume");
            f.awaitCall("pause aaaa");
            f.action("undo");
            f.awaitCall("resume aaaa");
            f.awaitStatus("Undone");
            assertFalse(engine.calls().contains("resume bbbb"), engine.calls().toString());

            // A resume is undone the same way: Beta, still paused, resumed and paused again.
            Fx.waitUntil(
                    "Alpha shown running",
                    () -> !f.shown("aaaa").paused() && f.shown("bbbb").paused());
            int before = count(engine, "pause bbbb");
            f.action("sort:name");
            f.action("select-rows:1");
            f.action("pause-resume");
            f.awaitCall("resume bbbb");
            f.action("undo");
            Fx.waitUntil("Beta paused again", () -> count(engine, "pause bbbb") == before + 1);
            assertEquals(1, count(engine, "pause aaaa"), "Alpha was not this resume's");
        }
    }

    @Test
    void aQueueMoveIsUndoneByPuttingTheOrderBack(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine()
                .put(FakeEngine.atQueuePosition(FakeEngine.torrent("aaaa", "Alpha"), 0))
                .put(FakeEngine.atQueuePosition(FakeEngine.torrent("bbbb", "Beta"), 1))
                .put(FakeEngine.atQueuePosition(FakeEngine.torrent("cccc", "Gamma"), 2));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("sort:name");
            f.action("select-rows:1+2");
            f.action("queue-top");
            Fx.waitUntil("Beta and Gamma on top", () -> order(engine).equals(List.of("bbbb", "cccc", "aaaa")));
            f.action("undo");
            f.awaitStatus("Undone");
            assertEquals(List.of("aaaa", "bbbb", "cccc"), order(engine));
            assertEquals(
                    List.of("setQueuePosition aaaa 0"),
                    engine.calls().stream()
                            .filter(c -> c.startsWith("setQueuePosition"))
                            .toList(),
                    "one call: the others shift with it");
        }
    }

    @Test
    void aFilePriorityIsUndoneToTheOneItHad(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine()
                .put(FakeEngine.torrent("aaaa", "Film"))
                .withFiles(
                        "aaaa",
                        List.of(
                                new TorrentDetail.FileStatus(0, "Film/film.mkv", 200, 100, Priority.NORMAL, 0, 40),
                                new TorrentDetail.FileStatus(1, "Film/extras.mkv", 100, 0, Priority.HIGH, 41, 63)));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("detail:files");
            Fx.waitUntil(
                    "the files shown",
                    () -> f.window.lateLinesForCapture().stream().anyMatch(l -> l.startsWith("file=")));
            f.action("files-priority:2:IGNORE");
            f.awaitCall("setFilePriority aaaa 1 IGNORE");
            f.action("undo");
            f.awaitCall("setFilePriority aaaa 1 HIGH");

            // The same priority again changes nothing, so there is nothing to undo.
            f.action("files-priority:1:NORMAL");
            f.awaitCall("setFilePriority aaaa 0 NORMAL");
            f.action("undo");
            f.awaitStatus(tr("status.undo.nothing"));
        }
    }

    private static List<String> order(FakeEngine engine) {
        return engine.torrents().stream()
                .sorted(Comparator.comparingInt(Torrent::queuePosition))
                .map(Torrent::id)
                .toList();
    }

    private static int count(FakeEngine engine, String call) {
        return (int) engine.calls().stream().filter(call::equals).count();
    }
}
