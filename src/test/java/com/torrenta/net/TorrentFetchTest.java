package com.torrenta.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentFetchTest {

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.torrent", exchange -> respond(exchange, 200, "d8:announce3:abce".getBytes()));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok.torrent");
            respond(exchange, 302, new byte[0]);
        });
        server.createContext("/missing", exchange -> respond(exchange, 404, "no".getBytes()));
        server.createContext("/huge", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] chunk = new byte[1 << 20];
                for (int i = 0; i < 11; i++) out.write(chunk);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchesAndFollowsRedirects() throws IOException {
        TorrentFetch fetch = new TorrentFetch();
        assertArrayEquals("d8:announce3:abce".getBytes(), fetch.fetch(base.resolve("/ok.torrent")));
        assertArrayEquals("d8:announce3:abce".getBytes(), fetch.fetch(base.resolve("/moved")));
    }

    @Test
    void errorsSayWhatHappened() {
        TorrentFetch fetch = new TorrentFetch();
        IOException notFound = assertThrows(IOException.class, () -> fetch.fetch(base.resolve("/missing")));
        assertTrue(notFound.getMessage().contains("404"), notFound.getMessage());
        IOException huge = assertThrows(IOException.class, () -> fetch.fetch(base.resolve("/huge")));
        assertTrue(huge.getMessage().contains("10 MiB"), huge.getMessage());
    }
}
