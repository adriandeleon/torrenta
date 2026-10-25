package com.torrenta.ui;

import java.util.List;
import java.util.function.IntConsumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Background;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;

import com.torrenta.session.SpeedHistory;

/**
 * A speed graph: download and upload over the chosen window, drawn on a canvas from a
 * {@link SpeedHistory}, with the window chips above and the rate now and the peak below. The
 * geometry is {@link SpeedPlot}; this class only paints it.
 *
 * <p>Painted by {@link #show} on each poll tick while its owner is showing it, and on a resize;
 * nothing ticks of its own. Colours come from the theme: a zero-sized swatch per colour carries a
 * style class whose background app.css sets from the theme's variables, and the paint reads the
 * background CSS gave it, so a theme switch recolours the next frame.
 */
final class SpeedGraph extends VBox {

    private final Canvas canvas = new Canvas();
    private final Pane plot;
    private final ToggleGroup windows = new ToggleGroup();
    private final Label downLegend = new Label();
    private final Label upLegend = new Label();
    private final Region downSwatch = swatch("speed-graph-down");
    private final Region upSwatch = swatch("speed-graph-up");
    private final Region gridSwatch = swatch("speed-graph-grid");
    private final Region textSwatch = swatch("speed-graph-text");
    private int windowSeconds;
    private IntConsumer onWindowChanged = w -> {};
    private SpeedHistory history;
    private long now;
    private SpeedPlot lastPlot;

