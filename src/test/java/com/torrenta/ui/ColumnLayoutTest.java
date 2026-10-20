package com.torrenta.ui;

import java.util.List;

import com.torrenta.ui.ColumnLayout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnLayoutTest {

    @Test
    void roundTrip() {
        List<Column> layout =
                List.of(new Column("name", 360, true), new Column("added", 130, false), new Column("size", 90, true));
        String text = ColumnLayout.format(layout);
        assertEquals("name:360,-added:130,size:90", text);
        assertEquals(layout, ColumnLayout.parse(text));
    }

    @Test
    void emptyAndBlankMeanNoLayout() {
        assertTrue(ColumnLayout.parse(null).isEmpty());
        assertTrue(ColumnLayout.parse("  ").isEmpty());
        assertEquals("", ColumnLayout.format(List.of()));
    }

    @Test
    void handEditsDegradeToOneColumnNotTheLayout() {
        List<Column> parsed = ColumnLayout.parse(" name:abc , -size, ratio:0, ,:12, eta:70.6");
        assertEquals(
                List.of(
                        new Column("name", ColumnLayout.DEFAULT_WIDTH, true),
                        new Column("size", ColumnLayout.DEFAULT_WIDTH, false),
                        new Column("ratio", ColumnLayout.DEFAULT_WIDTH, true),
                        new Column("eta", 70.6, true)),
                parsed);
        assertEquals("name,-size,ratio,eta:71", ColumnLayout.format(parsed), "a default width is written as none");
    }
}
