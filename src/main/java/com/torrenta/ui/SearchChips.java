package com.torrenta.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.torrenta.search.SearchProvider;

/**
 * Which providers a search asks, from the chips: every enabled provider is on unless it was
 * switched off by hand, so a provider added in Settings joins the next search without a click.
 * What is remembered is the set switched <em>off</em>, by name, pruned to the providers that still
 * exist. A Torznab endpoint and a definition file are both providers here; the window builds
 * them. Pure.
 */
final class SearchChips {

    private SearchChips() {}

    /** The providers the chips leave on, in the store's order. */
    static List<SearchProvider> chosen(List<SearchProvider> enabled, Set<String> off) {
        return enabled.stream().filter(e -> !off.contains(e.name())).toList();
    }

    /** The off set without names that no longer name an enabled provider. */
    static Set<String> keep(Set<String> off, List<SearchProvider> enabled) {
        Set<String> names = new LinkedHashSet<>();
        for (SearchProvider e : enabled) names.add(e.name());
        Set<String> kept = new LinkedHashSet<>(off);
        kept.retainAll(names);
        return kept;
    }
}
