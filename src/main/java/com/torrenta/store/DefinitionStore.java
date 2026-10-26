package com.torrenta.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.torrenta.search.Definition;
import com.torrenta.search.DefinitionException;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * The declarative search providers: {@code search-providers/<id>.json} in the config folder, one
 * file per provider, written by hand (or dropped in) and only read here. Every {@code *.json}
 * in the folder is an {@link Entry}: its definition when it parsed, its problem when it did not,
 * so the Settings page can say which is which. A file whose {@code id} is not its own name is a
 * problem too, since the id is what the settings disable it by.
 *
 * <p>The shipped examples ({@code com/torrenta/search/examples/}) are copied in by
 * {@link #installExamples()} when absent; one the user has changed is never written over, the
 * shipped text going beside it as {@code <id>.json.example} instead. {@link #refresh()} re-reads
 * the folder when any file's size or time changed, so an edit is picked up by the next search
 * without a restart. Synchronized: the window reads on the FX thread, a search on its own.
 */
public final class DefinitionStore {

    public static final String FOLDER = "search-providers";
    public static final String SUFFIX = ".json";
    public static final String EXAMPLE_SUFFIX = ".example";
    static final String EXAMPLES = "/com/torrenta/search/examples/";

    /** One file in the folder: loaded (definition set) or not (problem set). */
    public record Entry(Path file, String id, @Nullable Definition definition, String problem) {
        public boolean loaded() {
            return definition != null;
        }

        /** The definition's name, or the file's stem while it has none. */
        public String name() {
            return definition != null ? definition.name() : id;
        }
    }

    private record Stamp(long size, FileTime modified) {}

    private final Path dir;
    private final List<Entry> entries = new ArrayList<>();
    private Map<Path, Stamp> stamps = Map.of();

    public DefinitionStore(Path configDir) {
        this.dir = configDir.resolve(FOLDER);
    }

    /** The folder the files live in; may not exist yet. */
    public Path dir() {
        return dir;
    }

    /** Reads every {@code *.json} in the folder, in name order; a missing folder is empty. */
    public synchronized void load() {
        entries.clear();
        Map<Path, Stamp> seen = new LinkedHashMap<>();
        for (Path file : files()) {
            seen.put(file, stamp(file));
            entries.add(read(file));
        }
        stamps = seen;
    }

    /** Re-reads the folder if any file was added, removed or changed since; says whether it did. */
    public synchronized boolean refresh() {
        Map<Path, Stamp> now = new LinkedHashMap<>();
        for (Path file : files()) now.put(file, stamp(file));
        if (now.equals(stamps)) return false;
        load();
        return true;
    }

    /** Every file, loaded or not, in name order. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The definitions that loaded, in name order. */
    public synchronized List<Definition> definitions() {
        List<Definition> out = new ArrayList<>();
        for (Entry e : entries) if (e.loaded()) out.add(e.definition());
        return out;
    }

    /** The entries that did not load. */
    public synchronized List<Entry> problems() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (!e.loaded()) out.add(e);
        return out;
    }

    /**
     * Copies each shipped example into the folder when no file of its name is there; when one is
     * and differs, writes the shipped text as {@code <name>.example} beside it (refreshed when the
     * shipped text changes, never when the user's file does). Returns what was written.
     */
    public synchronized List<Path> installExamples() throws IOException {
        List<Path> written = new ArrayList<>();
        for (String name : exampleNames()) {
            String shipped = example(name);
            if (shipped == null) continue;
            Path target = dir.resolve(name);
            if (!Files.exists(target)) {
                Files.createDirectories(dir);
                write(target, shipped);
                written.add(target);
                continue;
            }
            String present = readQuietly(target);
            if (shipped.equals(present)) {
                Files.deleteIfExists(dir.resolve(name + EXAMPLE_SUFFIX));
                continue;
            }
            Path example = dir.resolve(name + EXAMPLE_SUFFIX);
            if (!shipped.equals(readQuietly(example))) {
                write(example, shipped);
                written.add(example);
            }
        }
        return written;
    }

    /**
     * Each file's text by id, as it is on disk now: what settings sync sends. Only the names a
     * definition can have; a file that cannot be read is left out.
     */
    public synchronized Map<String, String> texts() {
        Map<String, String> out = new TreeMap<>();
        for (Path file : files()) {
            String name = file.getFileName().toString();
            if (!name.endsWith(SUFFIX)) continue;
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!Definition.ID.matcher(id).matches()) continue;
            String text = readQuietly(file);
            if (text != null) out.put(id, text);
        }
        return out;
    }

    /**
     * Every {@code *.json} in the folder, loaded or not, into a zip at {@code target} as
     * {@code search-providers/<name>}, so unpacking it in the config folder puts them back. Written
     * beside the target and moved into place; returns how many files went in.
     */
    public synchronized int exportZip(Path target) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (Path file : files()) files.put(file.getFileName().toString(), Files.readAllBytes(file));
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Nowhere to write " + target);
        write(parent.resolve(target.getFileName()), zip(files));
        return files.size();
    }

    /** The files by name as a zip, each under {@code search-providers/}, in the map's order. */
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(FOLDER + "/" + e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** The export's suggested file name: {@code torrenta-search-providers-2026-10-25.zip}. */
    public static String exportName(LocalDate day) {
        return "torrenta-" + FOLDER + "-" + day + ".zip";
    }

    /** The chosen path with {@code .zip} added when it has none: a chooser does not always add it. */
    public static Path withZipSuffix(Path chosen) {
        String name = chosen.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? chosen : chosen.resolveSibling(name + ".zip");
    }

    /** The shipped examples' texts by id. */
    public static Map<String, String> shippedTexts() {
        Map<String, String> out = new TreeMap<>();
        for (String name : exampleNames()) {
            String text = example(name);
            if (text != null && name.endsWith(SUFFIX))
                out.put(name.substring(0, name.length() - SUFFIX.length()), text);
        }
        return out;
    }

    /** Writes {@code <id>.json}, as sync brings it; the next {@link #load} reads it. */
    public synchronized void put(String id, String text) throws IOException {
        if (!Definition.ID.matcher(id).matches()) throw new IOException("not a definition id: " + id);
        write(dir.resolve(id + SUFFIX), text);
        if (text.equals(shippedTexts().get(id))) Files.deleteIfExists(dir.resolve(id + SUFFIX + EXAMPLE_SUFFIX));
    }

    /**
     * Removes {@code <id>.json}, as sync removes it; a shipped example gets its shipped text back
     * instead, since that is what an example left out of the sync means.
     */
    public synchronized void remove(String id) throws IOException {
        if (!Definition.ID.matcher(id).matches()) throw new IOException("not a definition id: " + id);
        String shipped = shippedTexts().get(id);
        if (shipped != null) put(id, shipped);
        else Files.deleteIfExists(dir.resolve(id + SUFFIX));
    }

    /** The shipped examples' file names, from the index beside them. */
    public static List<String> exampleNames() {
        String index = example("index.txt");
        if (index == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : index.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    /** A shipped example's text, or null when there is none of that name. */
    public static @Nullable String example(String name) {
        try (InputStream in = DefinitionStore.class.getResourceAsStream(EXAMPLES + name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- files ----------------------------------------------------------------------------------

    private List<Path> files() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX))
                    .sorted((a, b) ->
                            a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Stamp stamp(Path file) {
        try {
            return new Stamp(Files.size(file), Files.getLastModifiedTime(file));
        } catch (IOException e) {
            return new Stamp(-1, FileTime.fromMillis(0));
        }
    }

    /** One file into an entry; nothing here throws. */
    public static Entry read(Path file) {
        String fileName = file.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - SUFFIX.length());
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Entry(file, stem, null, fileName + ": " + tr("search.def.unreadable", e.getMessage()));
        }
        try {
            Definition definition = Definition.parse(text, fileName);
            if (!definition.id().equals(stem)) {
                return new Entry(
                        file, stem, null, fileName + ": id: " + tr("search.def.idMismatch", definition.id(), fileName));
            }
            return new Entry(file, stem, definition, "");
        } catch (DefinitionException e) {
            return new Entry(file, stem, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Entry(
                    file, stem, null, fileName + ": " + Objects.requireNonNullElse(e.getMessage(), e.toString()));
        }
    }

    private static @Nullable String readQuietly(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Written beside the file and moved into place, so a crash mid-write never truncates it. */
    private static void write(Path file, String text) throws IOException {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
