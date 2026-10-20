package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.torrenta.engine.Torrent;

/**
 * The pane of filters beside the list: All, then the categories and the tags, each with how many
 * torrents it holds. Choosing an entry narrows the list; the name filter narrows it further.
 *
 * <p>A tree so that sections collapse, and so that Status and Tracker sections can join later as
 * two more branches. Entries are rebuilt only when the set of names changes; the counts are
 * properties the cells watch, refreshed from every poll. Not focus-traversable: the table is
 * hidden until the first torrent, and a traversable pane here would take the first keystroke of a
 * fresh launch; a command focuses it, Enter, Space and Escape hand the focus back.
 */
final class Sidebar extends VBox {

    private static final PseudoClass SECTION = PseudoClass.getPseudoClass("section");

    /** One line: a filter with its count, or a section heading with neither. */
    static final class Entry {
        final SidebarFilter filter;
        final String title;
        final IntegerProperty count = new SimpleIntegerProperty();

        Entry(SidebarFilter filter, String title) {
            this.filter = filter;
            this.title = title;
        }
    }

    private final TreeView<Entry> tree = new TreeView<>();
    private final TreeItem<Entry> all = leaf(SidebarFilter.ALL);
    private final TreeItem<Entry> categories = section("Categories");
    private final TreeItem<Entry> tags = section("Tags");
    private final Consumer<SidebarFilter> onSelect;
    private final Runnable onLeave;
    private List<String> shownCategories = null;
    private List<String> shownTags = null;
    private SidebarFilter current = SidebarFilter.ALL;
    private boolean rebuilding;

    /**
     * @param onSelect told of every change of filter, on the FX thread
     * @param onLeave Enter, Space or Escape: the person is done here
     */
    Sidebar(Consumer<SidebarFilter> onSelect, Runnable onLeave) {
        this.onSelect = onSelect;
        this.onLeave = onLeave;
        TreeItem<Entry> root = new TreeItem<>(new Entry(null, ""));
        root.getChildren().addAll(List.of(all, categories, tags));
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setFocusTraversable(false);
        tree.getStyleClass().add("sidebar-tree");
        tree.setCellFactory(v -> new Cell());
        tree.getSelectionModel().selectedItemProperty().addListener((o, was, item) -> {
            if (rebuilding || item == null || item.getValue().filter == null) return;
            if (item.getValue().filter.equals(current)) return;
            current = item.getValue().filter;
            onSelect.accept(current);
        });
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ESCAPE) {
                onLeave.run();
                e.consume();
            }
        });
        tree.getSelectionModel().select(all);
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().add(tree);
        getStyleClass().add("sidebar");
        setPrefWidth(200);
        setMinWidth(150);
        update(List.of(), List.of(), List.of());
    }

    /**
     * FX thread. The names that exist (from the store) and the torrents (from the poll); names in
     * use but not in the store are listed too, so every count has an entry.
     */
    void update(List<String> categoryNames, List<String> tagNames, List<Torrent> torrents) {
        List<String> c = SidebarFilter.names(categoryNames, torrents, false);
        List<String> t = SidebarFilter.names(tagNames, torrents, true);
        if (!c.equals(shownCategories) || !t.equals(shownTags)) {
            shownCategories = c;
            shownTags = t;
            rebuild(categories, c, false);
            rebuild(tags, t, true);
        }
        for (TreeItem<Entry> item : leaves())
            item.getValue().count.set(item.getValue().filter.count(torrents));
        // The choice may point at an entry that is not there: not yet (a category only some
        // torrent carries, before the first poll) or no more (a deleted one). Not yet is pending
        // until it appears; no more falls back to All, and the listener hears of it.
        boolean present = leaves().stream().anyMatch(i -> i.getValue().filter.equals(current));
        if (!present) {
            current = SidebarFilter.ALL;
            tree.getSelectionModel().select(all);
            onSelect.accept(current);
        } else if (tree.getSelectionModel().getSelectedItem() == null) {
            select(current);
        }
    }

    private void rebuild(TreeItem<Entry> section, List<String> names, boolean isTags) {
        rebuilding = true;
        try {
            List<TreeItem<Entry>> items = new ArrayList<>();
            for (String name : names) items.add(leaf(isTags ? SidebarFilter.tag(name) : SidebarFilter.category(name)));
            items.add(leaf(isTags ? SidebarFilter.UNTAGGED : SidebarFilter.UNCATEGORISED));
            section.getChildren().setAll(items);
        } finally {
            rebuilding = false;
        }
    }

    /**
     * Makes the filter the choice, without telling the listener (the caller chose it): the entry
     * is highlighted if it is there; otherwise nothing is, and the next {@link #update} settles it.
     */
    boolean select(SidebarFilter filter) {
        current = filter;
        for (TreeItem<Entry> item : leaves()) {
            if (item.getValue().filter.equals(filter)) {
                item.getParent().setExpanded(true);
                tree.getSelectionModel().select(item);
                return true;
            }
        }
        tree.getSelectionModel().clearSelection();
        return false;
    }

    SidebarFilter selected() {
        return current;
    }

    void focus() {
        tree.requestFocus();
        if (tree.getSelectionModel().getSelectedItem() == null)
            tree.getSelectionModel().select(all);
    }

    boolean hasFocus() {
        return tree.isFocused();
    }

    /** Every entry with its count, section by section, for the capture hook. */
    List<String> summary() {
        List<String> out = new ArrayList<>();
        for (TreeItem<Entry> item : tree.getRoot().getChildren()) {
            Entry e = item.getValue();
            if (e.filter != null) {
                out.add(e.title + " (" + e.count.get() + ")");
                continue;
            }
            StringBuilder line = new StringBuilder(e.title).append(":");
            for (TreeItem<Entry> child : item.getChildren()) {
                line.append(' ')
                        .append(child.getValue().title)
                        .append(" (")
                        .append(child.getValue().count.get())
                        .append(")");
            }
            out.add(line.toString());
        }
        return out;
    }

    private List<TreeItem<Entry>> leaves() {
        List<TreeItem<Entry>> out = new ArrayList<>();
        out.add(all);
        out.addAll(categories.getChildren());
        out.addAll(tags.getChildren());
        return out;
    }

    private static TreeItem<Entry> leaf(SidebarFilter filter) {
        return new TreeItem<>(new Entry(filter, filter.title()));
    }

    private static TreeItem<Entry> section(String title) {
        TreeItem<Entry> item = new TreeItem<>(new Entry(null, title));
        item.setExpanded(true);
        return item;
    }

    /** Title on the left, count on the right in the muted ink; a section is bold and has no count. */
    private static final class Cell extends TreeCell<Entry> {
        private final Label title = new Label();
        private final Label count = new Label();
        private final HBox box;

        Cell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            count.getStyleClass().add("sidebar-count");
            box = new HBox(6, title, spacer, count);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            count.textProperty().unbind();
            if (empty || entry == null) {
                setGraphic(null);
                pseudoClassStateChanged(SECTION, false);
                return;
            }
            title.setText(entry.title);
            boolean section = entry.filter == null;
            pseudoClassStateChanged(SECTION, section);
            count.setVisible(!section);
            if (!section) count.textProperty().bind(entry.count.asString());
            setGraphic(box);
        }
    }
}
