package com.torrenta.store;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.Category;
import com.torrenta.engine.TorrentLabels;

/**
 * The categories and tags that exist, whether or not a torrent carries them: {@code
 * categories.json} and {@code tags.json} in the config folder.
 *
 * <p>Which torrent carries which is the engine's business and lives beside the resume data; this
 * is the list the dialogs and the sidebar offer. Both files are small JSON documents a person can
 * edit, written whole and atomically on every change, as the settings file is. Kept in memory
 * after {@link #load()}; an unreadable file is logged and treated as empty, never thrown.
 * Synchronized: the dialogs write on the FX thread while the window reads from its poll.
 */
public final class LabelStore {

    private static final Logger LOG = Logger.getLogger(LabelStore.class.getName());
    private static final JsonFactory JSON = new JsonFactory();

    public static final String CATEGORIES_FILE = "categories.json";
    public static final String TAGS_FILE = "tags.json";

    private final Path dir;
    private final TreeMap<String, Category> categories = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final TreeSet<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public LabelStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Reads both files; missing is empty, unreadable is empty and a log line. */
    public synchronized void load() {
        categories.clear();
        tags.clear();
        Path c = dir.resolve(CATEGORIES_FILE);
        if (Files.isRegularFile(c)) {
            try {
                for (Category category : parseCategories(Files.readString(c)))
                    categories.put(category.name(), category);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read " + c + "; no categories", e);
                categories.clear();
            }
        }
        Path t = dir.resolve(TAGS_FILE);
        if (Files.isRegularFile(t)) {
            try {
                tags.addAll(parseTags(Files.readString(t)));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read " + t + "; no tags", e);
                tags.clear();
            }
        }
    }

    /** Every category, by name ignoring case. */
    public synchronized List<Category> categories() {
        return List.copyOf(categories.values());
    }

    public synchronized Optional<Category> category(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(categories.get(name.trim()));
    }

    /** Every tag, sorted ignoring case. */
    public synchronized List<String> tags() {
        return List.copyOf(tags);
    }

    /** Adds the category, or replaces the one of that name (its save path, say). Written at once. */
    public synchronized void putCategory(Category category) throws IOException {
        categories.remove(category.name());
        categories.put(category.name(), category);
        writeCategories();
    }

    public synchronized void removeCategory(String name) throws IOException {
        if (categories.remove(name == null ? "" : name.trim()) != null) writeCategories();
    }

    /** Adds the ones not there yet, normalised as a torrent's tags are; written if any was new. */
    public synchronized void addTags(Collection<String> names) throws IOException {
        if (tags.addAll(TorrentLabels.normalizeTags(names))) writeTags();
    }

    public synchronized void removeTag(String name) throws IOException {
        if (tags.remove(name == null ? "" : name.trim())) writeTags();
    }

    private void writeCategories() throws IOException {
        write(dir.resolve(CATEGORIES_FILE), formatCategories(categories.values()));
    }

    private void writeTags() throws IOException {
        write(dir.resolve(TAGS_FILE), formatTags(tags));
    }

    /** Written beside the file and moved into place, so a crash mid-write never truncates it. */
    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
        try {
            Files.writeString(tmp, text);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- the two formats, pure ------------------------------------------------------------------

    /** {@code [{"name":"…","savePath":"…"}, …]}, one entry per line. */
    public static String formatCategories(Collection<Category> categories) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartArray();
            for (Category c : categories) {
                g.writeRaw("\n  ");
                g.writeStartObject();
                g.writeStringField("name", c.name());
                g.writeStringField("savePath", c.savePath());
                g.writeEndObject();
            }
            if (!categories.isEmpty()) g.writeRaw("\n");
            g.writeEndArray();
            g.writeRaw("\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** The inverse; an entry without a usable name is skipped, anything else unreadable throws. */
    public static List<Category> parseCategories(String text) throws IOException {
        List<Category> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of categories");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                String name = "";
                String savePath = "";
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken value = p.nextToken();
                    if (value == JsonToken.VALUE_STRING && field.equals("name")) name = p.getText();
                    else if (value == JsonToken.VALUE_STRING && field.equals("savePath")) savePath = p.getText();
                    else p.skipChildren();
                }
                if (!name.isBlank()) out.add(new Category(name, savePath));
            }
        } catch (RuntimeException e) {
            throw new IOException("not a list of categories: " + e.getMessage(), e);
        }
        return out;
    }

    /** {@code ["…", …]}. */
    public static String formatTags(Collection<String> tags) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartArray();
            for (String tag : tags) g.writeString(tag);
            g.writeEndArray();
            g.writeRaw("\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** The inverse, normalised; anything that is not a list of strings throws. */
    public static List<String> parseTags(String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of tags");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry == JsonToken.VALUE_STRING) out.add(p.getText());
                else p.skipChildren();
            }
        } catch (RuntimeException e) {
            throw new IOException("not a list of tags: " + e.getMessage(), e);
        }
        return TorrentLabels.normalizeTags(out);
    }
}
