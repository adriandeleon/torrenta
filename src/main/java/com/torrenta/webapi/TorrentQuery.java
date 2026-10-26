package com.torrenta.webapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.torrenta.engine.Torrent;
import com.torrenta.http.Form;
import org.jspecify.annotations.Nullable;

/**
 * {@code torrents/info}'s parameters applied to the rows, as qBittorrent applies them: {@code
 * filter} (a {@link QbitState.Filter}), {@code category} and {@code tag} (present and empty means
 * none), {@code hashes} ({@code |} between them, or {@code all}), then {@code sort} by any field
 * of the row with {@code reverse}, then {@code offset} (negative counts from the end) and {@code
 * limit}. A parameter that does not read is ignored, as qBittorrent ignores it. Pure.
 */
public final class TorrentQuery {

    private TorrentQuery() {}

    /** A torrent and the row written for it; the hash in the row is the one clients send back. */
    public record Row(Torrent torrent, Map<String, Object> fields) {
        String hash() {
            return String.valueOf(fields.get("hash"));
        }
    }

    public static List<Map<String, Object>> apply(List<Row> rows, Form params) {
        QbitState.Filter filter =
                QbitState.filter(params.first("filter").orElse("all")).orElse(QbitState.Filter.ALL);
        List<Row> kept = new ArrayList<>();
        Set<String> hashes = hashes(params);
        for (Row r : rows) {
            Torrent t = r.torrent();
            if (!filter.matches(t)) continue;
            if (params.has("category")
                    && !t.category().equals(params.first("category").orElse(""))) continue;
            if (params.has("tag")) {
                String tag = params.first("tag").orElse("");
                if (tag.isEmpty() ? !t.tags().isEmpty() : !t.tags().contains(tag)) continue;
            }
            if (hashes != null
                    && !hashes.contains(r.hash().toLowerCase(Locale.ROOT))
                    && !hashes.contains(t.id().toLowerCase(Locale.ROOT))) continue;
            kept.add(r);
        }
        String sort = params.first("sort").orElse("");
        if (!sort.isEmpty()) {
            Comparator<Row> by = Comparator.comparing(r -> r.fields().get(sort), TorrentQuery::compare);
            if (Boolean.parseBoolean(params.first("reverse").orElse("false"))) by = by.reversed();
            kept.sort(by);
        }
        int offset = integer(params, "offset", 0);
        if (offset < 0) offset = Math.max(0, kept.size() + offset);
        int limit = integer(params, "limit", 0);
        List<Row> page = kept.subList(Math.min(offset, kept.size()), kept.size());
        if (limit > 0 && limit < page.size()) page = page.subList(0, limit);
        return page.stream().map(Row::fields).toList();
    }

    /** Lower case; null for every torrent (absent, or {@code all}). */
    static @Nullable Set<String> hashes(Form params) {
        return hashes(params.first("hashes").orElse(""));
    }

    /** {@code a|b} in lower case; null for every torrent (blank, or {@code all}). */
    static @Nullable Set<String> hashes(String value) {
        if (value.isBlank() || value.equalsIgnoreCase("all")) return null;
        return Arrays.stream(value.split("\\|"))
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toSet());
    }

    private static int integer(Form params, String name, int fallback) {
        try {
            return Integer.parseInt(params.first(name).orElse("").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Numbers by value, words ignoring case, false before true; a missing field first. */
    static int compare(Object a, Object b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }
}
