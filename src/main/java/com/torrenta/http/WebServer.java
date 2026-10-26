package com.torrenta.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The JDK's own HTTP server ({@code jdk.httpserver}) with a virtual thread per request, in front of
 * a {@link Router}: HTTP, or HTTPS from a {@link Tls} context. Every request's body is read whole
 * under {@link Limits} before a handler runs (a {@code Content-Length} past the limit is refused
 * without reading it); a {@link HttpProblem} is its status, anything else a handler throws is 500
 * and logged. A file response honours {@code Range} ({@link Ranges}).
 */
public final class WebServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());

    private final HttpServer server;
    private final ExecutorService executor;
    private final boolean secure;

    private WebServer(HttpServer server, ExecutorService executor, boolean secure) {
        this.server = server;
        this.executor = executor;
        this.secure = secure;
    }

    /**
     * Listens on {@code address} (port 0 for any free one) and serves {@code router} until {@link
     * #close}; HTTPS when {@code tls} is given, HTTP otherwise.
     */
    public static WebServer start(InetSocketAddress address, Router router, Limits limits, SSLContext tls)
            throws IOException {
        HttpServer server;
        if (tls != null) {
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls));
            server = https;
        } else {
            server = HttpServer.create(address, 0);
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        boolean secure = tls != null;
        server.createContext("/", exchange -> serve(exchange, router, limits, secure));
        server.start();
        return new WebServer(server, executor, secure);
    }

    /** Where it listens: the port it was given, or the one it found for port 0. */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    public boolean secure() {
        return secure;
    }

    /** Stops taking requests and lets those in flight finish, a second at most. */
    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }

    private static void serve(HttpExchange exchange, Router router, Limits limits, boolean secure) {
        try (exchange) {
            Response response;
            try {
                response = router.dispatch(request(exchange, limits, secure));
            } catch (HttpProblem problem) {
                response = Response.text(problem.status(), problem.getMessage());
            } catch (Exception e) {
                LOG.log(
                        Level.WARNING,
                        "A request failed: " + exchange.getRequestMethod() + " " + exchange.getRequestURI(),
                        e);
                response = Response.text(500, "Internal Server Error");
            }
            write(exchange, response);
        } catch (IOException e) {
            // The client went away mid-answer; nothing to tell it.
            LOG.log(Level.FINE, "A response was not delivered", e);
        }
    }

    /** The exchange as a {@link Request}, its body read under the limit. */
    static Request request(HttpExchange exchange, Limits limits, boolean secure) throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > limits.maxBody())
                    throw HttpProblem.tooLarge("a body over " + limits.maxBody() + " bytes");
            } catch (NumberFormatException e) {
                throw HttpProblem.badRequest("Content-Length is not a number");
            }
        }
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limits.maxBody() + 1));
        }
        if (body.length > limits.maxBody()) throw HttpProblem.tooLarge("a body over " + limits.maxBody() + " bytes");
        return new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                Form.parse(exchange.getRequestURI().getRawQuery()),
                headers,
                exchange.getRemoteAddress(),
                body,
                limits,
                secure);
    }

    private static void write(HttpExchange exchange, Response response) throws IOException {
        for (Map.Entry<String, String> h : response.headers())
            exchange.getResponseHeaders().add(h.getKey(), h.getValue());
        if (response.file() != null) {
            writeFile(exchange, response);
            return;
        }
        if (response.ranged() != null) {
            writeRanged(exchange, response);
            return;
        }
        byte[] body = response.body();
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * A {@link Response.Ranged} body, whole or the part {@code Range} asks for, as a file is; HEAD
     * has the headers alone. A write that throws leaves the response short, and the exchange is
     * closed by the caller.
     */
    private static void writeRanged(HttpExchange exchange, Response response) throws IOException {
        Response.Ranged body = response.ranged();
        long size = body.size();
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        Ranges.Plan plan = Ranges.plan(exchange.getRequestHeaders().getFirst("Range"), size);
        long first = 0;
        long length = size;
        int status = response.status();
        switch (plan) {
            case Ranges.Whole w -> {}
            case Ranges.Part p -> {
                first = p.first();
                length = p.length();
                status = 206;
                exchange.getResponseHeaders().set("Content-Range", p.contentRange(size));
            }
            case Ranges.Unsatisfiable u -> {
                exchange.getResponseHeaders().set("Content-Range", u.contentRange(size));
                exchange.sendResponseHeaders(416, -1);
                return;
            }
        }
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, length == 0 ? -1 : length);
        if (length == 0) return;
        try (OutputStream out = exchange.getResponseBody()) {
            body.write(first, length, out);
        }
    }

    /** A file, whole or the part {@code Range} asks for; gone is 404, a range past it 416. */
    private static void writeFile(HttpExchange exchange, Response response) throws IOException {
        if (!Files.isRegularFile(response.file())) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            byte[] gone = "Not Found".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, gone.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(gone);
            }
            return;
        }
        long size = Files.size(response.file());
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        Ranges.Plan plan = Ranges.plan(exchange.getRequestHeaders().getFirst("Range"), size);
        long first = 0;
        long length = size;
        int status = response.status();
        switch (plan) {
            case Ranges.Whole w -> {}
            case Ranges.Part p -> {
                first = p.first();
                length = p.length();
                status = 206;
                exchange.getResponseHeaders().set("Content-Range", p.contentRange(size));
            }
            case Ranges.Unsatisfiable u -> {
                exchange.getResponseHeaders().set("Content-Range", u.contentRange(size));
                exchange.sendResponseHeaders(416, -1);
                return;
            }
        }
        exchange.sendResponseHeaders(status, length == 0 ? -1 : length);
        if (length == 0) return;
        try (FileChannel channel = FileChannel.open(response.file(), StandardOpenOption.READ);
                OutputStream out = exchange.getResponseBody()) {
            channel.position(first);
            InputStream in = Channels.newInputStream(channel);
            byte[] buffer = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) break;
                out.write(buffer, 0, n);
                left -= n;
            }
        }
    }
}
