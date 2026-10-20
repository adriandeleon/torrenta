package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.WatchFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchFolderStoreTest {

    @Test
    void entriesRoundTripThroughTheFile(@TempDir Path dir) throws IOException {
        WatchFolderStore store = new WatchFolderStore(dir.resolve("cfg"));
        store.load();
        assertTrue(store.entries().isEmpty(), "no folder yet is simply empty");

        List<WatchFolder> list = List.of(
                new WatchFolder(
                        "/inbox", WatchFolder.AfterAdd.DELETE, WatchFolder.SaveTo.FOLDER, "/data", "Movies", true),
                new WatchFolder("/drop", WatchFolder.AfterAdd.RENAME, WatchFolder.SaveTo.WATCH_FOLDER, "", "", false));
        store.set(list);
        assertEquals(list, store.entries());

        WatchFolderStore again = new WatchFolderStore(dir.resolve("cfg"));
        again.load();
        assertEquals(list, again.entries(), "in the file's order, every field back");
        assertEquals(
                "[\n"
                        + "  {\"folder\":\"/inbox\",\"afterAdd\":\"delete\",\"saveTo\":\"folder\",\"savePath\":\"/data\","
                        + "\"category\":\"Movies\",\"startPaused\":true},\n"
                        + "  {\"folder\":\"/drop\",\"afterAdd\":\"rename\",\"saveTo\":\"watch_folder\",\"savePath\":\"\","
                        + "\"category\":\"\",\"startPaused\":false}\n"
                        + "]\n",
                Files.readString(dir.resolve("cfg").resolve(WatchFolderStore.FILE)));
        try (var files = Files.list(dir.resolve("cfg"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".part")), "written atomically");
        }

        again.set(List.of());
        assertEquals("[]\n", Files.readString(dir.resolve("cfg").resolve(WatchFolderStore.FILE)));
    }

    @Test
    void anUnreadableFileIsEmptyNotFatal(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(WatchFolderStore.FILE), "{ not a list");
        WatchFolderStore store = new WatchFolderStore(dir);
        store.load();
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void theFormatIsPureAndForgiving() throws IOException {
        assertEquals("[]\n", WatchFolderStore.format(List.of()));
        assertEquals(List.of(), WatchFolderStore.parse("[]"));
        assertEquals(
                List.of(new WatchFolder("/w", WatchFolder.AfterAdd.RENAME, WatchFolder.SaveTo.DEFAULT, "", "", false)),
                WatchFolderStore.parse("[{\"folder\":\"/w\",\"afterAdd\":\"burn\",\"saveTo\":\"moon\",\"x\":[1]},"
                        + "{\"folder\":\"\"},{\"nope\":1},7]"),
                "unknown choices fall back, unknown fields are skipped, entries without a folder are dropped");
        assertEquals(
                List.of(new WatchFolder("/w", WatchFolder.AfterAdd.DELETE, WatchFolder.SaveTo.DEFAULT, "", "", true)),
                WatchFolderStore.parse("[{\"startPaused\":true,\"afterAdd\":\"DELETE\",\"folder\":\"/w\"}]"),
                "order of fields free, case of a choice free");
        assertThrows(IOException.class, () -> WatchFolderStore.parse("{}"));
        assertThrows(IOException.class, () -> WatchFolderStore.parse("[{"));
    }
}
