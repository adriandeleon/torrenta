package com.torrenta.rss;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * The reader's files under {@code rss/} in the config folder: {@code feeds.json}, the feed list,
 * and {@code articles/<feedId>.json}, one per feed, with the articles (read flags included), the
 * last answer's validators, the time of the last refresh and its error. Machine data, this
 * machine's, never synced: another machine has read other articles.
 *
 * <p>Written whole and atomically on every change, as the other stores are; a feed keeps at most
 * {@link #MAX_ARTICLES}, the oldest dropped. Synchronized: fetches write from their own threads
 * while the window reads on the FX thread.
 */
public final class RssStore {

    private static final Logger LOG = Logger.getLogger(RssStore.class.getName());
    private static final JsonFactory JSON = new JsonFactory();

    public static final String DIR = "rss";
    public static final String FEEDS_FILE = "feeds.json";
    public static final String ARTICLES_DIR = "articles";
    public static final int MAX_ARTICLES = 500;

    private final Path dir;
    private final List<Feed> feeds = new ArrayList<>();
    private final Map<String, FeedState> states = new LinkedHashMap<>();

    public RssStore(Path configDir) {
        this.dir = configDir.resolve(DIR);
    }

    public Path dir() {
        return dir;
    }

    /** Reads the feed list and every feed's articles; missing is empty, unreadable is empty and a log line. */
    public synchronized void load() {
        feeds.clear();
        states.clear();
        Path file = dir.resolve(FEEDS_FILE);
        if (Files.isRegularFile(file)) {
            try {
                feeds.addAll(parseFeeds(Files.readString(file)));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read " + file + "; no feeds", e);
                feeds.clear();
            }
        }
        for (Feed feed : feeds) {
            Path articles = articlesFile(feed.id());
            if (!Files.isRegularFile(articles)) continue;
            try {
                states.put(
                        feed.id(),
                        parseState(Files.readString(articles), feed.id()).capped(MAX_ARTICLES));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read " + articles + "; the feed starts empty", e);
            }
        }
    }

    /** Every feed, in the file's order. */
    public synchronized List<Feed> feeds() {
        return List.copyOf(feeds);
    }

    public synchronized Feed feed(String id) {
        for (Feed f : feeds) if (f.id().equals(id)) return f;
        return null;
    }

    /** Replaces the list and writes it at once; the articles of a feed no longer listed are deleted. */
    public synchronized void setFeeds(List<Feed> list) throws IOException {
        List<String> gone = new ArrayList<>();
        for (Feed f : feeds) {
            if (list.stream().noneMatch(n -> n.id().equals(f.id()))) gone.add(f.id());
        }
        feeds.clear();
        feeds.addAll(list);
        write(dir.resolve(FEEDS_FILE), formatFeeds(feeds));
        for (String id : gone) {
            states.remove(id);
            Files.deleteIfExists(articlesFile(id));
        }
    }

    /** The feed's articles and refresh facts; {@link FeedState#EMPTY} for a feed never fetched. */
    public synchronized FeedState state(String feedId) {
        return states.getOrDefault(feedId, FeedState.EMPTY);
    }

    /** Keeps and writes the state, capped to {@link #MAX_ARTICLES}; returns what was kept. */
    public synchronized FeedState setState(String feedId, FeedState state) throws IOException {
        FeedState kept = state.capped(MAX_ARTICLES);
        states.put(feedId, kept);
        write(articlesFile(feedId), formatState(kept));
        return kept;
    }

    private Path articlesFile(String feedId) {
        return dir.resolve(ARTICLES_DIR).resolve(feedId + ".json");
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

    // ---- the formats, pure ----------------------------------------------------------------------

    /** {@code [{"id":"…","url":"…","name":"…","refreshMinutes":0}, …]}, one feed per line. */
    public static String formatFeeds(Collection<Feed> list) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Feed f : list) {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(out)) {
                g.writeStartObject();
                g.writeStringField("id", f.id());
                g.writeStringField("url", f.url());
                g.writeStringField("name", f.name());
                g.writeNumberField("refreshMinutes", f.refreshMinutes());
                g.writeEndObject();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(out);
        }
        if (!list.isEmpty()) sb.append('\n');
        return sb.append("]\n").toString();
    }

    /** The inverse; a feed without an id or a URL is skipped; anything unreadable throws. */
    public static List<Feed> parseFeeds(String text) throws IOException {
        List<Feed> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of feeds");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                String id = "";
                String url = "";
                String name = "";
                int minutes = 0;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken value = p.nextToken();
                    if (value == JsonToken.VALUE_STRING) {
                        switch (field) {
                            case "id" -> id = p.getText();
                            case "url" -> url = p.getText();
                            case "name" -> name = p.getText();
                            default -> {}
                        }
                    } else if (field.equals("refreshMinutes") && value == JsonToken.VALUE_NUMBER_INT) {
                        minutes = p.getIntValue();
                    } else {
                        p.skipChildren();
                    }
                }
                if (!id.isBlank() && !url.isBlank()) out.add(new Feed(id, url, name, minutes));
            }
        } catch (RuntimeException e) {
            throw new IOException("not a list of feeds: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * {@code {"etag":"…","lastModified":"…","refreshedEpochMs":…,"error":"…","articles":[…]}},
     * one article per line: guid, title, link, torrent, published (ISO, absent when unknown),
     * description, read.
     */
    public static String formatState(FeedState state) {
        StringWriter head = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(head)) {
            g.writeStartObject();
            g.writeStringField("etag", state.etag());
            g.writeStringField("lastModified", state.lastModified());
            g.writeNumberField("refreshedEpochMs", state.refreshedEpochMs());
            g.writeStringField("error", state.error());
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String h = head.toString();
        StringBuilder sb = new StringBuilder(h.substring(0, h.length() - 1)).append(",\"articles\":[");
        int i = 0;
        for (Article a : state.articles()) {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(out)) {
                g.writeStartObject();
                g.writeStringField("guid", a.guid());
                g.writeStringField("title", a.title());
                g.writeStringField("link", a.link());
                g.writeStringField("torrent", a.torrentLink());
                if (a.published() != null)
                    g.writeStringField("published", a.published().toString());
                g.writeStringField("description", a.description());
                g.writeBooleanField("read", a.read());
                g.writeEndObject();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(out);
        }
        if (!state.articles().isEmpty()) sb.append('\n');
        return sb.append("]}\n").toString();
    }

    /** The inverse; an article with neither guid nor title nor link is skipped; unreadable throws. */
    public static FeedState parseState(String text, String feedId) throws IOException {
        String etag = "";
        String lastModified = "";
        long refreshed = 0;
        String error = "";
        List<Article> articles = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a feed's articles");
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                switch (field) {
                    case "etag" -> etag = value == JsonToken.VALUE_STRING ? p.getText() : "";
                    case "lastModified" -> lastModified = value == JsonToken.VALUE_STRING ? p.getText() : "";
                    case "error" -> error = value == JsonToken.VALUE_STRING ? p.getText() : "";
                    case "refreshedEpochMs" -> refreshed = value == JsonToken.VALUE_NUMBER_INT ? p.getLongValue() : 0;
                    case "articles" -> {
                        if (value != JsonToken.START_ARRAY) {
                            p.skipChildren();
                            break;
                        }
                        JsonToken entry;
                        while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                            if (entry == null) throw new IOException("the list is cut short");
                            if (entry != JsonToken.START_OBJECT) {
                                p.skipChildren();
                                continue;
                            }
                            Article a = parseArticle(p, feedId);
                            if (a != null) articles.add(a);
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("not a feed's articles: " + e.getMessage(), e);
        }
        return new FeedState(etag, lastModified, refreshed, error, articles);
    }

    /** Positioned after an article's {@code {}; returns past its {@code }}. */
    private static Article parseArticle(JsonParser p, String feedId) throws IOException {
        String guid = "";
        String title = "";
        String link = "";
        String torrent = "";
        Instant published = null;
        String description = "";
        boolean read = false;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            JsonToken value = p.nextToken();
            if (value == JsonToken.VALUE_STRING) {
                switch (field) {
                    case "guid" -> guid = p.getText();
                    case "title" -> title = p.getText();
                    case "link" -> link = p.getText();
                    case "torrent" -> torrent = p.getText();
                    case "description" -> description = p.getText();
                    case "published" -> published = FeedParser.parseDate(p.getText());
                    default -> {}
                }
            } else if (field.equals("read") && (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE)) {
                read = p.getBooleanValue();
            } else {
                p.skipChildren();
            }
        }
        if (guid.isBlank() && title.isBlank() && link.isBlank()) return null;
        return new Article(feedId, guid, title, link, torrent, published, description, read);
    }
}
