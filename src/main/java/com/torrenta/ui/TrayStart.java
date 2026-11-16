package com.torrenta.ui;

import java.time.Duration;

/**
 * What a start behind the tray icon ({@code --minimized}) does when the icon has not come. A login
 * start can run before the desktop's tray does (GNOME starts the session's programs while its
 * AppIndicator extension may still be loading, and the first look at the bus then finds no
 * watcher that a second would), so a tray that can still come is asked for again, the window
 * staying hidden meanwhile; one that will not come gives the window back minimised, as a start
 * without a tray is, never as a full window the person asked not to see.
 */
final class TrayStart {

    /** How many times a failed tray is asked for again: with {@link #RETRY_EVERY}, twenty seconds. */
    static final int RETRIES = 10;

    static final Duration RETRY_EVERY = Duration.ofSeconds(2);

    private TrayStart() {}

    /**
     * Whether a tray that failed for the {@code failures}-th time is asked for again: only while the
     * window waits hidden behind it, only where a tray can still appear, and only so many times.
     */
    static boolean tryAgain(boolean waitingHidden, boolean canComeLater, int failures) {
        return waitingHidden && canComeLater && failures <= RETRIES;
    }
}
