package com.torrenta.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.torrenta.engine.Torrent;
import com.torrenta.ui.SidebarEntries.Entry;
import com.torrenta.ui.SidebarEntries.Section;
import com.torrenta.ui.SidebarFilter.Axis;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * The pane of filters beside the list: All, then Status, Categories, Tags and Trackers, each entry
 * with how many torrents it holds. Each section keeps a choice of its own and the list shows what
 * passes all of them ({@link SidebarSelection}); the name filter narrows it further.
 *
 * <p>A tree, so that sections collapse (remembered; a click on the heading or its chevron, Enter
 * or Space on it) and Up and Down walk every section. The
 * tree's own selection is only the cursor: a click, Enter or Space makes the entry under it the
 * section's choice (Enter then returns to the list, Escape returns without choosing), and the
 * choices are the entries drawn as chosen, one per section at most. Arrows do not choose, since
 * walking from Status down to Trackers would otherwise pick a category and a tag on the way.
 * Entries and counts come from {@link SidebarEntries}, reconciled in place. Not focus-traversable:
 * the table is hidden until the first torrent, and a traversable pane here would take the first
 * keystroke of a fresh launch; a command focuses it.
 *
 * <p>The edge that faces the list drags to resize ({@link SidebarWidth}, remembered); a
 * double-click on it goes back to the default.
 */
final class Sidebar extends VBox {

    private static final PseudoClass SECTION = PseudoClass.getPseudoClass("section");
    private static final PseudoClass CHOSEN = PseudoClass.getPseudoClass("chosen");
    /** A saved view whose query no longer reads: struck through, the reason in its tooltip. */
    private static final PseudoClass BROKEN = PseudoClass.getPseudoClass("broken");

    private final SidebarEntries entries = new SidebarEntries();
    private final TreeView<Entry> tree = new TreeView<>();
    private final TreeItem<Entry> all;
    private final Map<Axis, TreeItem<Entry>> sections = new EnumMap<>(Axis.class);

    /** Every axis has its section from the constructor on. */
    private TreeItem<Entry> section(Axis axis) {
        return java.util.Objects.requireNonNull(sections.get(axis), "a section per axis");
    }

    private final Consumer<Entry> onChoose;
    private final Consumer<Set<Axis>> onCollapse;
    private final Runnable onLeave;
    private final DoubleConsumer onResize;
    private SidebarSelection selection = SidebarSelection.ALL;
    private @Nullable ViewActions viewActions;

    /** What the menu on a saved view does: the window's, by the view's slug. */
    interface ViewActions {
        void rename(String slug);

        /** The omnibox's query becomes the view's. */
        void useCurrentFilter(String slug);

        /** There is a query in the omnibox to use. */
        boolean canUseCurrentFilter();

        void delete(String slug);
    }

    void setViewActions(ViewActions actions) {
        this.viewActions = actions;
        tree.refresh();
    }

    /** The menu on a saved view: Rename…, Use the Current Filter, Delete…. */
    private javafx.scene.control.ContextMenu viewMenu(ViewActions viewActions, String slug) {
        javafx.scene.control.MenuItem rename = new javafx.scene.control.MenuItem(tr("sidebar.view.rename"));
        rename.setOnAction(e -> viewActions.rename(slug));
        javafx.scene.control.MenuItem use = new javafx.scene.control.MenuItem(tr("sidebar.view.useFilter"));
        use.setOnAction(e -> viewActions.useCurrentFilter(slug));
        javafx.scene.control.MenuItem delete = new javafx.scene.control.MenuItem(tr("sidebar.view.delete"));
        delete.setOnAction(e -> viewActions.delete(slug));
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(rename, use, delete);
        menu.setOnShowing(e -> use.setDisable(!viewActions.canUseCurrentFilter()));
        return menu;
    }

    private boolean rebuilding;
    /** On the right of the list, so the grip is the left edge. */
    private boolean right;
    /** The drag in progress: where it began on the screen and the width then; NaN when none. */
    private double dragX = Double.NaN;

    private double dragWidth;
    /** The press was on the grip, so its click is the grip's too and never the tree's. */
    private boolean gripPressed;

