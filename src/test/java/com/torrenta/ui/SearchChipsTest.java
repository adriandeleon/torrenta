package com.torrenta.ui;

import java.util.List;
import java.util.Set;

import com.torrenta.search.SearchCategory;
import com.torrenta.search.SearchProvider;
import com.torrenta.search.SearchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchChipsTest {

    /** A provider is its name here; a Torznab endpoint and a definition file look the same to the chips. */
    private record Named(String name) implements SearchProvider {
        @Override
        public List<SearchResult> search(String query, SearchCategory category) {
            return List.of();
        }
    }

    private static final SearchProvider PROWLARR = new Named("Prowlarr");
    private static final SearchProvider JACKETT = new Named("Jackett");
    private static final SearchProvider LOCAL = new Named("Local");

    @Test
    void everyEnabledProviderIsOnUnlessSwitchedOff() {
        List<SearchProvider> enabled = List.of(PROWLARR, JACKETT, LOCAL);
        assertEquals(enabled, SearchChips.chosen(enabled, Set.of()));
        assertEquals(List.of(PROWLARR, LOCAL), SearchChips.chosen(enabled, Set.of("Jackett")), "in the store's order");
        assertEquals(List.of(), SearchChips.chosen(enabled, Set.of("Prowlarr", "Jackett", "Local")));
    }

    @Test
    void aProviderAddedInSettingsJoinsTheNextSearchAndARemovedOneIsForgotten() {
        Set<String> off = Set.of("Jackett", "Gone");
        Set<String> kept = SearchChips.keep(off, List.of(PROWLARR, JACKETT, LOCAL));
        assertEquals(Set.of("Jackett"), kept, "a name no provider has any more is dropped");
        assertEquals(List.of(PROWLARR, LOCAL), SearchChips.chosen(List.of(PROWLARR, JACKETT, LOCAL), kept));
        assertEquals(Set.of(), SearchChips.keep(off, List.of()), "no providers, nothing to remember");
    }
}
