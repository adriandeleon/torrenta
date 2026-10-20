package com.torrenta.engine;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadPlanTest {

    private static final Path DOWNLOADS = Path.of("/home/x/Downloads").toAbsolutePath();
    private static final Path INCOMPLETE =
            Path.of("/home/x/Downloads/incomplete").toAbsolutePath();

    @Test
    void offMeansStraightToTheDestination() {
        DownloadPlan plan = DownloadPlan.of(DOWNLOADS, false, INCOMPLETE);
        assertEquals(DOWNLOADS, plan.start());
        assertEquals(DOWNLOADS, plan.destination());
        assertFalse(plan.viaIncomplete());
        assertTrue(plan.incomplete().isEmpty());
    }

    @Test
    void onMeansTheIncompleteFolderFirst() {
        DownloadPlan plan = DownloadPlan.of(DOWNLOADS, true, INCOMPLETE);
        assertEquals(INCOMPLETE, plan.start());
        assertEquals(DOWNLOADS, plan.destination());
        assertTrue(plan.viaIncomplete());
        assertEquals(Optional.of(INCOMPLETE), plan.incomplete());
    }

    @Test
    void theSameFolderTwiceIsNoMove() {
        DownloadPlan plan = DownloadPlan.of(DOWNLOADS, true, Path.of("/home/x/Downloads/../Downloads"));
        assertFalse(plan.viaIncomplete(), "normalised before comparing");
        assertTrue(DownloadPlan.of(DOWNLOADS, true, null).incomplete().isEmpty(), "no folder, no move");
    }

    @Test
    void aCategoryWithAPathSetsTheDestination() {
        assertEquals(DOWNLOADS, DownloadPlan.destination(DOWNLOADS, Optional.empty()));
        assertEquals(DOWNLOADS, DownloadPlan.destination(DOWNLOADS, Optional.of(new Category("Linux"))));
        assertEquals(
                Path.of("/data/iso").toAbsolutePath(),
                DownloadPlan.destination(DOWNLOADS, Optional.of(new Category("Linux", "/data/iso"))));
        // The category's folder is the destination; the incomplete folder still comes first.
        DownloadPlan plan = DownloadPlan.of(
                DownloadPlan.destination(DOWNLOADS, Optional.of(new Category("Linux", "/data/iso"))), true, INCOMPLETE);
        assertEquals(INCOMPLETE, plan.start());
        assertEquals(Path.of("/data/iso").toAbsolutePath(), plan.destination());
    }

    @Test
    void theStorageChoicesFollowThePlan() {
        AddRequest.Storage via = AddRequest.Storage.of(DownloadPlan.of(DOWNLOADS, true, INCOMPLETE), true, false);
        assertEquals(INCOMPLETE, via.incompletePath());
        assertTrue(via.partSuffix());
        assertFalse(via.preallocate());
        AddRequest.Storage direct = AddRequest.Storage.of(DownloadPlan.of(DOWNLOADS, false, INCOMPLETE), false, true);
        assertEquals(null, direct.incompletePath());
        assertTrue(direct.preallocate());
        AddRequest request = AddRequest.defaults(new AddRequest.Magnet("magnet:?xt=urn:btih:x"), DOWNLOADS);
        assertEquals(AddRequest.Storage.DEFAULT, request.storage());
        assertEquals(via, request.withStorage(via).storage());
    }
}
