package com.torrenta.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One request as a handler sees it: its method, path and query, its headers (names compared
 * ignoring case), its cookies, where it came from, and its body, read whole and under {@link
 * Limits} before the handler runs. The form and the multipart parts are read from the body on
 * demand, by its content type.
 */
public final class Request {

    private final String method;
    private final String path;
    private final Form query;
    private final Map<String, List<String>> headers;
    private final InetSocketAddress remote;
    private final byte[] body;
    private final Limits limits;
    private final boolean secure;

    /**
     * @param headers by lower-case name
     * @param secure the request came over HTTPS
     */
    public Request(
            String method,
            String path,
            Form query,
            Map<String, List<String>> headers,
            InetSocketAddress remote,
            byte[] body,
            Limits limits,
            boolean secure) {
        this.method = method.toUpperCase(Locale.ROOT);
        this.path = path;
        this.query = query;
        this.headers = Map.copyOf(headers);
        this.remote = remote;
        this.body = body;
        this.limits = limits;
        this.secure = secure;
    }

    public String method() {
        return method;
    }

    /** The path, decoded, without the query. */
    public String path() {
        return path;
    }

    public Form query() {
        return query;
    }

    /** The first value of a header, whatever the case of its name. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    public Map<String, String> cookies() {
        return Cookies.parse(header("cookie").orElse(null));
    }

    public InetSocketAddress remote() {
        return remote;
    }

    public boolean secure() {
        return secure;
    }

    public byte[] body() {
        return body.clone();
    }

    /** The body as a form: an urlencoded one, else the multipart's plain fields; empty for anything else. */
    public Form form() {
        String type = header("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (type.startsWith("application/x-www-form-urlencoded"))
            return Form.parse(new String(body, StandardCharsets.UTF_8));
        if (type.startsWith("multipart/form-data")) {
            Map<String, List<String>> fields = new java.util.LinkedHashMap<>();
            for (Multipart.Part p : parts())
                if (!p.isFile())
                    fields.computeIfAbsent(p.name(), k -> new java.util.ArrayList<>())
                            .add(p.text());
            return new Form(fields);
        }
        return Form.EMPTY;
    }

    /** The body's parts, for a {@code multipart/form-data} body; empty for any other. */
    public List<Multipart.Part> parts() {
        Optional<String> boundary = Multipart.boundary(header("content-type").orElse(null));
        return boundary.map(b -> Multipart.parse(body, b, limits)).orElse(List.of());
    }
}
