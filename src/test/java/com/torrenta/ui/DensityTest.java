package com.torrenta.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DensityTest {

    @Test
    void readsItsIdsAndFallsBack() {
        assertEquals(Density.COMPACT, Density.byId("compact", Density.DEFAULT));
        assertEquals(Density.COMFORTABLE, Density.byId(" Comfortable ", Density.COMPACT));
        assertEquals(Density.DEFAULT, Density.byId("roomy", Density.DEFAULT));
        assertEquals(Density.DEFAULT, Density.byId(null, Density.DEFAULT));
        assertEquals(Density.COMFORTABLE, Density.DEFAULT, "the redesign's default");
    }

    @Test
    void togglesBetweenTheTwo() {
        assertEquals(Density.COMPACT, Density.COMFORTABLE.other());
        assertEquals(Density.COMFORTABLE, Density.COMPACT.other());
    }

    @Test
    void comfortableRowsHideTheColumnsTheirCellAlreadyDraws() {
        List<ColumnLayout.Column> layout = ColumnLayout.parse(Density.COMFORTABLE.defaultLayout());
        assertEquals("name", layout.getFirst().id(), "the name leads");
        assertTrue(layout.getFirst().visible());
        for (String drawn : List.of("progress", "state")) {
            assertFalse(
                    layout.stream()
                            .filter(c -> c.id().equals(drawn))
                            .findFirst()
                            .orElseThrow()
                            .visible(),
                    drawn + " is inside the name cell");
        }
        for (String shown : List.of("size", "down", "up", "eta", "ratio")) {
            assertTrue(layout.stream().anyMatch(c -> c.id().equals(shown) && c.visible()), shown);
        }
        assertTrue(
                layout.stream().anyMatch(c -> c.id().equals("queue") && !c.visible()),
                "the Queue column is carried hidden, so the header's menu can show it");
    }

    @Test
    void onlyComfortableFixesTheRowHeight() {
        assertTrue(Density.COMFORTABLE.cellSize() > 0);
        assertTrue(Density.COMPACT.cellSize() < 0);
        assertEquals("", Density.COMPACT.defaultLayout(), "compact's default is the columns as built");
    }
}
