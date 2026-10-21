package com.torrenta.ui;

import static com.torrenta.i18n.Messages.tr;

/**
 * Where the sidebar of filters sits: down the left of the list, down its right, or nowhere. The
 * settings keep it as two keys, whether it shows ({@code ui.sidebar}, which View → Sidebar flips)
 * and on which side ({@code ui.sidebarSide}), so hiding it and showing it again keeps the side.
 */
public enum SidebarPlace {
    LEFT("left"),
    RIGHT("right"),
    HIDDEN("hidden");

    private final String id;

    SidebarPlace(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean shown() {
        return this != HIDDEN;
    }

    /** The place the two keys describe; a side that is not {@code right} is the left. */
    public static SidebarPlace of(boolean shown, String side) {
        if (!shown) return HIDDEN;
        return side != null && side.trim().equalsIgnoreCase(RIGHT.id) ? RIGHT : LEFT;
    }

    /** The other side; hidden stays hidden. */
    public SidebarPlace otherSide() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            case HIDDEN -> HIDDEN;
        };
    }

    /** The name the ChoiceBox shows, in the UI language ({@code sidebarPlace.<id>}); read at call time. */
    @Override
    public String toString() {
        return tr("sidebarPlace." + id);
    }
}
