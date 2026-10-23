package com.torrenta.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentStorageTest {

    @Test
    void emptyMeansNothingOwed() {
        assertTrue(TorrentStorage.NONE.isEmpty());
        assertFalse(new TorrentStorage("/x", false, Set.of()).isEmpty(), "a move is owed");
        assertFalse(new TorrentStorage("", true, Set.of()).isEmpty(), "the suffix is wanted");
        assertFalse(new TorrentStorage("", false, Set.of(1)).isEmpty(), "a name is to be taken back");
        assertTrue(new TorrentStorage("  ", false, null).isEmpty(), "trimmed, null is none");
    }

    @Test
    void withersKeepTheRest() {
        TorrentStorage owed = new TorrentStorage("/x", true, Set.of(0, 2));
        assertEquals(new TorrentStorage("", true, Set.of(0, 2)), owed.withDestination(""));
        assertEquals(new TorrentStorage("/x", true, Set.of(2)), owed.withSuffixed(Set.of(2)));
    }

    @Test
    void theSuffixGoesOnAndOff() {
        assertEquals("pair/a.bin.part", TorrentStorage.suffixed("pair/a.bin"));
        assertEquals("pair/a.bin", TorrentStorage.unsuffixed("pair/a.bin.part"));
        assertEquals("pair/a.bin", TorrentStorage.unsuffixed("pair/a.bin"), "nothing to take off");
        assertEquals("x.part", TorrentStorage.unsuffixed("x.part.part"), "a file called .part keeps its name");
    }

    @Test
    void encodeDecodeRoundTrip() throws IOException {
        TorrentStorage owed = new TorrentStorage("/data/iso", true, Set.of(3, 0));
        String json = new String(owed.encode(), StandardCharsets.UTF_8);
        assertEquals("{\"destination\":\"/data/iso\",\"partSuffix\":true,\"suffixed\":[0,3]}", json, "indices sorted");
        assertEquals(owed, TorrentStorage.decode(owed.encode()));
        assertEquals(TorrentStorage.NONE, TorrentStorage.decode(TorrentStorage.NONE.encode()));
    }

    @Test
    void decodeIsLenientAboutExtrasAndStrictAboutShape() throws IOException {
        TorrentStorage owed = TorrentStorage.decode(
                "{\"destination\":\"/d\",\"later\":{\"a\":[1]},\"suffixed\":[1,\"x\",2],\"partSuffix\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(new TorrentStorage("/d", true, Set.of(1, 2)), owed);
        assertThrows(IOException.class, () -> TorrentStorage.decode("[]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IOException.class, () -> TorrentStorage.decode("{\"suffixed\":[1".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> TorrentStorage.decode("nope".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void namesAreKeptWithTheSuffixOnTopAndAnOwnNameIsNoRename() throws java.io.IOException {
        java.util.Map<Integer, String> originals = java.util.Map.of(0, "pair/a.bin", 1, "pair/b.bin");
        TorrentStorage owed = new TorrentStorage("", true, Set.of(1))
                .withRenamed(java.util.Map.of(0, "pair/x.bin", 1, "pair/y.bin"), originals);
        assertEquals("pair/x.bin", owed.name(0, "pair/a.bin"));
        assertEquals("pair/y.bin.part", owed.onDisk(1, "pair/b.bin"));
        assertEquals(owed, TorrentStorage.decode(owed.encode()));
        TorrentStorage back = owed.withRenamed(java.util.Map.of(0, "pair/a.bin"), originals);
        assertEquals(java.util.Map.of(1, "pair/y.bin"), back.renamed(), "its own name again");
        assertFalse(new TorrentStorage("", false, Set.of(), java.util.Map.of(0, "x")).isEmpty(), "a name to keep");
    }
}
