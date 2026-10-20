package com.torrenta.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.torrenta.engine.Torrent;
import com.torrenta.ui.SidebarEntries.Entry;
import com.torrenta.ui.SidebarEntries.Section;
import com.torrenta.ui.SidebarFilter.Axis;

/**
 * The pane of filters beside the list: All, then Status, Categories, Tags and Trackers, each entry
 * with how many torrents it holds. Each section keeps a choice of its own and the list shows what
 * passes all of them ({@link SidebarSelection}); the name filter narrows it further.
 *
 * <p>A tree, so that sections collapse (remembered) and Up and Down walk every section. The
 * tree's own selection is only the cursor: a click, Enter or Space makes the entry under it the
 * section's choice (Enter then returns to the list, Escape returns without choosing), and the
 * choices are the entries drawn as chosen, one per section at most. Arrows do not choose, since
 * walking from Status down to Trackers would otherwise pick a category and a tag on the way.
 * Entries and counts come from {@link SidebarEntries}, reconciled in place. Not focus-traversable:
 * the table is hidden until the first torrent, and a traversable pane here would take the first
 * keystroke of a fresh launch; a command focuses it.
 */
final class Sidebar extends VBox {

    private static final PseudoClass SECTION = PseudoClass.getPseudoClass("section");
    private static final PseudoClass CHOSEN = PseudoClass.getPseudoClass("chosen");

    private final SidebarEntries entries = new SidebarEntries();
    private final TreeView<Entry> tree = new TreeView<>();
    private final TreeItem<Entry> all;
    private final Map<Axis, TreeItem<Entry>> sections = new EnumMap<>(Axis.class);
    private final Consumer<Entry> onChoose;
    private final Consumer<Set<Axis>> onCollapse;
    private final Runnable onLeave;
    private SidebarSelection selection = SidebarSelection.ALL;
    private boolean rebuilding;

