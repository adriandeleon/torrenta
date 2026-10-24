package com.torrenta.engine.direct;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.TorrentLabels;

/**
 * One download from a link, as it is kept between runs: what to fetch, where it goes, how far it
 * got. What is happening right now (the process, the rate) is {@link DirectDownloads}'s.
 *
 * @param id {@code url-} and the link's SHA-1, so it never looks like an info-hash and adding a
 *     link twice is the same download
 * @param file the file's name in {@code savePath}; while incomplete it is there with {@link
 *     #PART} after it
 * @param ownName the name a person gave it in the list, or empty; the file keeps its own
 * @param added epoch seconds
 * @param completed epoch seconds, 0 until it has finished
 * @param size the whole file in bytes, -1 until the server says
 * @param paused stopped by a person
 * @param error why it stopped by itself, or empty
 * @param tags sorted, as {@link TorrentLabels} keeps them
 * @param rateLimit bytes per second, 0 for none
 */
public record DirectDownload(
        String id,
        String url,
        String file,
        String ownName,
        String savePath,
        long added,
        long completed,
        long size,
        boolean paused,
        String error,
        String category,
        List<String> tags,
        long rateLimit) {

    /** The suffix an incomplete file carries, as the engine's own {@code .part} files do. */
    public static final String PART = ".part";

    static final String PREFIX = "url-";

    private static final JsonFactory JSON = new JsonFactory();

    public DirectDownload {
        ownName = ownName == null ? "" : ownName.strip();
        error = error == null ? "" : error;
        category = category == null ? "" : category;
        tags = tags == null ? List.of() : List.copyOf(tags);
        rateLimit = Math.max(0, rateLimit);
    }

    /** A new download of {@code url}, not started. */
    public static DirectDownload of(String url, String file, String savePath, long now) {
        return new DirectDownload(idOf(url), url, file, "", savePath, now, 0, -1, false, "", "", List.of(), 0);
    }

    public static String idOf(String url) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(url.strip().getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether an id in the list is a download from a link rather than a torrent. */
    public static boolean isId(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    public String name() {
        return ownName.isEmpty() ? file : ownName;
    }

    public boolean finished() {
        return completed > 0;
    }

    /** The file's name on disk now: with {@link #PART} until it has finished. */
    public String onDisk() {
        return finished() ? file : file + PART;
    }

    public DirectDownload withFile(String f) {
        return new DirectDownload(
                id, url, f, ownName, savePath, added, completed, size, paused, error, category, tags, rateLimit);
    }

    public DirectDownload withOwnName(String n) {
        return new DirectDownload(
                id, url, file, n, savePath, added, completed, size, paused, error, category, tags, rateLimit);
    }

    public DirectDownload withSavePath(String p) {
        return new DirectDownload(
                id, url, file, ownName, p, added, completed, size, paused, error, category, tags, rateLimit);
    }

    public DirectDownload withCompleted(long at, long finalSize) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, at, finalSize, paused, "", category, tags, rateLimit);
    }

    public DirectDownload withSize(long s) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, completed, s, paused, error, category, tags, rateLimit);
    }

    public DirectDownload withPaused(boolean p) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, completed, size, p, p ? "" : error, category, tags, rateLimit);
    }

    public DirectDownload withError(String e) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, completed, size, paused, e, category, tags, rateLimit);
    }

    public DirectDownload withCategory(String c) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, completed, size, paused, error, c, tags, rateLimit);
    }

    public DirectDownload withTags(List<String> t) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, completed, size, paused, error, category, t, rateLimit);
    }

    public DirectDownload withRateLimit(long limit) {
        return new DirectDownload(
                id, url, file, ownName, savePath, added, completed, size, paused, error, category, tags, limit);
    }

    // ---- the file, pure ---------------------------------------------------------------------------

    /** {@code downloads.json}: a list, one object per line, as the watch folders' file is. */
    public static String format(Collection<DirectDownload> downloads) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (DirectDownload d : downloads)
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(formatOne(d));
        if (!downloads.isEmpty()) sb.append('\n');
        return sb.append("]\n").toString();
    }

    private static String formatOne(DirectDownload d) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("url", d.url);
            g.writeStringField("file", d.file);
            if (!d.ownName.isEmpty()) g.writeStringField("name", d.ownName);
            g.writeStringField("savePath", d.savePath);
            g.writeNumberField("added", d.added);
            if (d.completed > 0) g.writeNumberField("completed", d.completed);
            g.writeNumberField("size", d.size);
            if (d.paused) g.writeBooleanField("paused", true);
            if (!d.error.isEmpty()) g.writeStringField("error", d.error);
            if (!d.category.isEmpty()) g.writeStringField("category", d.category);
            if (!d.tags.isEmpty()) {
                g.writeArrayFieldStart("tags");
                for (String t : d.tags) g.writeString(t);
                g.writeEndArray();
            }
            if (d.rateLimit > 0) g.writeNumberField("rateLimit", d.rateLimit);
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** The inverse. An entry with no link or no file is skipped; the id is the link's again. */
    public static List<DirectDownload> parse(String text) throws IOException {
        List<DirectDownload> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of downloads");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                String url = "", file = "", name = "", savePath = "", error = "", category = "";
                long added = 0, completed = 0, size = -1, rateLimit = 0;
                boolean paused = false;
                List<String> tags = new ArrayList<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken value = p.nextToken();
                    switch (field) {
                        case "url" -> url = p.getValueAsString("");
                        case "file" -> file = p.getValueAsString("");
                        case "name" -> name = p.getValueAsString("");
                        case "savePath" -> savePath = p.getValueAsString("");
                        case "error" -> error = p.getValueAsString("");
                        case "category" -> category = p.getValueAsString("");
                        case "added" -> added = p.getValueAsLong(0);
                        case "completed" -> completed = p.getValueAsLong(0);
                        case "size" -> size = p.getValueAsLong(-1);
                        case "rateLimit" -> rateLimit = p.getValueAsLong(0);
                        case "paused" -> paused = p.getValueAsBoolean(false);
                        case "tags" -> {
                            if (value == JsonToken.START_ARRAY) {
                                while (p.nextToken() != JsonToken.END_ARRAY) {
                                    if (p.currentToken() == JsonToken.VALUE_STRING) tags.add(p.getText());
                                    else p.skipChildren();
                                }
                            }
                        }
                        default -> p.skipChildren();
                    }
                }
                if (url.isBlank() || file.isBlank()) continue;
                out.add(new DirectDownload(
                        idOf(url),
                        url,
                        DirectNames.safe(file),
                        name,
                        savePath,
                        added,
                        completed,
                        size,
                        paused,
                        error,
                        category,
                        TorrentLabels.normalizeTags(tags),
                        rateLimit));
            }
        }
        return out;
    }
}
