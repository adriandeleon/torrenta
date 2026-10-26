package com.torrenta.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Name-value pairs as a query string or an {@code application/x-www-form-urlencoded} body carries
 * them: {@code a=1&b=two&a=3}, decoded as UTF-8, a name that repeats keeping every value in order.
 * Pure.
 */
public record Form(Map<String, List<String>> values) {

    public static final Form EMPTY = new Form(Map.of());

    public Form {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        values = java.util.Collections.unmodifiableMap(copy);
    }

    /** The pairs of {@code text}; a pair without {@code =} is a name with an empty value. */
    public static Form parse(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return new Form(values);
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw HttpProblem.badRequest("bad escape in '" + text + "'");
        }
    }

    /** The first value of {@code name}. */
    public Optional<String> first(String name) {
        List<String> all = values.get(name);
        return all == null || all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every value of {@code name}, in order; empty when there is none. */
    public List<String> all(String name) {
        return values.getOrDefault(name, List.of());
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }
}
