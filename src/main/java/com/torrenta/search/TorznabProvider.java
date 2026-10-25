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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import static com.torrenta.i18n.Messages.tr;

/**
 * A Torznab server: Jackett or Prowlarr, which put hundreds of indexers behind one RSS-shaped API.
 *
 * <p>{@code ?t=caps} says who it is and which categories it knows (the Settings page's Test);
 * {@code ?t=search&q=…&cat=…} answers with RSS items whose {@code torznab:attr} elements carry
 * seeders, peers, size, info-hash and the magnet. Parsed with the JDK's StAX, nothing else. The
 * client is {@link com.torrenta.net.TorrentFetch}'s shape: redirects followed, bounded in time.
 */
public final class TorznabProvider implements SearchProvider {

    /** How long one request may take, connect included. */
    public static final Duration TIMEOUT = SearchHttp.TIMEOUT;

    static final String TORZNAB_NS = "http://torznab.com/schemas/2015/feed";

    /** What {@code t=caps} said. */
    public record Capabilities(String title, List<String> categories) {
        public Capabilities {
            title = title == null ? "" : title.trim();
            categories = List.copyOf(categories);
        }
    }

    private final TorznabEndpoint endpoint;
    private final HttpClient client;

    public TorznabProvider(TorznabEndpoint endpoint) {
        this(endpoint, defaultClient());
    }

    public TorznabProvider(TorznabEndpoint endpoint, HttpClient client) {
        this.endpoint = endpoint;
        this.client = client;
    }

    /** The client every provider shares: one connection pool, redirects followed, bounded. */
    public static HttpClient defaultClient() {
        return SearchHttp.defaultClient();
    }

    public TorznabEndpoint endpoint() {
        return endpoint;
    }

    @Override
    public String name() {
        return endpoint.name();
    }

    @Override
    public List<SearchResult> search(String query, SearchCategory category) throws SearchException {
        URI uri = searchUri(endpoint.url(), endpoint.apiKey(), query, category);
        try (InputStream in = fetch(uri)) {
            return parseResults(in, name());
        } catch (IOException | XMLStreamException e) {
            throw new SearchException(tr("search.error.unreadable", reason(e)), e);
        }
    }

    /** Asks {@code t=caps}: the server's title and its categories. */
    public Capabilities capabilities() throws SearchException {
        URI uri = capsUri(endpoint.url(), endpoint.apiKey());
        try (InputStream in = fetch(uri)) {
            return parseCaps(in);
        } catch (IOException | XMLStreamException e) {
            throw new SearchException(tr("search.error.unreadable", reason(e)), e);
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    /** A server whose key is on another machine is not asked: it would only refuse. */
    private InputStream fetch(URI uri) throws SearchException {
        if (endpoint.keyNeeded()) throw new SearchException(tr("search.error.keyNeeded"));
        return SearchHttp.get(
                client,
                uri,
                Map.of("Accept", "application/rss+xml, application/xml, text/xml, */*"),
                tr("search.error.apiKeyRefused"));
    }

    /** An exception's message, as a status line should say it. */
    static String reason(Exception e) {
        return SearchHttp.reason(e);
    }

    // ---- URLs, pure -----------------------------------------------------------------------------

    /** {@code <base>?t=caps[&apikey=…]}; a base that already has a query gets {@code &}. */
    public static URI capsUri(String base, String apiKey) {
        StringBuilder sb = new StringBuilder(base.trim()).append(joiner(base)).append("t=caps");
        if (!apiKey.isEmpty()) sb.append("&apikey=").append(encode(apiKey));
        return toUri(sb.toString());
    }

    /** {@code <base>?t=search&q=…[&cat=…][&apikey=…]}. */
    public static URI searchUri(String base, String apiKey, String query, SearchCategory category) {
        StringBuilder sb = new StringBuilder(base.trim())
                .append(joiner(base))
                .append("t=search&q=")
                .append(encode(query == null ? "" : query.trim()));
        if (category != null && !category.queryValue().isEmpty())
            sb.append("&cat=").append(category.queryValue());
        if (!apiKey.isEmpty()) sb.append("&apikey=").append(encode(apiKey));
        return toUri(sb.toString());
    }

    private static String joiner(String base) {
        String b = base.trim();
        if (b.contains("?")) return b.endsWith("?") || b.endsWith("&") ? "" : "&";
        return "?";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static URI toUri(String s) {
        try {
            return new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a URL: " + s, e);
        }
    }

    // ---- XML, pure ------------------------------------------------------------------------------

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        // The answer comes from the network: no entities, no DTD, nothing fetched on its say-so.
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        try {
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // Not every implementation knows the property; the two above already forbid it.
        }
        return f;
    }

    /**
     * The {@code caps} document: {@code <server title="…">} (or {@code <indexer>}'s text, as some
     * Jackett builds put it) and every {@code <category>} and {@code <subcat>} by name. A Torznab
     * {@code <error>} is thrown as its description.
     */
    public static Capabilities parseCaps(InputStream in) throws XMLStreamException, SearchException {
        XMLStreamReader r = factory().createXMLStreamReader(in);
        String title = "";
        List<String> categories = new ArrayList<>();
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String local = r.getLocalName();
                switch (local) {
                    case "error" -> throw torznabError(r);
                    case "server" -> {
                        String t = attribute(r, "title");
                        if (title.isEmpty() && !t.isEmpty()) title = t;
                    }
                    case "indexer" -> {
                        String t = r.getElementText().trim();
                        if (title.isEmpty() && !t.isEmpty()) title = t;
                    }
                    case "category", "subcat" -> {
                        String n = attribute(r, "name");
                        if (n.isEmpty()) n = attribute(r, "id");
                        if (!n.isEmpty()) categories.add(n);
                    }
                    default -> {}
                }
            }
        } finally {
            r.close();
        }
        return new Capabilities(title, categories);
    }

