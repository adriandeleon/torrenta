package com.torrenta.ui;

import java.util.List;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * The Overview's pieces map: a few rows of squares, each standing for a run of pieces, coloured
 * as the run is: all there, some missing, or some missing that no connected peer has (the amber
 * cells the diagnosis talks about). The whole torrent fits at a glance however many pieces it
 * has; the per-piece truth is the Files tab's progress.
 *
 * <p>The cells are Regions styled by app.css ({@code .piece-cell.have} …), so a theme switch
 * recolours them with no repaint of ours; {@link #cells} is the pure rule.
 */
final class PieceStrip extends Pane {

    /** What one square says about its run of pieces. */
    enum Cell {
        HAVE("have"),
        MISSING("missing"),
        /** Some piece of the run is missing and on no connected peer. */
        RARE("rare");

        final String styleClass;

        Cell(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    static final double CELL = 10;
    static final double GAP = 3;
    static final int ROWS = 3;

    private boolean[] have = new boolean[0];
    private int[] availability = new int[0];
    private Cell[] shown = new Cell[0];

    PieceStrip() {
        getStyleClass().add("piece-strip");
        setMinHeight(height(ROWS));
        setPrefHeight(height(ROWS));
        widthProperty().addListener(o -> rebuild());
    }

    private static double height(int rows) {
        return rows * CELL + (rows - 1) * GAP;
    }

    /**
     * {@code count} cells over {@code have.length} pieces: cell {@code i} covers pieces
     * {@code [i·n/count, (i+1)·n/count)}, so every piece is in exactly one cell and the runs differ
     * by one at most. With fewer pieces than cells, one cell per piece. Availability counts are
     * used only when there is one per piece.
     */
    static Cell[] cells(boolean[] have, int[] availability, int count) {
        int n = have.length;
        if (n == 0 || count <= 0) return new Cell[0];
        int cells = Math.min(count, n);
        boolean counted = availability != null && availability.length == n;
        Cell[] out = new Cell[cells];
        for (int c = 0; c < cells; c++) {
            int from = (int) ((long) c * n / cells);
            int to = (int) ((long) (c + 1) * n / cells);
            Cell cell = Cell.HAVE;
            for (int i = from; i < to; i++) {
                if (have[i]) continue;
                if (counted && availability[i] == 0) {
                    cell = Cell.RARE;
                    break;
                }
                cell = Cell.MISSING;
            }
            out[c] = cell;
        }
        return out;
    }

    /** How many squares fit across {@code width}. */
    static int columns(double width) {
        return Math.max(1, (int) ((width + GAP) / (CELL + GAP)));
    }

    void show(boolean[] pieces, int[] counts) {
        have = pieces;
        availability = counts;
        rebuild();
    }

    /** The cells as shown, for the capture hook: {@code have/missing/rare} counts. */
    String summary() {
        int h = 0, m = 0, r = 0;
        for (Cell c : shown) {
            switch (c) {
                case HAVE -> h++;
                case MISSING -> m++;
                case RARE -> r++;
            }
        }
        return shown.length + " cells (" + h + " have, " + m + " missing, " + r + " rare)";
    }

    private void rebuild() {
        int columns = columns(getWidth());
        Cell[] next = cells(have, availability, columns * ROWS);
        List<javafx.scene.Node> kids = getChildren();
        while (kids.size() > next.length) kids.removeLast();
        while (kids.size() < next.length) {
            Region r = new Region();
            r.getStyleClass().add("piece-cell");
            r.resize(CELL, CELL);
            kids.add(r);
        }
        for (int i = 0; i < next.length; i++) {
            Region r = (Region) kids.get(i);
            if (i < shown.length && shown[i] == next[i]) continue;
            r.getStyleClass().removeAll("have", "missing", "rare");
            r.getStyleClass().add(next[i].styleClass);
        }
        shown = next;
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        int columns = columns(getWidth());
        // Spread the squares over the full width, as the mockup does, rather than leaving a ragged
        // margin at the right: the gap absorbs the remainder.
        double gap = columns > 1 ? (getWidth() - columns * CELL) / (columns - 1) : 0;
        List<javafx.scene.Node> kids = getChildren();
        for (int i = 0; i < kids.size(); i++) {
            int row = i / columns;
            int col = i % columns;
            kids.get(i).resizeRelocate(col * (CELL + gap), row * (CELL + GAP), CELL, CELL);
        }
    }
}
