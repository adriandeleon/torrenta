package com.torrenta.ui;

/**
 * What one tick of the window's poll does beyond what must go on with no window: the rule {@code
 * MainWindow.polled} follows. The snapshot, the speed histories, the share limits, the tray and the
 * badge, the port clock, the interface watch and the leak check run on every tick whatever this
 * says; it decides only the drawing.
 *
 * @param window the list, the sidebar, the status bar and the detail pane are handed to the FX
 *     thread (and the free space is read for the bar)
 * @param detail the detail pane's torrent is fetched (a status with the pieces bitfield, files,
 *     peers)
 * @param stats the Statistics window is updated
 */
record PollWork(boolean window, boolean detail, boolean stats) {

    /**
     * @param onScreen the main window is showing and not minimised ({@link #onScreen})
     * @param detailShown the detail pane is open
     * @param statsShowing the Statistics window is showing: its own stage, which can stay up while
     *     the main window is minimised, so it follows its own state, not the main window's
     */
    static PollWork of(boolean onScreen, boolean detailShown, boolean statsShowing) {
        return new PollWork(onScreen, onScreen && detailShown, statsShowing);
    }

    /** Hidden to the tray is not showing; minimised is showing but iconified. Neither is on screen. */
    static boolean onScreen(boolean showing, boolean iconified) {
        return showing && !iconified;
    }

    /**
     * The window has come back onto the screen: the ticks it skipped are made up at once with the
     * latest snapshot, not at the next poll up to a second later.
     */
    static boolean catchUp(boolean wasOnScreen, boolean onScreen) {
        return onScreen && !wasOnScreen;
    }
}
