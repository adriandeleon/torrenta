package com.torrenta.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentPreview;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Add dialog's file tree, built from the flat file list, and the rules its keys follow. Pure,
 * so the shape and the rules are tested.
 *
 * <p>A file is wanted or not (its checkbox) and has a priority; an unwanted file reads and is
 * added as Skip. The two move together: lowering Low gives Skip and clears the box, raising Skip
 * gives Low and ticks it.
 */
public final class FileTree {

    /** A folder or a file. A file has the torrent's index; a folder has {@code -1}. */
    public static final class Node {
        private final String name;
        private final int index;
        private final long size;
        private final List<Node> children = new ArrayList<>();
        private final ObjectProperty<Priority> priority = new SimpleObjectProperty<>(this, "priority", Priority.NORMAL);
        private final BooleanProperty wanted = new SimpleBooleanProperty(this, "wanted", true);

        Node(String name, int index, long size) {
            this.name = name;
            this.index = index;
            this.size = size;
        }

        public String name() {
            return name;
        }

        public int index() {
            return index;
        }

        public boolean isFile() {
            return index >= 0;
        }

        /** A file's own size, or a folder's total. */
        public long size() {
            return isFile() ? size : children.stream().mapToLong(Node::size).sum();
        }

        public List<Node> children() {
            return children;
        }

        public ObjectProperty<Priority> priorityProperty() {
            return priority;
        }

        /** A file's checkbox; a folder's is its files', which the tree item works out. */
        public BooleanProperty wantedProperty() {
            return wanted;
        }
    }

    /** What a file's priority column says, and what the + and − keys move between. */
    public record Choice(boolean wanted, Priority priority) {

        /** What the column reads: Skip for an unwanted file, whatever its priority underneath. */
        public String label() {
            return wanted ? priority.label() : Priority.IGNORE.label();
        }
    }

    /** One step up ({@code +1}) or down ({@code -1}) the ladder Skip, Low, Normal, High. */
    public static Choice step(Choice from, int direction) {
        Priority[] ladder = {Priority.IGNORE, Priority.LOW, Priority.NORMAL, Priority.HIGH};
        int at = from.wanted() && from.priority() != Priority.IGNORE
                ? from.priority().ordinal()
                : 0;
        int to = Math.max(0, Math.min(ladder.length - 1, at + Integer.signum(direction)));
        // Skip keeps the priority underneath, so ticking the box again brings Low back, not Normal.
        return to == 0 ? new Choice(false, Priority.LOW) : new Choice(true, ladder[to]);
    }

    /** Steps every file under the node; a folder moves from its highest file, so all agree after. */
    public static void step(Node node, int direction) {
        List<Node> files = files(node);
        if (files.isEmpty()) return;
        Choice top = files.stream().map(FileTree::choice).max(FileTree::compare).orElseThrow();
        Choice next = step(top, direction);
        for (Node file : files) {
            file.priorityProperty().set(next.priority());
            file.wantedProperty().set(next.wanted());
        }
    }

    public static Choice choice(Node file) {
        return new Choice(file.wantedProperty().get(), file.priorityProperty().get());
    }

    private static int compare(Choice a, Choice b) {
        return Integer.compare(rank(a), rank(b));
    }

    private static int rank(Choice c) {
        return c.wanted() ? c.priority().ordinal() : 0;
    }

    /**
     * What a row's priority column says: a file's own, a folder's when all its files agree, else
     * "Mixed".
     */
    public static String label(Node node) {
        List<String> labels =
                files(node).stream().map(f -> choice(f).label()).distinct().toList();
        return labels.size() == 1 ? labels.get(0) : labels.isEmpty() ? "" : tr("dialog.add.priority.mixed");
    }

    /** The bytes the wanted files add up to: what the disk bar says the torrent will take. */
    public static long wantedSize(Node node) {
        return files(node).stream()
                .filter(f -> f.wantedProperty().get())
                .mapToLong(Node::size)
                .sum();
    }

    /** The priorities to add with, by torrent index: only files that are not plain Normal. */
    public static Map<Integer, Priority> priorities(Node node) {
        Map<Integer, Priority> out = new java.util.TreeMap<>();
        for (Node file : files(node)) {
            Priority p = file.wantedProperty().get() ? file.priorityProperty().get() : Priority.IGNORE;
            if (p != Priority.NORMAL) out.put(file.index(), p);
        }
        return out;
    }

    private FileTree() {}

    /**
     * Folders in the order their first file appears, files in index order. A single-file torrent
     * is one file at the root; a multi-file one has the torrent's root folder as its root.
     */
    public static Node build(TorrentPreview preview) {
        Node root = new Node(preview.name(), -1, 0);
        Map<String, Node> folders = new LinkedHashMap<>();
        for (TorrentPreview.FileEntry file : preview.files()) {
            String[] parts = file.path().split("/");
            // The first segment of a multi-file torrent's paths is the root folder itself.
            int start = parts.length > 1 ? 1 : 0;
            Node parent = root;
            StringBuilder key = new StringBuilder();
            for (int i = start; i < parts.length - 1; i++) {
                key.append('/').append(parts[i]);
                Node folder = folders.get(key.toString());
                if (folder == null) {
                    folder = new Node(parts[i], -1, 0);
                    folders.put(key.toString(), folder);
                    parent.children().add(folder);
                }
                parent = folder;
            }
            parent.children().add(new Node(parts[parts.length - 1], file.index(), file.size()));
        }
        return root;
    }

    /** Every file under the node, depth first, in the order shown. */
    public static List<Node> files(Node node) {
        List<Node> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<Node> out) {
        if (node.isFile()) out.add(node);
        for (Node child : node.children()) collect(child, out);
    }
}
