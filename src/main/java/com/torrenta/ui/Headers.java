package com.torrenta.ui;

import java.util.Locale;

import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;

/**
 * Column headers as the redesign sets them: small capitals. JavaFX CSS has no text-transform, so
 * the title itself is uppercased, once, after a table's columns are in place; the column menu
 * shows the same text, which reads fine there too.
 */
final class Headers {

    private Headers() {}

    static void uppercase(TableView<?> table) {
        for (TableColumnBase<?, ?> column : table.getColumns()) uppercase(column);
    }

    static void uppercase(TreeTableView<?> table) {
        for (TableColumnBase<?, ?> column : table.getColumns()) uppercase(column);
    }

    private static void uppercase(TableColumnBase<?, ?> column) {
        String text = column.getText();
        if (text != null) column.setText(text.toUpperCase(Locale.ROOT));
    }
}
