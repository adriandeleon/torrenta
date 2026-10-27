package com.torrenta.ui;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.torrenta.engine.Torrent;

/**
 * Every sidebar count from one pass over the torrents: each status, category, tag and tracker
 * host, the three "none" entries and All. {@link SidebarFilter#count} runs one filter over every
 * torrent, which for the whole sidebar is entries × torrents a poll (1.25 million
 * matches at 250 entries and 5,000 torrents); this is torrents × (statuses + labels). Pure, and
 * {@link #of(SidebarFilter)} gives what {@code count} gives for every filter, which the test holds
 * it to.
 */
final class SidebarCounts {

    private static final SidebarStatus[] STATUSES = SidebarStatus.values();

    private final int all;
    private final EnumMap<SidebarStatus, Integer> statuses = new EnumMap<>(SidebarStatus.class);
    private final Map<String, Integer> categories = new HashMap<>();
    private final Map<String, Integer> tags = new HashMap<>();
    private final Map<String, Integer> hosts = new HashMap<>();
    private int untagged;
    private int trackerless;
    private int trackerError;

    private SidebarCounts(List<Torrent> torrents) {
        all = torrents.size();
        int[] status = new int[STATUSES.length];
        for (Torrent t : torrents) {
            for (int i = 0; i < STATUSES.length; i++) if (STATUSES[i].matches(t)) status[i]++;
            // Uncategorised is the empty name, which is also what an empty category's filter matches.
            categories.merge(t.category(), 1, Integer::sum);
            if (t.tags().isEmpty()) untagged++;
            else countEach(t.tags(), tags);
            if (t.trackers().isEmpty()) trackerless++;
            else countEach(t.trackers(), hosts);
            if (t.trackerError()) trackerError++;
        }
        for (int i = 0; i < STATUSES.length; i++) statuses.put(STATUSES[i], status[i]);
    }

    static SidebarCounts of(List<Torrent> torrents) {
        return new SidebarCounts(torrents);
    }

    /**
     * Each name once per torrent, as {@code contains} counts it: a list that names a tag twice is
     * still one torrent with that tag.
     */
    private static void countEach(List<String> names, Map<String, Integer> into) {
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (names.indexOf(name) == i) into.merge(name, 1, Integer::sum);
        }
    }

    /** What {@code filter.count(torrents)} would be. A view counts everything here, as it matches. */
    int of(SidebarFilter filter) {
        return switch (filter.kind()) {
            case ALL, VIEW -> all;
            case STATUS -> statuses.get(filter.status());
            case CATEGORY -> categories.getOrDefault(filter.value(), 0);
            case UNCATEGORISED -> categories.getOrDefault("", 0);
            case TAG -> tags.getOrDefault(filter.value(), 0);
            case UNTAGGED -> untagged;
            case TRACKER -> hosts.getOrDefault(filter.value(), 0);
            case TRACKERLESS -> trackerless;
            case TRACKER_ERROR -> trackerError;
        };
    }

    /** Whether some torrent has a tracker in error: the section lists the entry only then. */
    boolean anyTrackerError() {
        return trackerError > 0;
    }
}