    /**
     * @param onChoose told of every entry chosen by hand, on the FX thread
     * @param onCollapse told of the collapsed sections whenever one opens or closes
     * @param onLeave Enter or Escape: the person is done here
     * @param onResize told of the width a drag of the edge ended at, or a double-click reset it to
     */
    Sidebar(Consumer<Entry> onChoose, Consumer<Set<Axis>> onCollapse, Runnable onLeave, DoubleConsumer onResize) {
        this.onChoose = onChoose;
        this.onCollapse = onCollapse;
        this.onLeave = onLeave;
        this.onResize = onResize;
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
        if (section(Axis.VIEW).getChildren().isEmpty()) root.getChildren().remove(section(Axis.VIEW));
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
        setPrefWidth(SidebarWidth.DEFAULT);
        setMinWidth(SidebarWidth.MIN);
        // Filters, so the grip is the sidebar's before any cell or the scrollbar sees the mouse.
        addEventFilter(MouseEvent.MOUSE_MOVED, e -> setCursor(onGrip(e) ? Cursor.H_RESIZE : null));
        addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (Double.isNaN(dragX)) setCursor(null);
        });
        addEventFilter(MouseEvent.MOUSE_PRESSED, this::onGripPressed);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (Double.isNaN(dragX)) return;
            setSidebarWidth(SidebarWidth.dragged(dragWidth, e.getScreenX() - dragX, right, frameWidth()));
            e.consume();
        });
        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (Double.isNaN(dragX)) return;
            dragX = Double.NaN;
            if (!onGrip(e)) setCursor(null);
            onResize.accept(getPrefWidth());
            e.consume();
        });
        addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (gripPressed) e.consume();
        });
    }

    private void onGripPressed(MouseEvent e) {
        gripPressed = e.getButton() == MouseButton.PRIMARY && onGrip(e);
        if (!gripPressed) return;
        e.consume();
        if (e.getClickCount() == 2) {
            setSidebarWidth(SidebarWidth.DEFAULT);
            onResize.accept(getPrefWidth());
            return;
        }
        dragX = e.getScreenX();
        dragWidth = getWidth();
    }

    private boolean onGrip(MouseEvent e) {
        return SidebarWidth.onGrip(e.getX(), getWidth(), right);
    }

    /** The sidebar and the list together: the room a width is clamped to. */
    private double frameWidth() {
        return getParent() instanceof Region parent ? parent.getWidth() : 0;
    }

    /** The width to draw, within {@link SidebarWidth}'s bounds; at start, from the settings. */
    void setSidebarWidth(double width) {
        setPrefWidth(SidebarWidth.clamp(width, frameWidth()));
    }

    /** Which side of the list it sits on: the grip is the edge that faces the list. */
    void setRight(boolean right) {
        this.right = right;
    }

    /** The capture hook's mouse: a drag of the grip by {@code dx} px, through the real handlers. */
    void dragForCapture(double dx) {
        double x = right ? 1 : getWidth() - 1;
        fireEvent(mouse(this, MouseEvent.MOUSE_PRESSED, x, 0));
        fireEvent(mouse(this, MouseEvent.MOUSE_DRAGGED, x, dx));
        fireEvent(mouse(this, MouseEvent.MOUSE_RELEASED, x, dx));
    }

    /** The capture hook's mouse: a click on a section's heading, by its axis name. */
    void clickHeadingForCapture(String axis) {
        TreeItem<Entry> item = sections.get(Axis.valueOf(axis.toUpperCase(java.util.Locale.ROOT)));
        for (javafx.scene.Node n : tree.lookupAll(".tree-cell")) {
            if (n instanceof Cell cell && cell.getTreeItem() == item) {
                cell.fireEvent(mouse(cell, MouseEvent.MOUSE_PRESSED, 20, 0));
                cell.fireEvent(mouse(cell, MouseEvent.MOUSE_RELEASED, 20, 0));
                cell.fireEvent(mouse(cell, MouseEvent.MOUSE_CLICKED, 20, 0));
                return;
            }
        }
    }

    /** A primary-button event at {@code x} px across {@code node} (then {@code dx} further), as the scene would make it. */
    private static MouseEvent mouse(
            javafx.scene.Node node, javafx.event.EventType<MouseEvent> type, double x, double dx) {
        javafx.geometry.Point2D scene = node.localToScene(x, 8).add(dx, 0);
        javafx.geometry.Point2D screen = node.localToScreen(x, 8).add(dx, 0);
        return new MouseEvent(
                type,
                scene.getX(),
                scene.getY(),
                screen.getX(),
                screen.getY(),
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null);
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
        update(List.of(), categoryNames, tagNames, torrents);
    }

    /** {@link #update}, with the saved views. */
    void update(
            List<SidebarEntries.View> views,
            List<String> categoryNames,
            List<String> tagNames,
            List<Torrent> torrents) {
        for (Section s : entries.update(views, categoryNames, tagNames, torrents)) mirror(s);
        for (Axis axis : entries.stale(selection, torrents)) {
            // The Views section is empty once its last view is deleted: its All is not listed then.
            List<TreeItem<Entry>> listed = section(axis).getChildren();
            choose(
                    listed.isEmpty()
                            ? new Entry(SidebarFilter.ALL, axis)
                            : listed.getFirst().getValue());
        }
    }

    private void mirror(Section section) {
        TreeItem<Entry> item = section(section.axis);
        rebuilding = true;
        try {
            List<TreeItem<Entry>> items = new ArrayList<>();
            for (Entry e : section.entries()) items.add(new TreeItem<>(e));
            item.getChildren().setAll(items);
            // Views appear with the first one saved: until then the section is not there at all.
            if (section.axis == Axis.VIEW) {
                List<TreeItem<Entry>> top =
                        tree.getRoot() == null ? null : tree.getRoot().getChildren();
                if (top != null) {
                    boolean shown = top.contains(item);
                    if (items.isEmpty() && shown) top.remove(item);
                    else if (!items.isEmpty() && !shown) top.add(1, item);
                }
            }
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
                    new StringBuilder(s.title).append(section(s.axis).isExpanded() ? ":" : " [collapsed]:");
            for (Entry e : s.entries()) line.append(' ').append(line(e));
            out.add(line.toString());
        }
        return out;
    }

    /** The dot's colour class for a status, keyed to the same state colours the rows use. */
    static String dotClass(SidebarStatus status) {
        return switch (status) {
            case DOWNLOADING, ACTIVE -> "dot-down";
            case SEEDING -> "dot-up";
            case COMPLETED -> "dot-done";
            case STALLED -> "dot-stall";
            case ERRORED -> "dot-error";
            case PAUSED, INACTIVE, CHECKING, LINKS -> "dot-idle";
        };
    }

    private String line(Entry e) {
        return e.title + (e.chosenIn(selection) ? "*" : "") + " (" + e.count.get() + ")"
                + (e.problem.isEmpty() ? "" : " [" + e.problem + "]");
    }

    /**
     * A mark, the title, then the count at the right edge in the muted ink. The mark is a dot in
     * the state's colour for a status, a small folder for a category, nothing otherwise; a section
     * is small capitals with a chevron where the count would be, and a press anywhere on it folds
     * or opens it (the tree's own double-click toggle is held back, so each press is one fold).
     */
    private final class Cell extends TreeCell<Entry> {
        private final Label title = new Label();
        private final Label count = new Label();
        private final Region dot = new Region();
        private final javafx.scene.Node folder = Icons.folderSmall();
        /** Downloads from links get the chain their rows carry, not a state's dot. */
        private final javafx.scene.Node link = Icons.linkSmall();

        private final javafx.scene.Node chevron = Icons.chevronSmall();
        private final HBox box;

        Cell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            count.getStyleClass().add("sidebar-count");
            dot.getStyleClass().add("sidebar-dot");
            folder.getStyleClass().add("sidebar-folder");
            link.getStyleClass().add("sidebar-folder");
            chevron.getStyleClass().add("sidebar-chevron");
            box = new HBox(8, title, spacer, count);
            box.setAlignment(Pos.CENTER_LEFT);
            addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                TreeItem<Entry> item = getTreeItem();
                if (e.getButton() != MouseButton.PRIMARY || item == null || item.getValue() == null) return;
                if (!item.getValue().isHeading()) return;
                item.setExpanded(!item.isExpanded());
                if (e.getClickCount() > 1) e.consume();
            });
        }

        /** The count, or a section's chevron, at the right edge. */
        private void tail(javafx.scene.Node node) {
            if (box.getChildren().getLast() != node)
                box.getChildren().set(box.getChildren().size() - 1, node);
        }

        private void mark(javafx.scene.@Nullable Node node) {
            if (node == null) {
                if (box.getChildren().size() == 4) box.getChildren().removeFirst();
            } else if (box.getChildren().size() == 3) {
                box.getChildren().addFirst(node);
            } else if (box.getChildren().getFirst() != node) {
                box.getChildren().set(0, node);
            }
        }

        private javafx.scene.@Nullable Node markFor(Entry entry) {
            if (entry.isHeading()) return null;
            // Only the All above the sections gets a dot; a section's own All stands bare.
            if (entry.axis == Axis.ALL) {
                dot.getStyleClass().removeIf(c -> c.startsWith("dot-"));
                dot.getStyleClass().add("dot-all");
                return dot;
            }
            return switch (entry.filter.kind()) {
                case STATUS -> {
                    if (entry.filter.status() == SidebarStatus.LINKS) yield link;
                    dot.getStyleClass().removeIf(c -> c.startsWith("dot-"));
                    dot.getStyleClass().add(dotClass(entry.filter.status()));
                    yield dot;
                }
                case CATEGORY, UNCATEGORISED -> folder;
                default -> null;
            };
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            count.textProperty().unbind();
            chevron.rotateProperty().unbind();
            if (empty || entry == null) {
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                pseudoClassStateChanged(SECTION, false);
                pseudoClassStateChanged(CHOSEN, false);
                pseudoClassStateChanged(BROKEN, false);
                return;
            }
            pseudoClassStateChanged(BROKEN, !entry.problem.isEmpty());
            ViewActions actions = viewActions;
            setContextMenu(
                    entry.filter.kind() == SidebarFilter.Kind.VIEW && actions != null
                            ? viewMenu(actions, entry.filter.value())
                            : null);
            setTooltip(entry.problem.isEmpty() ? null : new javafx.scene.control.Tooltip(entry.problem));
            boolean section = entry.isHeading();
            title.setText(section ? entry.title.toUpperCase(java.util.Locale.ROOT) : entry.title);
            pseudoClassStateChanged(SECTION, section);
            mark(markFor(entry));
            pseudoClassStateChanged(CHOSEN, entry.chosenIn(selection));
            if (section) {
                tail(chevron);
                chevron.rotateProperty()
                        .bind(javafx.beans.binding.Bindings.when(getTreeItem().expandedProperty())
                                .then(0.0)
                                .otherwise(-90.0));
            } else {
                tail(count);
                count.textProperty().bind(entry.count.asString());
            }
            setGraphic(box);
        }
    }
}
