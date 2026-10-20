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
            // The same visual size for both families: a 16-unit box scaled like a 24dp one.
            assertEquals(line ? 1.2 : 0.8, svg.getScaleX(), 1e-9, m.getName());
            seen.add(m.getName());
        }
        assertTrue(
                seen.containsAll(List.of(
                        "open",
                        "magnet",
                        "pause",
                        "run",
                        "refresh",
                        "trash",
                        "find",
                        "history",
                        "settings",
                        "palette")),
                seen.toString());
    }
}
