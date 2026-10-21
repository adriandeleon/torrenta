package com.torrenta.search;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A lenient HTML tokeniser and tree builder of our own, enough for a listing page: tags with
 * quoted, unquoted and bare attributes, comments, doctypes and CDATA skipped, entities decoded,
 * {@code <script>} and {@code <style>} contents dropped, unclosed tags tolerated. Not a browser:
 * what it does with a page is
 *
 * <ul>
 *   <li>a start tag of {@code li}, {@code dt}/{@code dd}, {@code tr}, {@code td}/{@code th},
 *       {@code thead}/{@code tbody}/{@code tfoot}, {@code option} and {@code a} closes an open
 *       one of its kind first, and a block (div, table, ul, p, h1…) closes an open {@code p};
 *   <li>an end tag closes the nearest open element of its name and everything opened since;
 *       one with no open element is ignored;
 *   <li>void elements ({@code br}, {@code img}, {@code input}, …) and any tag written
 *       {@code <x/>} have no children, so an RSS or XML document parses too (a {@code <link>}
 *       with no attributes is RSS's, with content, not HTML's void one);
 *   <li>no {@code tbody} is invented: a {@code tr} written directly under {@code table} stays
 *       there, so write {@code table tr}, not {@code table > tbody > tr}, unless the page has it;
 *   <li>tag and attribute names are lower-cased; text is kept as written.
 * </ul>
 *
 * Pure; never throws on any input.
 */
