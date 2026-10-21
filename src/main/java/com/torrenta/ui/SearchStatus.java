package com.torrenta.ui;

import java.util.List;
import java.util.Map;

import static com.torrenta.i18n.Messages.tr;

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
            Integer count = counts.get(name);
            String failure = failures.get(name);
            String answer;
            if (count != null) {
                total += count;
                answer = tr("search.status.results", count);
            } else if (failure != null) {
                answer = failure;
            } else {
                answer = "…";
            }
            sb.append(tr("search.status.provider", name, answer));
        }
        if (done && total == 0 && !asked.isEmpty()) {
            boolean anyAnswered = asked.stream().anyMatch(counts::containsKey);
            String why = anyAnswered ? tr("search.status.nothingFound") : tr("search.status.noProviderAnswered");
            sb.insert(0, why + " · ");
        }
        return sb.toString();
    }

    /** What the line says before a search can run. */
    static String cannotSearch(boolean providersConfigured, boolean providersChosen, boolean queryGiven) {
        if (!providersConfigured) return tr("search.status.noProviders");
        if (!providersChosen) return tr("search.status.allOff");
        if (!queryGiven) return tr("search.status.typeQuery");
        return "";
    }
}
