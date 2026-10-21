package com.torrenta.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Draws the tray icon: the application's own mark, a teal arrow dropping into a periwinkle tray,
 * without the tile it sits on in the launcher. The arrow carries the state, since a tray icon that
 * never changes tells nobody anything: teal while something downloads, muted when nothing does, red
 * when a torrent has stopped on an error.
 *
 * <p>The geometry is {@code branding/torrenta-icon.svg}'s, in its 512-unit space, scaled to the
 * size asked for: the panel entry and the dock entry stay the same mark, which is how a window
 * manager tells a person the running window and the launcher are one application. A recoloured
 * arrow rather than a badge, because the mark has one focal element and that is what a glance
 * reads. Pure Java2D and headless-safe, so both backends consume it.
 */
public final class TrayIconRenderer {

    /** The icon's tray colour; the redesign's periwinkle, which the seeding state also wears. */
    private static final Color TRAY = new Color(0x9AA2D4);
    /** The icon's teal, from the middle of its gradient: one flat colour reads better at 16px. */
    private static final Color DOWNLOADING = new Color(0x2DD4BF);

    private static final Color IDLE = new Color(0x8B90A0);
    private static final Color ATTENTION = new Color(0xE5484D);

    /** The mark's bounding box in the SVG's units: tray strokes included, tile excluded. */
    private static final double LEFT = 129;

    private static final double TOP = 128;
    private static final double RIGHT = 383;
    private static final double BOTTOM = 391;
    /** Room left around the mark, as a fraction of the canvas, so round caps do not touch the edge. */
    private static final double INSET = 0.04;

    private TrayIconRenderer() {}

    public static BufferedImage render(TrayDisplay display, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Fit the mark's height, centre it; it is a little narrower than tall.
        double scale = size * (1 - 2 * INSET) / (BOTTOM - TOP);
        AffineTransform at = new AffineTransform();
        at.translate(size / 2.0, size / 2.0);
        at.scale(scale, scale);
        at.translate(-(LEFT + RIGHT) / 2, -(TOP + BOTTOM) / 2);
        g.transform(at);

        // The tray: open at the top, where the arrow lands.
        Path2D tray = new Path2D.Double();
        tray.moveTo(146, 302);
        tray.lineTo(146, 348);
        tray.quadTo(146, 374, 172, 374);
        tray.lineTo(340, 374);
        tray.quadTo(366, 374, 366, 348);
        tray.lineTo(366, 302);
        g.setColor(TRAY);
        g.setStroke(new BasicStroke(34, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(tray);

        // The arrow: shaft and head in one colour, the state's.
        g.setColor(colorFor(display));
        g.fill(new RoundRectangle2D.Double(232, 128, 48, 150, 28, 28));
        Path2D head = new Path2D.Double();
        head.moveTo(186, 232);
        head.lineTo(256, 304);
        head.lineTo(326, 232);
        g.setStroke(new BasicStroke(46, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(head);

        g.dispose();
        return image;
    }

    static Color colorFor(TrayDisplay display) {
        if (display.attention()) return ATTENTION;
        return display.downloading() ? DOWNLOADING : IDLE;
    }
}
