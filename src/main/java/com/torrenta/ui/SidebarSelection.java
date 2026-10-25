package com.torrenta.ui;

import java.util.EnumMap;
import java.util.Map;

import com.torrenta.engine.Torrent;
import com.torrenta.ui.SidebarFilter.Axis;

/**
 * The sidebar's choices, one per section, composed: a torrent shows when it passes every one. The
 * single rule the list is filtered by (with the name box, in {@code MainWindow.rowFilter}). Pure,
 * immutable; {@link #with} is how a click or a key changes it.
 *
 * @param status the Status section's choice, or {@link SidebarFilter#ALL}
 * @param category the Categories section's, or ALL
 * @param tag the Tags section's, or ALL
 * @param tracker the Trackers section's, or ALL
 * @param view the Views section's, or ALL: a saved view's slug, whose query the window ANDs in
 */
public record SidebarSelection(
        SidebarFilter status, SidebarFilter category, SidebarFilter tag, SidebarFilter tracker, SidebarFilter view) {

    public static final SidebarSelection ALL = new SidebarSelection(
            SidebarFilter.ALL, SidebarFilter.ALL, SidebarFilter.ALL, SidebarFilter.ALL, SidebarFilter.ALL);

    public SidebarSelection {
        status = onAxis(status, Axis.STATUS);
        category = onAxis(category, Axis.CATEGORY);
        tag = onAxis(tag, Axis.TAG);
        tracker = onAxis(tracker, Axis.TRACKER);
        view = onAxis(view, Axis.VIEW);
    }

    /** No view chosen. */
    public SidebarSelection(SidebarFilter status, SidebarFilter category, SidebarFilter tag, SidebarFilter tracker) {
        this(status, category, tag, tracker, SidebarFilter.ALL);
    }

    private static SidebarFilter onAxis(SidebarFilter f, Axis axis) {
        return f != null && f.axis() == axis ? f : SidebarFilter.ALL;
    }

    public boolean matches(Torrent t) {
        return status.matches(t) && category.matches(t) && tag.matches(t) && tracker.matches(t);
    }

    public boolean isAll() {
        return equals(ALL);
    }

    /** The choice for the axis, {@link SidebarFilter#ALL} when it has none; ALL for {@link Axis#ALL}. */
    public SidebarFilter on(Axis axis) {
        return switch (axis) {
            case ALL -> SidebarFilter.ALL;
            case VIEW -> view;
            case STATUS -> status;
            case CATEGORY -> category;
            case TAG -> tag;
            case TRACKER -> tracker;
        };
    }

    /** Whether the entry is one of the choices: highlighted in the sidebar. */
    public boolean has(SidebarFilter f) {
        return f.kind() == SidebarFilter.Kind.ALL ? isAll() : on(f.axis()).equals(f);
    }

    /**
     * This with the entry as its axis's choice; {@link SidebarFilter#ALL} itself clears every
     * axis (the entry above the sections), a section's own All clears that axis only — which is
     * what {@link #cleared} does.
     */
    public SidebarSelection with(SidebarFilter f) {
        return switch (f.axis()) {
            case ALL -> ALL;
            case VIEW -> new SidebarSelection(status, category, tag, tracker, f);
            case STATUS -> new SidebarSelection(f, category, tag, tracker, view);
            case CATEGORY -> new SidebarSelection(status, f, tag, tracker, view);
            case TAG -> new SidebarSelection(status, category, f, tracker, view);
            case TRACKER -> new SidebarSelection(status, category, tag, f, view);
        };
    }

    /** An entry chosen in the sidebar: a section's own All clears its axis, anything else is {@link #with}. */
    public SidebarSelection choose(SidebarFilter f, Axis axis) {
        return f.kind() == SidebarFilter.Kind.ALL ? cleared(axis) : with(f);
    }

    public SidebarSelection cleared(Axis axis) {
        return switch (axis) {
            case ALL -> ALL;
            case VIEW -> new SidebarSelection(status, category, tag, tracker, SidebarFilter.ALL);
            case STATUS -> new SidebarSelection(SidebarFilter.ALL, category, tag, tracker, view);
            case CATEGORY -> new SidebarSelection(status, SidebarFilter.ALL, tag, tracker, view);
            case TAG -> new SidebarSelection(status, category, SidebarFilter.ALL, tracker, view);
            case TRACKER -> new SidebarSelection(status, category, tag, SidebarFilter.ALL, view);
        };
    }

    /** One formatted filter per axis, keyed by the axis's settings suffix; {@code all} where none. */
    public Map<Axis, String> format() {
        Map<Axis, String> out = new EnumMap<>(Axis.class);
        out.put(Axis.STATUS, status.format());
        out.put(Axis.CATEGORY, category.format());
        out.put(Axis.TAG, tag.format());
        out.put(Axis.TRACKER, tracker.format());
        out.put(Axis.VIEW, view.format());
        return out;
    }

    /** For the log and the capture hook: the choices that are not All, space-separated, or {@code all}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (SidebarFilter f : new SidebarFilter[] {view, status, category, tag, tracker}) {
            if (f.kind() == SidebarFilter.Kind.ALL) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(f.format());
        }
        return sb.isEmpty() ? "all" : sb.toString();
    }

    /** The inverse of {@link #format}: each value read on its own axis, unreadable ones All. */
    public static SidebarSelection parse(String status, String category, String tag, String tracker) {
        return parse(status, category, tag, tracker, null);
    }

    public static SidebarSelection parse(String status, String category, String tag, String tracker, String view) {
        return new SidebarSelection(
                SidebarFilter.parse(status, Axis.STATUS),
                SidebarFilter.parse(category, Axis.CATEGORY),
                SidebarFilter.parse(tag, Axis.TAG),
                SidebarFilter.parse(tracker, Axis.TRACKER),
                SidebarFilter.parse(view, Axis.VIEW));
    }
}
