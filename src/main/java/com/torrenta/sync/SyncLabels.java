package com.torrenta.sync;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * The category names and tags that travel, as the entries of the repository's labels file. Pure.
 *
 * <p>Names only: a category's save path is a folder of one machine, as {@code download.dir} is, so
 * a category that arrives has none and one that is already here keeps its own. Which torrent
 * carries which label is not synced either; the torrents are this machine's.
 *
 * <p>Each name is one entry, {@code category.<name in lower case>=<name>} or {@code tag.…}, so
 * {@link SyncMerge} merges them one at a time like settings: an added name carries over, a removed
 * one is removed everywhere, and the same name spelled with other capitals on two machines is one
 * entry whose spelling the merge decides, never two names of which each machine deletes the other's.
 */
public final class SyncLabels {

    static final String CATEGORY = "category.";
    static final String TAG = "tag.";

    /**
     * What a sync changes in this machine's lists.
     *
     * @param putCategories names to add, or to respell where one equal ignoring case is here
     * @param removedCategories names here that are gone from the merge
     * @param putTags tags to add, or to respell
     * @param removedTags tags here that are gone from the merge
     */
    public record Change(
            List<String> putCategories,
            List<String> removedCategories,
            List<String> putTags,
            List<String> removedTags) {

        public boolean isEmpty() {
            return putCategories.isEmpty() && removedCategories.isEmpty() && putTags.isEmpty() && removedTags.isEmpty();
        }
    }

    private SyncLabels() {}

    /** The entries for these lists; of two names equal ignoring case, the first is kept. */
    public static Map<String, String> entries(Collection<String> categories, Collection<String> tags) {
        Map<String, String> out = new TreeMap<>();
        put(out, CATEGORY, categories);
        put(out, TAG, tags);
        return out;
    }

    private static void put(Map<String, String> out, String prefix, Collection<String> names) {
        for (String name : names) {
            String trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty()) out.putIfAbsent(prefix + key(trimmed), trimmed);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The category names among {@code entries}, by key. */
    public static List<String> categories(Map<String, String> entries) {
        return names(entries, CATEGORY);
    }

    /** The tags among {@code entries}, by key. */
    public static List<String> tags(Map<String, String> entries) {
        return names(entries, TAG);
    }

    private static List<String> names(Map<String, String> entries, String prefix) {
        List<String> out = new ArrayList<>();
        new TreeMap<>(entries).forEach((key, value) -> {
            if (key.startsWith(prefix) && value != null && !value.isBlank()) out.add(value.trim());
        });
        return out;
    }

    /** What turns the lists {@code before} into {@code after}, both as {@link #entries}. */
    public static Change change(Map<String, String> before, Map<String, String> after) {
        List<String> putCategories = new ArrayList<>();
        List<String> removedCategories = new ArrayList<>();
        List<String> putTags = new ArrayList<>();
        List<String> removedTags = new ArrayList<>();
        TreeMap<String, String> keys = new TreeMap<>(before);
        keys.putAll(after);
        for (String key : keys.keySet()) {
            boolean category = key.startsWith(CATEGORY);
            if (!category && !key.startsWith(TAG)) continue;
            String was = before.get(key);
            String now = after.get(key);
            if (now != null && now.isBlank()) now = null;
            if (now == null && was != null) (category ? removedCategories : removedTags).add(was);
            else if (now != null && !now.trim().equals(was)) (category ? putCategories : putTags).add(now.trim());
        }
        return new Change(
                List.copyOf(putCategories),
                List.copyOf(removedCategories),
                List.copyOf(putTags),
                List.copyOf(removedTags));
    }

    /** The entries of a repository file, labels only; an unreadable text is empty. */
    public static Map<String, String> parse(@Nullable String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text == null ? "" : text));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(CATEGORY) || key.startsWith(TAG)) out.put(key, p.getProperty(key));
        }
        return out;
    }
}
