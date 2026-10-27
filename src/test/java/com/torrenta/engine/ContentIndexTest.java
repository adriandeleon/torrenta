package com.torrenta.engine;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentIndexTest {

    static final String R1 = "a".repeat(64);
    static final String R2 = "b".repeat(64);

    private static ContentIndex.Entry entry(String root, String torrent, int index, String path, long size) {
        return new ContentIndex.Entry(root, torrent, index, Path.of(path).toAbsolutePath(), size);
    }

    @Test
    void aFileIsFoundByItsRootWhateverTorrentHasIt() {
        ContentIndex index = new ContentIndex();
        index.put("t1", List.of(entry(R1, "t1", 0, "/d/One/a.bin", 10), entry(R2, "t1", 1, "/d/One/b.bin", 20)));
        index.put("t2", List.of(entry(R1.toUpperCase(), "t2", 3, "/d/Two/copy.bin", 10)));
        assertEquals(2, index.find(R1).size(), "the same bytes in two torrents");
        assertEquals("t1", index.find(R1).getFirst().torrentId(), "in the order indexed");
        assertEquals(R1, index.find(R1).get(1).root(), "roots kept in lower case");
        assertEquals(1, index.find(R2.toUpperCase()).size(), "looked up in any case");
        assertEquals(3, index.size());
    }

    @Test
    void aTorrentIndexedAgainReplacesWhatWasKnownAndRemovedIsGone() {
        ContentIndex index = new ContentIndex();
        index.put("t1", List.of(entry(R1, "t1", 0, "/d/One/a.bin", 10)));
        index.put("t1", List.of(entry(R1, "t1", 0, "/d/One/renamed.bin", 10)));
        assertEquals(
                List.of(Path.of("/d/One/renamed.bin").toAbsolutePath()),
                index.find(R1).stream().map(ContentIndex.Entry::path).toList(),
                "a rename: the new path, once");
        index.put("t1", List.of());
        assertTrue(index.find(R1).isEmpty(), "nothing complete any more");
        index.put("t1", List.of(entry(R1, "t1", 0, "/d/One/a.bin", 10)));
        index.remove("t1");
        assertTrue(index.find(R1).isEmpty());
        assertTrue(index.of("t1").isEmpty());
        assertEquals(0, index.size());
    }

    @Test
    void onlyRealRootsOfRealFilesOfThatTorrentAreKept() {
        ContentIndex index = new ContentIndex();
        index.put(
                "t1",
                List.of(
                        entry("", "t1", 0, "/d/a", 10),
                        entry("0".repeat(64), "t1", 1, "/d/b", 10),
                        entry("xyz", "t1", 2, "/d/c", 10),
                        entry(R1, "t1", 3, "/d/empty", 0),
                        entry(R2, "other", 4, "/d/theirs", 10),
                        entry(R1, "t1", 5, "/d/kept", 10)));
        assertEquals(1, index.size());
        assertEquals(5, index.of("t1").getFirst().index());
        assertFalse(ContentIndex.isRoot("0".repeat(64)), "libtorrent's all-zero hash is no root");
        assertTrue(ContentIndex.isRoot(R1));
    }

    @Test
    void theEmptyIndexTakesNothing() {
        ContentIndex.EMPTY.put("t1", List.of(entry(R1, "t1", 0, "/d/a", 10)));
        assertEquals(0, ContentIndex.EMPTY.size());
        assertTrue(ContentIndex.EMPTY.find(R1).isEmpty());
    }
}
