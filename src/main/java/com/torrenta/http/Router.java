package com.torrenta.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Which handler answers which request: an exact path first (qBittorrent's API is all exact paths),
 * else the longest prefix (a folder of static files), each for its methods. A path that some
 * route has but not for this method is 405 with {@code Allow}; one no route has is 404. Filters
 * run before any route, in order, and may answer instead (authentication, the {@code Host} check).
 */
public final class Router {

    /** One route's work. */
    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    /** Runs before the routes; an answer here is the answer, empty lets the request through. */
    @FunctionalInterface
    public interface Filter {
        Optional<Response> before(Request request) throws Exception;
    }

    private final Map<String, Map<String, Handler>> exact = new LinkedHashMap<>();
    private final Map<String, Map<String, Handler>> prefixes = new LinkedHashMap<>();
    private final List<Filter> filters = new ArrayList<>();

    public Router route(String method, String path, Handler handler) {
        exact.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(method.toUpperCase(java.util.Locale.ROOT), handler);
        return this;
    }

    /** Everything under {@code prefix} (which ends in {@code /}), for {@code method}. */
    public Router prefix(String method, String prefix, Handler handler) {
        if (!prefix.endsWith("/")) throw new IllegalArgumentException("a prefix ends in /: " + prefix);
        prefixes.computeIfAbsent(prefix, k -> new LinkedHashMap<>())
                .put(method.toUpperCase(java.util.Locale.ROOT), handler);
        return this;
    }

    public Router filter(Filter filter) {
        filters.add(filter);
        return this;
    }

    /** The answer to {@code request}: a filter's, a route's, 405 or 404. */
    public Response dispatch(Request request) throws Exception {
        for (Filter f : filters) {
            Optional<Response> answer = f.before(request);
            if (answer.isPresent()) return answer.get();
        }
        Map<String, Handler> byMethod = exact.get(request.path());
        if (byMethod == null) byMethod = longestPrefix(request.path());
        if (byMethod == null) return Response.text(404, "Not Found");
        Handler handler = byMethod.get(request.method());
        if (handler == null)
            return Response.text(405, "Method Not Allowed")
                    .withHeader("Allow", String.join(", ", new TreeSet<>(byMethod.keySet())));
        return handler.handle(request);
    }

    private @Nullable Map<String, Handler> longestPrefix(String path) {
        String best = null;
        for (String prefix : prefixes.keySet())
            if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) best = prefix;
        return best == null ? null : prefixes.get(best);
    }
}
