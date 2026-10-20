package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The transfer list's column layout as one settings value: {@code name:360,size:90,-added:140}.
 *
 * <p>Order is the column order, the number is the width, a leading {@code -} means hidden. A width
 * that cannot be read becomes {@link #DEFAULT_WIDTH}, meaning "whatever the column's default is",
 * and an id nobody knows is carried along untouched: the file is hand-editable and a typo should
 * cost one column, not the layout.
 */
public final class ColumnLayout {

    public static final double DEFAULT_WIDTH = -1;

    public record Column(String id, double width, boolean visible) {}

    private ColumnLayout() {}

    public static List<Column> parse(String value) {
        List<Column> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        for (String item : value.split(",")) {
            String entry = item.trim();
            if (entry.isEmpty()) continue;
            boolean visible = !entry.startsWith("-");
            if (!visible) entry = entry.substring(1);
            String id = entry;
            double width = DEFAULT_WIDTH;
            int colon = entry.indexOf(':');
            if (colon >= 0) {
                id = entry.substring(0, colon);
                try {
                    width = Double.parseDouble(entry.substring(colon + 1));
                    if (width <= 0 || Double.isNaN(width)) width = DEFAULT_WIDTH;
                } catch (NumberFormatException e) {
                    width = DEFAULT_WIDTH;
                }
            }
            if (!id.isEmpty()) out.add(new Column(id, width, visible));
        }
        return out;
    }

    public static String format(List<Column> columns) {
        StringBuilder out = new StringBuilder();
        for (Column c : columns) {
            if (out.length() > 0) out.append(',');
            if (!c.visible()) out.append('-');
            out.append(c.id());
            if (c.width() > 0) out.append(':').append(String.format(Locale.ROOT, "%.0f", c.width()));
        }
        return out.toString();
    }
}
