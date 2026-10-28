package com.torrenta.ui;

import java.nio.file.Path;

import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The list's selection through the omnibox: what a typed command is for. */
@ExtendWith(FxToolkit.class)
class MainWindowSelectionTest {

    @Test
    void aTypedCommandActsOnEveryTorrentSelected(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine()
                .put(FakeEngine.torrent("aaaa", "Film"))
                .put(FakeEngine.torrent("bbbb", "Album", TorrentState.SEEDING, 1.0));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            assertEquals(2, Fx.call(f::selected));
            // '>' turns the omnibox into the command line; the list, and its selection, stay.
            f.action("omnibox:>pause");
            assertEquals(2, Fx.call(f::selected), "the selection the command is for");
            f.action("focus-key:ENTER");
            f.awaitCall("pause aaaa");
            f.awaitCall("pause bbbb");
        }
    }
}
