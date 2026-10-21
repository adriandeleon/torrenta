package com.torrenta.search;

import java.util.List;
import java.util.Map;

/**
 * What a search came back with: the merged, ranked results, and which providers did not answer
 * and why, so the status line can say "Prowlarr: timed out" beside the results that did arrive.
 *
 * @param results merged across the providers that answered, best first
 * @param failures provider name to the message, in the providers' order; empty when all answered
 * @param asked how many providers were asked
 */
public record SearchOutcome(List<SearchResult> results, Map<String, String> failures, int asked) {

    public SearchOutcome {
        results = List.copyOf(results);
        failures = Map.copyOf(failures);
    }

    /** Every provider answered. */
    public boolean complete() {
        return failures.isEmpty();
    }
}
