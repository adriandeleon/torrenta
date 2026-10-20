package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentStoreTest {

    private static final String V1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String V2 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void saveLoadDelete(@TempDir Path dir) throws IOException {
        TorrentStore store = new TorrentStore(dir.resolve("t"));
        assertTrue(store.loadAll().isEmpty(), "no folder yet is simply empty");

        store.save(V1, new byte[] {1, 2, 3});
        store.save(V2, new byte[] {4});
        assertTrue(store.contains(V1));
        Map<String, byte[]> all = store.loadAll();
        assertEquals(2, all.size());
        assertArrayEquals(new byte[] {1, 2, 3}, all.get(V1));
        assertArrayEquals(new byte[] {4}, all.get(V2));

        store.save(V1, new byte[] {9});
        assertArrayEquals(new byte[] {9}, store.loadAll().get(V1), "overwritten in place");

        store.delete(V1);
        assertFalse(store.contains(V1));
        assertEquals(1, store.loadAll().size());
        store.delete(V1); // twice is fine
    }

    @Test
    void leavesNoTempFilesAndIgnoresStrangers(@TempDir Path dir) throws IOException {
        TorrentStore store = new TorrentStore(dir);
        store.save(V1, new byte[] {1});
        Files.writeString(dir.resolve("notes.txt"), "mine");
        Files.writeString(dir.resolve("zz" + TorrentStore.SUFFIX), "not a hash");
        Files.createDirectories(dir.resolve(V2 + TorrentStore.SUFFIX)); // a folder with our name
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".part")));
        }
        assertEquals(1, store.loadAll().size());
        assertTrue(Files.exists(dir.resolve("notes.txt")), "never deletes what is not ours");
    }

    @Test
    void anIdThatIsNotAHashNeverBecomesAPath(@TempDir Path dir) {
        TorrentStore store = new TorrentStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.save("../escape", new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("ABCDEF0123456789ABCDEF0123456789ABCDEF01", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> store.delete(""));
    }
}
