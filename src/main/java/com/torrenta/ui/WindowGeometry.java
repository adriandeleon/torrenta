package com.torrenta.ui;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the main window opens: where it was last time, if that is still on a screen, else a first
 * size wide enough for the default columns, centred. Pure: screens come in as rectangles, so the
 * rules (a monitor unplugged, a window bigger than the screen it lands on, a value edited by hand)
 * are tested without a display.
 *
 * <p>Stored as {@code ui.window.bounds=x,y,width,height} (the normal bounds, never the maximised
 * ones, so un-maximising after a restart goes back to them) and {@code ui.window.maximized}; both
 * machine-local, since another machine's screens are not this one's.
 */
final class WindowGeometry {

    /** The scene size before this class existed, and still the smallest first window. */
    static final double FALLBACK_WIDTH = 960;

    static final double FALLBACK_HEIGHT = 600;

    /** Smaller than this is not a window anyone meant to keep. */
    static final double MIN_WIDTH = 480;

    static final double MIN_HEIGHT = 320;

    /** How much of a screen a first window may take. */
    static final double FIRST_RUN_SHARE = 0.9;

    /**
     * What the list needs beside its columns: its border, the vertical scrollbar, the header's
     * column-menu corner, and the sidebar's edge.
     */
    static final double LIST_CHROME = 40;

    /** A first window's height: room for comfortable rows above the detail pane. */
    static final double FIRST_RUN_HEIGHT = 680;

    /** How much of the window's top strip must be on a screen for it to count as there. */
    static final double GRIP = 64;

    private WindowGeometry() {}

    /** A rectangle in screen coordinates. */
    record Bounds(double x, double y, double width, double height) {
        double maxX() {
            return x + width;
        }

        double maxY() {
            return y + height;
        }

        /** The area this and {@code other} share; zero when they do not meet. */
        double overlap(Bounds other) {
            double w = Math.min(maxX(), other.maxX()) - Math.max(x, other.x);
            double h = Math.min(maxY(), other.maxY()) - Math.max(y, other.y);
            return w <= 0 || h <= 0 ? 0 : w * h;
        }
    }

    /** What is stored: the normal bounds and whether the window was maximised over them. */
    record Saved(Bounds bounds, boolean maximized) {}

    /** {@code x,y,width,height}, whole pixels. */
    static String format(Bounds b) {
        return String.format(
                Locale.ROOT,
                "%d,%d,%d,%d",
                Math.round(b.x()),
                Math.round(b.y()),
                Math.round(b.width()),
                Math.round(b.height()));
    }

    /** The stored value read back; empty for anything but four numbers with a sensible size. */
    static Optional<Bounds> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String[] parts = text.split(",");
        if (parts.length != 4) return Optional.empty();
        double[] v = new double[4];
        try {
            for (int i = 0; i < 4; i++) v[i] = Double.parseDouble(parts[i].trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        for (double d : v) if (!Double.isFinite(d)) return Optional.empty();
        if (v[2] < MIN_WIDTH || v[3] < MIN_HEIGHT) return Optional.empty();
        return Optional.of(new Bounds(v[0], v[1], v[2], v[3]));
    }

    /**
     * The first window on {@code screen} (its visual bounds): wide enough for {@code wantWidth}
     * (the default columns and the sidebar) and {@code wantHeight}, never under the old 960×600,
     * never over {@link #FIRST_RUN_SHARE} of the screen, centred.
     */
    static Bounds firstRun(double wantWidth, double wantHeight, Bounds screen) {
        double width = Math.floor(Math.min(Math.max(FALLBACK_WIDTH, wantWidth), screen.width() * FIRST_RUN_SHARE));
        double height = Math.floor(Math.min(Math.max(FALLBACK_HEIGHT, wantHeight), screen.height() * FIRST_RUN_SHARE));
        return centred(width, height, screen);
    }

    /**
     * {@code saved} put back on the screens there are now: on the one it overlaps most, made no
     * bigger than that screen and moved inside it. Empty when its top strip is on no screen (the
     * monitor it was on is gone), so the caller opens a first-run window instead.
     */
    static Optional<Bounds> restore(Bounds saved, List<Bounds> screens) {
        Bounds grip = new Bounds(saved.x(), saved.y(), saved.width(), Math.min(GRIP, saved.height()));
        Bounds best = null;
        double most = 0;
        for (Bounds s : screens) {
            double o = grip.overlap(s);
            if (o > most) {
                most = o;
                best = s;
            }
        }
        // A sliver is not "on the screen": at least a grip's square of the top strip must show.
        if (best == null || most < GRIP * Math.min(GRIP, saved.height())) return Optional.empty();
        double width = Math.min(saved.width(), best.width());
        double height = Math.min(saved.height(), best.height());
        double x = Math.min(Math.max(saved.x(), best.x()), best.maxX() - width);
        double y = Math.min(Math.max(saved.y(), best.y()), best.maxY() - height);
        return Optional.of(new Bounds(x, y, width, height));
    }

    /** Where the window opens: the saved bounds if they still fit somewhere, else a first window. */
    static Bounds choose(
            Optional<Bounds> saved, List<Bounds> screens, Bounds primary, double wantWidth, double wantHeight) {
        return saved.flatMap(b -> restore(b, screens)).orElseGet(() -> firstRun(wantWidth, wantHeight, primary));
    }

    private static Bounds centred(double width, double height, Bounds screen) {
        return new Bounds(
                screen.x() + Math.floor((screen.width() - width) / 2),
                screen.y() + Math.floor((screen.height() - height) / 2),
                width,
                height);
    }
}
