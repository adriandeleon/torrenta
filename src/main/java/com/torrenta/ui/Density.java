package com.torrenta.ui;

/**
 * How tall the transfer list's rows are. Comfortable rows are two lines, the name over a thin
 * progress bar and the status sentence, so the Progress and Status columns are not needed;
 * compact rows are one line with those as columns. Each density remembers its own column layout.
 */
public enum Density {
    COMFORTABLE("comfortable", "Comfortable", 48, DefaultLayouts.COMFORTABLE),
    COMPACT("compact", "Compact", -1, "");

    /** The first-run density, and the fallback for a value the settings file holds that is not one. */
    public static final Density DEFAULT = COMFORTABLE;

    private final String id;
    private final String label;
    private final double cellSize;
    private final String defaultLayout;

    Density(String id, String label, double cellSize, String defaultLayout) {
        this.id = id;
        this.label = label;
        this.cellSize = cellSize;
        this.defaultLayout = defaultLayout;
    }

    public String id() {
        return id;
    }

    /** The table's fixed cell size, or -1 for the computed one. */
    public double cellSize() {
        return cellSize;
    }

    /**
     * The column layout to use when none has been remembered for this density, in {@link
     * ColumnLayout}'s format; empty means the columns' own defaults.
     */
    public String defaultLayout() {
        return defaultLayout;
    }

    public Density other() {
        return this == COMFORTABLE ? COMPACT : COMFORTABLE;
    }

    public static Density byId(String id, Density fallback) {
        if (id != null) {
            for (Density d : values()) if (d.id.equalsIgnoreCase(id.trim())) return d;
        }
        return fallback;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Held apart so the enum constants can name it before the class body runs. */
    private static final class DefaultLayouts {
        /** Name wide, then the numbers; progress and status live inside the name cell. */
        static final String COMFORTABLE = "name:480,size:80,down:96,up:96,eta:80,ratio:64,-progress,-state,-done,"
                + "-seeds,-peers,-added,-completed,-tracker,-category,-tags,-path";
    }
}