    /**
     * @param windowSeconds the span to start with, one of {@link SpeedHistory#WINDOWS}
     * @param traversable whether the chips take the keyboard focus: yes in a window of their own,
     *     no in the main window's chrome, whose rule is that nothing there is traversable
     */
    SpeedGraph(int windowSeconds, boolean traversable) {
        this.windowSeconds = windowSeconds;
        getStyleClass().add("speed-graph");
        setSpacing(4);

        HBox chips = new HBox(6);
        chips.setAlignment(Pos.CENTER_LEFT);
        for (int w : SpeedHistory.WINDOWS) {
            ToggleButton chip = new ToggleButton(SpeedPlot.windowLabel(w));
            chip.getStyleClass().add("search-chip");
            chip.setFocusTraversable(traversable);
            chip.setUserData(w);
            chip.setToggleGroup(windows);
            chip.setSelected(w == windowSeconds);
            chips.getChildren().add(chip);
        }
        windows.selectedToggleProperty().addListener((o, was, now) -> {
            // A group lets its one selected chip be clicked off; a window is always chosen.
            if (now == null) {
                was.setSelected(true);
                return;
            }
            int chosen = (Integer) now.getUserData();
            if (chosen != this.windowSeconds) {
                this.windowSeconds = chosen;
                paint();
                onWindowChanged.accept(chosen);
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        downLegend.getStyleClass().addAll("speed-graph-legend", "speed-graph-legend-down");
        upLegend.getStyleClass().addAll("speed-graph-legend", "speed-graph-legend-up");
        HBox head = new HBox(16, chips, spacer, downLegend, upLegend);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(6, 8, 2, 8));

        plot = new Pane(canvas, downSwatch, upSwatch, gridSwatch, textSwatch);
        plot.getStyleClass().add("speed-graph-plot");
        // Its own size, not its children's: the canvas is bound to the pane, which would otherwise
        // size itself from the canvas and never grow.
        plot.setMinHeight(60);
        plot.setPrefHeight(180);
        plot.setPrefWidth(400);
        canvas.widthProperty().bind(plot.widthProperty());
        canvas.heightProperty().bind(plot.heightProperty());
        canvas.widthProperty().addListener(o -> paint());
        canvas.heightProperty().addListener(o -> paint());
        VBox.setVgrow(plot, Priority.ALWAYS);
        getChildren().addAll(head, plot);
        showLegend(0, 0, 0, 0);
    }

    /** The plot's preferred height, under the chips and the legend; 180 unless told. */
    void setPlotHeight(double height) {
        plot.setPrefHeight(height);
    }

    /** Hears the window chips; the owner remembers the choice and tells the other graph. */
    void setOnWindowChanged(IntConsumer handler) {
        onWindowChanged = handler;
    }

    /** The other graph chose: follow, quietly if it is already this. */
    void setWindow(int seconds) {
        for (var toggle : windows.getToggles()) {
            if ((Integer) toggle.getUserData() == seconds) toggle.setSelected(true);
        }
    }

    int window() {
        return windowSeconds;
    }

    /** FX thread. The history to draw, as of {@code nowSeconds} (the history's clock). */
    void show(SpeedHistory history, long nowSeconds) {
        this.history = history;
        this.now = nowSeconds;
        paint();
    }

    /** FX thread. Nothing to draw: an empty plot and a zero legend. */
    void clear() {
        history = null;
        paint();
    }

    /** What the capture hook prints: the window, how many points, the latest and the peak. */
    String summaryForCapture() {
        SpeedPlot p = lastPlot;
        if (p == null) return "window=" + windowSeconds + " points=0";
        SpeedHistory h = history;
        var latest = h == null ? java.util.Optional.<SpeedHistory.Sample>empty() : h.latest();
        return "window=" + windowSeconds + " points=" + p.samples().size() + " ceiling=" + Bytes.rate(p.ceiling())
                + " down=" + latest.map(s -> Bytes.rate(s.download())).orElse("—")
                + " up=" + latest.map(s -> Bytes.rate(s.upload())).orElse("—")
                + " peak-down=" + Bytes.rate(p.peakDownload()) + " peak-up=" + Bytes.rate(p.peakUpload());
    }

    private void paint() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        List<SpeedHistory.Sample> samples = history == null ? List.of() : history.samples(windowSeconds, now);
        SpeedPlot plot = SpeedPlot.of(samples, windowSeconds, now, w, h);
        lastPlot = plot;
        SpeedHistory.Sample latest = history == null ? null : history.latest().orElse(null);
        showLegend(
                latest == null ? 0 : latest.download(),
                plot.peakDownload(),
                latest == null ? 0 : latest.upload(),
                plot.peakUpload());
        if (w < 10 || h < 10) return;

        Color down = colorOf(downSwatch, DOWN_FALLBACK);
        Color up = colorOf(upSwatch, UP_FALLBACK);
        Color grid = colorOf(gridSwatch, GRID_FALLBACK);
        Color text = colorOf(textSwatch, TEXT_FALLBACK);

        g.setFont(Font.font("Inter", 11));
        g.setLineWidth(1);
        for (long rate : plot.gridRates()) {
            double y = Math.round(plot.y(rate)) + 0.5;
            g.setStroke(grid);
            g.strokeLine(0, y, w, y);
            g.setFill(text);
            g.fillText(Bytes.rate(rate), 6, y - 5);
        }

        if (samples.isEmpty()) return;
        double[][] downLine = plot.downloadLine();
        double[][] upLine = plot.uploadLine();
        int n = samples.size();
        // The area under the download line, washed; then the two lines, upload under download.
        double[] areaX = new double[n + 2];
        double[] areaY = new double[n + 2];
        System.arraycopy(downLine[0], 0, areaX, 0, n);
        System.arraycopy(downLine[1], 0, areaY, 0, n);
        areaX[n] = downLine[0][n - 1];
        areaY[n] = h;
        areaX[n + 1] = downLine[0][0];
        areaY[n + 1] = h;
        g.setFill(down.deriveColor(0, 1, 1, 0.18));
        g.fillPolygon(areaX, areaY, n + 2);
        g.setLineWidth(1.5);
        g.setLineJoin(StrokeLineJoin.ROUND);
        if (n == 1) {
            g.setFill(up);
            g.fillOval(upLine[0][0] - 2, upLine[1][0] - 2, 4, 4);
            g.setFill(down);
            g.fillOval(downLine[0][0] - 2, downLine[1][0] - 2, 4, 4);
            return;
        }
        g.setStroke(up);
        g.strokePolyline(upLine[0], upLine[1], n);
        g.setStroke(down);
        g.strokePolyline(downLine[0], downLine[1], n);
    }

    private void showLegend(long down, long peakDown, long up, long peakUp) {
        downLegend.setText(StatsText.legend("↓", down, peakDown));
        upLegend.setText(StatsText.legend("↑", up, peakUp));
    }

    /** A region of no size whose background the theme paints; see the class comment. */
    private static Region swatch(String styleClass) {
        Region r = new Region();
        r.getStyleClass().add(styleClass);
        r.setManaged(false);
        r.resize(0, 0);
        r.setMouseTransparent(true);
        return r;
    }

    /** The colour the theme gave a swatch, once CSS has run; the fallback until then. */
    private static Color colorOf(Region swatch, Color fallback) {
        if (swatch.getBackground() == null && swatch.getScene() != null) swatch.applyCss();
        Background bg = swatch.getBackground();
        if (bg == null || bg.getFills().isEmpty()) return fallback;
        Paint paint = bg.getFills().get(0).getFill();
        return paint instanceof Color c ? c : fallback;
    }

    /** Caret teal, the seeding ladder's dark step, Ink's border and muted ink: the default theme's. */
    private static final Color DOWN_FALLBACK = Color.web("#22B8A6");

    private static final Color UP_FALLBACK = Color.web("#9aa2d4");
    private static final Color GRID_FALLBACK = Color.web("#2a2f3f");
    private static final Color TEXT_FALLBACK = Color.web("#8b90a3");
}
