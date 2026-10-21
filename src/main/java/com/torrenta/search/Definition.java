package com.torrenta.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One declarative provider: a JSON file that says where to search and how to read the answer,
 * interpreted by {@link DeclarativeProvider}. The format is in {@code docs/search-providers.md};
 * in short:
 *
 * <pre>{@code
 * { "id": "linuxtracker", "name": "LinuxTracker", "homepage": "https://linuxtracker.org",
 *   "type": "html",
 *   "search": { "url": "https://linuxtracker.org/torrents/?q={query}&cat={category}",
 *               "page": "…&page={page}", "pages": 1, "delayMs": 1000, "localFilter": false },
 *   "headers": { "User-Agent": "…" },
 *   "categories": { "default": "0", "Software": "12" },
 *   "fields": { "rows": "table.torrent-table tr",
 *               "name": "a.torrent-name",
 *               "size": "td:nth-of-type(4)",
 *               "detailUrl": { "selector": "a.torrent-name", "attr": "href" },
 *               "infoHash": { "selector": "a.torrent-name", "attr": "href", "regex": "([0-9a-f]{40})" } } }
 * }</pre>
 *
 * <p>Every field is a {@link Field}: a string is the selector (html) or path (json); an object
 * adds {@code attr} (html: the attribute instead of the text), {@code regex} (the first group,
 * or the match, of the text), {@code template} ({@code {other}} filled from the other fields and
 * {@code {query}}), {@code format} (a date pattern) and {@code value} (a constant). A key in
 * {@code fields} that is not one of the known ones is an auxiliary field for templates to use.
 * Pure; {@link #parse} throws {@link DefinitionException} naming the file and the key at fault.
 */
public record Definition(
        String id,
        String name,
        String homepage,
        Type type,
        Search search,
        Map<String, String> headers,
        Map<String, String> categories,
        Map<String, Field> fields) {

    public enum Type {
        JSON,
        HTML
    }

    /** The URL templates and how many pages to walk. */
    public record Search(String url, String page, int pages, int delayMs, boolean localFilter) {}

    /**
     * How one field is read off a row. {@code path} is the JSON path or the CSS selector; empty
     * means the row itself.
     */
    public record Field(String path, String attr, String regex, String template, String format, String value) {
        public Field {
            path = path == null ? "" : path.trim();
            attr = attr == null ? "" : attr.trim();
            regex = regex == null ? "" : regex;
            template = template == null ? "" : template;
            format = format == null ? "" : format.trim();
            value = value == null ? "" : value;
        }

        /** A plain selector or path. */
        public static Field of(String path) {
            return new Field(path, "", "", "", "", "");
        }

        public boolean isTemplate() {
            return !template.isEmpty();
        }

        public boolean isConstant() {
            return !value.isEmpty();
        }
    }

    /** The fields with a meaning; anything else in {@code fields} is auxiliary. */
    public static final List<String> KNOWN_FIELDS =
            List.of("name", "size", "seeds", "leechers", "date", "magnet", "torrentUrl", "infoHash", "detailUrl");

    public static final String ROWS = "rows";
    public static final String DEFAULT_CATEGORY = "default";
    public static final int MAX_PAGES = 10;
    public static final int MAX_DELAY_MS = 10_000;
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private static final Set<String> TOP =
            Set.of("id", "name", "homepage", "type", "search", "headers", "categories", "fields");
    private static final Set<String> SEARCH = Set.of("url", "page", "pages", "delayMs", "localFilter");
    private static final Set<String> FIELD = Set.of("path", "selector", "attr", "regex", "template", "format", "value");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    public Definition {
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        categories = Map.copyOf(categories == null ? Map.of() : categories);
        fields = Map.copyOf(fields == null ? Map.of() : fields);
    }

    /** The site's value for a category: its own entry, else the default, else empty. */
    public String categoryValue(SearchCategory category) {
        SearchCategory c = category == null ? SearchCategory.ALL : category;
        for (Map.Entry<String, String> e : categories.entrySet()) {
            if (e.getKey().equalsIgnoreCase(c.label()) || e.getKey().equalsIgnoreCase(c.name())) return e.getValue();
        }
        return categories.getOrDefault(DEFAULT_CATEGORY, "");
    }

    public Optional<Field> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    /** The row selector or path. */
    public Field rows() {
        return fields.get(ROWS);
    }

    // ---- parsing --------------------------------------------------------------------------------

    /**
     * Reads and checks a definition; {@code file} is only for the messages.
     *
     * @throws DefinitionException for anything that would make the provider misbehave: a missing
     *     key, an unknown one (a typo would otherwise be silently ignored), a bad value
     */
    public static Definition parse(String json, String file) throws DefinitionException {
        Object root;
        try {
            root = JsonExtractor.read(json);
        } catch (IOException e) {
            throw new DefinitionException(file, "", e.getMessage());
        }
        Map<String, Object> top = object(root, file, "");
        unknown(top, TOP, file, "");

        String id = string(top, "id", file, "", true);
        if (!ID.matcher(id).matches())
            throw new DefinitionException(file, "id", "must be lower-case letters, digits, '.', '_' or '-'");
        String name = string(top, "name", file, "", true);
        String homepage = string(top, "homepage", file, "", false);
        String typeText = string(top, "type", file, "", true).toLowerCase(Locale.ROOT);
        Type type =
                switch (typeText) {
                    case "json" -> Type.JSON;
                    case "html" -> Type.HTML;
                    default ->
                        throw new DefinitionException(
                                file, "type", "must be \"json\" or \"html\", not \"" + typeText + "\"");
                };

        Map<String, Object> searchObject = object(require(top, "search", file, ""), file, "search");
        unknown(searchObject, SEARCH, file, "search");
        boolean localFilter = bool(searchObject, "localFilter", file, "search", false);
        String url = string(searchObject, "url", file, "search", true);
        if (!isHttp(url)) throw new DefinitionException(file, "search.url", "must start with http:// or https://");
        if (!localFilter && !url.contains("{query}"))
            throw new DefinitionException(file, "search.url", "must contain {query} (or set search.localFilter)");
        String page = string(searchObject, "page", file, "search", false);
        int pages = integer(searchObject, "pages", file, "search", 1);
        if (pages < 1 || pages > MAX_PAGES)
            throw new DefinitionException(file, "search.pages", "must be between 1 and " + MAX_PAGES);
        if (pages > 1 && page.isEmpty())
            throw new DefinitionException(file, "search.page", "is needed when search.pages is more than 1");
        if (!page.isEmpty() && !page.contains("{page}"))
            throw new DefinitionException(file, "search.page", "must contain {page}");
        if (!page.isEmpty() && !isHttp(page))
            throw new DefinitionException(file, "search.page", "must start with http:// or https://");
        int delayMs = integer(searchObject, "delayMs", file, "search", 0);
        if (delayMs < 0 || delayMs > MAX_DELAY_MS)
            throw new DefinitionException(file, "search.delayMs", "must be between 0 and " + MAX_DELAY_MS);
        Search search = new Search(url, page, pages, delayMs, localFilter);

        Map<String, String> headers = strings(top.get("headers"), file, "headers");
        Map<String, String> categories = strings(top.get("categories"), file, "categories");
        for (String key : categories.keySet()) {
            if (key.equalsIgnoreCase(DEFAULT_CATEGORY)) continue;
            boolean known = false;
            for (SearchCategory c : SearchCategory.values()) {
                if (key.equalsIgnoreCase(c.label()) || key.equalsIgnoreCase(c.name())) known = true;
            }
            if (!known)
                throw new DefinitionException(
                        file, "categories." + key, "is not a category; the categories are " + categoryNames());
        }

        Map<String, Object> fieldsObject = object(require(top, "fields", file, ""), file, "fields");
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fieldsObject.entrySet()) {
            String key = e.getKey();
            if (!key.matches("[A-Za-z0-9_]+"))
                throw new DefinitionException(file, "fields." + key, "a field's name is letters, digits and '_'");
            fields.put(key, field(e.getValue(), file, "fields." + key, type));
        }
        Field rows = fields.get(ROWS);
        if (rows == null) throw new DefinitionException(file, "fields.rows", "is missing");
        if (rows.isTemplate()
                || rows.isConstant()
                || !rows.attr().isEmpty()
                || !rows.regex().isEmpty())
            throw new DefinitionException(file, "fields.rows", "is a selector or path alone");
        if (rows.path().isEmpty() && type == Type.HTML)
            throw new DefinitionException(file, "fields.rows", "is missing");
        if (!fields.containsKey("name")) throw new DefinitionException(file, "fields.name", "is missing");
        if (!fields.containsKey("magnet") && !fields.containsKey("torrentUrl") && !fields.containsKey("infoHash"))
            throw new DefinitionException(file, "fields", "needs one of magnet, torrentUrl or infoHash");
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            if (!e.getValue().isTemplate()) continue;
            var m = PLACEHOLDER.matcher(e.getValue().template());
            while (m.find()) {
                String ref = m.group(1);
                if (ref.equals("query")) continue;
                Field other = fields.get(ref);
                if (other == null)
                    throw new DefinitionException(
                            file, "fields." + e.getKey() + ".template", "{" + ref + "} names no field");
                if (other.isTemplate())
                    throw new DefinitionException(
                            file, "fields." + e.getKey() + ".template", "{" + ref + "} is itself a template");
            }
        }
        return new Definition(id, name, homepage, type, search, headers, categories, fields);
    }

    private static Field field(Object value, String file, String at, Type type) throws DefinitionException {
        if (value instanceof String s) {
            if (s.isBlank() && at.equals("fields." + ROWS) && type == Type.JSON) return Field.of("");
            if (s.isBlank()) throw new DefinitionException(file, at, "is empty");
            checkPath(s, file, at, type);
            return Field.of(s);
        }
        Map<String, Object> o = object(value, file, at);
        unknown(o, FIELD, file, at);
        if (o.containsKey("path") && o.containsKey("selector"))
            throw new DefinitionException(file, at, "has both path and selector; they are the same thing");
        String path = string(o, type == Type.JSON ? "path" : "selector", file, at, false);
        if (path.isEmpty()) path = string(o, type == Type.JSON ? "selector" : "path", file, at, false);
        String attr = string(o, "attr", file, at, false);
        String regex = string(o, "regex", file, at, false);
        String template = string(o, "template", file, at, false);
        String format = string(o, "format", file, at, false);
        String constant = string(o, "value", file, at, false);
        if (path.isEmpty() && template.isEmpty() && constant.isEmpty() && attr.isEmpty())
            throw new DefinitionException(
                    file, at, "needs a " + (type == Type.JSON ? "path" : "selector") + ", a template or a value");
        if (!template.isEmpty() && !path.isEmpty())
            throw new DefinitionException(
                    file, at, "has both a template and a " + (type == Type.JSON ? "path" : "selector"));
        if (!constant.isEmpty() && (!path.isEmpty() || !template.isEmpty()))
            throw new DefinitionException(file, at, "a value stands alone");
        if (!attr.isEmpty() && type == Type.JSON)
            throw new DefinitionException(file, at + ".attr", "is for html definitions");
        if (!path.isEmpty()) checkPath(path, file, at, type);
        if (!regex.isEmpty()) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new DefinitionException(file, at + ".regex", "does not compile: " + e.getDescription());
            }
        }
        if (!format.isEmpty()) {
            if (!at.endsWith(".date")) throw new DefinitionException(file, at + ".format", "is for the date field");
            try {
                java.time.format.DateTimeFormatter.ofPattern(format, Locale.ENGLISH);
            } catch (IllegalArgumentException e) {
                throw new DefinitionException(file, at + ".format", "is not a date pattern: " + e.getMessage());
            }
        }
        return new Field(path, attr, regex, template, format, constant);
    }

    private static void checkPath(String path, String file, String at, Type type) throws DefinitionException {
        if (type != Type.HTML) return;
        try {
            Selector.parse(path);
        } catch (IllegalArgumentException e) {
            throw new DefinitionException(file, at, e.getMessage());
        }
    }

    private static String categoryNames() {
        List<String> names = new ArrayList<>();
        for (SearchCategory c : SearchCategory.values()) names.add(c.label());
        return String.join(", ", names);
    }

    private static boolean isHttp(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    // ---- the JSON tree, typed ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String file, String at) throws DefinitionException {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new DefinitionException(file, at, "must be an object {…}");
    }

    private static Object require(Map<String, Object> o, String key, String file, String at)
            throws DefinitionException {
        Object v = o.get(key);
        if (v == null || v == JsonExtractor.NULL) throw new DefinitionException(file, join(at, key), "is missing");
        return v;
    }

    private static String string(Map<String, Object> o, String key, String file, String at, boolean required)
            throws DefinitionException {
        Object v = o.get(key);
        if (v == null || v == JsonExtractor.NULL) {
            if (required) throw new DefinitionException(file, join(at, key), "is missing");
            return "";
        }
        if (!(v instanceof String s)) throw new DefinitionException(file, join(at, key), "must be a string");
        if (required && s.isBlank()) throw new DefinitionException(file, join(at, key), "is empty");
        return s.trim();
    }

    private static int integer(Map<String, Object> o, String key, String file, String at, int fallback)
            throws DefinitionException {
        Object v = o.get(key);
        if (v == null || v == JsonExtractor.NULL) return fallback;
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
        throw new DefinitionException(file, join(at, key), "must be a whole number");
    }

    private static boolean bool(Map<String, Object> o, String key, String file, String at, boolean fallback)
            throws DefinitionException {
        Object v = o.get(key);
        if (v == null || v == JsonExtractor.NULL) return fallback;
        if (v instanceof Boolean b) return b;
        throw new DefinitionException(file, join(at, key), "must be true or false");
    }

    private static Map<String, String> strings(Object value, String file, String at) throws DefinitionException {
        if (value == null || value == JsonExtractor.NULL) return Map.of();
        Map<String, Object> o = object(value, file, at);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : o.entrySet()) {
            if (!(e.getValue() instanceof String s))
                throw new DefinitionException(file, join(at, e.getKey()), "must be a string");
            if (e.getKey().isBlank()) throw new DefinitionException(file, at, "has an empty key");
            out.put(e.getKey().trim(), s);
        }
        return out;
    }

    private static void unknown(Map<String, Object> o, Set<String> known, String file, String at)
            throws DefinitionException {
        for (String key : o.keySet()) {
            if (!known.contains(key))
                throw new DefinitionException(
                        file,
                        join(at, key),
                        "is not a key here; the keys are "
                                + String.join(", ", known.stream().sorted().toList()));
        }
    }

    private static String join(String at, String key) {
        return at.isEmpty() ? key : at + "." + key;
    }
}
