package com.torrenta.tray;

/**
 * A platform tray or menu-bar icon. Implementations render {@link TrayDisplay} themselves (each
 * backend has its own preferred pixel sizes) and may be called from any thread: the window's poll
 * thread drives {@link #update}, and anything slow goes to the backend's own thread.
 *
 * <p>The package is free of JavaFX, as Modula's is: it is handed callbacks, and the window marshals
 * them onto the FX thread.
 */
public interface TorrentaTray {

    /** Repaints the icon, the tooltip and the menu's status line. */
    void update(TrayDisplay display, String line);

    /** Removes the icon; the instance is dead afterwards. */
    void dispose();

    /** The backend's name, for the capture hook and the log. */
    String backend();
}
