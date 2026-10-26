package com.torrenta.webapi;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * The API's answers as JSON, from plain values: maps (in their order), lists, strings, numbers,
 * booleans and null. jackson-core's streaming writer, which escapes; nothing reflective.
 */
final class JsonOut {

    private static final JsonFactory JSON = new JsonFactory();

    private JsonOut() {}

    static String write(Object value) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            write(g, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
            case List<?> list -> {
                g.writeStartArray();
                for (Object item : list) write(g, item);
                g.writeEndArray();
            }
            case Boolean b -> g.writeBoolean(b);
            case Integer i -> g.writeNumber(i);
            case Long l -> g.writeNumber(l);
            case Double d -> g.writeNumber(d);
            case Number n -> g.writeNumber(n.doubleValue());
            default -> g.writeString(String.valueOf(value));
        }
    }
}
