package com.torrenta.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlocklistFetchTest {

    private static final byte[] LIST = "Org:1.2.3.0-1.2.3.255\n".getBytes();

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        byte[] gz;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(LIST);
            out.finish();
            gz = bytes.toByteArray();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plain", exchange -> respond(exchange, LIST));
        server.createContext("/list", exchange -> respond(exchange, gz)); // gzip without a .gz name
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void plainAndGzippedComeBackAsText() throws IOException {
        assertArrayEquals(LIST, new BlocklistFetch().fetch(base.resolve("/plain")));
        assertArrayEquals(LIST, new BlocklistFetch().fetch(base.resolve("/list")), "gunzipped by magic number");
    }

    @Test
    void errorsAreExceptions() {
        assertThrows(IOException.class, () -> new BlocklistFetch().fetch(base.resolve("/missing")));
    }
}
