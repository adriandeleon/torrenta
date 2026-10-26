package com.torrenta.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleTest {

    private static byte[] resource(String name) throws Exception {
        return Files.readAllBytes(java.nio.file.Path.of(
                PickleTest.class.getResource("/com/torrenta/migrate/" + name).toURI()));
    }

    @Test
    void delugeTwosStateIsProtocolTwoObjectsOfPlainValues() throws Exception {
        Object top = Pickle.read(resource("deluge-2.2/state/torrents.state"));
        Pickle.Obj manager = (Pickle.Obj) top;
        assertEquals("deluge.core.torrentmanager.TorrentManagerState", manager.type());
        List<?> torrents = (List<?>) manager.state().get("torrents");
        assertEquals(3, torrents.size());
        Pickle.Obj seed = (Pickle.Obj) torrents.getFirst();
        assertEquals("deluge.core.torrentmanager.TorrentState", seed.type());
        Map<String, Object> s = seed.state();
        assertEquals("7d434420c3804d11c60483ef340365e3766d8e42", s.get("torrent_id"));
        assertEquals(Boolean.FALSE, s.get("paused"));
        assertEquals(-1L, s.get("max_connections"));
        assertEquals(List.of(4L), s.get("file_priorities"));
        assertEquals(2.5, s.get("stop_ratio"));
        assertNull(s.get("magnet"));
        // The memo: later torrents name their keys by reference to the first's.
        Map<String, Object> magnet = ((Pickle.Obj) torrents.get(1)).state();
        assertEquals(s.keySet(), magnet.keySet());
        assertTrue(((String) magnet.get("magnet")).startsWith("magnet:?xt=urn:btih:233640c5"));
        assertEquals(List.of(1L, 0L), ((Pickle.Obj) torrents.get(2)).state().get("file_priorities"));
    }

    @Test
    void delugeOneThreesProtocolZeroReadsTheSame() throws Exception {
        Pickle.Obj manager = (Pickle.Obj) Pickle.read(resource("deluge-1.3-protocol0.state"));
        assertEquals("deluge.core.torrentmanager.TorrentManagerState", manager.type());
        List<?> torrents = (List<?>) manager.state().get("torrents");
        assertEquals(2, torrents.size(), "the same object twice, by the memo");
        Map<String, Object> s = ((Pickle.Obj) torrents.getFirst()).state();
        assertEquals(Boolean.TRUE, s.get("paused"), "I01 is True");
        assertEquals(List.of(1L), s.get("file_priorities"));
        assertEquals(1.5, s.get("stop_ratio"));
        assertEquals(3L, s.get("queue"));
        assertNull(s.get("magnet"));
        assertEquals("Ünïcode name", s.get("name"), "raw-unicode-escape");
        assertEquals(1L << 40, s.get("big"));
    }

    @Test
    void aCallIsANameAndItsArgumentsNeverMade() throws Exception {
        // pickle.dumps of os.system("echo hi") at protocol 0, as an attacker would write it.
        Object v = Pickle.read("cos\nsystem\n(S'echo hi'\ntR.".getBytes(StandardCharsets.US_ASCII));
        Pickle.Obj call = (Pickle.Obj) v;
        assertEquals("os.system", call.type());
        assertEquals(List.of("echo hi"), call.args());
    }

    @Test
    void pythonsQuotedStringsKeepTheirEscapes() throws IOException {
        assertEquals("a'b\né", new String(Pickle.pythonString("'a\\'b\\n\\xc3\\xa9'"), StandardCharsets.UTF_8));
        assertEquals("tab\there", new String(Pickle.pythonString("\"tab\\there\""), StandardCharsets.UTF_8));
        assertEquals("é😀\\x", Pickle.rawUnicodeEscape("é\\U0001f600\\x".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void whatItCannotReadIsRefusedWhole() {
        // A persistent id needs the program that wrote it.
        assertThrows(IOException.class, () -> Pickle.read("P1\n.".getBytes(StandardCharsets.US_ASCII)));
        // A length past the end is refused before anything is allocated.
        assertThrows(IOException.class, () -> Pickle.read(new byte[] {(byte) 0x80, 2, 'X', -1, -1, -1, 0x7f}));
        // No STOP.
        assertThrows(IOException.class, () -> Pickle.read(new byte[] {(byte) 0x80, 2, ']'}));
        // A reference to nothing stored.
        assertThrows(IOException.class, () -> Pickle.read(new byte[] {(byte) 0x80, 2, 'h', 9, '.'}));
        assertThrows(IOException.class, () -> Pickle.read(new byte[0]));
    }
}