    /**
     * The RSS answer: one {@link SearchResult} per {@code <item>}. The magnet is the
     * {@code magneturl} attribute, or the {@code <link>} when it is one; else the {@code .torrent}
     * is the {@code <enclosure url>}, or the {@code <link>}. Seeds and leechers come from
     * {@code seeders} and {@code leechers}, or {@code peers} less seeders. An item with no link is
     * skipped. A Torznab {@code <error>} is thrown as its description.
     */
    public static List<SearchResult> parseResults(InputStream in, String provider)
            throws XMLStreamException, SearchException {
        XMLStreamReader r = factory().createXMLStreamReader(in);
        List<SearchResult> out = new ArrayList<>();
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String local = r.getLocalName();
                if (local.equals("error")) throw torznabError(r);
                if (local.equals("item")) {
                    SearchResult result = readItem(r, provider);
                    if (result != null) out.add(result);
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    /** Positioned on {@code <item>}; returns past {@code </item>}. */
    private static SearchResult readItem(XMLStreamReader r, String provider) throws XMLStreamException {
        String title = "";
        String link = "";
        String guid = "";
        String comments = "";
        String pubDate = "";
        String enclosure = "";
        long enclosureLength = -1;
        long sizeElement = -1;
        String magnet = "";
        String infoHash = "";
        long size = -1;
        int seeders = -1;
        int leechers = -1;
        int peers = -1;
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                continue;
            }
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            String local = r.getLocalName();
            switch (local) {
                case "title" -> title = r.getElementText();
                case "link" -> link = r.getElementText();
                case "guid" -> guid = r.getElementText();
                case "comments" -> comments = r.getElementText();
                case "pubDate" -> pubDate = r.getElementText();
                case "size" -> {
                    sizeElement = parseLong(r.getElementText());
                }
                case "enclosure" -> {
                    enclosure = attribute(r, "url");
                    enclosureLength = parseLong(attribute(r, "length"));
                    depth++;
                }
                case "attr" -> {
                    String n = attribute(r, "name").toLowerCase(Locale.ROOT);
                    String v = attribute(r, "value").trim();
                    switch (n) {
                        case "seeders" -> seeders = parseInt(v);
                        case "leechers" -> leechers = parseInt(v);
                        case "peers" -> peers = parseInt(v);
                        case "size" -> size = parseLong(v);
                        case "infohash" -> infoHash = v;
                        case "magneturl" -> magnet = v;
                        default -> {}
                    }
                    depth++;
                }
                default -> depth++;
            }
        }
        SearchResult.Link where;
        if (isMagnet(magnet)) where = new SearchResult.Link.Magnet(magnet);
        else if (isMagnet(link)) where = new SearchResult.Link.Magnet(link);
        else if (isHttp(enclosure)) where = new SearchResult.Link.TorrentUrl(enclosure);
        else if (isHttp(link)) where = new SearchResult.Link.TorrentUrl(link);
        else return null;
        if (size < 0) size = sizeElement >= 0 ? sizeElement : enclosureLength;
        if (leechers < 0 && peers >= 0 && seeders >= 0) leechers = Math.max(0, peers - seeders);
        String detail = isHttp(comments) ? comments : isHttp(guid) && !guid.equals(where.value()) ? guid : "";
        return new SearchResult(
                title, size, seeders, leechers, parseDate(pubDate), where, infoHash, provider, detail.trim());
    }

    private static SearchException torznabError(XMLStreamReader r) {
        String code = attribute(r, "code");
        String description = attribute(r, "description");
        if (description.isEmpty()) description = tr("search.error.serverError");
        return new SearchException(
                code.isEmpty() ? description : tr("search.error.serverErrorCode", description, code));
    }

    private static String attribute(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(name)) {
                String v = r.getAttributeValue(i);
                return v == null ? "" : v.trim();
            }
        }
        return "";
    }

    private static boolean isMagnet(String s) {
        return s != null && s.trim().regionMatches(true, 0, "magnet:", 0, 7);
    }

    private static boolean isHttp(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? -1 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseInt(String s) {
        try {
            return s == null || s.isBlank() ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** RFC 1123 as RSS writes it ("Sat, 18 Oct 2026 09:12:00 +0000"), or ISO; null otherwise. */
    public static Instant parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Not RFC 1123; ISO next.
        }
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignored) {
            // Not ISO either.
        }
        try {
            return ZonedDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
