package com.torrenta.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * What a torrent is filed under: one category or none, and any number of tags; and the name it is
 * shown under when a person renamed it, which is Torrenta's alone (the files keep theirs).
 *
 * <p>Normalised on construction so two spellings of the same thing cannot coexist: names are
 * trimmed, tags are trimmed, split on commas (a tag never holds one; the dialogs and the Tags column
 * join with them), deduplicated and sorted without regard to case. Encoded as a small JSON document
 * for the {@code <hash>.labels} sidecar beside the torrent's resume data, which libtorrent's own
 * format has no room for.
 *
 * @param category the category's name, or empty for none
 * @param tags sorted, distinct, never blank
 * @param name the name it is shown under, or empty for the torrent's own
 */
public record TorrentLabels(String category, List<String> tags, String name) {

    public static final TorrentLabels NONE = new TorrentLabels("", List.of(), "");

    private static final JsonFactory JSON = new JsonFactory();

    public TorrentLabels {
        category = normalizeName(category);
        tags = normalizeTags(tags);
        name = normalizeName(name);
    }

    /** Filed under a category and tags, under the torrent's own name. */
    public TorrentLabels(String category, List<String> tags) {
        this(category, tags, "");
    }

    public boolean isEmpty() {
        return category.isEmpty() && tags.isEmpty() && name.isEmpty();
    }

    public TorrentLabels withCategory(String category) {
        return new TorrentLabels(category, tags, name);
    }

    public TorrentLabels withTags(Collection<String> names) {
        return new TorrentLabels(category, names == null ? List.of() : List.copyOf(names), name);
    }

    /** Shown under {@code name}; empty goes back to the torrent's own. */
    public TorrentLabels withName(String name) {
        return new TorrentLabels(category, tags, name);
    }

    /** Trimmed; null is none. */
    public static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    /** Split on commas, trimmed, blanks dropped, distinct, sorted ignoring case. */
    public static List<String> normalizeTags(Collection<String> names) {
        if (names == null) return List.of();
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            if (name == null) continue;
            for (String part : name.split(",")) {
                String tag = part.trim();
                if (!tag.isEmpty()) out.add(tag);
            }
        }
        return List.copyOf(out);
    }

    /** {@code {"category":"…","tags":["…"]}}, with {@code "name":"…"} when it was renamed. */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("category", category);
            g.writeArrayFieldStart("tags");
            for (String tag : tags) g.writeString(tag);
            g.writeEndArray();
            if (!name.isEmpty()) g.writeStringField("name", name);
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e); // a byte array does not fail
        }
        return out.toByteArray();
    }

    /** The inverse of {@link #encode()}; fields it does not know are skipped. */
    public static TorrentLabels decode(byte[] bytes) throws IOException {
        String category = "";
        String name = "";
        List<String> tags = new ArrayList<>();
        try (JsonParser p = JSON.createParser(new String(bytes, StandardCharsets.UTF_8))) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a labels object");
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("category") && value == JsonToken.VALUE_STRING) {
                    category = p.getText();
                } else if (field.equals("name") && value == JsonToken.VALUE_STRING) {
                    name = p.getText();
                } else if (field.equals("tags") && value == JsonToken.START_ARRAY) {
                    JsonToken entry;
                    while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                        if (entry == null) throw new IOException("the tags are cut short");
                        if (entry == JsonToken.VALUE_STRING) tags.add(p.getText());
                        else p.skipChildren();
                    }
                } else {
                    p.skipChildren();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("not a labels object: " + e.getMessage(), e);
        }
        return new TorrentLabels(category, tags, name);
    }
}
