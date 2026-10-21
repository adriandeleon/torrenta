package com.torrenta.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PieceSizeTest {

    private static final int KIB = 1024;
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;

    @Test
    void automaticAimsAtAboutFifteenHundredPieces() {
        assertEquals(16 * KIB, PieceSize.suggest(0));
        assertEquals(16 * KIB, PieceSize.suggest(1));
        assertEquals(16 * KIB, PieceSize.suggest(1500L * 16 * KIB), "exactly 1500 pieces still fits");
        assertEquals(32 * KIB, PieceSize.suggest(1500L * 16 * KIB + 1), "one byte more needs the next size");
        assertEquals(1 * MIB, PieceSize.suggest(1 * GIB), "1 GiB: 1024 pieces of 1 MiB");
        assertEquals(4 * MIB, PieceSize.suggest(5 * GIB), "5 GiB: 1280 pieces of 4 MiB");
        assertEquals(8 * MIB, PieceSize.suggest(6 * GIB), "6 GiB: 1536 pieces of 4 MiB would be too many");
        assertEquals(16 * MIB, PieceSize.suggest(100 * GIB), "capped at 16 MiB whatever the count");
    }

    @Test
    void theLadderIsPowersOfTwoFromSixteenKibToThirtyTwoMib() {
        List<Integer> choices = PieceSize.choices();
        assertEquals(12, choices.size());
        assertEquals(16 * KIB, choices.get(0));
        assertEquals(32 * (int) MIB, choices.get(choices.size() - 1));
        for (int i = 1; i < choices.size(); i++) assertEquals(choices.get(i - 1) * 2, choices.get(i));
    }

    @Test
    void countsPackedAndAlignedLayouts() {
        List<Long> sizes = List.of(100L * KIB, 50L * KIB);
        assertEquals(10, PieceSize.count(sizes, 16 * KIB, false), "150 KiB back to back");
        assertEquals(11, PieceSize.count(sizes, 16 * KIB, true), "7 of a, then 4 of b on its own boundary");
        assertEquals(0, PieceSize.count(List.of(), 16 * KIB, true));
        assertEquals(0, PieceSize.count(0, 16 * KIB));
        assertEquals(1, PieceSize.count(1, 16 * KIB));
    }

    @Test
    void labelsAreWholeUnits() {
        assertEquals("16 KiB", PieceSize.label(16 * KIB));
        assertEquals("512 KiB", PieceSize.label(512 * KIB));
        assertEquals("1 MiB", PieceSize.label((int) MIB));
        assertEquals("32 MiB", PieceSize.label(32 * (int) MIB));
    }
}
