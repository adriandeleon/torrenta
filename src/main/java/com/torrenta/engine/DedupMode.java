package com.torrenta.engine;

import java.util.Locale;

import static com.torrenta.i18n.Messages.tr;

/**
 * What to do with a new torrent's files that are on disk already ({@code add.dedup}): a link
 * costs nothing, so it is the default everywhere; a copy costs the file's size again on the other
 * volume and its time, so it is asked about in the Add dialog unless chosen outright, and an add
 * without a dialog makes one only when chosen outright.
 */
public enum DedupMode {
    /** Download everything, as if nothing were on disk. */
    OFF("off"),
    /** Link what is on the same volume; never copy. */
    LINK("link"),
    /** Link, and offer the copies in the Add dialog, unticked; no copies without a dialog. */
    ASK("ask"),
    /** Link, and copy what is on another volume, with or without a dialog. */
    COPY("copy");

    private final String key;

    DedupMode(String key) {
        this.key = key;
    }

    public String label() {
        return tr("dedupMode." + key);
    }

    /** The label, so a ChoiceBox shows it; read at call time, never cached. */
    @Override
    public String toString() {
        return label();
    }

    /** Its name in the settings file; unknown text is {@link #ASK}, the default. */
    public static DedupMode byName(String name) {
        String n = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        for (DedupMode m : values()) if (m.name().equals(n)) return m;
        return ASK;
    }

    /** Files on disk are used at all. */
    public boolean links() {
        return this != OFF;
    }

    /** The Add dialog offers the copies. */
    public boolean offersCopies() {
        return this == ASK || this == COPY;
    }

    /** Copies are made without asking: ticked in the dialog, and made by an add without one. */
    public boolean copiesWithoutAsking() {
        return this == COPY;
    }
}
