package com.torrenta.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncMergeTest {

    @Test
    void firstSyncKeepsBothSidesAndThisMachineOnADifference() {
        SyncMerge.Result r =
                SyncMerge.merge(null, Map.of("a", "1", "shared", "mine"), Map.of("b", "2", "shared", "theirs"));
        assertEquals(Map.of("a", "1", "b", "2", "shared", "mine"), r.merged());
        assertEquals(List.of("b"), r.received());
        assertEquals(List.of("a", "shared"), r.sent());
        assertEquals(List.of("shared"), r.conflicts());
    }

    @Test
    void oneSidedChangesCarryOver() {
        Map<String, String> base = Map.of("a", "1", "b", "2", "c", "3", "d", "4");
        Map<String, String> mine = Map.of("a", "1", "b", "2x", "c", "3", "e", "5"); // b changed, d removed, e added
        Map<String, String> theirs = Map.of("a", "1y", "b", "2", "d", "4"); // a changed, c removed
        SyncMerge.Result r = SyncMerge.merge(base, mine, theirs);
        assertEquals(Map.of("a", "1y", "b", "2x", "e", "5"), r.merged());
        assertEquals(List.of("a", "c"), r.received(), "their change and their removal");
        assertEquals(List.of("b", "d", "e"), r.sent(), "our change, our removal, our addition");
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    void bothChangedDifferentlyKeepsThisMachineAndSaysSo() {
        SyncMerge.Result r = SyncMerge.merge(Map.of("k", "base"), Map.of("k", "mine"), Map.of("k", "theirs"));
        assertEquals(Map.of("k", "mine"), r.merged());
        assertEquals(List.of("k"), r.conflicts());
        assertEquals(List.of("k"), r.sent());
    }

    @Test
    void sameChangeOnBothSidesIsNotAChange() {
        SyncMerge.Result r = SyncMerge.merge(Map.of("k", "base"), Map.of("k", "new"), Map.of("k", "new"));
        assertEquals(Map.of("k", "new"), r.merged());
        assertTrue(r.received().isEmpty() && r.sent().isEmpty() && r.conflicts().isEmpty());
    }

    @Test
    void removedHereChangedThereKeepsThisMachinesRemoval() {
        SyncMerge.Result r = SyncMerge.merge(Map.of("k", "base"), Map.of(), Map.of("k", "theirs"));
        assertEquals(Map.of(), r.merged());
        assertEquals(List.of("k"), r.conflicts());
    }
}
