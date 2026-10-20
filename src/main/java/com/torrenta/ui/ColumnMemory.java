package com.torrenta.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.PauseTransition;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Duration;

/** Puts a {@link ColumnLayout} onto a table and reads one back; watches the table for changes. */
final class ColumnMemory {

    /** Column drags and resizes come as bursts; one write per burst is plenty. */
    private static final Duration SETTLE = Duration.millis(500);

    private ColumnMemory() {}

    /**
     * Reorders, resizes and shows or hides the table's columns to match. Columns the layout does
     * not mention keep their place after the ones it does; ids it mentions that the table lacks
     * are ignored.
     */
    static <S> void apply(TableView<S> table, List<ColumnLayout.Column> layout) {
        Map<String, TableColumn<S, ?>> byId = new LinkedHashMap<>();
        for (TableColumn<S, ?> column : table.getColumns()) byId.put(column.getId(), column);

        List<TableColumn<S, ?>> ordered = new ArrayList<>();
        for (ColumnLayout.Column c : layout) {
            TableColumn<S, ?> column = byId.remove(c.id());
            if (column == null) continue;
            if (c.width() > 0) column.setPrefWidth(c.width());
            column.setVisible(c.visible());
            ordered.add(column);
        }
        ordered.addAll(byId.values());
        // The map guarantees each column once: a TableView's column list rejects duplicates.
        if (!ordered.equals(table.getColumns())) table.getColumns().setAll(ordered);
    }

    static List<ColumnLayout.Column> capture(TableView<?> table) {
        List<ColumnLayout.Column> out = new ArrayList<>();
        for (TableColumn<?, ?> column : table.getColumns()) {
            out.add(new ColumnLayout.Column(column.getId(), column.getWidth(), column.isVisible()));
        }
        return out;
    }

    /** Calls back, debounced, after any reorder, resize or show/hide. */
    static void watch(TableView<?> table, Runnable onChange) {
        PauseTransition settle = new PauseTransition(SETTLE);
        settle.setOnFinished(e -> onChange.run());
        InvalidationListener touched = o -> settle.playFromStart();
        for (TableColumn<?, ?> column : table.getColumns()) {
            column.widthProperty().addListener(touched);
            column.visibleProperty().addListener(touched);
        }
        table.getColumns().addListener((ListChangeListener<TableColumn<?, ?>>) c -> settle.playFromStart());
    }
}
