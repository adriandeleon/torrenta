package com.torrenta.ui;

/**
 * How wide the sidebar is: what a drag of its inner edge makes it, within bounds that keep its
 * titles readable and leave the list room. Pure; {@link Sidebar} draws it, the settings keep it
 * ({@code ui.sidebar.width}, this machine's).
 */
final class SidebarWidth {

    /** A first launch's width, and what a double-click on the edge goes back to. */
    static final double DEFAULT = 200;
    /** Narrower than this and "Downloading" with its count no longer fits. */
    static final double MIN = 150;
    /** Wider than this is only empty ground beside short names. */
    static final double MAX = 480;
    /** What the list keeps however far the edge is dragged. */
    static final double LIST_ROOM = 320;
    /** How far inside the edge the grip reaches, in px. */
    static final double GRIP = 5;

    private SidebarWidth() {}

    /**
     * The width to draw, from a wanted one and the frame it sits in.
     *
     * @param frame the width of the sidebar and the list together; 0 or less while not laid out,
     *     when only the fixed bounds apply
     */
    static double clamp(double wanted, double frame) {
        double max = frame > 0 ? Math.min(MAX, frame - LIST_ROOM) : MAX;
        if (Double.isNaN(wanted)) wanted = DEFAULT;
        return Math.max(MIN, Math.min(max, wanted));
    }

    /**
     * The width after the edge moved {@code dx} px from where the drag began; the edge faces the
     * list, so on the right of it a drag to the left widens.
     */
    static double dragged(double start, double dx, boolean right, double frame) {
        return clamp(start + (right ? -dx : dx), frame);
    }

    /** Whether a point {@code x} px from the sidebar's left is on the edge that faces the list. */
    static boolean onGrip(double x, double width, boolean right) {
        return right ? x >= 0 && x <= GRIP : x <= width && x >= width - GRIP;
    }
}
