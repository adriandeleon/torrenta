package com.torrenta.ui;

import static com.torrenta.i18n.Messages.tr;

/**
 * Where the detail pane sits: under the list, beside it on the right, or collapsed away. The
 * settings keep it as two keys, whether it shows ({@code detail.visible}) and where
 * ({@code detail.side}), so collapsing it and bringing it back keeps the place; each place keeps
 * its own divider, since a fraction of the height means nothing as a fraction of the width.
 */
public enum DetailPlace {
    BOTTOM("bottom", DetailSplit.DEFAULT),
    RIGHT("right", 0.58),
    HIDDEN("hidden", DetailSplit.DEFAULT);

    private final String id;
    private final double defaultDivider;

    DetailPlace(String id, double defaultDivider) {
        this.id = id;
        this.defaultDivider = defaultDivider;
    }

    public String id() {
        return id;
    }

    public boolean shown() {
        return this != HIDDEN;
    }

    /** Beside the list rather than under it: the split runs left to right. */
    public boolean beside() {
        return this == RIGHT;
    }

    /** The place the two keys describe; a side that is not {@code right} is the bottom. */
    public static DetailPlace of(boolean shown, String side) {
        if (!shown) return HIDDEN;
        return side != null && side.trim().equalsIgnoreCase(RIGHT.id) ? RIGHT : BOTTOM;
    }

    /** The other place it can show in; hidden stays hidden. */
    public DetailPlace otherSide() {
        return switch (this) {
            case BOTTOM -> RIGHT;
            case RIGHT -> BOTTOM;
            case HIDDEN -> HIDDEN;
        };
    }

    /**
     * The divider as written, the list's share of the split, by {@link DetailSplit}'s rule; none,
     * or one that would leave the pane its tabs alone, is this place's default.
     */
    public double divider(String written) {
        return DetailSplit.position(written, defaultDivider);
    }

    /** The name the ChoiceBox shows, in the UI language ({@code detailPlace.<id>}); read at call time. */
    @Override
    public String toString() {
        return tr("detailPlace." + id);
    }
}
