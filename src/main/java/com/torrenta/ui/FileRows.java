package com.torrenta.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.TreeItem;

import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentDetail;

/**
 * The Files tab's rows: one stable object per file, updated in place each tick, in a tree of
 * folders whose progress is the sum of their files'. The tree is rebuilt only when the set of
 * files changes (the metadata arrived, a different torrent was selected); otherwise the rows
 * keep their identity so the selection and the expanded folders survive.
 */
public final class FileRows {

    /** A file (with an index) or a folder (index -1). */
    public static final class Row {
        private final int index;
        private final String name;
        private final String path;
        private final LongProperty size = new SimpleLongProperty(this, "size");
        private final LongProperty done = new SimpleLongProperty(this, "done");
        private final ObjectProperty<Priority> priority = new SimpleObjectProperty<>(this, "priority", Priority.NORMAL);
        private final List<Row> children = new ArrayList<>();

        Row(int index, String name, String path) {
            this.index = index;
            this.name = name;
            this.path = path;
        }

        public int index() {
            return index;
        }

        public boolean isFile() {
            return index >= 0;
        }

        public String name() {
            return name;
        }

        /** The path inside the torrent, forward slashes; for a folder, its own path. */
        public String path() {
            return path;
        }

        public LongProperty sizeProperty() {
            return size;
        }

        public LongProperty doneProperty() {
            return done;
        }

        public ObjectProperty<Priority> priorityProperty() {
            return priority;
        }

        public double progress() {
            return size.get() == 0 ? 1.0 : (double) done.get() / size.get();
        }

        List<Row> children() {
            return children;
        }
    }

    /**
     * The folder a row's "open folder" shows: a folder row is that folder under the save path, a
     * file row its containing folder, which for a single-file torrent is the save path itself.
     */
    public static Path folderOf(Path savePath, Row row) {
        Path own = savePath.resolve(row.path());
        if (!row.isFile()) return own;
        Path parent = own.getParent();
        return parent == null ? savePath : parent;
    }

    private final Map<Integer, Row> byIndex = new LinkedHashMap<>();
    private Row root;
    private TreeItem<Row> rootItem;

    public TreeItem<Row> rootItem() {
        return rootItem;
    }

    public Row get(int index) {
        return byIndex.get(index);
    }

    /** True when the tree was rebuilt (so the view should re-root), false when updated in place. */
    public boolean apply(List<TorrentDetail.FileStatus> files) {
        boolean sameSet =
                files.size() == byIndex.size() && files.stream().allMatch(f -> byIndex.containsKey(f.index()));
        if (!sameSet) {
            rebuild(files);
            return true;
        }
        for (TorrentDetail.FileStatus f : files) {
            Row row = byIndex.get(f.index());
            row.size.set(f.size());
            row.done.set(f.done());
            row.priority.set(f.priority());
        }
        refreshFolders(root);
        return false;
    }

    public void clear() {
        byIndex.clear();
        root = null;
        rootItem = null;
    }

    private void rebuild(List<TorrentDetail.FileStatus> files) {
        byIndex.clear();
        root = new Row(-1, "", "");
        Map<String, Row> folders = new LinkedHashMap<>();
        for (TorrentDetail.FileStatus f : files) {
            String[] parts = f.path().split("/");
            Row parent = root;
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                key.append('/').append(parts[i]);
                Row folder = folders.get(key.toString());
                if (folder == null) {
                    folder = new Row(-1, parts[i], key.substring(1));
                    folders.put(key.toString(), folder);
                    parent.children.add(folder);
                }
                parent = folder;
            }
            Row row = new Row(f.index(), parts[parts.length - 1], f.path());
            row.size.set(f.size());
            row.done.set(f.done());
            row.priority.set(f.priority());
            parent.children.add(row);
            byIndex.put(f.index(), row);
        }
        refreshFolders(root);
        rootItem = toItem(root);
        rootItem.setExpanded(true);
        for (TreeItem<Row> child : rootItem.getChildren()) child.setExpanded(true);
    }

    private static void refreshFolders(Row row) {
        if (row == null || row.isFile()) return;
        long size = 0;
        long done = 0;
        for (Row child : row.children) {
            refreshFolders(child);
            size += child.size.get();
            done += child.done.get();
        }
        row.size.set(size);
        row.done.set(done);
    }

    private static TreeItem<Row> toItem(Row row) {
        TreeItem<Row> item = new TreeItem<>(row);
        for (Row child : row.children) item.getChildren().add(toItem(child));
        return item;
    }
}
