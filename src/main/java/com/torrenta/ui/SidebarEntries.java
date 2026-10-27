package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

import com.torrenta.engine.Torrent;
import com.torrenta.ui.SidebarFilter.Axis;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * The sidebar's entries, section by section, reconciled in place from every poll as
 * {@link TorrentRows} does for the table: an entry keeps its identity (and so its cell) while it
 * is listed, only its count moves, and a section is rebuilt only when the set of names it lists
 * changes. Pure — no scene graph — so the reconcile is tested; {@link Sidebar} mirrors it into a
 * tree.
 */
final class SidebarEntries {

    /**
     * One line: a filter with its count, or a section's heading (no count; its filter is All). The
     * section's own All carries the section's axis, so choosing it clears that axis alone.
     */
    static final class Entry {
        final SidebarFilter filter;
        final boolean heading;
        final Axis axis;
        final String title;
        /** For a view whose query no longer reads: what is wrong, for the tooltip; empty otherwise. */
        final String problem;

        final IntegerProperty count = new SimpleIntegerProperty();

        Entry(SidebarFilter filter, Axis axis) {
            this(filter, axis, filter.title(), "");
        }

        private Entry(SidebarFilter filter, Axis axis, String title, String problem) {
            this(filter, false, axis, title, problem);
        }

        private Entry(SidebarFilter filter, boolean heading, Axis axis, String title, String problem) {
            this.filter = filter;
            this.heading = heading;
            this.axis = axis;
            this.title = title;
            this.problem = problem;
        }

        boolean isHeading() {
            return heading;
        }

        /** The entry is one of the selection's choices, or the All of an axis with none. */
        boolean chosenIn(SidebarSelection selection) {
            if (heading) return false;
            if (filter.kind() != SidebarFilter.Kind.ALL) return selection.has(filter);
            return axis == Axis.ALL ? selection.isAll() : selection.on(axis).kind() == SidebarFilter.Kind.ALL;
        }
    }

    /** A heading with its entries; the entries list is replaced whole when it is rebuilt. */
    static final class Section {
        final Axis axis;
        final String title;
        final Entry heading;
        private List<Entry> entries = List.of();
        private @Nullable List<String> names = null;

        Section(Axis axis, String title) {
            this.axis = axis;
            this.title = title;
            this.heading = new Entry(SidebarFilter.ALL, true, axis, title, "");
        }

        List<Entry> entries() {
            return entries;
        }
    }

    /**
     * A saved view as the sidebar lists it: its slug, its name, and its query read with the clock
     * of this poll (the window reads them; {@code added<} counts back from now).
     */
    record View(String slug, String name, FilterQuery query) {

        /** What is wrong with the query, from its first term that does not read; empty if none. */
        String problem() {
            return query.chips().stream()
                    .filter(c -> !c.valid())
                    .map(FilterQuery.Chip::label)
                    .findFirst()
                    .orElse("");
        }
    }

    final Entry all = new Entry(SidebarFilter.ALL, Axis.ALL);
    final Section views = new Section(Axis.VIEW, tr("sidebar.section.views"));
    final Section status = new Section(Axis.STATUS, tr("sidebar.section.status"));
    final Section categories = new Section(Axis.CATEGORY, tr("sidebar.section.categories"));
    final Section tags = new Section(Axis.TAG, tr("sidebar.section.tags"));
    final Section trackers = new Section(Axis.TRACKER, tr("sidebar.section.trackers"));

    SidebarEntries() {
        List<Entry> fixed = new ArrayList<>();
        fixed.add(new Entry(SidebarFilter.ALL, Axis.STATUS));
        for (SidebarStatus s : SidebarStatus.values()) fixed.add(new Entry(SidebarFilter.status(s), Axis.STATUS));
        status.entries = List.copyOf(fixed);
        status.names = List.of();
        // No views until one is saved: an empty section is not a rebuild.
        views.names = List.of();
    }

    List<Section> sections() {
        return List.of(views, status, categories, tags, trackers);
    }

