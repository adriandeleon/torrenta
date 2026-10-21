package com.torrenta.os;

import java.awt.EventQueue;
import java.awt.Taskbar;

/**
 * The macOS dock badge: the download rate in the red roundel, cleared when nothing downloads.
 * AWT mutations go to the EDT; needs AWT headful, which {@link Badges} checks.
 */
final class AwtBadge implements Badge {

    private final Taskbar taskbar;

    AwtBadge(Taskbar taskbar) {
        this.taskbar = taskbar;
    }

    @Override
    public void update(BadgeState state) {
        String text = state.active() ? state.text() : null;
        EventQueue.invokeLater(() -> taskbar.setIconBadge(text));
    }

    @Override
    public void dispose() {
        EventQueue.invokeLater(() -> taskbar.setIconBadge(null));
    }

    @Override
    public String backend() {
        return "dock";
    }
}
