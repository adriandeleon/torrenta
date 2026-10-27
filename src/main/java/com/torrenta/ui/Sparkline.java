package com.torrenta.ui;

import java.util.List;

import javafx.scene.layout.Region;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;

import com.torrenta.session.SpeedHistory;

/**
 * A small rate graph with no axes: the status bar's minute of download or upload, the Overview's
 * half hour of both. Shapes rather than a canvas, so app.css colours them from the theme
 * ({@code .spark-down}, {@code .spark-up}, {@code .spark-fill}) and a theme switch needs nothing of
 * ours. {@link #points} is the pure mapping.
 */
final class Sparkline extends Region {

    /** Which rates it draws. */
    enum Series {
        DOWN,
        UP,
        BOTH
    }

    /** Below this the line stays flat: a trickle of protocol bytes is not a mountain. */
    static final long FLOOR = 1024;

    private final Series series;
    /** Draw the stretch before the first sample as zero: true of a session, which starts idle. */
    private boolean zeroBefore;

    private final Polygon downFill = new Polygon();
    private final Polyline down = new Polyline();
    private final Polyline up = new Polyline();
    private List<SpeedHistory.Sample> samples = List.of();
    private int window = 60;
    private long now;

    Sparkline(Series series, double width, double height) {
        this.series = series;
        getStyleClass().add("sparkline");
        downFill.getStyleClass().add("spark-fill");
        down.getStyleClass().add("spark-down");
        up.getStyleClass().add("spark-up");
        switch (series) {
            case DOWN -> getChildren().addAll(down);
            case UP -> getChildren().addAll(up);
            case BOTH -> getChildren().addAll(downFill, up, down);
        }
        setPrefSize(width, height);
        setMinSize(Region.USE_PREF_SIZE, height);
        widthProperty().addListener(o -> redraw());
        heightProperty().addListener(o -> redraw());
    }

    /** See {@link #zeroBefore}; off by default, since a torrent's past before it was watched is unknown. */
    void setZeroBefore(boolean on) {
        zeroBefore = on;
    }

    void show(List<SpeedHistory.Sample> samples, int windowSeconds, long nowSeconds) {
        this.samples = samples;
        this.window = windowSeconds;
        this.now = nowSeconds;
        redraw();
    }

    void clear() {
        show(List.of(), window, now);
    }

    private void redraw() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        long ceiling = ceiling(samples, series);
        double[] d = lead(points(samples, window, now, w, h, ceiling, true), h);
        double[] u = lead(points(samples, window, now, w, h, ceiling, false), h);
        down.getPoints().setAll(boxed(d));
        up.getPoints().setAll(boxed(u));
        downFill.getPoints().setAll(boxed(area(d, h)));
    }

    /**
     * The scale's top: a little over the highest rate drawn, never under {@link #FLOOR}. One scale
     * for both lines when both are drawn, so the two compare.
     */
    static long ceiling(List<SpeedHistory.Sample> samples, Series series) {
        long peak = 0;
        for (SpeedHistory.Sample s : samples) {
            if (series != Series.UP) peak = Math.max(peak, s.download());
            if (series != Series.DOWN) peak = Math.max(peak, s.upload());
        }
        return Math.max(FLOOR, peak + peak / 8);
    }

    /**
     * The line's points, x then y, oldest first: x from the window's left edge ({@code now -
     * window}) to the right ({@code now}), y from the bottom (0) to the top ({@code ceiling}), a
     * pixel in from each edge so the stroke is not clipped. No samples is a flat line along the
     * bottom; one sample is a flat line at its height.
     */
    static double[] points(
            List<SpeedHistory.Sample> samples,
            int windowSeconds,
            long now,
            double width,
            double height,
            long ceiling,
            boolean download) {
        double top = 1;
        double bottom = height - 1;
        if (samples.isEmpty()) return new double[] {0, bottom, width, bottom};
        if (samples.size() == 1) {
            double y = y(rate(samples.getFirst(), download), ceiling, top, bottom);
            return new double[] {0, y, width, y};
        }
        double[] out = new double[samples.size() * 2];
        long left = now - windowSeconds;
        for (int i = 0; i < samples.size(); i++) {
            SpeedHistory.Sample s = samples.get(i);
            double fraction = Math.clamp((double) (s.at() - left) / windowSeconds, 0, 1);
            out[2 * i] = fraction * width;
            out[2 * i + 1] = y(rate(s, download), ceiling, top, bottom);
        }
        return out;
    }

    private double[] lead(double[] line, double height) {
        return zeroBefore ? zeroBefore(line, height) : line;
    }

    /**
     * The line with a flat stretch along the bottom from the left edge to its first point, when it
     * starts later than the left edge.
     */
    static double[] zeroBefore(double[] line, double height) {
        if (line.length < 2 || line[0] <= 0) return line;
        double[] out = new double[line.length + 4];
        out[0] = 0;
        out[1] = height - 1;
        out[2] = line[0];
        out[3] = height - 1;
        System.arraycopy(line, 0, out, 4, line.length);
        return out;
    }

    /**
     * The window a graph of at most {@code maxSeconds} should show for samples starting at the
     * first one's time: the whole of it once the history covers it, else the history's span
     * rounded up to whole minutes, never under one. A torrent selected a minute ago gets a
     * minute's graph, not a sliver at the right edge of half an hour.
     */
    static int window(List<SpeedHistory.Sample> samples, long now, int maxSeconds) {
        if (samples.isEmpty()) return 60;
        long span = now - samples.getFirst().at() + 1;
        long minutes = Math.max(1, (span + 59) / 60);
        return (int) Math.min(maxSeconds, minutes * 60);
    }

    /** The line closed down to the baseline at both ends: the filled area under it. */
    static double[] area(double[] line, double height) {
        if (line.length < 4) return new double[0];
        double[] out = new double[line.length + 4];
        System.arraycopy(line, 0, out, 0, line.length);
        out[line.length] = line[line.length - 2];
        out[line.length + 1] = height;
        out[line.length + 2] = line[0];
        out[line.length + 3] = height;
        return out;
    }

    private static long rate(SpeedHistory.Sample s, boolean download) {
        return download ? s.download() : s.upload();
    }

    private static double y(long rate, long ceiling, double top, double bottom) {
        double fraction = Math.clamp((double) rate / Math.max(1, ceiling), 0, 1);
        return bottom - fraction * (bottom - top);
    }

    private static Double[] boxed(double[] values) {
        Double[] out = new Double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }
}
