package com.torrenta.sync;

import java.util.Map;

/** This machine's side of a sync: the live settings. */
public interface SyncTarget {

    /** The synced entries as they are now. */
    Map<String, String> read();

    /**
     * Replaces the synced entries with {@code merged}, if they still hold {@code expected} (what
     * {@link #read} gave this round); false when they changed meanwhile, and nothing is written.
     */
    boolean apply(Map<String, String> expected, Map<String, String> merged);
}
