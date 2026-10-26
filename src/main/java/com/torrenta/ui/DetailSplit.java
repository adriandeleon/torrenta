package com.torrenta.ui;

import org.jspecify.annotations.Nullable;

/**
 * The list/detail divider's rules. The empty state ("No torrents yet") fills the list's half until
 * the first torrent, and the engine's first poll comes after the window is shown, so every launch
 * starts with it; laid out while the window was still finding its size, it pushed the divider
 * down to the detail pane's tab strip, the position listener saved that, and the pane opened
 * collapsed from then on. So a position is only remembered while there is a list beside it, and
 * the saved one is applied again when the list appears.
 */
final class DetailSplit {

    /** Where the divider sits with nothing saved: a little over half for the list. */
    static final double DEFAULT = 0.62;

    /**
     * Past this the pane below is its tab strip and little else, which is what the empty state's
     * push used to save; such a value, or one too small to leave the list a row, is not restored.
     */
    static final double MAX = 0.9;

    static final double MIN = 0.1;

    private DetailSplit() {}

    /** The saved position as a fraction of the split, or {@link #DEFAULT} for none or a bad one. */
    static double position(@Nullable String saved) {
        return position(saved, DEFAULT);
    }

    /** The same with another fallback: the pane beside the list has a default of its own. */
    static double position(@Nullable String saved, double fallback) {
        if (saved == null) return fallback;
        try {
            double d = Double.parseDouble(saved.trim());
            return d > MIN && d < MAX ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
