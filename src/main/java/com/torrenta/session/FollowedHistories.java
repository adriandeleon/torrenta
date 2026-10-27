package com.torrenta.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * The full {@link SpeedHistory} of the torrents chosen last, by id: a day of a torrent's rates is
 * some 55 KiB, so arrowing down a thousand rows must not keep a thousand of them. Only the
 * {@link #KEPT} most recently followed are kept; the one followed last (the selection) is never the
 * one that goes. Reading or recording does not count as following, so the poll's adds leave the
 * order alone.
 *
 * <p>Followed from the FX thread, fed by the poll thread: every method holds the instance's lock,
 * and a history's own lock is only ever taken inside it, never the other way round.
 */
final class FollowedHistories {

    /** How many full histories are kept: the selection and the few chosen before it. */
    static final int KEPT = 8;

    private final Map<String, SpeedHistory> histories;

    FollowedHistories() {
        this(KEPT);
    }

    FollowedHistories(int kept) {
        histories = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SpeedHistory> eldest) {
                return size() > kept;
            }
        };
    }

    /** Makes {@code id} the latest followed, its history from {@code start} if it has none. */
    synchronized void follow(String id, Supplier<SpeedHistory> start) {
        SpeedHistory h = histories.remove(id);
        histories.put(id, h != null ? h : start.get());
    }

    /** {@code id}'s history; null for one not followed, or no longer kept. */
    synchronized @Nullable SpeedHistory get(String id) {
        return histories.get(id);
    }

    /** One second of {@code id}'s rates, if it is followed. */
    synchronized void add(String id, long atSeconds, long download, long upload) {
        SpeedHistory h = histories.get(id);
        if (h != null) h.add(atSeconds, download, upload);
    }

    /** Forgets every torrent not in {@code present}. */
    synchronized void retainAll(Set<String> present) {
        histories.keySet().retainAll(present);
    }

    /** The ids kept, the latest followed last. */
    synchronized List<String> ids() {
        return List.copyOf(histories.keySet());
    }
}
