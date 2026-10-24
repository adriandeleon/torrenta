package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelStoreTest {

    @Test
    void categoriesRoundTripThroughTheFile(@TempDir Path dir) throws IOException {
        LabelStore store = new LabelStore(dir.resolve("cfg"));
        store.load();
        assertTrue(store.categories().isEmpty(), "no folder yet is simply empty");

        store.putCategory(new Category("Movies", "/data/movies"));
        store.putCategory(new Category("linux"));
        store.putCategory(new Category("Movies", "/elsewhere"));
        assertEquals(
                List.of(new Category("linux"), new Category("Movies", "/elsewhere")),
                store.categories(),
                "sorted ignoring case, a second put replaces the path");
        assertEquals("/elsewhere", store.category(" Movies ").orElseThrow().savePath());
        assertTrue(store.category("none").isEmpty());

        LabelStore again = new LabelStore(dir.resolve("cfg"));
        again.load();
        assertEquals(store.categories(), again.categories());

        again.removeCategory("linux");
        again.removeCategory("never there");
        assertEquals(List.of(new Category("Movies", "/elsewhere")), again.categories());
        assertEquals(
                "[\n  {\"name\":\"Movies\",\"savePath\":\"/elsewhere\"}\n]\n",
                Files.readString(dir.resolve("cfg").resolve(LabelStore.CATEGORIES_FILE)));
        try (var files = Files.list(dir.resolve("cfg"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".part")), "written atomically");
        }
    }

    @Test
    void everyWriteIsHeardAndANoOpIsNot(@TempDir Path dir) throws IOException {
        LabelStore store = new LabelStore(dir);
        store.load();
        int[] heard = {0};
        store.setOnChanged(() -> heard[0]++);
        store.putCategory(new Category("Movies"));
        store.addTags(List.of("hd"));
        store.addTags(List.of("HD"));
        store.removeTag("never there");
        store.removeCategory("never there");
        assertEquals(2, heard[0], "only the two writes");
        store.removeTag("hd");
        store.removeCategory("movies");
        assertEquals(4, heard[0]);
    }

    @Test
    void tagsRoundTripNormalised(@TempDir Path dir) throws IOException {
        LabelStore store = new LabelStore(dir);
        store.load();
        store.addTags(List.of("hd, 4k", " HD "));
        assertEquals(List.of("4k", "hd"), store.tags(), "split, trimmed, one spelling");
        store.addTags(List.of("hd"));
        store.removeTag("4k");
        LabelStore again = new LabelStore(dir);
        again.load();
        assertEquals(List.of("hd"), again.tags());
        assertEquals("[\"hd\"]\n", Files.readString(dir.resolve(LabelStore.TAGS_FILE)));
    }

    @Test
    void anUnreadableFileIsEmptyNotFatal(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(LabelStore.CATEGORIES_FILE), "{ not a list");
        Files.writeString(dir.resolve(LabelStore.TAGS_FILE), "[\"ok\"");
        LabelStore store = new LabelStore(dir);
        store.load();
        assertTrue(store.categories().isEmpty());
        assertTrue(store.tags().isEmpty());
    }

    @Test
    void theFormatsArePure() throws IOException {
        assertEquals("[]\n", LabelStore.formatCategories(List.of()));
        assertEquals(List.of(), LabelStore.parseCategories("[]"));
        assertEquals(
                List.of(new Category("a", "/p")),
                LabelStore.parseCategories(
                        "[{\"savePath\":\"/p\",\"name\":\"a\",\"x\":[1,2]},{\"name\":\"\"},{\"nope\":1}]"),
                "order of fields free, unknown fields skipped, nameless entries dropped");
        assertThrows(IOException.class, () -> LabelStore.parseCategories("{}"));
        assertThrows(IOException.class, () -> LabelStore.parseCategories("[1"));
        assertEquals(List.of("a", "b"), LabelStore.parseTags("[\"b\", \"a\", 7, [\"x\"]]"));
        assertThrows(IOException.class, () -> LabelStore.parseTags("\"a\""));
        assertEquals("[\"a\",\"b\"]\n", LabelStore.formatTags(List.of("a", "b")));
    }
}
