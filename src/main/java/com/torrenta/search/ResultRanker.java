package com.torrenta.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The order results come back in, and which of two copies of the same torrent is kept. Pure.
 *
 * <p>Seeds first (a dead torrent is no use however well it is named), then how many of the
 * query's words the name contains (so "ubuntu 24.04" ranks a name with both words above one with
 * "ubuntu" alone), then the newest, then the name so the order is stable.
 */
public final class ResultRanker {

    private ResultRanker() {}

    /** Merges every provider's list: the same torrent once, the best copy kept, then ranked. */
    public static List<SearchResult> merge(List<List<SearchResult>> lists, String query) {
        List<SearchResult> all = new ArrayList<>();
        for (List<SearchResult> list : lists) all.addAll(list);
        return rank(dedupe(all), query);
    }

    /**
     * One result per {@link SearchResult#identity()}; of two copies the one with more seeds is
     * kept (the first one on a tie), and it borrows a size or a date the other knew.
     */
    public static List<SearchResult> dedupe(List<SearchResult> results) {
        Map<String, SearchResult> byIdentity = new LinkedHashMap<>();
        for (SearchResult r : results) {
            byIdentity.merge(r.identity(), r, ResultRanker::better);
        }
        return new ArrayList<>(byIdentity.values());
    }

    private static SearchResult better(SearchResult kept, SearchResult other) {
        SearchResult winner = other.seeds() > kept.seeds() ? other : kept;
        SearchResult loser = winner == kept ? other : kept;
        long size = winner.size() >= 0 ? winner.size() : loser.size();
        Instant published = winner.published() != null ? winner.published() : loser.published();
        String detail = !winner.detailUrl().isEmpty() ? winner.detailUrl() : loser.detailUrl();
        int leechers = winner.leechers() >= 0 ? winner.leechers() : loser.leechers();
        if (size == winner.size()
                && published == winner.published()
                && detail.equals(winner.detailUrl())
                && leechers == winner.leechers()) return winner;
        return new SearchResult(
                winner.name(),
                size,
                winner.seeds(),
                leechers,
                published,
                winner.link(),
                winner.infoHash(),
                winner.provider(),
                detail);
    }

    /** Sorted copy: seeds, then query coverage, then recency, then name. */
    public static List<SearchResult> rank(List<SearchResult> results, String query) {
        List<String> terms = terms(query);
        Comparator<SearchResult> order = Comparator.<SearchResult>comparingInt(r -> -r.seeds())
                .thenComparingInt(r -> -coverage(r.name(), terms))
                .thenComparing(
                        SearchResult::published,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder().reversed()))
                .thenComparing(SearchResult::name, String.CASE_INSENSITIVE_ORDER);
        List<SearchResult> sorted = new ArrayList<>(results);
        sorted.sort(order);
        return sorted;
    }

    /** How many of the query's words the name contains, ignoring case. */
    public static int coverage(String name, String query) {
        return coverage(name, terms(query));
    }

    private static int coverage(String name, List<String> terms) {
        String lower = name.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String term : terms) if (lower.contains(term)) hits++;
        return hits;
    }

    /** The query's distinct words, lower-cased; blank is no words. */
    static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty() && !out.contains(word)) out.add(word);
        }
        return out;
    }
}
