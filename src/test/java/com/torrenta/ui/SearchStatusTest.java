package com.torrenta.ui;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchStatusTest {

    @Test
    void oneClausePerProviderInTheOrderAsked() {
        List<String> asked = List.of("Prowlarr", "Jackett", "Local");
        assertEquals(
                "Prowlarr: … · Jackett: … · Local: …",
                SearchStatus.line(asked, Map.of(), Map.of(), false),
                "nothing yet");
        assertEquals(
                "Prowlarr: 42 results · Jackett: timed out after 20 s · Local: …",
                SearchStatus.line(asked, Map.of("Prowlarr", 42), Map.of("Jackett", "timed out after 20 s"), false));
        assertEquals(
                "Prowlarr: 1 result · Jackett: could not connect · Local: 0 results",
                SearchStatus.line(
                        asked, Map.of("Prowlarr", 1, "Local", 0), Map.of("Jackett", "could not connect"), true));
    }

    @Test
    void aSearchThatFoundNothingSaysSoFirst() {
        List<String> asked = List.of("Prowlarr", "Jackett");
        assertEquals(
                "Nothing found · Prowlarr: 0 results · Jackett: could not connect",
                SearchStatus.line(asked, Map.of("Prowlarr", 0), Map.of("Jackett", "could not connect"), true));
        assertEquals(
                "No provider answered · Prowlarr: timed out after 20 s · Jackett: could not connect",
                SearchStatus.line(
                        asked,
                        Map.of(),
                        Map.of("Prowlarr", "timed out after 20 s", "Jackett", "could not connect"),
                        true));
        assertEquals(
                "Prowlarr: 0 results · Jackett: …",
                SearchStatus.line(asked, Map.of("Prowlarr", 0), Map.of(), false),
                "not while one is still being waited for");
        assertEquals("", SearchStatus.line(List.of(), Map.of(), Map.of(), true));
    }

    @Test
    void whyASearchCannotRun() {
        assertEquals(
                "No search provider yet: add one in Settings → Search", SearchStatus.cannotSearch(false, false, true));
        assertEquals(
                "Every provider is switched off; turn one on to search", SearchStatus.cannotSearch(true, false, true));
        assertEquals("Type what to search for and press Enter", SearchStatus.cannotSearch(true, true, false));
        assertEquals("", SearchStatus.cannotSearch(true, true, true));
    }

    @Test
    void clearIsLiveOnceThereIsSomethingToClear() {
        assertFalse(SearchStatus.canClear("", false), "a fresh tab");
        assertFalse(SearchStatus.canClear("   ", false), "blanks are no query");
        assertTrue(SearchStatus.canClear("ubuntu", false), "a query typed, not run");
        assertTrue(SearchStatus.canClear("", true), "a search ran and its query was erased");
    }
}
