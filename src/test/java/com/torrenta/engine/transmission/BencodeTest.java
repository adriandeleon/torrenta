package com.torrenta.engine.transmission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.torrenta.engine.TorrentPreview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decoder behind {@code inspect} for a daemon that has none, and the small JSON tree beside it. */
class BencodeTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void decodesTheFourTypes() throws Exception {
        Object value = Bencode.decode(b("d1:ai42e4:listl1:x2:yye3:str3:abce"));
        assertTrue(value instanceof Map<?, ?>);
        Map<?, ?> m = (Map<?, ?>) value;
        assertEquals(42L, m.get("a"));
        assertEquals(2, ((List<?>) m.get("list")).size());
        assertArrayEquals(b("abc"), (byte[]) m.get("str"));
        assertEquals(-7L, Bencode.decode(b("i-7e")));
        assertThrows(IOException.class, () -> Bencode.decode(b("d1:a")));
        assertThrows(IOException.class, () -> Bencode.decode(b("i42e trailing")));
        assertThrows(IOException.class, () -> Bencode.decode(b("5:ab")));
        assertThrows(IOException.class, () -> Bencode.decode(b("x")));
        assertThrows(IOException.class, () -> Bencode.decode(b("di1e1:ae")), "keys are strings");
    }

    @Test
    void aMultiFileTorrentGetsItsFilesTrackersAndHashWithPaddingLeftOut() throws Exception {
        String info =
                "d5:filesld6:lengthi100e4:pathl1:a5:a.bineed4:attr1:p6:lengthi28e4:pathl4:.pad2:28eed6:lengthi50e4:pathl3:sub5:b.bineee"
                        + "4:name4:pair12:piece lengthi16384e6:pieces20:" + "0".repeat(20) + "7:privatei1ee";
        String torrent = "d8:announce28:udp://a.example.org/announce13:announce-listll28:udp://a.example.org/announceel"
                + "28:udp://b.example.org/announceee7:comment5:hello10:created by3:tor13:creation datei1700000000e4:info"
                + info + "e";
        TorrentPreview p = Bencode.preview(b(torrent));
        assertEquals("pair", p.name());
        assertEquals(150, p.size(), "padding is not counted");
        assertEquals(2, p.files().size());
        assertEquals(
                new TorrentPreview.FileEntry(0, "pair/a/a.bin", 100), p.files().get(0));
        assertEquals(
                new TorrentPreview.FileEntry(2, "pair/sub/b.bin", 50),
                p.files().get(1),
                "the torrent's own index, with the gap");
        assertEquals(List.of("udp://a.example.org/announce", "udp://b.example.org/announce"), p.trackers());
        assertEquals("hello", p.comment());
        assertEquals("tor", p.createdBy());
        assertEquals(1700000000L, p.creationDate());
        assertTrue(p.isPrivate());
        assertTrue(p.v1());
        assertFalse(p.v2());
        assertTrue(p.hasRootFolder());
        assertEquals(
                Bencode.hex(Bencode.digest("SHA-1", b(info))),
                p.id(),
                "the hash is over the info dictionary's exact bytes");
        assertEquals(40, p.id().length());
    }

    @Test
    void aV2OnlyTorrentWalksTheFileTree() throws Exception {
        String info = "d9:file treed5:a.bind0:d6:lengthi100e11:pieces root32:" + "1".repeat(32) + "eee"
                + "12:meta versioni2e4:name6:single12:piece lengthi16384ee";
        TorrentPreview p = Bencode.preview(b("d4:info" + info + "e"));
        assertTrue(p.v2());
        assertFalse(p.v1());
        assertEquals(1, p.files().size());
        assertEquals("single", p.files().get(0).path(), "one file in the tree carries the torrent's name");
        assertEquals(100, p.size());
        assertEquals(40, p.id().length(), "the v2 hash cut to 20 bytes, as the engine's best hash is");
        assertTrue(p.trackers().isEmpty());
    }

    @Test
    void notATorrentIsSaidSo() {
        assertThrows(IOException.class, () -> Bencode.preview(b("d4:name1:xe")), "no info");
        assertThrows(IOException.class, () -> Bencode.preview(b("d4:infod4:name1:xee")), "no pieces");
        assertThrows(IOException.class, () -> Bencode.preview(b("li1ee")), "a list");
    }

    @Test
    void jsonTreeRoundTripsAndReadsLeniently() throws Exception {
        Object v = Json.parse("{\"a\":1,\"b\":[true,null,2.5,\"s\"],\"c\":{\"d\":\"7\"}}");
        Map<String, Object> m = Json.obj(v);
        assertEquals(1L, Json.lng(m, "a", 0));
        assertEquals(7L, Json.lng(Json.obj(m, "c"), "d", 0), "a number in a string is read");
        assertEquals("1", Json.str(m, "a", ""), "a number asked as text is its text");
        assertEquals("x", Json.str(m, "missing", "x"));
        assertEquals(4, Json.list(m, "b").size());
        assertEquals(List.of("s"), Json.strings(m, "b"));
        assertTrue(Json.bool(Map.of("k", 1L), "k", false));
        assertFalse(Json.bool(m, "missing", false));
        assertEquals(2.5, Json.dbl(Json.obj(Map.of("x", List.of())), "x", 2.5));
        assertEquals("{\"a\":1,\"b\":[true,null,2.5,\"s\"],\"c\":{\"d\":\"7\"}}", Json.write(v));
        assertThrows(IOException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IOException.class, () -> Json.parse(""));
        assertThrows(IOException.class, () -> Json.parse("<html>"));
    }
}
