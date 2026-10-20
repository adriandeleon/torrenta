package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentPreview.FileEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTreeTest {

    @Test
    void multiFileTorrentBecomesFoldersAndFiles() {
        TorrentPreview preview = new TorrentPreview(
                "id",
                "pair",
                150,
                List.of(
                        new FileEntry(0, "pair/a.bin", 100),
                        new FileEntry(2, "pair/sub/b.bin", 30),
                        new FileEntry(3, "pair/sub/deeper/c.bin", 20)),
                false,
                List.of(),
                "",
                "",
                0);
        FileTree.Node root = FileTree.build(preview);
        assertEquals("pair", root.name());
        assertFalse(root.isFile());
        assertEquals(150, root.size(), "a folder's size is its files' total");
        assertEquals(
                List.of("a.bin", "sub"),
                root.children().stream().map(FileTree.Node::name).toList());
        FileTree.Node sub = root.children().get(1);
        assertEquals(50, sub.size());
        assertEquals(
                List.of("b.bin", "deeper"),
                sub.children().stream().map(FileTree.Node::name).toList());
        assertEquals(
                List.of(0, 2, 3),
                FileTree.files(root).stream().map(FileTree.Node::index).toList(),
                "torrent indices, with the gap");
    }

    @Test
    void singleFileTorrentIsOneFileAtTheRoot() {
        TorrentPreview preview = new TorrentPreview(
                "id", "payload.bin", 7, List.of(new FileEntry(0, "payload.bin", 7)), false, List.of(), "", "", 0);
        FileTree.Node root = FileTree.build(preview);
        assertEquals(1, root.children().size());
        assertTrue(root.children().get(0).isFile());
        assertEquals("payload.bin", root.children().get(0).name());
        assertFalse(preview.hasRootFolder());
    }

    @Test
    void magnetNames() {
        assertEquals("Torrenta test", MagnetNames.displayName("magnet:?xt=urn:btih:abc&dn=Torrenta%20test&tr=x"));
        assertEquals("", MagnetNames.displayName("magnet:?xt=urn:btih:abc"));
        assertEquals("", MagnetNames.displayName("magnet:"));
    }

    @Test
    void freeSpaceOfAPathThatDoesNotExistYetUsesItsNearestParent() {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        assertTrue(FreeSpace.of(tmp).isPresent());
        assertTrue(FreeSpace.of(tmp.resolve("torrenta-does-not-exist").resolve("deeper"))
                .isPresent());
        assertEquals(
                FreeSpace.of(tmp).getAsLong() / (1L << 30),
                FreeSpace.of(tmp.resolve("nope")).getAsLong() / (1L << 30),
                "same volume, to the GiB");
    }
}
