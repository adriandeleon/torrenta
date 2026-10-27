package com.torrenta.store;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.search.TorznabEndpoint;

/**
 * The search providers: {@code search-providers.json} in the config folder, one entry per
 * Torznab endpoint.
 *
 * <p>Written whole and atomically on every change, as the watch-folder store is. Not in the
 * settings file. Settings sync carries the entries but never their keys ({@code SyncSearch}): an
 * API key is a secret and this machine's. Each entry carries
 * a {@code type}, {@code torznab} today, so the declarative providers can share the file later;
 * an entry of a type this build does not know is skipped on read and lost on the next write.
 * Synchronized: the Settings window writes on the FX thread while a search reads from its own.
 */
public final class SearchProviderStore {

    private static final Logger LOG = Logger.getLogger(SearchProviderStore.class.getName());
    private static final JsonFactory JSON = new JsonFactory();

    public static final String FILE = "search-providers.json";
    static final String TYPE_TORZNAB = "torznab";

    private final Path dir;
    private final List<TorznabEndpoint> entries = new ArrayList<>();

    public SearchProviderStore(Path dir) {
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
            LOG.log(Level.WARNING, "Could not read " + file + "; no search providers", e);
            entries.clear();
        }
    }

    /** Every entry, in the file's order. */
    public synchronized List<TorznabEndpoint> entries() {
        return List.copyOf(entries);
    }

    /** The enabled entries, in the file's order: the ones a search asks. */
    public synchronized List<TorznabEndpoint> enabled() {
        return entries.stream().filter(TorznabEndpoint::enabled).toList();
    }

    /** Replaces the list and writes it at once. */
    public synchronized void set(List<TorznabEndpoint> list) throws IOException {
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
     * {@code [{"type":"torznab","name":"…","url":"…","apiKey":"…","enabled":true,"categories":-1},
     * …]}, one entry per line; {@code "keyNeeded":true} after the categories on an entry still
     * waiting for its key.
     */
    public static String format(Collection<TorznabEndpoint> entries) {
        // One object per line, the comma at the line's end: each is generated on its own, since
        // Jackson puts its comma after anything written raw before the next value.
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (TorznabEndpoint e : entries) {
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(formatEntry(e));
        }
        if (!entries.isEmpty()) sb.append('\n');
        return sb.append("]\n").toString();
    }

    private static String formatEntry(TorznabEndpoint e) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", TYPE_TORZNAB);
            g.writeStringField("name", e.name());
            g.writeStringField("url", e.url());
            g.writeStringField("apiKey", e.apiKey());
            g.writeBooleanField("enabled", e.enabled());
            g.writeNumberField("categories", e.categories());
            if (e.keyNeeded()) g.writeBooleanField("keyNeeded", true);
            g.writeEndObject();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toString();
    }

    /**
     * The inverse; an entry without a URL, or of another type, is skipped; anything unreadable
     * throws.
     */
    public static List<TorznabEndpoint> parse(String text) throws IOException {
        List<TorznabEndpoint> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of search providers");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                String type = TYPE_TORZNAB;
                String name = "";
                String url = "";
                String apiKey = "";
                boolean enabled = true;
                int categories = -1;
                boolean keyNeeded = false;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken value = p.nextToken();
                    if (value == JsonToken.VALUE_STRING) {
                        switch (field) {
                            case "type" -> type = p.getText();
                            case "name" -> name = p.getText();
                            case "url" -> url = p.getText();
                            case "apiKey" -> apiKey = p.getText();
                            default -> {}
                        }
                    } else if (field.equals("enabled")
                            && (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE)) {
                        enabled = p.getBooleanValue();
                    } else if (field.equals("keyNeeded")
                            && (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE)) {
                        keyNeeded = p.getBooleanValue();
                    } else if (field.equals("categories") && value == JsonToken.VALUE_NUMBER_INT) {
                        categories = p.getIntValue();
                    } else {
                        p.skipChildren();
                    }
                }
                if (type.equals(TYPE_TORZNAB) && !url.isBlank())
                    out.add(new TorznabEndpoint(name, url, apiKey, enabled, categories, keyNeeded));
            }
        } catch (RuntimeException e) {
            throw new IOException("not a list of search providers: " + e.getMessage(), e);
        }
        return out;
    }
}
