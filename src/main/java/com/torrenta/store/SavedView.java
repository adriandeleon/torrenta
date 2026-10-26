package com.torrenta.store;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A filter saved under a name: the omnibox's text as typed, read again by {@code ui.FilterQuery}
 * each time, so a view saved before a key existed still reads, and one that no longer reads says
 * so rather than showing nothing.
 *
 * <p>Kept one key per field ({@code ui.view.<slug>.name}, {@code ui.view.<slug>.query}) and the
 * order in {@code ui.views.order}, so settings sync merges view by view: a view added on each of
 * two computers survives on both. Pure: {@link #read} and {@link #keys} are the codec, the rest
 * returns a new list.
 *
 * @param slug the key's middle part, from the name when the view was made; it stays when renamed
 * @param name what the sidebar shows
 * @param query the omnibox text
 */
public record SavedView(String slug, String name, String query) {

    public static final String PREFIX = "ui.view.";
    public static final String ORDER = "ui.views.order";

    public SavedView {
        name = name == null ? "" : name.strip();
        query = query == null ? "" : query.strip();
    }

    /**
     * The views in the settings: those {@link #ORDER} names, in its order, then any others (a view
     * another computer added while this one's order won the merge), by name. A view without a
     * name or a query is left out.
     */
    public static List<SavedView> read(Map<String, String> settings) {
        Set<String> slugs = new TreeSet<>();
        for (String key : settings.keySet()) {
            if (!key.startsWith(PREFIX)) continue;
            String rest = key.substring(PREFIX.length());
            int dot = rest.lastIndexOf('.');
            if (dot > 0) slugs.add(rest.substring(0, dot));
        }
        List<SavedView> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String slug : settings.getOrDefault(ORDER, "").split(",")) {
            String s = slug.strip();
            if (!s.isEmpty() && slugs.contains(s) && placed.add(s)) add(out, settings, s);
        }
        List<SavedView> rest = new ArrayList<>();
        for (String slug : slugs) if (!placed.contains(slug)) add(rest, settings, slug);
        rest.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        out.addAll(rest);
        return List.copyOf(out);
    }

    private static void add(List<SavedView> out, Map<String, String> settings, String slug) {
        SavedView v = new SavedView(
                slug,
                settings.getOrDefault(PREFIX + slug + ".name", ""),
                settings.getOrDefault(PREFIX + slug + ".query", ""));
        if (!v.name().isEmpty() && !v.query().isEmpty()) out.add(v);
    }

    /** The keys that hold these views, the order included; whatever else starts with {@link #PREFIX} is stale. */
    public static Map<String, String> keys(List<SavedView> views) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (SavedView v : views) {
            out.put(PREFIX + v.slug() + ".name", v.name());
            out.put(PREFIX + v.slug() + ".query", v.query());
            order.add(v.slug());
        }
        out.put(ORDER, String.join(",", order));
        return out;
    }

    /** The list with a new view at the end, its slug made from the name and unused. */
    public static List<SavedView> added(List<SavedView> views, String name, String query) {
        Set<String> taken = new HashSet<>();
        for (SavedView v : views) taken.add(v.slug());
        List<SavedView> out = new ArrayList<>(views);
        out.add(new SavedView(slug(name, taken), name, query));
        return List.copyOf(out);
    }

    public static List<SavedView> renamed(List<SavedView> views, String slug, String name) {
        return views.stream()
                .map(v -> v.slug().equals(slug) ? new SavedView(slug, name, v.query()) : v)
                .toList();
    }

    public static List<SavedView> requeried(List<SavedView> views, String slug, String query) {
        return views.stream()
                .map(v -> v.slug().equals(slug) ? new SavedView(slug, v.name(), query) : v)
                .toList();
    }

    public static List<SavedView> removed(List<SavedView> views, String slug) {
        return views.stream().filter(v -> !v.slug().equals(slug)).toList();
    }

    /**
     * A slug for the name: lower case, accents dropped, anything but letters and digits a dash,
     * "view" when nothing is left, and a number after it when that is taken. Dots and commas
     * cannot appear, which is what keeps it safe in a key and in {@link #ORDER}.
     */
    static String slug(String name, Set<String> taken) {
        String plain = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        String base = plain.isEmpty() ? "view" : plain;
        String slug = base;
        for (int n = 2; taken.contains(slug); n++) slug = base + "-" + n;
        return slug;
    }
}
