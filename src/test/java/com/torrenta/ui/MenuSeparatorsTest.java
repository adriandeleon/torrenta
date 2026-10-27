package com.torrenta.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuSeparatorsTest {

    private static final boolean S = true;
    private static final boolean I = false;

    @Test
    void aSeparatorBetweenTwoShownGroupsShows() {
        assertEquals(List.of(true, true, true), MenuSeparators.shown(List.of(I, S, I), List.of(true, false, true)));
    }

    @Test
    void anEmptiedGroupLeavesOneSeparatorNotTwo() {
        // a | b | c, with b hidden: a | c
        assertEquals(
                List.of(true, true, false, false, true),
                MenuSeparators.shown(List.of(I, S, I, S, I), List.of(true, false, false, false, true)));
    }

    @Test
    void noSeparatorAtEitherEnd() {
        assertEquals(
                List.of(false, false, true, false, false),
                MenuSeparators.shown(List.of(S, I, I, S, I), List.of(false, false, true, false, false)));
    }

    @Test
    void separatorsInARowCountAsOne() {
        assertEquals(
                List.of(true, true, false, true),
                MenuSeparators.shown(List.of(I, S, S, I), List.of(true, false, false, true)));
    }

    @Test
    void aMenuWithNothingShownShowsNoSeparator() {
        assertEquals(
                List.of(false, false, false), MenuSeparators.shown(List.of(I, S, I), List.of(false, false, false)));
        assertEquals(List.of(), MenuSeparators.shown(List.of(), List.of()));
    }
}
