package com.torrenta.ui;

import java.util.List;
import java.util.function.IntConsumer;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.AppInfo;
import com.torrenta.engine.SessionStats;

/**
 * The Statistics window: the session's counters in sections (this session, all time, network,
 * disk, torrents) over the session speed graph. Non-modal and reusable, like the event log:
 * closing hides it, and the window polls the engine's counters only while it shows. The rows
 * are {@link StatsText}'s; this class puts labels under them.
 */
final class StatisticsWindow {

    private final Stage stage = new Stage();
    private final List<StatsText.Row> layout = StatsText.rows(SessionStats.NONE);
    private final Label[] values = new Label[layout.size()];
    private final SpeedGraph graph;
    private SessionStats shown = SessionStats.NONE;

    StatisticsWindow(Window owner, int windowSeconds, IntConsumer onWindowChanged) {
        stage.initOwner(owner);
        stage.setTitle(AppInfo.NAME + " — Statistics");
        graph = new SpeedGraph(windowSeconds, true);
        graph.setOnWindowChanged(onWindowChanged);
        graph.setMinHeight(160);

        // Two columns of sections: the two about bytes on the left, the three gauges on the right.
        VBox left = new VBox(section(StatsText.SECTIONS.get(0)), section(StatsText.SECTIONS.get(1)));
        VBox right = new VBox(
                section(StatsText.SECTIONS.get(2)),
                section(StatsText.SECTIONS.get(3)),
                section(StatsText.SECTIONS.get(4)));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox sections = new HBox(32, left, right);
        sections.setPadding(new Insets(10, 16, 10, 16));
        VBox.setVgrow(graph, Priority.ALWAYS);

        VBox root = new VBox(sections, graph);
        root.getStyleClass().add("stats-window");
        Scene scene = new Scene(root, 760, 560);
        scene.getStylesheets()
                .add(StatisticsWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });
        stage.setScene(scene);
    }

    private GridPane section(String name) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(3);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints numbers = new ColumnConstraints();
        numbers.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, numbers);
        Label title = new Label(name);
        title.getStyleClass().add("stats-section");
        grid.add(title, 0, 0, 2, 1);
        int row = 1;
        for (int i = 0; i < layout.size(); i++) {
            if (!layout.get(i).section().equals(name)) continue;
            Label label = new Label(layout.get(i).label());
            label.getStyleClass().add("dialog-label");
            Label value = new Label(layout.get(i).value());
            value.getStyleClass().add("stats-value");
            values[i] = value;
            grid.add(label, 0, row);
            grid.add(value, 1, row);
            row++;
        }
        return grid;
    }

    /** Shows, or brings to the front; hidden by its own close button or Esc. */
    void show() {
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
        } else {
            stage.show();
        }
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    /** For the window's poll thread to know whether the counters are wanted this tick. */
    ReadOnlyBooleanProperty showingProperty() {
        return stage.showingProperty();
    }

    /** For a capture of the window itself. */
    Scene scene() {
        return stage.getScene();
    }

    SpeedGraph graph() {
        return graph;
    }

    /** FX thread. A fresh reading and the session history, as of {@code nowSeconds}. */
    void update(SessionStats stats, SpeedHistory history, long nowSeconds) {
        shown = stats;
        List<StatsText.Row> rows = StatsText.rows(stats);
        for (int i = 0; i < rows.size(); i++) {
            String text = rows.get(i).value();
            if (!text.equals(values[i].getText())) values[i].setText(text);
        }
        graph.show(history, nowSeconds);
    }

    /** What the capture hook prints: every row as {@code label=value}. */
    String summaryForCapture() {
        return StatsText.summary(shown);
    }
}
