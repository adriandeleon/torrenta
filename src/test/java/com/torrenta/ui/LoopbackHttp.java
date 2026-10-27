package com.torrenta.ui;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/**
 * One answer, the same to every request, on a loopback port: a stand-in Torznab server or
 * Transmission daemon for a window's Test button. Nothing leaves the machine.
 */
final class LoopbackHttp implements AutoCloseable {

    private final HttpServer server;

    LoopbackHttp(int status, String contentType, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /** {@code http://127.0.0.1:<port>}, no trailing slash. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A loopback URL that nothing listens on: this server's, once it has stopped. */
    static String refused() throws IOException {
        try (LoopbackHttp gone = new LoopbackHttp(200, "text/plain", "")) {
            return gone.url();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
