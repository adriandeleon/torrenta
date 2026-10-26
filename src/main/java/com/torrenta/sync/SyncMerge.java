package com.torrenta.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * A three-way merge of settings, one key at a time: an entry added, changed or removed on one side
 * carries over; one changed differently on both sides keeps this machine's value and is reported.
 * With no base (a first sync) everything on both sides is kept and a differing key keeps this
 * machine's. Pure.
 */
public final class SyncMerge {

    /**
     * @param merged what both sides hold after the sync
     * @param received keys whose value came from the repository
     * @param sent keys whose value goes to the repository
     * @param conflicts keys changed on both sides, where this machine won
     */
    public record Result(
            Map<String, String> merged, List<String> received, List<String> sent, List<String> conflicts) {}

    private SyncMerge() {}

    /** @param base the last synced state, or null on a first sync */
    public static Result merge(
            @Nullable Map<String, String> base, Map<String, String> mine, Map<String, String> theirs) {
        Map<String, String> merged = new TreeMap<>();
        List<String> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(mine.keySet());
        keys.addAll(theirs.keySet());
        if (base != null) keys.addAll(base.keySet());
        for (String key : keys) {
            String m = mine.get(key);
            String t = theirs.get(key);
            String value;
            if (Objects.equals(m, t)) {
                value = m;
            } else if (base == null) {
                // First sync: what only one side has is kept; a key both have keeps this machine's.
                if (m == null) {
                    value = t;
                    received.add(key);
                } else {
                    value = m;
                    sent.add(key);
                    if (t != null) conflicts.add(key);
                }
            } else if (Objects.equals(m, base.get(key))) {
                value = t; // only they changed it (a null is a removal)
                received.add(key);
            } else if (Objects.equals(t, base.get(key))) {
                value = m; // only we changed it
                sent.add(key);
            } else {
                value = m; // both changed, differently: this machine wins
                conflicts.add(key);
                sent.add(key);
            }
            if (value != null) merged.put(key, value);
        }
        return new Result(merged, List.copyOf(received), List.copyOf(sent), List.copyOf(conflicts));
    }
}
