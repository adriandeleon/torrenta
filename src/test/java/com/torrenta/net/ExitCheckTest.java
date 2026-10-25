package com.torrenta.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;
import com.torrenta.net.ExitCheck.Exits;
import com.torrenta.net.LeakCheck.Exit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitCheckTest {

    @Test
    void onlyABareAddressInAnOkIsAnAnswer() {
        assertEquals(
                Exit.seen("198.51.100.7"), ExitCheck.answer(200, "198.51.100.7\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                Exit.seen("2001:db8::7"),
                ExitCheck.answer(200, "2001:db8:0:0:0:0:0:7".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                Exit.failed("the server answered 503"),
                ExitCheck.answer(503, "198.51.100.7".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                Exit.failed("the server's answer is not an address"),
                ExitCheck.answer(200, "<html>your ip is 198.51.100.7</html>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ExitCheck.answer(200, new byte[200]).known(), "too long to be an address");
        assertFalse(
                ExitCheck.answer(200, "example.org".getBytes(StandardCharsets.UTF_8))
                        .known(),
                "never resolved");
    }

    @Test
    void theBoundRequestLeavesFromTheBindingsAddressIpv4First() throws Exception {
        InterfaceWatch.State tun = InterfaceWatch.of(
                "tun0", List.of(new InterfaceWatch.Nic("tun0", true, List.of("fe80::1", "2001:db8::2", "10.8.0.2"))));
        assertEquals(Optional.of(InetAddress.ofLiteral("10.8.0.2")), ExitCheck.localAddress(tun));
        InterfaceWatch.State v6 =
                InterfaceWatch.of("tun0", List.of(new InterfaceWatch.Nic("tun0", true, List.of("2001:db8::2"))));
        assertEquals(Optional.of(InetAddress.ofLiteral("2001:db8::2")), ExitCheck.localAddress(v6));
        InterfaceWatch.State byAddress = InterfaceWatch.of(
                "10.8.0.2", List.of(new InterfaceWatch.Nic("tun0", true, List.of("10.8.0.6", "10.8.0.2"))));
        assertEquals(
                Optional.of(InetAddress.ofLiteral("10.8.0.2")),
                ExitCheck.localAddress(byAddress),
                "the binding itself");
        assertEquals(Optional.empty(), ExitCheck.localAddress(InterfaceWatch.of("tun0", List.of())));
    }

    @Test
    void itIsAskedAgainAfterTenMinutesOrWhenSomethingMoved() {
        Exits last = new Exits(Exit.seen("198.51.100.7"), Exit.seen("203.0.113.20"), 1_000);
        assertTrue(ExitCheck.due(Exits.NONE, 1_000, false), "never asked");
        assertFalse(ExitCheck.due(last, 1_000 + ExitCheck.INTERVAL_SECONDS - 1, false));
        assertTrue(ExitCheck.due(last, 1_000 + ExitCheck.INTERVAL_SECONDS, false));
        assertTrue(ExitCheck.due(last, 1_001, true));
    }

    @Test
    void aUrlThatIsNotHttpIsRefusedBeforeAnythingIsSent() {
        assertEquals(
                Exit.failed("ftp://example.org is not an http or https URL"),
                ExitCheck.ask("ftp://example.org", Optional.empty()));
        assertFalse(ExitCheck.ask("not a url at all", Optional.empty()).known());
    }

    /**
     * A server that answers with the address the request came from: the bound request leaves from
     * the address it was given. Linux routes all of 127/8 to loopback; macOS has 127.0.0.1 alone.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theBoundRequestReallyLeavesFromTheBoundAddress() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body =
                    exchange.getRemoteAddress().getAddress().getHostAddress().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            InterfaceWatch.State lo =
                    InterfaceWatch.of("lo", List.of(new InterfaceWatch.Nic("lo", true, List.of("127.0.0.2"))));
            Exits exits = ExitCheck.ask(url, ExitCheck.localAddress(lo), 42);
            assertEquals(Exit.seen("127.0.0.2"), exits.bound());
            assertEquals(Exit.seen("127.0.0.1"), exits.around());
            assertEquals(42, exits.at());
        } finally {
            server.stop(0);
        }
    }
}
