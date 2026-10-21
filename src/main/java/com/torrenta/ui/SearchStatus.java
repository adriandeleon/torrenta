package com.torrenta.ui;

import java.util.List;
import java.util.Map;

/**
 * The Search tab's status line, pure: one clause per provider asked, in the order they were asked,
 * joined with middle dots — "Prowlarr: 42 results · Jackett: timed out after 20 s · Local: …",
 * where "…" is a provider still being waited for.
 */
final class SearchStatus {

    private SearchStatus() {}

    /**
     * @param asked the providers' names, in the order they were asked
     * @param counts how many results each provider that answered gave
     * @param failures why each provider that failed did
     * @param done the search is over: nothing is still being waited for, and a search that found
     *     nothing at all says so first
     */
    static String line(List<String> asked, Map<String, Integer> counts, Map<String, String> failures, boolean done) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (String name : asked) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(name).append(": ");
            Integer count = counts.get(name);
            String failure = failures.get(name);
            if (count != null) {
                total += count;
                sb.append(count == 1 ? "1 result" : count + " results");
            } else if (failure != null) {
                sb.append(failure);
            } else {
                sb.append("…");
            }
        }
        if (done && total == 0 && !asked.isEmpty()) {
            boolean anyAnswered = asked.stream().anyMatch(counts::containsKey);
            sb.insert(0, (anyAnswered ? "Nothing found" : "No provider answered") + " · ");
        }
        return sb.toString();
    }

    /** What the line says before a search can run. */
    static String cannotSearch(boolean providersConfigured, boolean providersChosen, boolean queryGiven) {
        if (!providersConfigured) return "No search provider yet: add one in Settings → Search";
        if (!providersChosen) return "Every provider is switched off; turn one on to search";
        if (!queryGiven) return "Type what to search for and press Enter";
        return "";
    }
}
