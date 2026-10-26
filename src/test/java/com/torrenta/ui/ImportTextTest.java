package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.migrate.ImportCandidate;
import com.torrenta.migrate.ImportPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Import dialog's words and rules. */
class ImportTextTest {

    private static ImportCandidate candidate(long have, long pieces, boolean stopped, boolean magnet) {
        return new ImportCandidate(
                "qBittorrent",
                "a",
                "Film",
                "",
                new byte[0],
                magnet ? null : new byte[] {1},
                "/x",
                "",
                "",
                List.of(),
                stopped,
                List.of(),
                have,
                pieces,
                -2,
                -2);
    }

    private static ImportPlan.Item item(ImportPlan.Verdict v) {
        return new ImportPlan.Item(candidate(1, 2, false, false), v, "");
    }

    @Test
    void aRowSaysHowFarItGotAndWhetherItWasStopped() {
        assertEquals("100 %", ImportText.progress(candidate(8, 8, false, false)));
        assertTrue(ImportText.progress(candidate(4, 8, true, false)).startsWith("50 %"));
        assertTrue(ImportText.progress(candidate(4, 8, true, false)).length() > "50 %".length(), "and stopped");
        assertEquals(
                ImportText.progress(candidate(0, 0, true, true)),
                com.torrenta.i18n.Messages.tr("dialog.import.progress.magnet"));
    }

    @Test
    void tickedAsThePlanAdvises() {
        for (ImportPlan.Verdict v : ImportPlan.Verdict.values())
            assertEquals(v.importedByDefault(), ImportText.tickedAtFirst(item(v)), v.name());
        assertFalse(ImportPlan.Verdict.ALREADY_HERE.choosable());
        assertFalse(ImportPlan.Verdict.CLIENT_RUNNING.choosable());
        assertTrue(ImportPlan.Verdict.MISSING.choosable(), "a person may take it, to download it again");
        assertTrue(ImportPlan.Verdict.MISSING.checkedOnTheAdd(), "without the pieces it lacks");
    }

    @Test
    void theNotesSayWhyAndWhatToDo() {
        Path backup = Path.of("/q/BT_backup");
        assertEquals(
                1,
                ImportText.notes(new ImportText.Loaded(backup, List.of(), true, false, List.of()))
                        .size(),
                "SQLite alone");
        assertEquals(
                1,
                ImportText.notes(new ImportText.Loaded(backup, List.of(), false, false, List.of()))
                        .size(),
                "empty");
        List<String> running = ImportText.notes(new ImportText.Loaded(
                backup,
                List.of(item(ImportPlan.Verdict.IMPORT), item(ImportPlan.Verdict.CLIENT_RUNNING)),
                false,
                true,
                List.of("x: bad")));
        assertEquals(2, running.size(), "held back while it runs, and the file that would not read");
        assertTrue(running.getFirst().contains("qBittorrent"));
        assertEquals(
                List.of(),
                ImportText.notes(new ImportText.Loaded(
                        backup, List.of(item(ImportPlan.Verdict.IMPORT)), false, true, List.of())),
                "running, but nothing held back: nothing to say");
    }

    /** The recorded fixtures' folders: each client known by what its folder holds. */
    @Test
    void eachFolderIsKnownByWhatItHolds(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path migrate = Path.of(
                ImportTextTest.class.getResource("/com/torrenta/migrate").toURI());
        assertEquals("Deluge", ImportText.clientOf(migrate.resolve("deluge-2.2")));
        assertEquals("Transmission", ImportText.clientOf(migrate.resolve("transmission-4.1")));
        assertEquals("qBittorrent", ImportText.clientOf(migrate.resolve("qbittorrent-5.1")));
        Path home = dir.resolve("home");
        java.nio.file.Files.createDirectories(home.resolve(".config/deluge/state"));
        java.nio.file.Files.writeString(home.resolve(".config/deluge/state/torrents.state"), "");
        java.nio.file.Files.createDirectories(home.resolve(".config/transmission"));
        assertEquals(
                List.of(home.resolve(".config/deluge")),
                ImportText.found("Linux", home, java.util.Map.of()),
                "a Transmission folder without its resume folder is not one; Deluge's with its state is");
    }

    @Test
    void theButtonCounts() {
        assertTrue(ImportText.importButton(3).contains("3"));
        assertFalse(ImportText.importButton(1).contains("1"), "one is a word");
    }
}
