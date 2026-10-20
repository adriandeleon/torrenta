package com.torrenta.store;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.WatchFolder;

/**
 * The watch folders: {@code watch-folders.json} in the config folder, one entry per folder.
 *
 * <p>A small JSON document a person can edit, written whole and atomically on every change, as
 * the label store's files are. Not in the settings file, and never synced: a folder is this
 * machine's. Kept in memory after {@link #load()}; an unreadable file is logged and treated as
 * empty, never thrown. Synchronized: the Settings window writes on the FX thread while the
 * watcher reads from its own.
 */
public final class WatchFolderStore {

    private static final Logger LOG = Logger.getLogger(WatchFolderStore.class.getName());
    private static final JsonFactory JSON = new JsonFactory();

    public static final String FILE = "watch-folders.json";

    private final Path dir;
    private final List<WatchFolder> entries = new ArrayList<>();

    public WatchFolderStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Reads the file; missing is empty, unreadable is empty and a log line. */
    public synchronized void load() {
        entries.clear();
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return;
        try {
            entries.addAll(parse(Files.readString(file)));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read " + file + "; no watch folders", e);
            entries.clear();
        }
    }

    /** Every entry, in the file's order. */
    public synchronized List<WatchFolder> entries() {
        return List.copyOf(entries);
    }

    /** Replaces the list and writes it at once. */
    public synchronized void set(List<WatchFolder> list) throws IOException {
        entries.clear();
        entries.addAll(list);
        write(dir.resolve(FILE), format(entries));
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

    // ---- the format, pure -----------------------------------------------------------------------

    /**
     * {@code [{"folder":"…","afterAdd":"rename","saveTo":"default","savePath":"","category":"",
     * "startPaused":false}, …]}, one entry per line.
     */
    public static String format(Collection<WatchFolder> entries) {
        // One object per line, the comma at the line's end: each is generated on its own, since
        // Jackson puts its comma after anything written raw before the next value.
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (WatchFolder w : entries) {
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(formatEntry(w));
        }
        if (!entries.isEmpty()) sb.append('\n');
        return sb.append("]\n").toString();
    }

    private static String formatEntry(WatchFolder w) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("folder", w.folder());
            g.writeStringField("afterAdd", w.afterAdd().name().toLowerCase(Locale.ROOT));
            g.writeStringField("saveTo", w.saveTo().name().toLowerCase(Locale.ROOT));
            g.writeStringField("savePath", w.savePath());
            g.writeStringField("category", w.category());
            g.writeBooleanField("startPaused", w.startPaused());
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /**
     * The inverse; an entry without a folder is skipped, an unknown choice falls back to the
     * default, anything else unreadable throws.
     */
    public static List<WatchFolder> parse(String text) throws IOException {
        List<WatchFolder> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of watch folders");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                String folder = "";
                WatchFolder.AfterAdd afterAdd = WatchFolder.AfterAdd.RENAME;
                WatchFolder.SaveTo saveTo = WatchFolder.SaveTo.DEFAULT;
                String savePath = "";
                String category = "";
                boolean startPaused = false;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken value = p.nextToken();
                    if (value == JsonToken.VALUE_STRING) {
                        switch (field) {
                            case "folder" -> folder = p.getText();
                            case "afterAdd" -> afterAdd = choice(WatchFolder.AfterAdd.class, p.getText(), afterAdd);
                            case "saveTo" -> saveTo = choice(WatchFolder.SaveTo.class, p.getText(), saveTo);
                            case "savePath" -> savePath = p.getText();
                            case "category" -> category = p.getText();
                            default -> {}
                        }
                    } else if (field.equals("startPaused")
                            && (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE)) {
                        startPaused = p.getBooleanValue();
                    } else {
                        p.skipChildren();
                    }
                }
                if (!folder.isBlank())
                    out.add(new WatchFolder(folder, afterAdd, saveTo, savePath, category, startPaused));
            }
        } catch (RuntimeException e) {
            throw new IOException("not a list of watch folders: " + e.getMessage(), e);
        }
        return out;
    }

    private static <E extends Enum<E>> E choice(Class<E> type, String text, E fallback) {
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
