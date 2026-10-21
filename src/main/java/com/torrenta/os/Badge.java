package com.torrenta.os;

/**
 * The taskbar or dock's word on download activity: the macOS dock badge with the rate, the Linux
 * launcher's count where the panel honours Unity's LauncherEntry signal. Implementations may be
 * called from any thread (the window's poll thread drives {@link #update}, at most once a second
 * and only when the state changed) and take anything slow to their own.
 */
public interface Badge {

    /** Shows {@code state}, or clears the badge when nothing downloads. */
    void update(BadgeState state);

    /** Clears the badge and lets go of what the backend holds; the instance is dead afterwards. */
    void dispose();

    /** The backend's name, for the capture hook and the log. */
    String backend();
}
