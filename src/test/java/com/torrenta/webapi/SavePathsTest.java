package com.torrenta.webapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Anyone with the password could otherwise write anywhere this account can. */
class SavePathsTest {

    @TempDir
    Path dir;

    private Path root() throws IOException {
        return Files.createDirectories(dir.resolve("downloads")).toRealPath();
    }

    @Test
    void aFolderInsideARootIsTakenAsItsRealPath() throws IOException {
        Path root = root();
        assertEquals(root, SavePaths.resolve("", root, List.of(root)).path().orElseThrow(), "blank: the default");
        assertEquals(
                root.resolve("tv"),
                SavePaths.resolve("tv", root, List.of(root)).path().orElseThrow(),
                "relative: under the default, as qBittorrent takes it");
        assertEquals(
                root.resolve("new/deeper"),
                SavePaths.resolve(root.resolve("new/deeper").toString(), root, List.of(root))
                        .path()
                        .orElseThrow(),
                "a folder that does not exist yet");
    }

    @Test
    void anythingElseIsRefusedWithTheReason() throws IOException {
        Path root = root();
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        SavePaths.Decision out = SavePaths.resolve(elsewhere.toString(), root, List.of(root));
        assertTrue(out.path().isEmpty());
        assertTrue(out.problem().contains(elsewhere.toString()), out.problem());
        assertTrue(SavePaths.resolve("../elsewhere", root, List.of(root)).path().isEmpty(), "climbing out");
        assertTrue(
                SavePaths.resolve(root + "/tv/../../elsewhere", root, List.of(root))
                        .path()
                        .isEmpty(),
                "climbing out through a folder that is not there");
        assertTrue(
                SavePaths.resolve(dir.resolve("downloads-but-not").toString(), root, List.of(root))
                        .path()
                        .isEmpty(),
                "a sibling whose name starts the same is not inside");
        assertTrue(SavePaths.resolve(root.toString(), root, List.of()).path().isEmpty(), "no roots, no writes");
    }

    /** A link inside a root that points out of it leads out. */
    @Test
    void aLinkOutOfARootIsOut() throws IOException {
        Path root = root();
        Path secret = Files.createDirectories(dir.resolve("secret"));
        Path link;
        try {
            link = Files.createSymbolicLink(root.resolve("shortcut"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
            return;
        }
        assertTrue(
                SavePaths.resolve(link.toString(), root, List.of(root)).path().isEmpty());
        assertTrue(
                SavePaths.resolve("shortcut/below", root, List.of(root)).path().isEmpty());
        assertFalse(SavePaths.inside(link.resolve("x"), List.of(root)));
        assertTrue(SavePaths.inside(secret, List.of(root, secret)), "unless the target is a root too");
    }
}
