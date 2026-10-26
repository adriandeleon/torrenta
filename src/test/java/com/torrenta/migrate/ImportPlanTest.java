package com.torrenta.migrate;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The verdicts: trust what matches, check what does not, leave alone what would go wrong. */
class ImportPlanTest {

    private static final String SAVE = Path.of("/srv/dl").toAbsolutePath().toString();

    private static ImportCandidate candidate(String id, long have, long pieces, ImportCandidate.FileEntry... files) {
        return new ImportCandidate(
                "qBittorrent",
                id,
                id,
                "",
                new byte[0],
                new byte[] {1},
                SAVE,
                "",
                "",
                List.of(),
                false,
                List.of(files),
                have,
                pieces,
                -2,
                -2);
    }

    /** A disk the test describes: path under the save path → size; absent is -1. */
    private static ImportPlan.FileProbe disk(Map<String, Long> sizes) {
        Map<Path, Long> byPath = new HashMap<>();
        sizes.forEach((k, v) -> byPath.put(Path.of(SAVE).resolve(k), v));
        return p -> byPath.getOrDefault(p, -1L);
    }

    private static ImportPlan.Verdict verdict(ImportCandidate c, ImportPlan.FileProbe disk) {
        return ImportPlan.of(List.of(c), Set.of(), disk, false).getFirst().verdict();
    }

    @Test
    void aCompleteTorrentWhoseFilesAreWholeComesOverAsItIs() {
        ImportCandidate c = candidate(
                "a",
                4,
                4,
                new ImportCandidate.FileEntry("x/1", 100, true),
                new ImportCandidate.FileEntry("x/2", 50, true));
        assertEquals(ImportPlan.Verdict.IMPORT, verdict(c, disk(Map.of("x/1", 100L, "x/2", 50L))));
    }

    /** libtorrent would believe the resume data over a short file: the plan looks first. */
    @Test
    void aFileOfAnotherSizeOrMissingIsChecked() {
        ImportCandidate c = candidate(
                "a",
                4,
                4,
                new ImportCandidate.FileEntry("x/1", 100, true),
                new ImportCandidate.FileEntry("x/2", 50, true));
        assertEquals(ImportPlan.Verdict.CHECK, verdict(c, disk(Map.of("x/1", 99L, "x/2", 50L))), "truncated");
        assertEquals(ImportPlan.Verdict.CHECK, verdict(c, disk(Map.of("x/1", 100L))), "one gone");
        assertEquals(ImportPlan.Verdict.MISSING, verdict(c, disk(Map.of())), "all gone: not imported by default");
        assertFalse(ImportPlan.Verdict.MISSING.importedByDefault());
        assertTrue(ImportPlan.Verdict.CHECK.importedByDefault());
    }

    @Test
    void anIncompleteTorrentNeedsOnlyTheFilesItHasPiecesOf() {
        ImportCandidate c = candidate(
                "a",
                2,
                4,
                new ImportCandidate.FileEntry("x/1", 100, true),
                new ImportCandidate.FileEntry("x/2", 100, false));
        assertEquals(ImportPlan.Verdict.IMPORT, verdict(c, disk(Map.of("x/1", 100L))), "the second not begun");
        assertEquals(ImportPlan.Verdict.IMPORT, verdict(c, disk(Map.of("x/1", 60L))), "sparse, shorter: fine");
        assertEquals(ImportPlan.Verdict.CHECK, verdict(c, disk(Map.of("x/1", 160L))), "longer than it can be");
        assertEquals(ImportPlan.Verdict.MISSING, verdict(c, disk(Map.of())));
    }

    @Test
    void whatIsHereAlreadyMagnetsAndNothingYet() {
        ImportCandidate fresh = candidate("a", 0, 4, new ImportCandidate.FileEntry("x", 100, false));
        assertEquals(ImportPlan.Verdict.IMPORT, verdict(fresh, disk(Map.of())), "nothing to find yet");
        ImportCandidate magnet = new ImportCandidate(
                "qBittorrent", "m", "m", "", new byte[0], null, SAVE, "", "", List.of(), true, List.of(), 0, 0, -2, -2);
        assertEquals(ImportPlan.Verdict.MAGNET, verdict(magnet, disk(Map.of())));
        assertEquals(
                ImportPlan.Verdict.ALREADY_HERE,
                ImportPlan.of(List.of(fresh), Set.of("a"), disk(Map.of()), false)
                        .getFirst()
                        .verdict());
    }

    /** Two clients writing one file is corruption; two reading one is not. */
    @Test
    void whileTheOtherClientRunsOnlyCompleteTorrentsComeOver() {
        ImportCandidate whole = candidate("a", 4, 4, new ImportCandidate.FileEntry("x", 100, true));
        ImportCandidate half = candidate("b", 2, 4, new ImportCandidate.FileEntry("y", 100, true));
        List<ImportPlan.Item> items =
                ImportPlan.of(List.of(whole, half), Set.of(), disk(Map.of("x", 100L, "y", 100L)), true);
        assertEquals(ImportPlan.Verdict.IMPORT, items.get(0).verdict());
        assertEquals(ImportPlan.Verdict.CLIENT_RUNNING, items.get(1).verdict());
        assertTrue(items.get(1).reason().contains("qBittorrent"), items.get(1).reason());
    }
}
