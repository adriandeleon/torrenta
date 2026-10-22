package com.torrenta.search;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.torrenta.i18n.Messages.tr;

/**
 * The one generic provider every {@link Definition} runs through: the URL is the definition's
 * template with the query and the category filled in, the answer is read as JSON or as HTML, the
 * rows are found with the definition's path or selector, and each field is read off a row the
 * same way, then typed. Nothing in here knows any site.
 *
 * <p>A row without a name or without a link (a magnet, a {@code .torrent} URL, or an info-hash to
 * make a magnet of) is skipped; a field that is missing is {@code -1}, empty or null, never a
 * failure. The pages are fetched in order with the definition's delay between them, all within
 * the service's 20 s. Relative URLs are resolved against the page they came from.
 */
public final class DeclarativeProvider implements SearchProvider {

    /** How long one request may take, connect included. */
    public static final Duration TIMEOUT = SearchHttp.TIMEOUT;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");
    private static final Pattern HEX40 = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    private static final Pattern BASE32 = Pattern.compile("[A-Za-z2-7]{32}");

    private final Definition definition;
    private final HttpClient client;
    private final Supplier<Instant> clock;

    public DeclarativeProvider(Definition definition) {
        this(definition, SearchHttp.defaultClient(), Instant::now);
    }

    public DeclarativeProvider(Definition definition, HttpClient client) {
        this(definition, client, Instant::now);
    }

    public DeclarativeProvider(Definition definition, HttpClient client, Supplier<Instant> clock) {
        this.definition = definition;
        this.client = client;
        this.clock = clock;
    }

