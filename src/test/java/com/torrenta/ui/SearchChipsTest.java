package com.torrenta.ui;

import java.util.List;
import java.util.Set;

import com.torrenta.search.TorznabEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchChipsTest {

    private static final TorznabEndpoint PROWLARR = new TorznabEndpoint("Prowlarr", "http://a:9696/1/api", "k");
    private static final TorznabEndpoint JACKETT = new TorznabEndpoint("Jackett", "http://b:9117/api", "");
    private static final TorznabEndpoint LOCAL = new TorznabEndpoint("Local", "http://c/api", "");

    @Test
    void everyEnabledProviderIsOnUnlessSwitchedOff() {
        List<TorznabEndpoint> enabled = List.of(PROWLARR, JACKETT, LOCAL);
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
