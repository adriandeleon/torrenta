package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {

    @Test
    void everyThemeResolvesToAStylesheetUrl() {
        for (Theme theme : Theme.values()) {
            String sheet = theme.stylesheet();
            // A full URL, not AtlantaFX's bare class-path path: a runtime switch needs the former.
            assertTrue(sheet.startsWith("jar:") || sheet.startsWith("file:"), theme + " -> " + sheet);
            assertTrue(sheet.endsWith(theme.id() + ".css"), sheet);
        }
    }

    @Test
    void sixThemesThreePairs() {
        assertEquals(6, Theme.values().length);
        assertEquals(
                3, java.util.Arrays.stream(Theme.values()).filter(Theme::isDark).count());
        assertEquals("Cupertino Dark", Theme.CUPERTINO_DARK.displayName());
        assertEquals("Cupertino Dark", Theme.CUPERTINO_DARK.toString(), "the ChoiceBox shows the name");
        assertFalse(Theme.NORD_LIGHT.isDark());
    }

    @Test
    void byIdFallsBackForAnythingUnknown() {
        assertSame(Theme.NORD_DARK, Theme.byId("nord-dark", Theme.DEFAULT));
        assertSame(Theme.DEFAULT, Theme.byId("", Theme.DEFAULT));
        assertSame(Theme.DEFAULT, Theme.byId("dracula", Theme.DEFAULT));
        assertSame(Theme.DEFAULT, Theme.byId(null, Theme.DEFAULT));
        assertSame(Theme.EDITORA_DARK, Theme.DEFAULT);
    }
}