public final class HtmlParser {

    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track",
            "wbr");
    private static final Set<String> RAW = Set.of("script", "style");
    private static final Set<String> CLOSE_P = Set.of(
            "address",
            "article",
            "aside",
            "blockquote",
            "div",
            "dl",
            "fieldset",
            "footer",
            "form",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "header",
            "hr",
            "main",
            "nav",
            "ol",
            "p",
            "pre",
            "section",
            "table",
            "ul");
    private static final Set<String> CELLS = Set.of("td", "th");
    private static final Set<String> SECTIONS = Set.of("thead", "tbody", "tfoot");
    private static final Set<String> TABLE = Set.of("table");
    private static final Set<String> NONE = Set.of();
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("copy", "©"),
            Map.entry("reg", "®"),
            Map.entry("trade", "™"),
            Map.entry("middot", "·"),
            Map.entry("ndash", "–"),
            Map.entry("mdash", "—"),
            Map.entry("hellip", "…"),
            Map.entry("laquo", "«"),
            Map.entry("raquo", "»"),
            Map.entry("lsquo", "‘"),
            Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"),
            Map.entry("bull", "•"),
            Map.entry("times", "×"),
            Map.entry("deg", "°"),
            Map.entry("euro", "€"),
            Map.entry("pound", "£"));

    private final String html;
    private final int n;
    private int i;
    private final HtmlNode root = HtmlNode.root();
    private final Deque<HtmlNode> open = new ArrayDeque<>();

    private HtmlParser(String html) {
        this.html = html == null ? "" : html;
        this.n = this.html.length();
        open.push(root);
    }

    /** The document's root: an element called {@code #root} whose children are the top level. */
    public static HtmlNode parse(String html) {
        HtmlParser p = new HtmlParser(html);
        p.run();
        return p.root;
    }

    private void run() {
        StringBuilder text = new StringBuilder();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            if (html.startsWith("<!--", i)) {
                flush(text);
                int end = html.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
            } else if (html.startsWith("<![CDATA[", i)) {
                int end = html.indexOf("]]>", i + 9);
                text.append(html, i + 9, end < 0 ? n : end);
                i = end < 0 ? n : end + 3;
            } else if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                flush(text);
                int end = html.indexOf('>', i);
                i = end < 0 ? n : end + 1;
            } else if (html.startsWith("</", i) && i + 2 < n && isNameStart(html.charAt(i + 2))) {
                flush(text);
                endTag();
            } else if (i + 1 < n && isNameStart(html.charAt(i + 1))) {
                flush(text);
                startTag();
            } else {
                text.append(c);
                i++;
            }
        }
        flush(text);
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':' || c == '.';
    }

    private void flush(StringBuilder text) {
        if (text.length() == 0) return;
        String t = text.toString();
        text.setLength(0);
        if (t.isBlank()) return;
        open.peek().append(HtmlNode.text(decode(t)));
    }

    private void endTag() {
        i += 2;
        int start = i;
        while (i < n && isNameChar(html.charAt(i))) i++;
        String name = html.substring(start, i).toLowerCase(Locale.ROOT);
        int end = html.indexOf('>', i);
        i = end < 0 ? n : end + 1;
        if (name.equals("br")) return;
        closeUntil(Set.of(name), NONE);
    }

    private void startTag() {
        i++;
        int start = i;
        while (i < n && isNameChar(html.charAt(i))) i++;
        String name = html.substring(start, i).toLowerCase(Locale.ROOT);
        Map<String, String> attrs = new LinkedHashMap<>();
        boolean selfClosed = false;
        while (i < n) {
            char c = html.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '>') {
                i++;
                break;
            } else if (c == '/') {
                i++;
                if (i < n && html.charAt(i) == '>') {
                    selfClosed = true;
                    i++;
                    break;
                }
            } else {
                attribute(attrs);
            }
        }
        if (RAW.contains(name)) {
            // Script and style contents are not markup and not wanted: skip to the end tag.
            int end = indexOfIgnoreCase("</" + name, i);
            if (end < 0) {
                i = n;
            } else {
                int close = html.indexOf('>', end);
                i = close < 0 ? n : close + 1;
            }
            return;
        }
        implyClosesBefore(name);
        HtmlNode element = HtmlNode.element(name, attrs);
        open.peek().append(element);
        // HTML's <link rel=…> is void and always has attributes; RSS's <link>…</link> has none.
        boolean isVoid = VOID.contains(name) && !(name.equals("link") && attrs.isEmpty());
        if (!isVoid && !selfClosed) open.push(element);
    }

    private void attribute(Map<String, String> attrs) {
        int start = i;
        while (i < n) {
            char c = html.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '='
                    || c == '>'
                    || (c == '/' && i + 1 < n && html.charAt(i + 1) == '>')) break;
            i++;
        }
        if (i == start) {
            i++; // a stray character, "<a / href>": step over it
            return;
        }
        String name = html.substring(start, i).toLowerCase(Locale.ROOT);
        int j = i;
        while (j < n && Character.isWhitespace(html.charAt(j))) j++;
        if (j >= n || html.charAt(j) != '=') {
            attrs.put(name, "");
            return;
        }
        i = j + 1;
        while (i < n && Character.isWhitespace(html.charAt(i))) i++;
        String value;
        if (i < n && (html.charAt(i) == '"' || html.charAt(i) == '\'')) {
            char quote = html.charAt(i);
            int end = html.indexOf(quote, i + 1);
            if (end < 0) end = n;
            value = html.substring(i + 1, end);
            i = Math.min(n, end + 1);
        } else {
            int s = i;
            while (i < n && !Character.isWhitespace(html.charAt(i)) && html.charAt(i) != '>') i++;
            value = html.substring(s, i);
        }
        attrs.put(name, decode(value));
    }

    private int indexOfIgnoreCase(String needle, int from) {
        int limit = n - needle.length();
        for (int k = from; k <= limit; k++) {
            if (html.regionMatches(true, k, needle, 0, needle.length())) return k;
        }
        return -1;
    }

    /** What an opening tag closes before it opens, the few cases a listing page needs. */
    private void implyClosesBefore(String name) {
        switch (name) {
            case "li" -> closeUntil(Set.of("li"), Set.of("ul", "ol", "table"));
            case "dt", "dd" -> closeUntil(Set.of("dt", "dd"), Set.of("dl"));
            case "tr" -> {
                closeUntil(CELLS, Set.of("tr", "table"));
                closeUntil(Set.of("tr"), Set.of("table", "thead", "tbody", "tfoot"));
            }
            case "td", "th" -> closeUntil(CELLS, Set.of("tr", "table"));
            case "thead", "tbody", "tfoot" -> {
                closeUntil(CELLS, TABLE);
                closeUntil(Set.of("tr"), TABLE);
                closeUntil(SECTIONS, TABLE);
            }
            case "option" -> closeUntil(Set.of("option"), Set.of("select", "datalist"));
            case "a" -> closeUntil(Set.of("a"), Set.of("td", "th", "li", "div"));
            default -> {
                if (CLOSE_P.contains(name)) closeUntil(Set.of("p"), Set.of("table", "td", "th", "button"));
            }
        }
    }

    /**
     * Pops the open elements down through the nearest one whose tag is in {@code these}, unless
     * one whose tag is in {@code stops} (or the root) is met first, in which case nothing moves.
     */
    private void closeUntil(Set<String> these, Set<String> stops) {
        int depth = 0;
        HtmlNode found = null;
        for (HtmlNode node : open) {
            if (node == root) break;
            depth++;
            if (these.contains(node.tag())) {
                found = node;
                break;
            }
            if (stops.contains(node.tag())) return;
        }
        if (found == null) return;
        for (int k = 0; k < depth; k++) open.pop();
    }

    /** Entities, named (the common ones) and numeric; an unknown one stays as written. */
    public static String decode(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int from = 0;
        while (amp >= 0) {
            sb.append(s, from, amp);
            int semi = s.indexOf(';', amp + 1);
            String replacement = null;
            if (semi > amp && semi - amp <= 10) {
                String body = s.substring(amp + 1, semi);
                replacement = entity(body);
            }
            if (replacement == null) {
                sb.append('&');
                from = amp + 1;
            } else {
                sb.append(replacement);
                from = semi + 1;
            }
            amp = s.indexOf('&', from);
        }
        sb.append(s, from, s.length());
        return sb.toString();
    }

    private static String entity(String body) {
        if (body.startsWith("#")) {
            try {
                int code = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X')
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                if (code <= 0 || code > 0x10FFFF || (code >= 0xD800 && code <= 0xDFFF)) return null;
                return new String(Character.toChars(code));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ENTITIES.get(body);
    }
}
