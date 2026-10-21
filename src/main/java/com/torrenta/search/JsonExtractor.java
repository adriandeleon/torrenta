package com.torrenta.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

/**
 * A JSON answer as a tree a definition's paths walk: objects as {@link Map}s, arrays as
 * {@link List}s, strings, numbers ({@link Long} or {@link Double}), booleans and
 * {@link #NULL}. Read once with Jackson's streaming parser, which is the only JSON library here.
 *
 * <p>A path is JSON Pointer's shape: {@code /response/docs/0/title}, with {@code ~1} for a slash
 * and {@code ~0} for a tilde in a key; the leading slash is optional and the empty path is the
 * root. Pure.
 */
public final class JsonExtractor {

    /** A JSON {@code null}, kept distinct from "no such member". */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(200).build())
            .build();

    private JsonExtractor() {}

    /** The whole document; malformed JSON throws. */
    public static Object read(String text) throws IOException {
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            JsonToken first = p.nextToken();
            if (first == null) throw new IOException("the answer is empty");
            return value(p, first);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IOException("not JSON: " + e.getMessage(), e);
        }
    }

    private static Object value(JsonParser p, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    map.put(name, value(p, p.nextToken()));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                JsonToken t;
                while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                    if (t == null) throw new IOException("the list is cut short");
                    list.add(value(p, t));
                }
                yield list;
            }
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> {
                try {
                    yield p.getLongValue();
                } catch (IOException e) {
                    yield p.getDoubleValue();
                }
            }
            case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> NULL;
            default -> throw new IOException("unexpected " + token);
        };
    }

    /**
     * The value at {@code path} under {@code root}: a member by name in an object, by index in a
     * list; null when any step is missing (a JSON null is {@link #NULL}).
     */
    public static Object at(Object root, String path) {
        Object current = root;
        for (String step : steps(path)) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(step)) return null;
                current = map.get(step);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(step);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (index < 0 || index >= list.size()) return null;
                current = list.get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    /** The value at {@code path} as text: a string as it is, a number plainly, a boolean as a word; null for a container, a null or nothing. */
    public static String text(Object root, String path) {
        return text(at(root, path));
    }

    /** A scalar's text; null for a container, a JSON null or no value. */
    public static String text(Object value) {
        if (value == null || value == NULL || value instanceof Map || value instanceof List) return null;
        if (value instanceof Double d) {
            return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString(d.longValue()) : d.toString();
        }
        return value.toString();
    }

    /**
     * The rows at {@code path}: a list's elements, an object's values (an API that keys its rows
     * by id), or nothing.
     */
    public static List<Object> rows(Object root, String path) {
        Object at = at(root, path);
        if (at instanceof List<?> list) return new ArrayList<>(list);
        if (at instanceof Map<?, ?> map) return new ArrayList<>(map.values());
        return List.of();
    }

    /** The path's steps, unescaped; {@code ""} and {@code "/"} are no steps at all. */
    static List<String> steps(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        String p = path.trim();
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return out;
        for (String step : p.split("/", -1)) out.add(step.replace("~1", "/").replace("~0", "~"));
        return out;
    }
}
