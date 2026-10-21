package com.torrenta.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * What the engine still has to do to a torrent's files: move them to their destination when it
 * completes, keep the incomplete ones under a {@code .part} name until each is done, and keep
 * the names a person (or the content layout) gave them.
 *
 * <p>libtorrent's resume data holds the save path and the renamed files, but not where the files
 * are meant to go afterwards nor which names are ours to take back, so this is the {@code
 * <hash>.storage} sidecar beside the resume data, as {@link TorrentLabels} is the {@code .labels}
 * one. Written when it changes, read back at start, gone when there is nothing left to do.
 *
 * @param destination the folder to move to on completion, or empty for none
 * @param partSuffix whether incomplete files are to carry the suffix
 * @param suffixed the file indices that carry it now, so exactly those are renamed back
 * @param renamed each renamed file's path by index, relative to the save path, forward slashes,
 *     without the suffix. libtorrent 2.1 writes renamed files into the resume data relative to
 *     the root folder and reads them back relative to the save path, and {@code torrent_file()}
 *     never shows a rename, so this is where the names are known and put back from.
 */
public record TorrentStorage(
        String destination, boolean partSuffix, Set<Integer> suffixed, Map<Integer, String> renamed) {

    public static final TorrentStorage NONE = new TorrentStorage("", false, Set.of());

    /** Transmission's choice; {@code .!qB} is qBittorrent's. One suffix, not a setting. */
    public static final String SUFFIX = ".part";

    private static final JsonFactory JSON = new JsonFactory();

    public TorrentStorage {
        destination = destination == null ? "" : destination.trim();
        suffixed = suffixed == null ? Set.of() : Set.copyOf(suffixed);
        renamed = renamed == null ? Map.of() : Map.copyOf(renamed);
    }

    /** No file renamed. */
    public TorrentStorage(String destination, boolean partSuffix, Set<Integer> suffixed) {
        this(destination, partSuffix, suffixed, Map.of());
    }

    /** Nothing left to do: no move pending, no suffix wanted, no suffix on, no name to keep. */
    public boolean isEmpty() {
        return destination.isEmpty() && !partSuffix && suffixed.isEmpty() && renamed.isEmpty();
    }

    public TorrentStorage withDestination(String path) {
        return new TorrentStorage(path, partSuffix, suffixed, renamed);
    }

    public TorrentStorage withSuffixed(Set<Integer> indices) {
        return new TorrentStorage(destination, partSuffix, indices, renamed);
    }

    /**
     * With these files renamed ({@code changes}, by index); a file given its torrent's own name
     * back ({@code originals}) is no longer a rename.
     */
    public TorrentStorage withRenamed(Map<Integer, String> changes, Map<Integer, String> originals) {
        Map<Integer, String> next = new TreeMap<>(renamed);
        changes.forEach((index, path) -> {
            if (path.equals(originals.get(index))) next.remove(index);
            else next.put(index, path);
        });
        return new TorrentStorage(destination, partSuffix, suffixed, next);
    }

    /** A file's name now, without the suffix: the one it was given, else its own. */
    public String name(int index, String original) {
        return renamed.getOrDefault(index, original);
    }

    /** A file's name on disk now: {@link #name} with the suffix while it carries one. */
    public String onDisk(int index, String original) {
        String name = name(index, original);
        return suffixed.contains(index) ? suffixed(name) : name;
    }

    /** The name a file carries while incomplete. */
    public static String suffixed(String path) {
        return path + SUFFIX;
    }

    /** The name without the suffix; a name that does not carry it comes back as it is. */
    public static String unsuffixed(String path) {
        return path.endsWith(SUFFIX) ? path.substring(0, path.length() - SUFFIX.length()) : path;
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("destination", destination);
            g.writeBooleanField("partSuffix", partSuffix);
            g.writeArrayFieldStart("suffixed");
            for (int index : new TreeSet<>(suffixed)) g.writeNumber(index);
            g.writeEndArray();
            if (!renamed.isEmpty()) {
                g.writeObjectFieldStart("renamed");
                for (Map.Entry<Integer, String> e : new TreeMap<>(renamed).entrySet())
                    g.writeStringField(Integer.toString(e.getKey()), e.getValue());
                g.writeEndObject();
            }
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** The inverse of {@link #encode()}; fields it does not know are skipped. */
    public static TorrentStorage decode(byte[] bytes) throws IOException {
        String destination = "";
        boolean partSuffix = false;
        Set<Integer> suffixed = new TreeSet<>();
        Map<Integer, String> renamed = new TreeMap<>();
        try (JsonParser p = JSON.createParser(new String(bytes, StandardCharsets.UTF_8))) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a storage object");
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("destination") && value == JsonToken.VALUE_STRING) {
                    destination = p.getText();
                } else if (field.equals("partSuffix") && value.isBoolean()) {
                    partSuffix = p.getBooleanValue();
                } else if (field.equals("suffixed") && value == JsonToken.START_ARRAY) {
                    JsonToken entry;
                    while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                        if (entry == null) throw new IOException("the indices are cut short");
                        if (entry == JsonToken.VALUE_NUMBER_INT) suffixed.add(p.getIntValue());
                        else p.skipChildren();
                    }
                } else if (field.equals("renamed") && value == JsonToken.START_OBJECT) {
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String index = p.currentName();
                        JsonToken path = p.nextToken();
                        if (path == JsonToken.VALUE_STRING && index.matches("\\d{1,9}"))
                            renamed.put(Integer.parseInt(index), p.getText());
                        else p.skipChildren();
                    }
                } else {
                    p.skipChildren();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("not a storage object: " + e.getMessage(), e);
        }
        return new TorrentStorage(destination, partSuffix, suffixed, renamed);
    }
}