    /**
     * @param onChoose told of every entry chosen by hand, on the FX thread
     * @param onCollapse told of the collapsed sections whenever one opens or closes
     * @param onLeave Enter or Escape: the person is done here
     */
    Sidebar(Consumer<Entry> onChoose, Consumer<Set<Axis>> onCollapse, Runnable onLeave) {
        this.onChoose = onChoose;
        this.onCollapse = onCollapse;
        this.onLeave = onLeave;
        all = new TreeItem<>(entries.all);
        TreeItem<Entry> root = new TreeItem<>(null);
        root.getChildren().add(all);
        for (Section s : entries.sections()) {
            TreeItem<Entry> item = new TreeItem<>(s.heading);
            item.setExpanded(true);
            item.expandedProperty().addListener((o, was, now) -> {
                if (!rebuilding) onCollapse.accept(collapsed());
            });
            sections.put(s.axis, item);
            root.getChildren().add(item);
            mirror(s);
        }
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setFocusTraversable(false);
        tree.getStyleClass().add("sidebar-tree");
        tree.setCellFactory(v -> new Cell());
        tree.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
            if (item != null && !item.getValue().isHeading()) choose(item.getValue());
        });
        tree.getSelectionModel().select(all);
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().add(tree);
        getStyleClass().add("sidebar");
        setPrefWidth(200);
        setMinWidth(150);
    }

    private void onKey(KeyEvent e) {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        boolean heading = item != null && item.getValue().isHeading();
        switch (e.getCode()) {
            case ENTER -> {
                if (heading) item.setExpanded(!item.isExpanded());
                else {
                    if (item != null) choose(item.getValue());
                    onLeave.run();
                }
                e.consume();
            }
            case SPACE -> {
                if (heading) item.setExpanded(!item.isExpanded());
                else if (item != null) choose(item.getValue());
                e.consume();
            }
            case ESCAPE -> {
                onLeave.run();
                e.consume();
            }
            default -> {}
        }
    }

    private void choose(Entry entry) {
        onChoose.accept(entry);
    }

    /**
     * FX thread. The names that exist (from the store) and the torrents (from the poll). A choice
     * that points at an entry no longer listed (a deleted category, a tracker no torrent has) is
     * cleared, and {@code onChoose} hears of it through the section's All.
     */
    void update(List<String> categoryNames, List<String> tagNames, List<Torrent> torrents) {
        for (Section s : entries.update(categoryNames, tagNames, torrents)) mirror(s);
        for (Axis axis : entries.stale(selection, torrents))
            choose(sections.get(axis).getChildren().get(0).getValue());
    }

    private void mirror(Section section) {
        TreeItem<Entry> item = sections.get(section.axis);
        rebuilding = true;
        try {
            List<TreeItem<Entry>> items = new ArrayList<>();
            for (Entry e : section.entries()) items.add(new TreeItem<>(e));
            item.getChildren().setAll(items);
        } finally {
            rebuilding = false;
        }
    }

    /** Draws the selection as the chosen entries, without telling the listener (the caller chose it). */
    void select(SidebarSelection selection) {
        this.selection = selection;
        tree.refresh();
    }

    SidebarSelection selected() {
        return selection;
    }

    /** Collapses these sections, opens the rest; at start, from the settings. */
    void setCollapsed(Set<Axis> axes) {
        rebuilding = true;
        try {
            sections.forEach((axis, item) -> item.setExpanded(!axes.contains(axis)));
        } finally {
            rebuilding = false;
        }
    }

    Set<Axis> collapsed() {
        Set<Axis> out = EnumSet.noneOf(Axis.class);
        sections.forEach((axis, item) -> {
            if (!item.isExpanded()) out.add(axis);
        });
        return out;
    }

    /** Focus, with the cursor on the first chosen entry (All when nothing is chosen), so keys start somewhere known. */
    void focus() {
        tree.requestFocus();
        TreeItem<Entry> start = all;
        for (TreeItem<Entry> section : sections.values()) {
            for (TreeItem<Entry> item : section.getChildren()) {
                Entry e = item.getValue();
                if (e.filter.kind() != SidebarFilter.Kind.ALL && e.chosenIn(selection)) {
                    start = item;
                    break;
                }
            }
            if (start != all) break;
        }
        start.getParent().setExpanded(true);
        tree.getSelectionModel().select(start);
        tree.getFocusModel().focus(tree.getRow(start));
    }

    /** The keys go here: the scene's focus owner, which holds even while the window itself is not active. */
    boolean hasFocus() {
        return tree.getScene() != null && tree.getScene().getFocusOwner() == tree;
    }

    /** The capture hook's keyboard: the key as the tree would get it from a person. */
    void pressForCapture(KeyCode code) {
        tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    /** The cursor's entry, for the capture hook; empty when none. */
    String cursorForCapture() {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? "" : item.getValue().title;
    }

    /**
     * Every entry with its count, section by section, for the capture hook; a chosen entry is
     * starred, a collapsed section bracketed.
     */
    List<String> summary() {
        List<String> out = new ArrayList<>();
        out.add(line(entries.all));
        for (Section s : entries.sections()) {
            StringBuilder line =
                    new StringBuilder(s.title).append(sections.get(s.axis).isExpanded() ? ":" : " [collapsed]:");
            for (Entry e : s.entries()) line.append(' ').append(line(e));
            out.add(line.toString());
        }
        return out;
    }

    private String line(Entry e) {
        return e.title + (e.chosenIn(selection) ? "*" : "") + " (" + e.count.get() + ")";
    }

    /** Title on the left, count on the right in the muted ink; a section is bold and has no count. */
    private final class Cell extends TreeCell<Entry> {
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
                pseudoClassStateChanged(CHOSEN, false);
                return;
            }
            boolean section = entry.isHeading();
            title.setText(entry.title);
            pseudoClassStateChanged(SECTION, section);
            pseudoClassStateChanged(CHOSEN, entry.chosenIn(selection));
            count.setVisible(!section);
            if (!section) count.textProperty().bind(entry.count.asString());
            setGraphic(box);
        }
    }
}
