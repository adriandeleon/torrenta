package com.torrenta.session;

import java.util.Optional;

import com.torrenta.engine.Engine;
import com.torrenta.engine.MutableSource;
import com.torrenta.engine.Publication;

/**
 * How many puts a publication needs (BEP 46). Pure.
 *
 * <p>libtorrent signs each put one above the highest number it finds in the DHT and cannot be told
 * a number. Followers keep the highest they have seen and take only higher ones, so a version has
 * to go above {@link Publication#seq()}: when the DHT has forgotten the item (nobody republished
 * it for a few hours), it would start again low, and the puts climb back above the record one at
 * a time. A republish whose item is still found as published needs none, which also keeps the
 * number from growing every hour and so the climb short.
 */
public final class PublishRules {

    /** Puts in one round at most; a longer climb carries on at the next republish. */
    public static final int CLIMB = 64;

    private PublishRules() {}

    /** 0 when the DHT holds the current version at the recorded number or above, else how many puts. */
    public static int putsNeeded(Publication p, Optional<Engine.MutableItem> found) {
        if (p.infoHash().isEmpty()) return 0;
        long seq = found.map(Engine.MutableItem::seq).orElse(0L);
        boolean current = found.flatMap(i -> MutableSource.infoHash(i.value()))
                        .map(p.infoHash()::equalsIgnoreCase)
                        .orElse(false)
                && seq >= p.seq();
        if (current) return 0;
        return (int) Math.min(CLIMB, Math.max(1, p.seq() + 1 - seq));
    }

    /** The value a publication's item holds: its current version. */
    public static byte[] item(Publication p) {
        return MutableSource.item(p.infoHash());
    }

    /** The info-hash a created torrent is published by: its v1 one when it has one, for every client's sake. */
    public static String infoHashOf(String v1, String v2) {
        return v1 == null || v1.isEmpty() ? v2 : v1;
    }
}
