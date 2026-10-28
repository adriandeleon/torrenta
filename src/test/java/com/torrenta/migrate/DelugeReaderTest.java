package com.torrenta.migrate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Deluge reader's rules without an engine: its daemon, its config files, a folder that will not read. */
class DelugeReaderTest {

    @TempDir
    Path dir;

    @Test
    void aDelugeIsRunningWhenItsDaemonPortAnswers() throws IOException {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Files.writeString(
                    dir.resolve("core.conf"),
                    "{\"file\": 1, \"format\": 1}{\"daemon_port\": " + listening.getLocalPort()
                            + ", \"other\": [1, 2]}");
            assertTrue(DelugeReader.running(dir));
        }
        assertFalse(DelugeReader.running(dir), "the port no longer answers");
        Files.writeString(dir.resolve("core.conf"), "{\"file\": 1}{\"daemon_port\": 0}");
        assertFalse(DelugeReader.running(dir));
    }

    @Test
    void aConfigFileWithoutItsSecondObjectHasNothingInIt() throws IOException {
        assertEquals(58846, DelugeReader.daemonPort("{\"file\": 1}"), "the default");
        assertEquals(58846, DelugeReader.daemonPort("[]"));
        assertEquals(Map.of(), DelugeReader.labels("{\"file\": 1}"));
        assertEquals(
                Map.of("ab", "films"),
                DelugeReader.labels(
                        "{\"file\": 1}{\"labels\": {}, \"torrent_labels\": {\"AB\": \"films\", \"cd\": \"\","
                                + " \"ef\": 3}}"));
    }

    @Test
    void whatWillNotReadIsSaidFileByFileAndTheRestStillComesOver() throws IOException {
        Path state = Files.createDirectories(dir.resolve("state"));
        Files.writeString(state.resolve("torrents.fastresume"), "not bencode");
        Files.writeString(dir.resolve("label.conf"), "{\"file\": 1}{\"torrent_labels\": ");
        Files.writeString(state.resolve("torrents.state"), "not a pickle");
        DelugeReader.Found found = DelugeReader.read(dir);
        assertEquals(List.of(), found.candidates());
        assertEquals(3, found.unreadable().size(), found.unreadable().toString());
        assertThrows(IOException.class, () -> DelugeReader.torrents("a string"));
    }

    @Test
    void aMagnetWithoutResumeDataStartsFromItsHashAndItsName() throws IOException {
        String hash = "ab".repeat(20);
        ImportCandidate c = DelugeReader.candidate(
                hash,
                null,
                null,
                Map.of(
                        "magnet",
                        "magnet:?xt=urn:btih:" + hash + "&dn=Big%20Film&tr=udp%3A%2F%2Ft%3A1",
                        "paused",
                        true,
                        "stop_at_ratio",
                        true,
                        "stop_ratio",
                        2L),
                "films");
        assertEquals("Big Film", c.name());
        assertTrue(c.stopped());
        assertEquals(2.0, c.ratioLimit(), 1e-9);
        assertEquals("films", c.category());
        ImportCandidate bare = DelugeReader.candidate(hash, null, null, Map.of("stop_at_ratio", false), "");
        assertEquals(hash, bare.name(), "no magnet: the hash names it");
        assertEquals(-1, bare.ratioLimit(), 1e-9, "stop at ratio off: no limit");
        assertThrows(IOException.class, () -> DelugeReader.candidate("short", null, null, Map.of(), ""));
    }
}
