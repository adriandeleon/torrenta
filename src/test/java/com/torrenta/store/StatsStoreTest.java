package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsStoreTest {

    @Test
    void roundTrip() {
        StatsStore.Totals totals = new StatsStore.Totals(12_345_678_901L, 987_654_321L, 86_400 * 3 + 7);
        String text = StatsStore.encode(totals);
        assertEquals(totals, StatsStore.decode(text));
        assertEquals(text, StatsStore.encode(StatsStore.decode(text)), "the same totals are the same bytes");
        assertTrue(text.contains("download.bytes=12345678901\n"), text);
    }

    @Test
    void aMissingKeyIsZeroAndNonsenseIsAnError() {
        assertEquals(new StatsStore.Totals(0, 5, 0), StatsStore.decode("upload.bytes=5\n"));
        assertEquals(StatsStore.Totals.ZERO, StatsStore.decode(""));
        assertThrows(IllegalArgumentException.class, () -> StatsStore.decode("download.bytes=lots\n"));
        assertThrows(IllegalArgumentException.class, () -> StatsStore.decode("active.seconds=-1\n"));
    }

    @Test
    void noFileIsZeroAndAnUnreadableFileIsZeroToo(@TempDir Path dir) throws IOException {
        StatsStore store = new StatsStore(dir.resolve("deeper").resolve(StatsStore.FILE_NAME));
        assertEquals(StatsStore.Totals.ZERO, store.load());
        Files.createDirectories(dir.resolve("deeper"));
        Files.writeString(store.file(), "download.bytes=garbage\n");
        assertEquals(StatsStore.Totals.ZERO, store.load(), "a broken file costs the totals, not the start");
    }

    @Test
    void saveIsAtomicAndCreatesTheFolder(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config").resolve(StatsStore.FILE_NAME);
        StatsStore store = new StatsStore(file);
        store.save(new StatsStore.Totals(1, 2, 3));
        assertEquals(new StatsStore.Totals(1, 2, 3), store.load());
        store.save(new StatsStore.Totals(10, 20, 30));
        assertEquals(new StatsStore.Totals(10, 20, 30), store.load(), "written over in place");
        try (Stream<Path> files = Files.list(file.getParent())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of(StatsStore.FILE_NAME), names, "no temp file left beside it");
        }
        assertFalse(Files.exists(dir.resolve(StatsStore.FILE_NAME)), "nothing written anywhere else");
    }

    @Test
    void totalsAccumulateASessionsDelta() {
        StatsStore.Totals loaded = new StatsStore.Totals(100, 50, 600);
        StatsStore.Totals afterFive = loaded.plus(10, 5, 300);
        assertEquals(new StatsStore.Totals(110, 55, 900), afterFive);
        // The engine always adds the whole session to what it loaded, never to what it last wrote:
        // the tenth minute's save is the loaded totals plus ten minutes, not plus five twice.
        StatsStore.Totals afterTen = loaded.plus(new StatsStore.Totals(20, 10, 600));
        assertEquals(new StatsStore.Totals(120, 60, 1200), afterTen);
        assertEquals(StatsStore.Totals.ZERO.plus(StatsStore.Totals.ZERO), StatsStore.Totals.ZERO);
    }
}
