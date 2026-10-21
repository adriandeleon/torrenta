package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

import com.torrenta.engine.Torrent;
import com.torrenta.ui.SidebarFilter.Axis;

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
     * One line: a filter with its count, or a section's heading (no filter, no count). The
     * section's own All carries the section's axis, so choosing it clears that axis alone.
     */
    static final class Entry {
        final SidebarFilter filter;
        final Axis axis;
        final String title;
        final IntegerProperty count = new SimpleIntegerProperty();

        Entry(SidebarFilter filter, Axis axis) {
            this(filter, axis, filter.title());
        }

        private Entry(SidebarFilter filter, Axis axis, String title) {
            this.filter = filter;
            this.axis = axis;
            this.title = title;
        }

        boolean isHeading() {
            return filter == null;
        }

        /** The entry is one of the selection's choices, or the All of an axis with none. */
        boolean chosenIn(SidebarSelection selection) {
            if (filter == null) return false;
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
        private List<String> names = null;

        Section(Axis axis, String title) {
            this.axis = axis;
            this.title = title;
            this.heading = new Entry(null, axis, title);
        }

        List<Entry> entries() {
            return entries;
        }
    }

    final Entry all = new Entry(SidebarFilter.ALL, Axis.ALL);
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
    }

    List<Section> sections() {
        return List.of(status, categories, tags, trackers);
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
        List<Section> rebuilt = new ArrayList<>();
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
