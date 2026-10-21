package com.torrenta.search;

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

    /** The {@code cat=} parameter's value, or empty for {@link #ALL}. */
    public String queryValue() {
        return newznab == 0 ? "" : Integer.toString(newznab);
    }

    @Override
    public String toString() {
        return label();
    }
}
