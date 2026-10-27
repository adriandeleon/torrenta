package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

/**
 * Which separators a menu shows once some of its items are hidden: one between two groups that
 * each still have an item, none at either end and never two in a row. A missing capability or
 * Simple mode can empty a whole group, and its separators would otherwise stand together.
 */
final class MenuSeparators {

    private MenuSeparators() {}

    /**
     * For each entry, whether it shows: a non-separator as {@code visible} says, a separator when
     * a shown item comes before it (since the last separator shown) and another comes after it.
     */
    static List<Boolean> shown(List<Boolean> separator, List<Boolean> visible) {
        int n = separator.size();
        List<Boolean> out = new ArrayList<>(n);
        boolean itemSince = false;
        for (int i = 0; i < n; i++) {
            if (!separator.get(i)) {
                out.add(visible.get(i));
                if (visible.get(i)) itemSince = true;
                continue;
            }
            boolean itemAfter = false;
            for (int j = i + 1; j < n && !itemAfter; j++) {
                if (!separator.get(j) && visible.get(j)) itemAfter = true;
            }
            boolean show = itemSince && itemAfter;
            out.add(show);
            if (show) itemSince = false;
        }
        return out;
    }

    /** FX thread. The rule applied to {@code items}, and to every submenu's within them. */
    static void tidy(List<MenuItem> items) {
        List<Boolean> separator = new ArrayList<>(items.size());
        List<Boolean> visible = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            separator.add(item instanceof SeparatorMenuItem);
            visible.add(item.isVisible());
            if (item instanceof Menu sub) tidy(sub.getItems());
        }
        List<Boolean> shown = shown(separator, visible);
        for (int i = 0; i < items.size(); i++) {
            if (separator.get(i)) items.get(i).setVisible(shown.get(i));
        }
    }
}
