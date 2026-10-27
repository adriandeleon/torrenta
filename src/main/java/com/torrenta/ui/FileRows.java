package com.torrenta.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.TreeItem;

import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentDetail;
import org.jspecify.annotations.Nullable;

/**
 * The Files tab's rows: one stable object per file, updated in place each tick, in a tree of
 * folders whose progress is the sum of their files'. The tree is rebuilt only when the set of
 * files changes (the metadata arrived, a different torrent was selected, a file was renamed);
 * otherwise the rows
 * keep their identity so the selection and the expanded folders survive. An update touches only
 * the files whose status changed, and moves only the folders above them by the difference: a
 * torrent of 50,000 files mostly has a few changing at a time.
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
        /** The folder it is in; null for the root. */
        private @Nullable Row parent;
        /** What the engine said last, for a file: an update with the same changes nothing. */
        private TorrentDetail.@Nullable FileStatus status;
        /** Found on disk in another torrent and put in place at the add; null when downloaded. */
        private com.torrenta.engine.@Nullable FoundFile found;

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

        /** How the file came from disk rather than the swarm, and from which torrent; null for neither. */
        public com.torrenta.engine.@Nullable FoundFile found() {
            return found;
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
    private @Nullable Row root;
    private @Nullable TreeItem<Row> rootItem;

    public @Nullable TreeItem<Row> rootItem() {
        return rootItem;
    }

    public @Nullable Row get(int index) {
        return byIndex.get(index);
    }

    /** True when the tree was rebuilt (so the view should re-root), false when updated in place. */
    public boolean apply(List<TorrentDetail.FileStatus> files) {
        // The same files under the same names: a rename moves a row in the tree, so it rebuilds.
        boolean sameSet = files.size() == byIndex.size()
                && files.stream()
                        .allMatch(f -> byIndex.containsKey(f.index())
                                && byIndex.get(f.index()).path().equals(f.path()));
        if (!sameSet) {
            rebuild(files);
            return true;
        }
        changed = 0;
        for (TorrentDetail.FileStatus f : files) {
            Row row = Objects.requireNonNull(byIndex.get(f.index()), "sameSet");
            if (f.equals(row.status)) continue;
            changed++;
            long size = f.size() - row.size.get();
            long done = f.done() - row.done.get();
            set(row, f);
            if (size == 0 && done == 0) continue;
            for (Row folder = row.parent; folder != null; folder = folder.parent) {
                folder.size.set(folder.size.get() + size);
                folder.done.set(folder.done.get() + done);
            }
        }
        return false;
    }

    /** How many files the last in-place {@link #apply} updated; for the tests. */
    int changedLastTime() {
        return changed;
    }

    private int changed;

    private static void set(Row row, TorrentDetail.FileStatus f) {
        row.status = f;
        row.size.set(f.size());
        row.done.set(f.done());
        row.priority.set(f.priority());
        row.found = f.found();
    }

    /**
     * {@code wanted} (a {@link #folderOf}), or the nearest folder above it that exists: a folder no
     * piece has reached yet is not on the disk, and the save path is the floor.
     */
    public static Path nearestFolder(Path savePath, Path wanted, Predicate<Path> isDirectory) {
        Path floor = savePath.normalize();
        Path folder = wanted.normalize();
        // A path that climbs out of the save path (it should never, libtorrent sanitises) opens the floor.
        if (!folder.startsWith(floor)) return floor;
        while (folder != null && !folder.equals(floor) && !isDirectory.test(folder)) folder = folder.getParent();
        return folder != null ? folder : floor;
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
                    folder.parent = parent;
                    folders.put(key.toString(), folder);
                    parent.children.add(folder);
                }
                parent = folder;
            }
            Row row = new Row(f.index(), parts[parts.length - 1], f.path());
            row.parent = parent;
            set(row, f);
            parent.children.add(row);
            byIndex.put(f.index(), row);
        }
        refreshFolders(root);
        rootItem = toItem(root);
        rootItem.setExpanded(true);
        for (TreeItem<Row> child : rootItem.getChildren()) child.setExpanded(true);
    }

    private static void refreshFolders(@Nullable Row row) {
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
