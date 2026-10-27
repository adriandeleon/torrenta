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
import com.torrenta.session.SpeedHistory;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Statistics window: the session's counters in sections (this session, all time, network,
 * disk, torrents) over the session speed graph. Non-modal and reusable, like the event log:
 * closing hides it, and the window polls the engine's counters only while it shows. The rows
 * are {@link StatsText}'s; this class puts labels under them.
 */
final class StatisticsWindow {

    /** The window's width; its height is what the sections and the graph ask for. */
    private static final double WIDTH = 760;

    private static final double PLOT_HEIGHT = 240;

    private final Stage stage = new Stage();
    private final List<StatsText.Row> layout = StatsText.rows(SessionStats.NONE);
    private final Label[] values = new Label[layout.size()];
    private final SpeedGraph graph;
    private SessionStats shown = SessionStats.NONE;

    StatisticsWindow(Window owner, int windowSeconds, IntConsumer onWindowChanged) {
        stage.initOwner(owner);
        stage.setTitle(tr("stats.title", AppInfo.NAME));
        graph = new SpeedGraph(windowSeconds, true);
        graph.setOnWindowChanged(onWindowChanged);
        graph.setMinHeight(160);

        // Two columns of sections, each one grid: a column's labels share one width, so its values
        // start at one x in every section, and a heading after the first stands off the rows above.
        List<String> names = StatsText.sections();
        HBox sections = new HBox(40);
        for (List<Integer> column : StatsText.columns()) {
            GridPane grid = column(column.stream().map(names::get).toList());
            HBox.setHgrow(grid, Priority.ALWAYS);
            sections.getChildren().add(grid);
        }
        sections.setPadding(new Insets(10, 16, 14, 16));
        // The plot taller than the detail pane's and clear of the window's bottom edge.
        graph.setPlotHeight(PLOT_HEIGHT);
        graph.setPadding(new Insets(0, 0, 10, 0));
        VBox.setVgrow(graph, Priority.ALWAYS);

        VBox root = new VBox(sections, graph);
        root.getStyleClass().add("stats-window");
        root.setPrefWidth(WIDTH);
        // As tall as the sections and the graph want: a fixed 560 cut the plot to its baseline.
        Scene scene = new Scene(root);
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

    private GridPane column(List<String> sectionNames) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(3);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints numbers = new ColumnConstraints();
        numbers.setMinWidth(Region.USE_PREF_SIZE);
        numbers.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, numbers);
        int row = 0;
        for (String name : sectionNames) {
            Label title = new Label(name);
            title.getStyleClass().add("stats-section");
            if (row > 0) title.getStyleClass().add("stats-section-next");
            grid.add(title, 0, row++, 2, 1);
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
