package com.torrenta.ui;

import java.util.Locale;
import java.util.Optional;

/**
 * How the window moves between its views: Ctrl+Tab and Ctrl+Shift+Tab cycle round, a place on the
 * strip reaches one, and a name from the capture hook is looked up by id. Pure, so the rule is
 * tested without a scene.
 */
public final class ViewSwitch {
    private ViewSwitch() {}

    /** The view after this one, round to the first after the last. */
    public static View next(View from) {
        View[] all = View.values();
        return all[(from.ordinal() + 1) % all.length];
    }

    /** The view before this one, round to the last before the first. */
    public static View previous(View from) {
        View[] all = View.values();
        return all[(from.ordinal() + all.length - 1) % all.length];
    }

    /** The view at this place on the strip (1 for the first), or empty for a place no view has. */
    public static Optional<View> numbered(int number) {
        View[] all = View.values();
        return number >= 1 && number <= all.length ? Optional.of(all[number - 1]) : Optional.empty();
    }

    /** The view with this id, case and surrounding blanks ignored; empty for anything else. */
    public static Optional<View> parse(String id) {
        if (id == null) return Optional.empty();
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (View v : View.values()) if (v.id().equals(wanted)) return Optional.of(v);
        return Optional.empty();
    }
}
