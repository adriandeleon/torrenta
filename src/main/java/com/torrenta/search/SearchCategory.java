package com.torrenta.search;

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

    private final String label;
    private final int newznab;

    SearchCategory(String label, int newznab) {
        this.label = label;
        this.newznab = newznab;
    }

    /** What the chips and the menus say. */
    public String label() {
        return label;
    }

    /** The Newznab category number; {@code 0} for {@link #ALL}, which sends none. */
    public int newznab() {
        return newznab;
    }

    /** The {@code cat=} parameter's value, or empty for {@link #ALL}. */
    public String queryValue() {
        return newznab == 0 ? "" : Integer.toString(newznab);
    }

    @Override
    public String toString() {
        return label;
    }
}
