package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentPreview.FileEntry;
import com.torrenta.i18n.Messages;
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
                0,
                true,
                false);
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
                "id",
                "payload.bin",
                7,
                List.of(new FileEntry(0, "payload.bin", 7)),
                false,
                List.of(),
                "",
                "",
                0,
                true,
                false);
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
        assertEquals(2, MagnetNames.trackers("magnet:?xt=urn:btih:abc&tr=a&tr=b&tr=a"), "distinct");
        assertEquals(0, MagnetNames.trackers("magnet:?xt=urn:btih:abc"));
        assertEquals(0, MagnetNames.trackers("magnet:"));
    }

    @Test
    void plusAndMinusClimbTheLadderAndSkipIsTheBottomRung() {
        FileTree.Choice normal = new FileTree.Choice(true, Priority.NORMAL);
        assertEquals(new FileTree.Choice(true, Priority.HIGH), FileTree.step(normal, +1));
        assertEquals(new FileTree.Choice(true, Priority.HIGH), FileTree.step(FileTree.step(normal, +1), +1), "top");
        FileTree.Choice low = FileTree.step(normal, -1);
        assertEquals(new FileTree.Choice(true, Priority.LOW), low);
        FileTree.Choice skip = FileTree.step(low, -1);
        assertEquals("Skip", skip.label());
        assertFalse(skip.wanted(), "Skip clears the box");
        assertEquals(skip, FileTree.step(skip, -1), "bottom");
        assertEquals(new FileTree.Choice(true, Priority.LOW), FileTree.step(skip, +1), "and + ticks it again");
        assertEquals(
                new FileTree.Choice(true, Priority.LOW),
                FileTree.step(new FileTree.Choice(false, Priority.HIGH), +1),
                "an unticked file is on the bottom rung whatever is underneath");
    }

    @Test
    void foldersStepTogetherAndSayMixedWhenTheirFilesDisagree() {
        FileTree.Node root = FileTree.build(threeFiles());
        FileTree.Node sub = root.children().get(1);
        FileTree.Node b = sub.children().get(0);
        FileTree.step(b, +1);
        assertEquals("High", FileTree.label(b));
        assertEquals(Messages.tr("dialog.add.priority.mixed"), FileTree.label(sub));
        assertEquals(Messages.tr("dialog.add.priority.mixed"), FileTree.label(root));

        FileTree.step(sub, -1);
        assertEquals("Normal", FileTree.label(sub), "a folder steps from its highest file, and all follow");
        b.wantedProperty().set(false);
        assertEquals("Skip", FileTree.label(b), "unticked reads Skip");

        assertEquals(120, FileTree.wantedSize(root), "the unticked file's 30 bytes are not wanted");
        FileTree.step(root.children().get(0), +1);
        assertEquals(
                java.util.Map.of(0, Priority.HIGH, 2, Priority.IGNORE),
                FileTree.priorities(root),
                "only what is not plain Normal, by the torrent's own index");
    }

    private static TorrentPreview threeFiles() {
        return new TorrentPreview(
                "id",
                "pair",
                150,
                List.of(
                        new FileEntry(0, "pair/a.bin", 100),
                        new FileEntry(2, "pair/sub/b.bin", 30),
                        new FileEntry(3, "pair/sub/c.bin", 20)),
                false,
                List.of(),
                "",
                "",
                0,
                true,
                false);
    }
}
