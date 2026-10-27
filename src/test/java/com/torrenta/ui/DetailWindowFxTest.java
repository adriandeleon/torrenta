package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The detail pane in the main window, on a fake engine with a full detail: what its actions ask the engine. */
@ExtendWith(FxToolkit.class)
class DetailWindowFxTest {

    private static final String ID = "abcd000000000000000000000000000000000000";

    @Test
    void theDetailPanesActionsReachTheEngine(@TempDir Path dir) {
        Torrent t = FakeEngine.torrent(ID, "Film");
        Torrent broken = new Torrent(
                ID,
                "Film",
                t.size(),
                t.done(),
                t.progress(),
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                t.added(),
                0,
                t.tracker(),
                t.savePath(),
                false,
                false,
                0,
                "",
                List.of(),
                "file error: disk full",
                t.trackers(),
                false);
        FakeEngine engine = new FakeEngine().put(broken).withDetail(ID, FakeEngine.richDetail(broken));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("detail:overview");
            // The error card's first remedy is Retry: a forced resume.
            f.action("remedy");
            Fx.waitUntil("the remedy to reach the engine", () -> engine.calls().contains("forceResume " + ID));

            f.action("files-rename:1:part one.mkv");
            Fx.waitUntil(
                    "the rename to reach the engine",
                    () -> engine.calls().contains("renamePath " + ID + " Film/part 1.mkv part one.mkv"));

            f.action("web-seed-remove:0");
            Fx.waitUntil(
                    "the web seed's removal",
                    () -> engine.calls().contains("removeWebSeed " + ID + " https://seed.example.org/files/"));
            f.action("web-seeds-add:https://more.example.org/");
            Fx.waitUntil(
                    "the web seed's addition",
                    () -> engine.calls().contains("addWebSeeds " + ID + " [https://more.example.org/]"));
        }
        assertTrue(engine.stopped());
    }
}
