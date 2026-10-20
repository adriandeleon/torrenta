package com.torrenta.update;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP half, against a local server: what GitHub answers is turned into one of three outcomes. */
class UpdateServiceTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", exchange -> respond(exchange, 200, """
                {"tag_name": "v0.9.0", "name": "Torrenta 0.9.0", "html_url": "https://example.invalid/r/v0.9.0",
                 "draft": false, "prerelease": false, "body": "\\"tag_name\\": \\"v99.0.0\\" appears in the notes"}
                """));
        server.createContext("/none", exchange -> respond(exchange, 404, "{\"message\":\"Not Found\"}"));
        server.createContext("/broken", exchange -> respond(exchange, 500, "boom"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aNewerReleaseIsFound() {
        UpdateService.Outcome outcome = new UpdateService(base + "/latest").checkNow("0.3.0");
        assertTrue(outcome.available());
        assertEquals("0.9.0", outcome.latest().version(), "from the top-level tag, not the one quoted in the notes");
        assertEquals("https://example.invalid/r/v0.9.0", outcome.latest().url());
        assertNull(outcome.error());
    }

    @Test
    void theSameOrANewerBuildIsUpToDate() {
        assertFalse(new UpdateService(base + "/latest").checkNow("0.9.0").available());
        assertFalse(
                new UpdateService(base + "/latest").checkNow("1.0.0-SNAPSHOT").available());
    }

    @Test
    void noReleasesYetIsNotAnError() {
        UpdateService.Outcome outcome = new UpdateService(base + "/none").checkNow("0.3.0");
        assertFalse(outcome.available());
        assertNull(outcome.error());
    }

    @Test
    void aServerErrorIsReportedNotThrown() {
        UpdateService.Outcome outcome = new UpdateService(base + "/broken").checkNow("0.3.0");
        assertFalse(outcome.available());
        assertNotNull(outcome.error());
        assertTrue(outcome.error().contains("500"), outcome.error());
    }

    @Test
    void offlineIsReportedNotThrown() {
        UpdateService.Outcome outcome = new UpdateService("http://127.0.0.1:1/latest").checkNow("0.3.0");
        assertFalse(outcome.available());
        assertNotNull(outcome.error());
    }
}
