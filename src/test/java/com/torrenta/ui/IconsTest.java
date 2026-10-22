package com.torrenta.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconsTest {

    /** Every glyph is a fresh Group holding one styled, scaled SVGPath with a path in it. */
    @Test
    void everyGlyphIsAStyledScaledPath() throws Exception {
        List<String> seen = new ArrayList<>();
        for (Method m : Icons.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            if (!Node.class.isAssignableFrom(m.getReturnType())) continue;
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
                        "newFile",
                        "newFile",
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
                        "folderSmall",
                        "fileSmall")),
                seen.toString());
    }

    /** Resized for a menu, a 16-unit line glyph is drawn at scale 1; the Group reports the size. */
    @Test
    void aGlyphResizesForAMenu() {
        Icons.Glyph glyph = Icons.copy().at(Icons.MENU_SIZE);
        SVGPath svg = (SVGPath) glyph.getChildren().get(0);
        assertEquals(1.0, svg.getScaleX(), 1e-9);
        assertEquals(1.0, svg.getScaleY(), 1e-9);
        assertEquals(16.0, Icons.MENU_SIZE);
    }

    /** Every command the menus list has a glyph, so no item in a menu is the odd one without. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "add-file",
                "add-magnet",
                "add-clipboard",
                "search",
                "settings",
                "pause-resume",
                "force-resume",
                "recheck",
                "reannounce",
                "sequential",
                "limits",
                "queue",
                "queue-top",
                "queue-up",
                "queue-down",
                "queue-bottom",
                "set-location",
                "choose-files",
                "set-category",
                "edit-tags",
                "copy-name",
                "copy-hash",
                "copy-magnet",
                "open-folder",
                "remove",
                "remove-data",
                "filter",
                "transfers",
                "rss",
                "view-next",
                "view-previous",
                "focus-sidebar",
                "toggle-detail",
                "select-all",
                "event-log",
                "check-updates",
                "about"
            })
    void everyMenuCommandHasAGlyph(String id) {
        Icons.Glyph a = Icons.forCommand(id);
        assertNotNull(a, id);
        assertTrue(a != Icons.forCommand(id), id + ": each call is a fresh node");
    }

    /** The Show: filters and the density toggle are palette-only and get none. */
    @Test
    void paletteOnlyCommandsHaveNoGlyph() {
        assertNull(Icons.forCommand("show-all"));
        assertNull(Icons.forCommand("toggle-density"));
    }
}