    /** Every entry in order, the top All first. */
    List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        out.add(all);
        for (Section s : sections()) out.addAll(s.entries());
        return out;
    }

    Optional<Entry> find(SidebarFilter filter, Axis axis) {
        for (Entry e : entries()) if (e.axis == axis && e.filter.equals(filter)) return Optional.of(e);
        return Optional.empty();
    }

    /** Whether the selection's choice on the axis is listed (All always is). */
    boolean lists(SidebarSelection selection, Axis axis) {
        SidebarFilter f = selection.on(axis);
        return f.kind() == SidebarFilter.Kind.ALL || find(f, axis).isPresent();
    }

    /**
     * The axes whose choice is no longer listed, after {@link #update} with the same torrents: a
     * deleted category, a tracker no torrent has. None while there are no torrents at all: an
     * empty session and an engine that has stopped look the same from here, and a choice must
     * not be lost to the poll that runs while the window closes.
     */
    List<Axis> stale(SidebarSelection selection, List<Torrent> torrents) {
        List<Axis> out = new ArrayList<>();
        if (torrents.isEmpty()) return out;
        for (Section s : sections()) if (!lists(selection, s.axis)) out.add(s.axis);
        return out;
    }

    /**
     * The names that exist (from the store) and the torrents (from the poll); names in use but not
     * in the store are listed too, so every count has an entry.
     *
     * @return the sections whose entries were rebuilt, for the tree to mirror; empty when only
     *     counts moved
     */
    List<Section> update(List<String> categoryNames, List<String> tagNames, List<Torrent> torrents) {
        return update(List.of(), categoryNames, tagNames, torrents);
    }

    /**
     * {@link #update}, the saved views too: listed in their order under their All, rebuilt when
     * one is added, removed, renamed or reordered or its query starts or stops reading, and each
     * counted by its own query over every torrent, as the other entries are.
     */
    List<Section> update(
            List<View> savedViews, List<String> categoryNames, List<String> tagNames, List<Torrent> torrents) {
        List<Section> rebuilt = new ArrayList<>();
        List<String> v = savedViews.stream()
                .map(view -> view.slug() + "\u0000" + view.name() + "\u0000" + view.problem())
                .toList();
        if (!v.equals(views.names)) {
            views.names = v;
            List<Entry> entries = new ArrayList<>();
            if (!savedViews.isEmpty()) entries.add(new Entry(SidebarFilter.ALL, Axis.VIEW));
            for (View view : savedViews)
                entries.add(new Entry(SidebarFilter.view(view.slug()), Axis.VIEW, view.name(), view.problem()));
            views.entries = List.copyOf(entries);
            rebuilt.add(views);
        }
        List<String> c = SidebarFilter.names(categoryNames, torrents, false);
        if (!c.equals(categories.names)) {
            categories.names = c;
            categories.entries = labelled(Axis.CATEGORY, c, SidebarFilter::category, SidebarFilter.UNCATEGORISED);
            rebuilt.add(categories);
        }
        List<String> t = SidebarFilter.names(tagNames, torrents, true);
        if (!t.equals(tags.names)) {
            tags.names = t;
            tags.entries = labelled(Axis.TAG, t, SidebarFilter::tag, SidebarFilter.UNTAGGED);
            rebuilt.add(tags);
        }
        // "Tracker error" is listed only while some torrent has one: part of the section's shape.
        List<String> hosts = new ArrayList<>(SidebarFilter.trackerHosts(torrents));
        if (torrents.stream().anyMatch(Torrent::trackerError)) hosts.add(SidebarFilter.TRACKER_ERROR.format());
        if (!hosts.equals(trackers.names)) {
            trackers.names = List.copyOf(hosts);
            List<Entry> entries = new ArrayList<>();
            entries.add(new Entry(SidebarFilter.ALL, Axis.TRACKER));
            for (String host : SidebarFilter.trackerHosts(torrents))
                entries.add(new Entry(SidebarFilter.tracker(host), Axis.TRACKER));
            entries.add(new Entry(SidebarFilter.TRACKERLESS, Axis.TRACKER));
            if (hosts.size() > SidebarFilter.trackerHosts(torrents).size())
                entries.add(new Entry(SidebarFilter.TRACKER_ERROR, Axis.TRACKER));
            trackers.entries = List.copyOf(entries);
            rebuilt.add(trackers);
        }
        for (Entry e : entries()) e.count.set(e.filter.count(torrents));
        for (int i = 0; i < savedViews.size(); i++) {
            FilterQuery q = savedViews.get(i).query();
            // The section's own All comes first; the views follow in order.
            views.entries.get(i + 1).count.set((int)
                    torrents.stream().filter(q::matches).count());
        }
        return rebuilt;
    }

    private static List<Entry> labelled(
            Axis axis, List<String> names, java.util.function.Function<String, SidebarFilter> of, SidebarFilter none) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(SidebarFilter.ALL, axis));
        for (String name : names) entries.add(new Entry(of.apply(name), axis));
        entries.add(new Entry(none, axis));
        return List.copyOf(entries);
    }
}
