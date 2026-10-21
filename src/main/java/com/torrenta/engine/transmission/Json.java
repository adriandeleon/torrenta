package com.torrenta.engine.transmission;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * A small JSON tree over jackson-core's streaming API, which is the only JSON library here.
 *
 * <p>Values are plain Java: {@code Map<String,Object>} (insertion order kept), {@code
 * List<Object>}, {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code null}.
 * The accessors read a field leniently, as an RPC answer deserves: a number where a string was
 * expected is its text, a missing field is the fallback, never an exception. Pure.
 */
final class Json {

    private static final JsonFactory FACTORY = new JsonFactory();

    private Json() {}

    /** Parses one document; anything that is not JSON is an {@link IOException}. */
    static Object parse(InputStream in) throws IOException {
        try (JsonParser p = FACTORY.createParser(in)) {
            if (p.nextToken() == null) throw new IOException("empty answer");
            Object value = read(p);
            return value;
        } catch (RuntimeException e) {
            throw new IOException("not JSON: " + e.getMessage(), e);
        }
    }

    static Object parse(String text) throws IOException {
        try (JsonParser p = FACTORY.createParser(text == null ? "" : text)) {
            if (p.nextToken() == null) throw new IOException("empty answer");
            return read(p);
        } catch (RuntimeException e) {
            throw new IOException("not JSON: " + e.getMessage(), e);
        }
    }

    private static Object read(JsonParser p) throws IOException {
        JsonToken t = p.currentToken();
        if (t == null) throw new IOException("the document is cut short");
        switch (t) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    p.nextToken();
                    map.put(name, read(p));
                }
                if (p.currentToken() != JsonToken.END_OBJECT) throw new IOException("the object is cut short");
                return map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                while (true) {
                    JsonToken next = p.nextToken();
                    if (next == null) throw new IOException("the list is cut short");
                    if (next == JsonToken.END_ARRAY) break;
                    list.add(read(p));
                }
                return list;
            }
            case VALUE_STRING -> {
                return p.getText();
            }
            case VALUE_NUMBER_INT -> {
                return p.getLongValue();
            }
            case VALUE_NUMBER_FLOAT -> {
                return p.getDoubleValue();
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return null;
            }
            default -> throw new IOException("unexpected " + t);
        }
    }

    /** The inverse of {@link #parse}: compact, the map's order kept. */
    static String write(Object value) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = FACTORY.createGenerator(out)) {
            write(g, value);
        } catch (IOException e) {
            throw new IllegalStateException(e); // a StringWriter does not fail
        }
        return out.toString();
    }

    private static void write(JsonGenerator g, Object value) throws IOException {
        switch (value) {
            case null -> g.writeNull();
            case Map<?, ?> map -> {
                g.writeStartObject();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    g.writeFieldName(String.valueOf(e.getKey()));
                    write(g, e.getValue());
                }
                g.writeEndObject();
            }
            case Iterable<?> list -> {
                g.writeStartArray();
                for (Object o : list) write(g, o);
                g.writeEndArray();
            }
            case String s -> g.writeString(s);
            case Boolean b -> g.writeBoolean(b);
            case Integer i -> g.writeNumber(i);
            case Long l -> g.writeNumber(l);
            case Double d -> g.writeNumber(d);
            case Float f -> g.writeNumber(f);
            case Number n -> g.writeNumber(n.toString());
            default -> g.writeString(value.toString());
        }
    }

    // ---- lenient accessors ----------------------------------------------------------------------

    /** The value as an object, or an empty one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static Map<String, Object> obj(Map<String, Object> parent, String key) {
        return obj(parent.get(key));
    }

    /** The value as a list, or an empty one. */
    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static List<Object> list(Map<String, Object> parent, String key) {
        return list(parent.get(key));
    }

    /** The strings of a list; anything that is not one is skipped. */
    static List<String> strings(Map<String, Object> parent, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(parent, key)) if (o instanceof String s) out.add(s);
        return out;
    }

    static String str(Map<String, Object> parent, String key, String fallback) {
        Object v = parent.get(key);
        if (v == null) return fallback;
        return v instanceof String s ? s : v.toString();
    }

    static long lng(Map<String, Object> parent, String key, long fallback) {
        Object v = parent.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    static int integer(Map<String, Object> parent, String key, int fallback) {
        long v = lng(parent, key, fallback);
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : v < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) v;
    }

    static double dbl(Map<String, Object> parent, String key, double fallback) {
        Object v = parent.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean bool(Map<String, Object> parent, String key, boolean fallback) {
        Object v = parent.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0;
        if (v instanceof String s) return s.equalsIgnoreCase("true") || s.equals("1");
        return fallback;
    }

    static boolean has(Map<String, Object> parent, String key) {
        return parent.containsKey(key);
    }
}
