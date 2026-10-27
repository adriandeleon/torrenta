package com.torrenta.ui;

import java.nio.file.Path;

import com.torrenta.engine.FakeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The main window on a fake engine: what the poll draws and what the commands ask of the engine. */
@ExtendWith(FxToolkit.class)
class MainWindowFxTest {

    @Test
    void theListShowsTheEnginesTorrents(@TempDir Path dir) {
        FakeEngine engine =
                new FakeEngine().put(FakeEngine.torrent("aaaa", "Film")).put(FakeEngine.torrent("bbbb", "Album"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            assertEquals(2, Fx.call(f::rows));
            engine.put(FakeEngine.torrent("cccc", "Book"));
            Fx.waitUntil("the next poll to add a row", () -> f.rows() == 3);
        }
        assertTrue(engine.stopped(), "closing the window stops the engine");
    }

    @Test
    void pauseOnTheSelectionReachesTheEngine(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("pause-resume");
            Fx.waitUntil("the pause to reach the engine", () -> engine.calls().contains("pause aaaa"));
        }
    }
}
