package com.torrenta.tray;

/**
 * Pure description of what the tray icon should show: the arrow's colour.
 *
 * <p>Value-equal snapshots let the caller skip redundant repaints, which matters because the window
 * polls once a second and a tray repaint crosses a process boundary on Linux.
 *
 * @param downloading something is coming in: the arrow is teal
 * @param attention a torrent has an error that needs a person: the arrow is red, which wins
 */
public record TrayDisplay(boolean downloading, boolean attention) {

    /** Nothing coming in and nothing wrong: the arrow is muted. */
    public static TrayDisplay idle() {
        return new TrayDisplay(false, false);
    }
}
