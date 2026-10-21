package com.torrenta.rss;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * RSS 2.0 (and RSS 1.0's RDF shape) and Atom, read as a stream with the JDK's StAX, entities and
 * DTDs off: a feed comes from the network. Lenient: a missing field is empty or null, never a
 * failure; only text that is not XML at all is.
 *
 * <p>Where the torrent is, in order: a {@code <torrent:magnetURI>}, a magnet as the {@code link}
 * or the {@code guid}, an {@code <enclosure>} of type {@code application/x-bittorrent} (or whose
 * URL ends in {@code .torrent}), a {@code link} ending in {@code .torrent}, a magnet in the
 * description, an info-hash element made into a magnet. Descriptions are kept as plain text:
 * tags stripped, entities decoded, whitespace folded.
 */
public final class FeedParser {

    /** The feed's own title (empty when it gave none) and its items, in the feed's order. */
    public record Parsed(String title, List<Article> articles) {
        public Parsed {
            title = title == null ? "" : title.trim();
            articles = List.copyOf(articles);
        }
    }

    private static final Pattern MAGNET_IN_TEXT = Pattern.compile("magnet:\\?[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_HASH = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    private static final Pattern TAG = Pattern.compile("<(?:[^>\"']|\"[^\"]*\"|'[^']*')*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Pattern BLANK_RUN = Pattern.compile("[ \\t\\x0B\\f\\r\\u00A0]+");
    private static final Pattern NEWLINE_RUN = Pattern.compile("\\s*\\n(\\s*\\n)+\\s*");

    private FeedParser() {}

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
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

    /** Reads the whole document; {@code feedId} goes onto every article. */
    public static Parsed parse(InputStream in, String feedId) throws XMLStreamException {
        XMLStreamReader r = factory().createXMLStreamReader(in);
        String title = "";
        List<Article> articles = new ArrayList<>();
        // The element path, so a <title> inside an <item> is not the channel's.
        List<String> path = new ArrayList<>();
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!path.isEmpty()) path.remove(path.size() - 1);
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String local = r.getLocalName();
                if (local.equals("item") || local.equals("entry")) {
                    articles.add(readItem(r, feedId, local.equals("entry")));
                    continue; // readItem consumed the end element
                }
                if (local.equals("title") && isFeedTitlePath(path)) {
                    String t = plainText(readText(r));
                    if (title.isEmpty()) title = t;
                    continue;
                }
                path.add(local);
            }
        } finally {
            r.close();
        }
        return new Parsed(title, articles);
    }

    /** {@code [rss, channel]}, {@code [RDF, channel]} or {@code [feed]}: the feed's own title. */
    private static boolean isFeedTitlePath(List<String> path) {
        if (path.size() == 1) return path.get(0).equals("feed");
        if (path.size() == 2) return path.get(1).equals("channel");
        return false;
    }

    /** Positioned on {@code <item>} or {@code <entry>}; returns past its end element. */
    private static Article readItem(XMLStreamReader r, String feedId, boolean atom) throws XMLStreamException {
        String title = "";
        String link = "";
        String guid = "";
        String description = "";
        String content = "";
        String published = "";
        String updated = "";
        String enclosureUrl = "";
        String enclosureType = "";
        String magnetUri = "";
        String infoHash = "";
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
                case "title" -> title = readText(r);
                case "link" -> {
                    if (atom) {
                        String rel = attribute(r, "rel").toLowerCase(Locale.ROOT);
                        String href = attribute(r, "href");
                        String type = attribute(r, "type");
                        String text = readText(r).trim();
                        if (href.isEmpty()) href = text;
                        if (rel.equals("enclosure")) {
                            if (enclosureUrl.isEmpty() || isTorrentType(type)) {
                                enclosureUrl = href;
                                enclosureType = type;
                            }
                        } else if (rel.isEmpty() || rel.equals("alternate")) {
                            if (link.isEmpty() || isMagnet(href)) link = href;
                        } else if (link.isEmpty() && isMagnet(href)) {
                            link = href;
                        }
                    } else {
                        link = readText(r);
                    }
                }
                case "guid", "id" -> guid = readText(r);
                case "description", "summary" -> description = readText(r);
                case "encoded", "content" -> content = readText(r);
                case "pubDate", "published", "issued", "date" -> {
                    String text = readText(r);
                    if (published.isEmpty()) published = text;
                }
                case "updated", "modified" -> updated = readText(r);
                case "enclosure" -> {
                    String url = attribute(r, "url");
                    String type = attribute(r, "type");
                    if (enclosureUrl.isEmpty() || isTorrentType(type)) {
                        enclosureUrl = url;
                        enclosureType = type;
                    }
                    depth++;
                }
                case "magnetURI", "magneturi", "magnetUri" -> magnetUri = readText(r);
                case "infoHash", "infohash", "info_hash" -> infoHash = readText(r);
                default -> depth++;
            }
        }
        String text = description.isBlank() ? content : description;
        String plain = plainText(text);
        String torrent = torrentLink(magnetUri, link, guid, enclosureUrl, enclosureType, text, infoHash, title);
        String page = isHttp(link) ? link.trim() : isHttp(guid) ? guid.trim() : "";
        Instant when = parseDate(published.isEmpty() ? updated : published);
        return new Article(feedId, guid, plainText(title), page, torrent, when, plain, false);
    }

    /**
     * The text of the element the reader is on, nested elements included (an Atom {@code xhtml}
     * content), positioned past its end element afterwards. Nested elements are not serialised:
     * a line break for a block, a space otherwise, so the plain text reads.
     */
    static String readText(XMLStreamReader r) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    sb.append(isBlock(r.getLocalName()) ? "\n" : " ");
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    depth--;
                    if (depth > 0) sb.append(isBlock(r.getLocalName()) ? "\n" : " ");
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                    sb.append(r.getText());
                default -> {}
            }
        }
        return sb.toString();
    }

    private static boolean isBlock(String local) {
        return switch (local.toLowerCase(Locale.ROOT)) {
            case "p", "div", "br", "li", "ul", "ol", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre" ->
                true;
            default -> false;
        };
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

    // ---- the rules, pure ------------------------------------------------------------------------

    /** Where the item's torrent is; empty when it carries none. See the class comment for the order. */
    static String torrentLink(
            String magnetUri,
            String link,
            String guid,
            String enclosureUrl,
            String enclosureType,
            String description,
            String infoHash,
            String title) {
        if (isMagnet(magnetUri)) return magnetUri.trim();
        if (isMagnet(link)) return link.trim();
        if (isMagnet(guid)) return guid.trim();
        if (isHttp(enclosureUrl) && (isTorrentType(enclosureType) || endsWithTorrent(enclosureUrl)))
            return enclosureUrl.trim();
        if (isHttp(link) && endsWithTorrent(link)) return link.trim();
        if (description != null) {
            Matcher m = MAGNET_IN_TEXT.matcher(description);
            if (m.find()) return decodeEntities(m.group());
        }
        if (infoHash != null) {
            Matcher m = INFO_HASH.matcher(infoHash.trim());
            if (m.matches()) return magnetFor(infoHash.trim().toLowerCase(Locale.ROOT), title);
        }
        return "";
    }

    private static String magnetFor(String hash, String title) {
        String urn = hash.length() == 64 ? "urn:btmh:1220" + hash : "urn:btih:" + hash;
        String dn = title == null || title.isBlank()
                ? ""
                : "&dn=" + java.net.URLEncoder.encode(title.trim(), java.nio.charset.StandardCharsets.UTF_8);
        return "magnet:?xt=" + urn + dn;
    }

    static boolean isTorrentType(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).contains("bittorrent");
    }

    static boolean endsWithTorrent(String url) {
        try {
            String path = new URI(url.trim()).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".torrent");
        } catch (URISyntaxException e) {
            String u = url.trim().toLowerCase(Locale.ROOT);
            int q = u.indexOf('?');
            return (q < 0 ? u : u.substring(0, q)).endsWith(".torrent");
        }
    }

    static boolean isMagnet(String s) {
        return s != null && s.trim().regionMatches(true, 0, "magnet:", 0, 7);
    }

    static boolean isHttp(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /**
     * HTML into text: {@code <br>}, {@code <p>}, {@code <li>} and the like become line breaks,
     * every other tag goes, entities are decoded (named, decimal, hex), runs of blanks fold to
     * one, runs of blank lines to one, and the ends are trimmed.
     */
    public static String plainText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;
        // Block boundaries first, so "a</p><p>b" keeps its break.
        s = s.replaceAll("(?i)<\\s*br\\s*/?\\s*>", "\n");
        s = s.replaceAll("(?i)</\\s*(p|div|li|tr|h[1-6]|blockquote|pre)\\s*>", "\n");
        s = s.replaceAll("(?i)<\\s*(p|div|li|tr|h[1-6]|blockquote|pre)(\\s[^>]*)?>", "\n");
        s = TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = BLANK_RUN.matcher(s).replaceAll(" ");
        s = s.replaceAll(" *\\n *", "\n");
        s = NEWLINE_RUN.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String out;
            if (e.startsWith("#x") || e.startsWith("#X")) {
                out = codePoint(e.substring(2), 16, m.group());
            } else if (e.startsWith("#")) {
                out = codePoint(e.substring(1), 10, m.group());
            } else {
                out = switch (e.toLowerCase(Locale.ROOT)) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "nbsp" -> " ";
                    case "hellip" -> "…";
                    case "mdash" -> "—";
                    case "ndash" -> "–";
                    case "lsquo" -> "‘";
                    case "rsquo" -> "’";
                    case "ldquo" -> "“";
                    case "rdquo" -> "”";
                    case "copy" -> "©";
                    case "reg" -> "®";
                    case "trade" -> "™";
                    case "middot" -> "·";
                    case "laquo" -> "«";
                    case "raquo" -> "»";
                    case "euro" -> "€";
                    case "pound" -> "£";
                    default -> m.group();
                };
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String codePoint(String digits, int radix, String whole) {
        try {
            int cp = Integer.parseInt(digits, radix);
            return Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : whole;
        } catch (NumberFormatException e) {
            return whole;
        }
    }

    private static final DateTimeFormatter[] LENIENT = {
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yy HH:mm:ss zzz", Locale.ENGLISH),
    };

    /**
     * RFC 1123 as RSS writes it, ISO 8601 as Atom does, and the common slips (no weekday, a
     * two-digit year, a zone name, a date alone); null when nothing fits.
     */
    public static Instant parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim().replaceAll("\\s+", " ");
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Not strict RFC 1123.
        }
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignored) {
            // Not an ISO instant.
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // Not an ISO offset date-time.
        }
        try {
            return ZonedDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // Not an ISO zoned date-time.
        }
        for (DateTimeFormatter f : LENIENT) {
            try {
                return ZonedDateTime.parse(t, f).toInstant();
            } catch (DateTimeParseException ignored) {
                // The next shape.
            }
        }
        try {
            return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Not a local date-time.
        }
        try {
            return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
