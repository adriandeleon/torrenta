package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoStackTest {

    private static final UndoStack.Undo NOTHING = e -> {};

    @Test
    void theNewestComesFirstAndTakingItLeavesTheOneBefore() {
        UndoStack stack = new UndoStack();
        stack.push("removed a", 1_000, NOTHING);
        stack.push("moved b", 2_000, NOTHING);
        assertEquals("moved b", stack.newest(3_000).orElseThrow().label());
        assertEquals("moved b", stack.take(3_000).orElseThrow().label());
        assertEquals("removed a", stack.take(3_000).orElseThrow().label());
        assertTrue(stack.take(3_000).isEmpty());
    }

    @Test
    void theStatusBarOffersTheNewestForTenSecondsAndCtrlZKeepsItTenMinutes() {
        UndoStack stack = new UndoStack();
        stack.push("removed a", 0, NOTHING);
        assertTrue(stack.offered(9_999));
        assertFalse(stack.offered(UndoStack.OFFER_MS), "the button goes after ten seconds");
        assertTrue(stack.newest(UndoStack.KEEP_MS - 1).isPresent(), "Ctrl+Z still works");
        assertTrue(stack.newest(UndoStack.KEEP_MS).isEmpty(), "forgotten after ten minutes");
        assertEquals(0, stack.size());
        assertFalse(stack.offered(0), "nothing to offer from an empty stack");
    }

    @Test
    void onlyTheLastTenAreKept() {
        UndoStack stack = new UndoStack();
        for (int i = 0; i < 15; i++) stack.push("action " + i, i, NOTHING);
        assertEquals(UndoStack.SIZE, stack.size());
        List<String> taken = new ArrayList<>();
        while (stack.newest(20).isPresent())
            taken.add(stack.take(20).orElseThrow().label());
        assertEquals("action 14", taken.getFirst());
        assertEquals("action 5", taken.getLast(), "the five oldest went first");
    }
}
