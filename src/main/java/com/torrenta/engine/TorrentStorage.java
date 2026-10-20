package com.torrenta.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * What the engine still has to do to a torrent's files: move them to their destination when it
 * completes, and keep the incomplete ones under a {@code .part} name until each is done.
 *
 * <p>libtorrent's resume data holds the save path and the renamed files, but not where the files
 * are meant to go afterwards nor which names are ours to take back, so this is the {@code
 * <hash>.storage} sidecar beside the resume data, as {@link TorrentLabels} is the {@code .labels}
 * one. Written when it changes, read back at start, gone when there is nothing left to do.
 *
 * @param destination the folder to move to on completion, or empty for none
 * @param partSuffix whether incomplete files are to carry the suffix
 * @param suffixed the file indices that carry it now, so exactly those are renamed back
 */
public record TorrentStorage(String destination, boolean partSuffix, Set<Integer> suffixed) {

    public static final TorrentStorage NONE = new TorrentStorage("", false, Set.of());

    /** Transmission's choice; {@code .!qB} is qBittorrent's. One suffix, not a setting. */
    public static final String SUFFIX = ".part";

    private static final JsonFactory JSON = new JsonFactory();

    public TorrentStorage {
        destination = destination == null ? "" : destination.trim();
        suffixed = suffixed == null ? Set.of() : Set.copyOf(suffixed);
    }

    /** Nothing left to do: no move pending, no suffix wanted, no suffix on. */
    public boolean isEmpty() {
        return destination.isEmpty() && !partSuffix && suffixed.isEmpty();
    }

    public TorrentStorage withDestination(String path) {
        return new TorrentStorage(path, partSuffix, suffixed);
    }

    public TorrentStorage withSuffixed(Set<Integer> indices) {
        return new TorrentStorage(destination, partSuffix, indices);
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
                } else {
                    p.skipChildren();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("not a storage object: " + e.getMessage(), e);
        }
        return new TorrentStorage(destination, partSuffix, suffixed);
    }
}
