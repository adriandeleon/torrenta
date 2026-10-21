package com.torrenta.os;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceMessageTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void encodeAndParseAreInverses() {
        List<String> inputs = List.of("/home/x/a.torrent", "magnet:?xt=urn:btih:abc&dn=A%20B", "https://x/y.torrent");
        String text = InstanceMessage.encode(TOKEN, inputs);
        assertEquals(TOKEN + "\n/home/x/a.torrent\nmagnet:?xt=urn:btih:abc&dn=A%20B\nhttps://x/y.torrent\n\n", text);
        InstanceMessage message = InstanceMessage.parse(text).orElseThrow();
        assertEquals(TOKEN, message.token());
        assertEquals(inputs, message.inputs());
    }

    @Test
    void noInputsIsJustTheTokenAndAsksForTheWindow() {
        String text = InstanceMessage.encode(TOKEN, List.of());
        assertEquals(TOKEN + "\n\n", text);
        assertEquals(List.of(), InstanceMessage.parse(text).orElseThrow().inputs());
    }

    @Test
    void whatIsNotAMessageIsEmpty() {
        assertEquals(Optional.empty(), InstanceMessage.parse(""), "nothing");
        assertEquals(Optional.empty(), InstanceMessage.parse("\n\n"), "no token");
        assertEquals(Optional.empty(), InstanceMessage.parse(TOKEN + "\na.torrent\n"), "never ended");
        assertEquals(Optional.empty(), InstanceMessage.parse(TOKEN), "a token alone, unfinished");
    }

    @Test
    void windowsLineEndsAreTolerated() {
        InstanceMessage message =
                InstanceMessage.parse(TOKEN + "\r\nC:\\a.torrent\r\n\r\n").orElseThrow();
        assertEquals(List.of("C:\\a.torrent"), message.inputs());
    }

    @Test
    void theCapsStopAFlood() {
        StringBuilder many = new StringBuilder(TOKEN).append('\n');
        for (int i = 0; i <= InstanceMessage.MAX_INPUTS; i++)
            many.append("magnet:?xt=urn:btih:").append(i).append('\n');
        many.append('\n');
        assertEquals(Optional.empty(), InstanceMessage.parse(many.toString()), "one over the count");
        String longLine = "x".repeat(InstanceMessage.MAX_LINE + 1);
        assertEquals(Optional.empty(), InstanceMessage.parse(TOKEN + "\n" + longLine + "\n\n"), "one over the line");
        assertTrue(
                InstanceMessage.parse(TOKEN + "\n" + "x".repeat(InstanceMessage.MAX_LINE) + "\n\n")
                        .isPresent(),
                "at the line cap is fine");
        assertThrows(
                IllegalArgumentException.class,
                () -> InstanceMessage.encode(
                        TOKEN, java.util.Collections.nCopies(InstanceMessage.MAX_INPUTS + 1, "m")));
    }

    @Test
    void anInputWithALineBreakCannotTravel() {
        assertThrows(IllegalArgumentException.class, () -> InstanceMessage.encode(TOKEN, List.of("a\nb")));
        assertThrows(IllegalArgumentException.class, () -> InstanceMessage.encode("tok\nen", List.of()));
        assertThrows(IllegalArgumentException.class, () -> InstanceMessage.encode("", List.of()));
        assertEquals(TOKEN + "\na\n\n", InstanceMessage.encode(TOKEN, List.of("a", "   ")), "a blank input is dropped");
    }

    @Test
    void theTokenIsComparedWhole() {
        InstanceMessage message = InstanceMessage.parse(TOKEN + "\n\n").orElseThrow();
        assertTrue(message.accepts(TOKEN));
        assertFalse(message.accepts(TOKEN.substring(1)));
        assertFalse(message.accepts(TOKEN + "0"));
        assertFalse(message.accepts(""));
        assertFalse(message.accepts(null));
    }

    /** The second instance's shell has its own working directory; the running one has another. */
    @Test
    void fileNamesBecomeAbsoluteAndLinksPassThrough() {
        Path cwd = Path.of("home", "x", "downloads").toAbsolutePath();
        List<String> out = InstanceMessage.absolute(
                List.of(
                        "a.torrent",
                        "../b.torrent",
                        "magnet:?xt=urn:btih:abc",
                        "HTTPS://x/y.torrent",
                        " ",
                        cwd.resolve("c.torrent").toString()),
                cwd);
        assertEquals(
                List.of(
                        cwd.resolve("a.torrent").toString(),
                        cwd.getParent().resolve("b.torrent").toString(),
                        "magnet:?xt=urn:btih:abc",
                        "HTTPS://x/y.torrent",
                        cwd.resolve("c.torrent").toString()),
                out);
    }
}
