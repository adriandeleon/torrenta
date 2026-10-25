package com.torrenta.ui;

import java.util.List;

import com.torrenta.session.SpeedHistory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeedPlotTest {

    private static final double EPS = 1e-9;

    @Test
    void timeRunsLeftToRightWithNowAtTheRightEdge() {
        SpeedPlot plot = SpeedPlot.of(List.of(), 60, 1000, 300, 100);
        assertEquals(300, plot.x(1000), EPS);
        assertEquals(0, plot.x(940), EPS);
        assertEquals(150, plot.x(970), EPS);
        assertEquals(0, plot.x(900), EPS, "before the window: clamped to the edge");
        assertEquals(300, plot.x(2000), EPS, "after now: clamped too");
    }

    @Test
    void ratesRunBottomToTopAgainstTheCeiling() {
        SpeedPlot plot = SpeedPlot.of(List.of(new SpeedHistory.Sample(1000, 20_000, 0)), 60, 1000, 300, 116);
        assertEquals(32 * 1024, plot.ceiling(), "the step above 20,000 B/s");
        assertEquals(116, plot.y(0), EPS, "zero is the bottom edge");
        assertEquals(SpeedPlot.TOP_PAD, plot.y(32 * 1024), EPS, "the ceiling sits under the label room");
        assertEquals(116 - 50, plot.y(16 * 1024), EPS, "half way up a 100-pixel plot area");
        assertEquals(SpeedPlot.TOP_PAD, plot.y(1_000_000), EPS, "over the ceiling: clamped");
    }

    @Test
    void theCeilingIsTheNextStepOfTheLadder() {
        assertEquals(16 * 1024, SpeedPlot.ceiling(0), "nothing moving still has a scale");
        assertEquals(16 * 1024, SpeedPlot.ceiling(1));
        assertEquals(16 * 1024, SpeedPlot.ceiling(16 * 1024 - 1));
        assertEquals(
                32 * 1024,
                SpeedPlot.ceiling(16 * 1024),
                "a peak on a step moves to the next, so the line never touches the top");
        assertEquals(1024 * 1024, SpeedPlot.ceiling(600_000));
        assertEquals(2L * 1024 * 1024 * 1024, SpeedPlot.ceiling(1_500_000_000L));
        assertEquals(SpeedPlot.CEILING_MAX, SpeedPlot.ceiling(Long.MAX_VALUE), "and stops somewhere");
    }

    @Test
    void peaksAndLinesComeFromTheSamples() {
        List<SpeedHistory.Sample> samples = List.of(
                new SpeedHistory.Sample(998, 100, 5000),
                new SpeedHistory.Sample(999, 300, 50),
                new SpeedHistory.Sample(1000, 200, 10));
        SpeedPlot plot = SpeedPlot.of(samples, 60, 1000, 600, 100);
        assertEquals(5000, SpeedPlot.peak(samples));
        assertEquals(300, plot.peakDownload());
        assertEquals(5000, plot.peakUpload());
        double[][] down = plot.downloadLine();
        assertEquals(3, down[0].length);
        assertEquals(plot.x(998), down[0][0], EPS);
        assertEquals(plot.y(100), down[1][0], EPS);
        assertEquals(600, down[0][2], EPS, "the latest sample is at the right edge");
        double[][] up = plot.uploadLine();
        assertEquals(plot.y(5000), up[1][0], EPS);
        assertEquals(List.of(16 * 1024L, 8 * 1024L), plot.gridRates());
    }

    @Test
    void aTinyCanvasDoesNotDivideByZero() {
        SpeedPlot plot = SpeedPlot.of(List.of(), 60, 0, 0, 0);
        assertEquals(0, plot.x(0), EPS);
        assertEquals(0, plot.y(0), EPS);
        assertEquals(0 - 1, plot.y(plot.ceiling()), EPS, "the plot area is at least a pixel tall");
    }

    @Test
    void windowLabels() {
        assertEquals("1 min", SpeedPlot.windowLabel(60));
        assertEquals("10 min", SpeedPlot.windowLabel(600));
        assertEquals("1 h", SpeedPlot.windowLabel(3600));
        assertEquals("24 h", SpeedPlot.windowLabel(86400));
    }
}