    public Definition definition() {
        return definition;
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public List<SearchResult> search(String query, SearchCategory category) throws SearchException {
        String q = query == null ? "" : query.trim();
        List<SearchResult> out = new ArrayList<>();
        Definition.Search search = definition.search();
        for (int page = 1; page <= search.pages(); page++) {
            if (page > 1 && search.delayMs() > 0) {
                try {
                    Thread.sleep(search.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SearchException(tr("net.interrupted"), e);
                }
            }
            URI uri = url(definition, q, category, page);
            String body;
            try (InputStream in =
                    SearchHttp.get(client, uri, definition.headers(), tr("search.error.requestRefused"))) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new SearchException(tr("search.error.unreadable", SearchHttp.reason(e)), e);
            }
            List<SearchResult> found = extract(definition, body, uri, q, clock.get());
            out.addAll(found);
            if (found.isEmpty()) break;
        }
        return search.localFilter() ? filter(out, q) : out;
    }

    // ---- the URL, pure --------------------------------------------------------------------------

    /**
     * Page {@code page}'s URL: {@code search.url} for the first, {@code search.page} for the rest,
     * with {@code {query}} and {@code {category}} URL-encoded and {@code {page}} the number.
     */
    public static URI url(Definition definition, String query, SearchCategory category, int page) {
        Definition.Search search = definition.search();
        String template = page <= 1 || search.page().isEmpty() ? search.url() : search.page();
        String q = query == null ? "" : query.trim();
        String filled = template.replace("{query}", encode(q))
                .replace("{category}", encode(definition.categoryValue(category)))
                .replace("{page}", Integer.toString(page));
        try {
            return new URI(filled);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a URL: " + filled, e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- the answer, pure -----------------------------------------------------------------------

    /**
     * Every result on one page, in the page's order; {@code pageUri} resolves relative links,
     * {@code now} anchors "2 days ago".
     *
     * @throws SearchException when a JSON answer is not JSON (an HTML page always parses)
     */
    public static List<SearchResult> extract(Definition definition, String body, URI pageUri, String query, Instant now)
            throws SearchException {
        List<SearchResult> out = new ArrayList<>();
        if (definition.type() == Definition.Type.JSON) {
            Object root;
            try {
                root = JsonExtractor.read(body);
            } catch (IOException e) {
                throw new SearchException(tr("search.error.notJson", e.getMessage()), e);
            }
            for (Object row : JsonExtractor.rows(root, definition.rows().path())) {
                Map<String, String> raw = rawFields(definition, query, (field) -> jsonRaw(field, row));
                Optional<SearchResult> result = result(definition, raw, pageUri, now);
                result.ifPresent(out::add);
            }
        } else {
            HtmlNode root = HtmlParser.parse(body);
            for (HtmlNode row : root.select(definition.rows().path())) {
                Map<String, String> raw = rawFields(definition, query, (field) -> htmlRaw(field, row));
                Optional<SearchResult> result = result(definition, raw, pageUri, now);
                result.ifPresent(out::add);
            }
        }
        return out;
    }

    /** Reads one field's raw text off a row, or null. */
    private interface RowReader {
        String read(Definition.Field field);
    }

    /**
     * Every field's text: the plain ones first, then the templates from them; a regex is applied
     * last in both cases.
     */
    static Map<String, String> rawFields(Definition definition, String query, RowReader reader) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Definition.Field> e : definition.fields().entrySet()) {
            if (e.getKey().equals(Definition.ROWS) || e.getValue().isTemplate()) continue;
            Definition.Field field = e.getValue();
            String value = field.isConstant() ? field.value() : reader.read(field);
            raw.put(e.getKey(), regex(field, value));
        }
        for (Map.Entry<String, Definition.Field> e : definition.fields().entrySet()) {
            if (!e.getValue().isTemplate()) continue;
            Matcher m = PLACEHOLDER.matcher(e.getValue().template());
            StringBuilder sb = new StringBuilder();
            boolean complete = true;
            while (m.find()) {
                String ref = m.group(1);
                String v = ref.equals("query") ? query : raw.get(ref);
                if (v == null || v.isEmpty()) complete = false;
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
            }
            m.appendTail(sb);
            raw.put(e.getKey(), complete ? regex(e.getValue(), sb.toString()) : null);
        }
        return raw;
    }

    private static String regex(Definition.Field field, String value) {
        if (value == null || field.regex().isEmpty()) return value;
        Matcher m = Pattern.compile(field.regex()).matcher(value);
        if (!m.find()) return null;
        return m.groupCount() >= 1 ? m.group(1) : m.group();
    }

    private static String jsonRaw(Definition.Field field, Object row) {
        return JsonExtractor.text(row, field.path());
    }

    private static String htmlRaw(Definition.Field field, HtmlNode row) {
        HtmlNode node = row;
        if (!field.path().isEmpty()) {
            Optional<HtmlNode> found = row.selectFirst(field.path());
            if (found.isEmpty()) return null;
            node = found.get();
        }
        if (!field.attr().isEmpty()) {
            String v = node.attr(field.attr());
            return v.isEmpty() ? null : v;
        }
        String text = node.text();
        return text.isEmpty() ? null : text;
    }

    /** The typed result of one row's texts, or empty when the row has no name or no link. */
    static Optional<SearchResult> result(Definition definition, Map<String, String> raw, URI pageUri, Instant now) {
        String name = raw.get("name");
        if (name == null || name.isBlank()) return Optional.empty();
        String magnet = raw.get("magnet");
        String torrentUrl = resolve(raw.get("torrentUrl"), pageUri);
        String infoHash = hash(raw.get("infoHash"));
        SearchResult.Link link;
        if (magnet != null && magnet.trim().regionMatches(true, 0, "magnet:", 0, 7)) {
            link = new SearchResult.Link.Magnet(magnet);
        } else if (torrentUrl != null) {
            link = new SearchResult.Link.TorrentUrl(torrentUrl);
        } else if (!infoHash.isEmpty()) {
            link = new SearchResult.Link.Magnet("magnet:?xt=urn:btih:" + infoHash + "&dn=" + encode(name.trim()));
        } else {
            return Optional.empty();
        }
        String format = definition.field("date").map(Definition.Field::format).orElse("");
        return Optional.of(new SearchResult(
                name,
                SizeText.parse(raw.get("size")),
                SizeText.count(raw.get("seeds")),
                SizeText.count(raw.get("leechers")),
                DateText.parse(raw.get("date"), format, now),
                link,
                infoHash,
                definition.name(),
                resolve(raw.get("detailUrl"), pageUri)));
    }

    /** A 40- or 64-digit hex hash lower-cased, a 32-letter base32 one as it is; else empty. */
    static String hash(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (HEX40.matcher(t).matches()) return t.toLowerCase(Locale.ROOT);
        if (BASE32.matcher(t).matches()) return t.toUpperCase(Locale.ROOT);
        return "";
    }

    /** An http(s) URL, a relative one resolved against the page; null for anything else. */
    static String resolve(String text, URI pageUri) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t;
        if (lower.startsWith("magnet:") || lower.contains("://")) return null;
        if (pageUri == null) return null;
        try {
            URI resolved = pageUri.resolve(new URI(t.replace(" ", "%20")));
            String s = resolved.toString();
            return s.startsWith("http") ? s : null;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** The results whose name has every word of the query, for a feed that cannot be asked. */
    static List<SearchResult> filter(List<SearchResult> results, String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult r : results) {
            String name = r.name().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !name.contains(w)) all = false;
            if (all) out.add(r);
        }
        return out;
    }
}
