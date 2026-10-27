package com.torrenta.session;

import java.util.Locale;
import java.util.Optional;

import com.torrenta.engine.Engine;
import com.torrenta.engine.Following;
import com.torrenta.engine.MutableSource;
import com.torrenta.engine.Torrent;

/**
 * What following an updatable torrent decides (BEP 46). Pure.
 *
 * <p>An item is a new version when its sequence number is higher than the one the torrent came
 * from and it names another torrent; a higher number naming the same torrent (a republish) only
 * moves the number. Once the new version is complete, the old one is let go of: removed from the
 * list (its files kept, the new version may share them through dedup's links) when the setting
 * says so, else left as it is, no longer followed either way.
 */
public final class FollowRules {

    private FollowRules() {}

    /**
     * What a lookup means for a followed torrent.
     *
     * @param infoHash the new version's, hex; empty when there is none to add
     * @param seq the sequence number to record
     */
    public record Step(String infoHash, long seq) {
        public boolean adds() {
            return !infoHash.isEmpty();
        }
    }

    /**
     * The step for torrent {@code id}, followed as {@code following}, given what the lookup
     * found; empty when there is nothing to do (nothing found, nothing newer, a value that names
     * no torrent).
     */
    public static Optional<Step> step(String id, Following following, Optional<Engine.MutableItem> found) {
        if (found.isEmpty() || found.get().seq() <= following.seq()) return Optional.empty();
        Optional<String> infoHash = MutableSource.infoHash(found.get().value());
        if (infoHash.isEmpty()) return Optional.empty();
        long seq = found.get().seq();
        // The id is a v1 hash, or a v2 one cut to 20 bytes: the same torrent either way.
        if (idOf(infoHash.get()).equals(id.toLowerCase(Locale.ROOT))) return Optional.of(new Step("", seq));
        return Optional.of(new Step(infoHash.get(), seq));
    }

    /** The id the engine gives the torrent a hash names: a v2 hash cut to 20 bytes. */
    public static String idOf(String infoHash) {
        String h = infoHash.toLowerCase(Locale.ROOT);
        return h.length() > 40 ? h.substring(0, 40) : h;
    }

    /**
     * Whether a new version is done replacing the old one: it is complete. The old one, if it is
     * still in the list, is then let go of.
     */
    public static boolean replaced(Torrent newer) {
        return newer.progress() >= 1.0 && !newer.direct();
    }
}
