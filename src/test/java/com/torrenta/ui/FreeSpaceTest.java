package com.torrenta.ui;

import java.nio.file.Path;
import java.util.OptionalLong;

import com.torrenta.engine.DownloadPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeSpaceTest {

    @Test
    void measuresTheNearestExistingAncestor(@TempDir Path dir) {
        OptionalLong existing = FreeSpace.of(dir);
        assertTrue(existing.isPresent());
        assertEquals(
                existing.isPresent(),
                FreeSpace.of(dir.resolve("not").resolve("yet")).isPresent());
    }

    @Test
    void theHintSaysWhereTheFilesStartAndEndUp() {
        Path downloads = Path.of("/home/x/Downloads").toAbsolutePath();
        Path incomplete = Path.of("/home/x/Downloads/incomplete").toAbsolutePath();
        DownloadPlan direct = DownloadPlan.of(downloads, false, incomplete);
        assertEquals("1.0 GiB free", FreeSpace.hint(direct, OptionalLong.empty(), OptionalLong.of(1L << 30)));
        assertEquals("", FreeSpace.hint(direct, OptionalLong.empty(), OptionalLong.empty()), "unmeasurable: nothing");

        DownloadPlan via = DownloadPlan.of(downloads, true, incomplete);
        assertEquals(
                "Starts in " + incomplete + " (2.0 GiB free)\nMoved here when complete (1.0 GiB free)",
                FreeSpace.hint(via, OptionalLong.of(2L << 30), OptionalLong.of(1L << 30)));
        assertEquals(
                "Starts in " + incomplete + "\nMoved here when complete",
                FreeSpace.hint(via, OptionalLong.empty(), OptionalLong.empty()));
    }
}
