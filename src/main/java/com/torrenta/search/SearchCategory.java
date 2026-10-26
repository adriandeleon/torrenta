package com.torrenta.search;

import java.util.Collection;
import java.util.Locale;

import static com.torrenta.i18n.Messages.tr;

/**
 * What kind of thing a search is for, in the terms Newznab and Torznab indexers share: a handful
 * of top-level category numbers that Jackett and Prowlarr map every indexer's own onto.
 *
 * <p>{@link #ALL} sends no category at all; the rest send their Newznab number as {@code cat=}.
 */
public enum SearchCategory {
    ALL("All", 0),
    MOVIES("Movies", 2000),
    TV("TV", 5000),
    MUSIC("Music", 3000),
    GAMES("Games", 1000),
    SOFTWARE("Software", 4000),
    BOOKS("Books", 7000),
    ANIME("Anime", 5070),
    OTHER("Other", 8000);

    private final String id;
    private final int newznab;

    SearchCategory(String id, int newznab) {
        this.id = id;
        this.newznab = newznab;
    }

    /**
     * The stable English name, the one a definition file's {@code categories} may use as a key
     * ("Movies"); never shown, so it does not move with the UI language.
     */
    public String id() {
        return id;
    }

    /** What the chips and the menus say, in the UI language; read at call time, never cached. */
    public String label() {
        return tr("search.category." + id.toLowerCase(Locale.ROOT));
    }

    /** The Newznab category number; {@code 0} for {@link #ALL}, which sends none. */
    public int newznab() {
        return newznab;
    }

    /**
     * The category an indexer's Newznab numbers put a result in, or null when none of them is
     * one of ours: a number's thousand block names it (2000–2999 Movies, 5000–5999 TV …), except
     * 5070, which is Anime and wins over TV when both are given. Indexer-specific numbers (100000
     * and up) and the blocks we have no chip for (6000) name nothing.
     */
    public static SearchCategory ofNewznab(Collection<Integer> numbers) {
        SearchCategory found = null;
        for (int n : numbers) {
            if (n == ANIME.newznab) return ANIME;
            if (found != null || n < 1000 || n >= 9000) continue;
            int block = n / 1000 * 1000;
            for (SearchCategory c : values()) {
                if (c != ALL && c != ANIME && c.newznab == block) found = c;
            }
        }
        return found;
    }

    /**
     * The category a filter names, by its English id or its label in the UI language, ignoring
     * case ({@code movies}, {@code Filme}); null for anything else, {@link #ALL} included.
     */
    public static SearchCategory named(String name) {
        for (SearchCategory c : values()) {
            if (c != ALL && (c.id.equalsIgnoreCase(name) || c.label().equalsIgnoreCase(name))) return c;
        }
        return null;
    }

    /** The {@code cat=} parameter's value, or empty for {@link #ALL}. */
    public String queryValue() {
        return newznab == 0 ? "" : Integer.toString(newznab);
    }

    @Override
    public String toString() {
        return label();
    }
}
