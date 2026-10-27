package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.torrenta.search.TorznabEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchProviderStoreTest {

    @Test
    void entriesRoundTripThroughTheFile(@TempDir Path dir) throws IOException {
        SearchProviderStore store = new SearchProviderStore(dir.resolve("cfg"));
        store.load();
        assertTrue(store.entries().isEmpty(), "no file yet is simply empty");

        List<TorznabEndpoint> list = List.of(
                new TorznabEndpoint("Prowlarr", "http://localhost:9696/1/api", "s3cret", true, 22),
                new TorznabEndpoint("", "http://nas:9117/api/v2.0/indexers/all/results/torznab/api", "", false, -1));
        store.set(list);
        assertEquals(list, store.entries());
        assertEquals(List.of(list.get(0)), store.enabled());

        SearchProviderStore again = new SearchProviderStore(dir.resolve("cfg"));
        again.load();
        assertEquals(list, again.entries(), "in the file's order, every field back");
        assertEquals(
                "[\n"
                        + "  {\"type\":\"torznab\",\"name\":\"Prowlarr\",\"url\":\"http://localhost:9696/1/api\","
                        + "\"apiKey\":\"s3cret\",\"enabled\":true,\"categories\":22},\n"
                        + "  {\"type\":\"torznab\",\"name\":\"nas\","
                        + "\"url\":\"http://nas:9117/api/v2.0/indexers/all/results/torznab/api\","
                        + "\"apiKey\":\"\",\"enabled\":false,\"categories\":-1}\n"
                        + "]\n",
                Files.readString(dir.resolve("cfg").resolve(SearchProviderStore.FILE)));
        try (var files = Files.list(dir.resolve("cfg"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".part")), "written atomically");
        }

        again.set(List.of());
        assertEquals("[]\n", Files.readString(dir.resolve("cfg").resolve(SearchProviderStore.FILE)));
    }

    @Test
    void anEntryWaitingForItsKeySaysSoInTheFile(@TempDir Path dir) throws IOException {
        SearchProviderStore store = new SearchProviderStore(dir);
        TorznabEndpoint waiting = new TorznabEndpoint("P", "http://nas:9696/1/api", "", true, -1, true);
        store.set(List.of(waiting));
        assertTrue(Files.readString(dir.resolve(SearchProviderStore.FILE)).contains("\"keyNeeded\":true"));
        SearchProviderStore again = new SearchProviderStore(dir);
        again.load();
        assertEquals(List.of(waiting), again.entries());
    }

    @Test
    void anUnreadableFileIsEmptyNotFatal(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(SearchProviderStore.FILE), "{ not a list");
        SearchProviderStore store = new SearchProviderStore(dir);
        store.load();
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void theFormatIsPureAndForgiving() throws IOException {
        assertEquals("[]\n", SearchProviderStore.format(List.of()));
        assertEquals(List.of(), SearchProviderStore.parse("[]"));
        List<TorznabEndpoint> parsed = SearchProviderStore.parse("[\n"
                + " {\"type\":\"torznab\",\"url\":\"http://h/api\",\"extra\":{\"deep\":[1,2]},\"categories\":\"many\"},\n"
                + " {\"type\":\"definition\",\"url\":\"http://other/api\"},\n"
                + " {\"type\":\"torznab\",\"name\":\"no url\"},\n"
                + " 42\n"
                + "]");
        assertEquals(
                List.of(new TorznabEndpoint("h", "http://h/api", "", true, -1)),
                parsed,
                "unknown fields skipped, another type skipped, no URL skipped, a stray value skipped");
        assertThrows(IOException.class, () -> SearchProviderStore.parse("{}"));
        assertThrows(IOException.class, () -> SearchProviderStore.parse("[{\"url\":\"x\""));
        assertThrows(IOException.class, () -> SearchProviderStore.parse(""));
    }
}
