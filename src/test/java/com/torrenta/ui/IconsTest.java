package com.torrenta.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconsTest {

    /** Every glyph is a fresh Group holding one styled, scaled SVGPath with a path in it. */
    @Test
    void everyGlyphIsAStyledScaledPath() throws Exception {
        List<String> seen = new ArrayList<>();
        for (Method m : Icons.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            if (m.getReturnType() != Node.class) continue;
            Node a = (Node) m.invoke(null);
            Node b = (Node) m.invoke(null);
            assertTrue(a != b, m.getName() + ": a node has one parent, so each call must be fresh");
            Group group = (Group) a;
            assertEquals(1, group.getChildren().size(), m.getName());
            SVGPath svg = (SVGPath) group.getChildren().get(0);
            assertFalse(svg.getContent().isBlank(), m.getName());
            boolean line = svg.getStyleClass().contains("icon-line");
            boolean filled = svg.getStyleClass().contains("toolbar-icon");
            assertTrue(line ^ filled, m.getName() + " is one family, not both or neither");
            // Every toolbar glyph is 17px (the redesign's), the sidebar's folder 13px; a Material
            // glyph, were one left, would be 17px too.
            double expected = m.getName().endsWith("Small") ? 13.0 / 16 : (line ? 17.0 / 16 : 17.0 / 24);
            assertEquals(expected, svg.getScaleX(), 1e-9, m.getName());
            if (!m.getName().endsWith("Small")) assertTrue(line, m.getName() + ": the toolbar is one line family");
            seen.add(m.getName());
        }
        assertTrue(
                seen.containsAll(List.of(
                        "open",
                        "magnet",
                        "pause",
                        "run",
                        "forceRun",
                        "refresh",
                        "trash",
                        "find",
                        "history",
                        "settings",
                        "palette",
                        "folderSmall")),
                seen.toString());
    }
}
