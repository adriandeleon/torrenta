package com.torrenta.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentPreview;

/** The Add dialog's file tree, built from the flat file list. Pure, so the shape is tested. */
public final class FileTree {

    /** A folder or a file. A file has the torrent's index; a folder has {@code -1}. */
    public static final class Node {
        private final String name;
        private final int index;
        private final long size;
        private final List<Node> children = new ArrayList<>();
        private final ObjectProperty<Priority> priority = new SimpleObjectProperty<>(this, "priority", Priority.NORMAL);

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
