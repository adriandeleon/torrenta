package com.torrenta.engine;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.torrenta.i18n.Messages.tr;

/**
 * Renaming inside a torrent, as rules: a file or a folder by its path, and the content layout on
 * add. Paths are the torrent's own, relative to the save path, with forward slashes; a folder is
 * every file under it. Pure, so the collisions and the layouts are tested rather than met on disk.
 */
public final class FileRenames {

    private FileRenames() {}

    /** How a torrent's files sit under the save path, chosen on add (qBittorrent's three). */
    public enum Layout {
        /** As the torrent has them. */
        ORIGINAL("original"),
        /** Inside a folder named after the torrent, even a single file. */
        SUBFOLDER("subfolder"),
        /** Without the torrent's root folder: its contents go straight into the save path. */
        NO_SUBFOLDER("noSubfolder");

        private final String label;

        Layout(String label) {
            this.label = label;
        }

        public String label() {
            return tr("contentLayout." + label);
        }

        /** The label, so a ChoiceBox shows it; read at call time, never cached. */
        @Override
        public String toString() {
            return label();
        }

        /** Its name in the settings file; unknown text is {@link #ORIGINAL}. */
        public static Layout byName(String name) {
            for (Layout l : values()) if (l.name().equalsIgnoreCase(name == null ? "" : name.trim())) return l;
            return ORIGINAL;
        }
    }

    /**
     * What is wrong with renaming {@code target} (a file's path, or a folder's) to {@code name},
     * in the interface language; empty when nothing is. {@code names} is every file's current
     * path by index.
     */
    public static Optional<String> problem(Map<Integer, String> names, String target, String name) {
        String segment = name == null ? "" : name.strip();
        if (segment.isEmpty()) return Optional.of(tr("rename.problem.empty"));
        if (segment.contains("/") || segment.contains("\\")) return Optional.of(tr("rename.problem.separator"));
        if (segment.equals(".") || segment.equals("..")) return Optional.of(tr("rename.problem.dots"));
        Map<Integer, String> changes = plan(names, target, segment);
        if (changes.isEmpty()) return Optional.of(tr("rename.problem.gone"));
        for (Map.Entry<Integer, String> other : names.entrySet()) {
            if (changes.containsKey(other.getKey())) continue;
            for (String renamed : changes.values()) {
                // The same path, or one that would have to be a file and a folder at once.
                if (renamed.equals(other.getValue())
                        || other.getValue().startsWith(renamed + "/")
                        || renamed.startsWith(other.getValue() + "/")) {
                    return Optional.of(tr("rename.problem.taken", segment));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The new path of every file the rename touches, by index: the target itself if it is a file,
     * or every file under it if it is a folder, with its last segment replaced by {@code name}.
     * Empty when no file is at or under {@code target}.
     */
    public static Map<Integer, String> plan(Map<Integer, String> names, String target, String name) {
        String segment = name.strip();
        int slash = target.lastIndexOf('/');
        String renamed = (slash < 0 ? "" : target.substring(0, slash + 1)) + segment;
        Map<Integer, String> out = new TreeMap<>();
        for (Map.Entry<Integer, String> file : names.entrySet()) {
            String path = file.getValue();
            if (path.equals(target)) out.put(file.getKey(), renamed);
            else if (path.startsWith(target + "/")) out.put(file.getKey(), renamed + path.substring(target.length()));
        }
        return out;
    }

    /**
     * The renames a layout makes of a torrent's files ({@code paths}, by index, as the torrent has
     * them): none for the original layout or when the torrent is already that way; every file out
     * of the one root folder for {@link Layout#NO_SUBFOLDER}; every file into a folder named after
     * the torrent, its extension dropped, for {@link Layout#SUBFOLDER}.
     */
    public static Map<Integer, String> layout(Map<Integer, String> paths, String torrentName, Layout layout) {
        Map<Integer, String> out = new TreeMap<>();
        if (paths.isEmpty() || layout == Layout.ORIGINAL) return out;
        String root = root(paths);
        if (layout == Layout.NO_SUBFOLDER) {
            if (root == null) return out;
            paths.forEach((index, path) -> out.put(index, path.substring(root.length() + 1)));
        } else {
            if (root != null) return out;
            String folder = stem(torrentName);
            paths.forEach((index, path) -> out.put(index, folder + "/" + path));
        }
        return out;
    }

    /** The first segment every path shares as a folder, or null when they do not all have one. */
    private static String root(Map<Integer, String> paths) {
        String root = null;
        for (String path : paths.values()) {
            int slash = path.indexOf('/');
            if (slash <= 0) return null;
            String first = path.substring(0, slash);
            if (root == null) root = first;
            else if (!root.equals(first)) return null;
        }
        return root;
    }

    /** {@code movie.mkv} → {@code movie}; a name with no extension, or only one, stays as it is. */
    static String stem(String name) {
        String n = name == null ? "" : name.strip();
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        return stem.isEmpty() ? "torrent" : stem;
    }
}
