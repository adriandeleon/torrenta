package com.torrenta.migrate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader's rules without an engine: where Transmission keeps things, and what it wrote. */
class TransmissionReaderTest {

    @TempDir
    Path dir;

    @Test
    void theFoldersLookedInAreEachPlatformsOwn() {
        Path home = Path.of("/home/me");
        assertEquals(
                List.of(Path.of("C:\\Users\\me\\AppData\\Local", "transmission")),
                TransmissionReader.knownLocations(
                        "Windows 11", home, Map.of("LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local")));
        assertEquals(List.of(), TransmissionReader.knownLocations("Windows 11", home, Map.of()));
        assertEquals(
                List.of(home.resolve("Library/Application Support/Transmission")),
                TransmissionReader.knownLocations("Mac OS X", home, Map.of()));
        assertEquals(
                List.of(
                        home.resolve(".config/transmission-daemon"),
                        home.resolve(".config/transmission"),
                        home.resolve(".var/app/com.transmissionbt.Transmission/config/transmission")),
                TransmissionReader.knownLocations("Linux", home, Map.of()));
        assertEquals(
                Path.of("/xdg/transmission-daemon"),
                TransmissionReader.knownLocations("Linux", home, Map.of("XDG_CONFIG_HOME", "/xdg"))
                        .getFirst());
    }

    @Test
    void theRpcPortIsReadUnlessRemoteControlIsOff() throws IOException {
        assertEquals(9091, TransmissionReader.rpcPort("{\"rpc-port\": 9091, \"x\": {\"y\": [1]}}"));
        assertEquals(0, TransmissionReader.rpcPort("{\"rpc-port\": 9091, \"rpc-enabled\": false}"));
        assertEquals(0, TransmissionReader.rpcPort("[9091]"));
        assertEquals(0, TransmissionReader.rpcPort("{\"rpc-port\": \"9091\"}"));
    }

    @Test
    void aTransmissionIsRunningWhenItsRpcPortAnswers() throws IOException {
        assertFalse(TransmissionReader.running(dir), "no settings.json");
        Files.writeString(dir.resolve("settings.json"), "{\"rpc-enabled\": false, \"rpc-port\": 1}");
        assertFalse(TransmissionReader.running(dir));
        Files.writeString(dir.resolve("settings.json"), "{not json");
        assertFalse(TransmissionReader.running(dir));
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Files.writeString(dir.resolve("settings.json"), "{\"rpc-port\": " + listening.getLocalPort() + "}");
            assertTrue(TransmissionReader.running(dir));
        }
        assertFalse(TransmissionReader.running(dir), "the port no longer answers");
    }

    @Test
    void aFolderWithoutResumeFilesHasNothingAndABadOneIsSaidByName() throws IOException {
        assertEquals(List.of(), TransmissionReader.read(dir).candidates());
        Files.createDirectories(dir.resolve("resume"));
        Files.createDirectories(dir.resolve("torrents"));
        Files.writeString(dir.resolve("resume/broken.resume"), "not bencode");
        Files.writeString(dir.resolve("resume/nolink.resume"), "d4:name1:xe");
        Files.writeString(dir.resolve("resume/ignored.txt"), "");
        TransmissionReader.Found found = TransmissionReader.read(dir);
        assertEquals(List.of(), found.candidates());
        assertEquals(2, found.unreadable().size(), found.unreadable().toString());
        assertTrue(found.unreadable().getFirst().startsWith("broken.resume"));
    }

    private static byte[] resume(Map<String, Object> fields) {
        return BencodeWriter.encode(fields);
    }

    @Test
    void aMagnetsResumeKeepsItsLabelsRatioAndTrackers() throws IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "Film".getBytes(StandardCharsets.UTF_8));
        r.put("destination", "/srv/dl".getBytes(StandardCharsets.UTF_8));
        r.put("paused", 1L);
        r.put("labels", List.of(bytes("category:Films"), bytes("category:Second"), bytes("hd"), bytes(" ")));
        r.put("ratio-limit", Map.of("ratio-mode", 1L, "ratio-limit", bytes("1.5")));
        String hash = "ab".repeat(20);
        ImportCandidate c = TransmissionReader.candidate(
                resume(r), null, "magnet:?xt=urn:btih:" + hash + "&tr=udp%3A%2F%2Ft.example%3A80&dn=Film");
        assertEquals(hash, c.id());
        assertEquals("Films", c.category(), "the first category label wins");
        assertEquals(List.of("category:Second", "hd"), c.tags(), "a second category is a tag");
        assertTrue(c.stopped());
        assertEquals(1.5, c.ratioLimit(), 1e-9);
        assertEquals("/srv/dl", c.savePath());

        r.put("ratio-limit", Map.of("ratio-mode", 2L));
        assertEquals(
                -1,
                TransmissionReader.candidate(resume(r), null, "magnet:?xt=urn:btih:" + hash)
                        .ratioLimit());
        r.put("ratio-limit", Map.of("ratio-mode", 1L, "ratio-limit", bytes("lots")));
        assertEquals(
                -2,
                TransmissionReader.candidate(resume(r), null, "magnet:?xt=urn:btih:" + hash)
                        .ratioLimit());
        r.remove("name");
        assertEquals(
                hash,
                TransmissionReader.candidate(resume(r), null, "magnet:?xt=urn:btih:" + hash)
                        .name());
        assertThrows(IOException.class, () -> TransmissionReader.candidate(resume(r), null, null));
        assertThrows(IOException.class, () -> TransmissionReader.candidate(bytes("i1e"), null, null));
    }

    @Test
    void piecesComeFromABitfieldWhenThereAreNoBlocks() {
        // 3.x without blocks: a pieces bitfield, first piece in the high bit.
        boolean[] have =
                TransmissionReader.have(Map.of("pieces", new byte[] {(byte) 0b1010_0000}), 4 * 16384, 16384, 4);
        assertEquals(List.of(true, false, true, false), list(have));
        boolean[] none = TransmissionReader.have(Map.of("pieces", bytes("none")), 4 * 16384, 16384, 4);
        assertEquals(List.of(false, false, false, false), list(none));
    }

    private static List<Boolean> list(boolean[] b) {
        List<Boolean> out = new java.util.ArrayList<>();
        for (boolean x : b) out.add(x);
        return out;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
