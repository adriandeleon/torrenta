package com.torrenta.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.torrenta.search.Definition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionStoreTest {

    private static final String GOOD = """
            { "id": "ID", "name": "NAME", "type": "json", "search": { "url": "https://x.test/?q={query}" },
              "fields": { "rows": "/items", "name": "/n", "magnet": "/m" } }
            """;

    private static String good(String id, String name) {
        return GOOD.replace("ID", id).replace("NAME", name);
    }

    @Test
    void everyJsonFileIsAnEntryLoadedOrNot(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir);
        store.load();
        assertTrue(store.entries().isEmpty(), "no folder yet is simply empty");
        assertEquals(dir.resolve(DefinitionStore.FOLDER), store.dir());

        Path folder = Files.createDirectories(store.dir());
        Files.writeString(folder.resolve("b-good.json"), good("b-good", "Bee"));
        Files.writeString(folder.resolve("a-good.json"), good("a-good", "Ay"));
        Files.writeString(folder.resolve("broken.json"), "{ \"id\": \"broken\" }");
        Files.writeString(folder.resolve("wrong-id.json"), good("other", "Other"));
        Files.writeString(folder.resolve("notes.txt"), "not a definition");
        Files.writeString(folder.resolve("a-good.json.example"), good("a-good", "Shipped"));
        store.load();
        List<DefinitionStore.Entry> entries = store.entries();
        assertEquals(
                List.of("a-good.json", "b-good.json", "broken.json", "wrong-id.json"),
                entries.stream().map(e -> e.file().getFileName().toString()).toList(),
                "name order, .json only");
        assertEquals(
                List.of("Ay", "Bee"),
                store.definitions().stream().map(Definition::name).toList());
        assertEquals(
                List.of("broken.json", "wrong-id.json"),
                store.problems().stream()
                        .map(e -> e.file().getFileName().toString())
                        .toList());
        DefinitionStore.Entry broken = entries.get(2);
        assertFalse(broken.loaded());
        assertEquals("broken", broken.id());
        assertEquals("broken", broken.name(), "a file that did not load is called by its stem");
        assertEquals("broken.json: name: is missing", broken.problem());
        assertEquals(
                "wrong-id.json: id: is \"other\" but the file is wrong-id.json",
                entries.get(3).problem());
        assertEquals("", entries.get(0).problem());
        assertEquals("a-good", entries.get(0).id());
    }

    @Test
    void refreshReReadsOnlyWhenAFileChanged(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir);
        Path folder = Files.createDirectories(store.dir());
        Path file = folder.resolve("one.json");
        Files.writeString(file, good("one", "One"));
        store.load();
        assertFalse(store.refresh(), "nothing changed");
        Files.writeString(file, good("one", "One renamed"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertTrue(store.refresh(), "the time moved");
        assertEquals("One renamed", store.definitions().get(0).name());
        Files.writeString(folder.resolve("two.json"), good("two", "Two"));
        assertTrue(store.refresh(), "a file arrived");
        assertEquals(2, store.definitions().size());
        Files.delete(file);
        assertTrue(store.refresh(), "a file went");
        assertEquals(
                List.of("Two"),
                store.definitions().stream().map(Definition::name).toList());
        assertFalse(store.refresh());
    }

    @Test
    void examplesAreCopiedOnceAndNeverWrittenOver(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir);
        List<String> names = DefinitionStore.exampleNames();
        assertTrue(names.contains("archive-org.json"), names.toString());
        List<Path> written = store.installExamples();
        assertEquals(names.size(), written.size(), "every example, into a folder made for it");
        for (String name : names) assertTrue(Files.isRegularFile(store.dir().resolve(name)), name);
        assertTrue(store.installExamples().isEmpty(), "a second run writes nothing");
        store.load();
        assertEquals(names.size(), store.definitions().size(), "every shipped example loads");
        assertTrue(store.problems().isEmpty());

        // The user edits one: it is kept, and the shipped text goes beside it as .example.
        Path mine = store.dir().resolve("archive-org.json");
        String edited = Files.readString(mine).replace("Internet Archive", "My Archive");
        Files.writeString(mine, edited);
        assertEquals(List.of(store.dir().resolve("archive-org.json.example")), store.installExamples());
        assertEquals(edited, Files.readString(mine), "never written over");
        assertEquals(
                DefinitionStore.example("archive-org.json"),
                Files.readString(store.dir().resolve("archive-org.json.example")));
        assertTrue(store.installExamples().isEmpty(), "the .example is up to date");
        store.load();
        assertEquals(names.size(), store.entries().size(), ".example files are not entries");

        // The user puts the shipped text back: the .example goes.
        Files.writeString(mine, DefinitionStore.example("archive-org.json"));
        assertTrue(store.installExamples().isEmpty());
        assertFalse(Files.exists(store.dir().resolve("archive-org.json.example")));

        // The user deletes one: it comes back on the next start, as the README says.
        Files.delete(mine);
        assertEquals(List.of(mine), store.installExamples());
    }

    @Test
    void syncReadsTheTextsAndPutsAndRemovesFiles(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir);
        store.installExamples();
        Map<String, String> shipped = DefinitionStore.shippedTexts();
        assertFalse(shipped.isEmpty(), "the examples are listed");
        assertEquals(shipped, store.texts(), "a fresh folder is the shipped examples");
        Files.writeString(store.dir().resolve("Not An Id.json"), "{}");

        store.put("mine", good("mine", "Mine"));
        assertEquals(good("mine", "Mine"), store.texts().get("mine"));
        assertFalse(store.texts().containsKey("Not An Id"), "only names a definition can have");

        String example = shipped.keySet().iterator().next();
        store.put(example, good(example, "Edited"));
        store.installExamples();
        assertTrue(Files.exists(store.dir().resolve(example + ".json.example")));
        store.remove(example);
        assertEquals(shipped.get(example), store.texts().get(example), "an example comes back as shipped");
        assertFalse(Files.exists(store.dir().resolve(example + ".json.example")), "and needs no copy beside it");

        store.remove("mine");
        assertFalse(Files.exists(store.dir().resolve("mine.json")));
    }

    @Test
    void theExportHoldsEveryJsonFileLoadedOrNotUnderTheFoldersName(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir.resolve("config"));
        Path folder = Files.createDirectories(store.dir());
        Files.writeString(folder.resolve("b-good.json"), good("b-good", "Bee"));
        Files.writeString(folder.resolve("a-good.json"), good("a-good", "Ay"));
        Files.writeString(folder.resolve("broken.json"), "{ \"id\": \"broken\" }");
        Files.writeString(folder.resolve("a-good.json.example"), "the shipped text");
        Files.writeString(folder.resolve("notes.txt"), "not a definition");
        Path target = dir.resolve("out").resolve("backup.zip");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "an older backup");

        assertEquals(3, store.exportZip(target), "the broken file is backed up too; the example and the notes are not");

        Map<String, String> unpacked = unzip(target);
        assertEquals(
                List.of("search-providers/a-good.json", "search-providers/b-good.json", "search-providers/broken.json"),
                new ArrayList<>(unpacked.keySet()));
        assertEquals(good("b-good", "Bee"), unpacked.get("search-providers/b-good.json"));
        assertEquals("{ \"id\": \"broken\" }", unpacked.get("search-providers/broken.json"));
        try (var left = Files.list(target.getParent())) {
            assertEquals(List.of(target), left.toList(), "written over in place, no part file left");
        }
    }

    @Test
    void anExportUnpackedInAnotherConfigFolderLoadsTheSameDefinitions(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir.resolve("here"));
        Path folder = Files.createDirectories(store.dir());
        Files.writeString(folder.resolve("a-good.json"), good("a-good", "Ay"));
        Files.writeString(folder.resolve("b-good.json"), good("b-good", "Bee"));
        Path zip = dir.resolve("backup.zip");
        store.exportZip(zip);

        Path there = dir.resolve("there");
        for (Map.Entry<String, String> e : unzip(zip).entrySet()) {
            Path file = there.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
        DefinitionStore restored = new DefinitionStore(there);
        restored.load();
        assertEquals(
                List.of("Ay", "Bee"),
                restored.definitions().stream().map(Definition::name).toList());
    }

    @Test
    void anEmptyFolderExportsAnEmptyZip(@TempDir Path dir) throws IOException {
        DefinitionStore store = new DefinitionStore(dir);
        Path zip = dir.resolve("empty.zip");
        assertEquals(0, store.exportZip(zip));
        assertTrue(unzip(zip).isEmpty());
    }

    @Test
    void theExportIsNamedForTheDay() {
        assertEquals(
                "torrenta-search-providers-2026-10-25.zip", DefinitionStore.exportName(LocalDate.of(2026, 10, 25)));
    }

    @Test
    void aChosenNameWithoutTheSuffixGetsIt() {
        assertEquals(Path.of("/b/defs.zip"), DefinitionStore.withZipSuffix(Path.of("/b/defs")));
        assertEquals(Path.of("/b/defs.ZIP"), DefinitionStore.withZipSuffix(Path.of("/b/defs.ZIP")));
        assertEquals(Path.of("/b/defs.v2.zip"), DefinitionStore.withZipSuffix(Path.of("/b/defs.v2")));
    }

    private static Map<String, String> unzip(Path zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (InputStream in = Files.newInputStream(zip);
                ZipInputStream entries = new ZipInputStream(in)) {
            for (ZipEntry e; (e = entries.getNextEntry()) != null; )
                out.put(e.getName(), new String(entries.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }
}
