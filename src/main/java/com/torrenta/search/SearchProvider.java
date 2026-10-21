package com.torrenta.search;

import java.util.List;

/**
 * One place a search goes: a Torznab endpoint, later a declarative definition. Blocking; the
 * service runs each on its own virtual thread with a timeout, so an implementation need not.
 */
public interface SearchProvider {

    /** The name shown beside a result and in a failure line: "Prowlarr", "Jackett: 1337x". */
    String name();

    /**
     * Searches; never null, empty when nothing matched.
     *
     * @throws SearchException with a message a status line can show when the provider could not
     *     answer (unreachable, a refused key, rate limited, unreadable response)
     */
    List<SearchResult> search(String query, SearchCategory category) throws SearchException;
}
