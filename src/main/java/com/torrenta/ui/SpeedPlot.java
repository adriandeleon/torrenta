package com.torrenta.ui;

import java.util.List;

import static com.torrenta.i18n.Messages.tr;

/**
 * Where a speed graph puts its points: the pure half of {@link SpeedGraph}, so the mapping is
 * tested rather than eyeballed. Time runs left to right with {@code now} at the right edge and
 * {@code now - window} at the left; rates run bottom to top against a ceiling that is the next
 * step of a binary ladder above the window's peak, so the line never touches the frame and the
 * scale does not twitch with every sample.
 *
 * @param windowSeconds the span shown
 * @param now the right edge, in the history's seconds
 * @param width the canvas width
 * @param height the canvas height
 * @param ceiling the rate at the top of the plot area, bytes per second
 * @param samples what is drawn, oldest first
 */
public record SpeedPlot(
        int windowSeconds, long now, double width, double height, long ceiling, List<SpeedHistory.Sample> samples) {

    /** Room above the ceiling line for its label. */
    static final double TOP_PAD = 16;
    /** The lowest ceiling: a graph of nothing still has a scale. */
    static final long FLOOR = 16 * 1024;
    /** The ceiling climbs by doubling: every step labels cleanly in KiB and MiB. */
    static final long CEILING_MAX = 1L << 40;

    public static SpeedPlot of(
            List<SpeedHistory.Sample> samples, int windowSeconds, long now, double width, double height) {
        return new SpeedPlot(windowSeconds, now, width, height, ceiling(peak(samples)), samples);
    }

    /** The highest rate, down or up, in the samples; 0 for none. */
    public static long peak(List<SpeedHistory.Sample> samples) {
        long peak = 0;
        for (SpeedHistory.Sample s : samples) peak = Math.max(peak, Math.max(s.download(), s.upload()));
        return peak;
    }

    public long peakDownload() {
        long peak = 0;
        for (SpeedHistory.Sample s : samples) peak = Math.max(peak, s.download());
        return peak;
    }

    public long peakUpload() {
        long peak = 0;
        for (SpeedHistory.Sample s : samples) peak = Math.max(peak, s.upload());
        return peak;
    }

    /** The smallest power-of-two multiple of 16 KiB that is above the peak (a peak on a step moves up). */
    public static long ceiling(long peak) {
        long c = FLOOR;
        while (c <= peak && c < CEILING_MAX) c *= 2;
        return c;
    }

    /** The x of a moment: the left edge for {@code now - window}, the right edge for {@code now}. */
    public double x(long at) {
        long left = now - windowSeconds;
        double fraction = (double) (at - left) / windowSeconds;
        return Math.clamp(fraction, 0, 1) * width;
    }

    /** The y of a rate: the bottom edge for 0, {@link #TOP_PAD} from the top for the ceiling. */
    public double y(long rate) {
        double plot = Math.max(1, height - TOP_PAD);
        double fraction = Math.clamp((double) rate / ceiling, 0, 1);
        return height - fraction * plot;
    }

    /** The rates a grid line is drawn at, with a label: the ceiling and its half. */
    public List<Long> gridRates() {
        return List.of(ceiling, ceiling / 2);
    }

    /** The download line's points, x then y per sample, oldest first. */
    public double[][] downloadLine() {
        return line(true);
    }

    public double[][] uploadLine() {
        return line(false);
    }

    private double[][] line(boolean download) {
        double[] xs = new double[samples.size()];
        double[] ys = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            SpeedHistory.Sample s = samples.get(i);
            xs[i] = x(s.at());
            ys[i] = y(download ? s.download() : s.upload());
        }
        return new double[][] {xs, ys};
    }

    /** The window's name for a chip: {@code 1 min}, {@code 10 min}, {@code 1 h}, {@code 24 h}. */
    public static String windowLabel(int seconds) {
        if (seconds < 3600) return tr("stats.window.minutes", seconds / 60);
        return tr("stats.window.hours", seconds / 3600);
    }
}
