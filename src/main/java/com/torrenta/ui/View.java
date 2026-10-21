package com.torrenta.ui;

import static com.torrenta.i18n.Messages.tr;

/**
 * The views that share the window's centre, in the order the strip shows them and Ctrl+Tab walks
 * them. The id is the command that shows the view, and what the capture hook says.
 */
public enum View {
    TRANSFERS("transfers", "view.transfers"),
    SEARCH("search", "view.search"),
    RSS("rss", "view.rss");

    private final String id;
    private final String key;

    View(String id, String key) {
        this.id = id;
        this.key = key;
    }

    /** The command id that shows this view, and the capture hook's name for it. */
    public String id() {
        return id;
    }

    /** The name on the strip, read from the catalogue at call time. */
    public String label() {
        return tr(key);
    }

    /** The place on the strip: 1 for the first. */
    public int number() {
        return ordinal() + 1;
    }
}
