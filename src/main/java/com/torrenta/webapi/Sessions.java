package com.torrenta.webapi;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The logged-in clients, by the {@code SID} cookie a login hands out: 32 random bytes, kept in
 * memory only (a restart logs everyone out, as qBittorrent's does), each forgotten after an hour
 * without a request. Thread-safe; the clock is the caller's.
 */
public final class Sessions {

    /** Idle time after which a session is forgotten: qBittorrent's default. */
    public static final long IDLE_MILLIS = 60 * 60 * 1000;

    private final SecureRandom random;
    private final Map<String, Long> lastSeen = new HashMap<>();

    public Sessions(SecureRandom random) {
        this.random = random;
    }

    /** A new session, from now. */
    public synchronized String open(long now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        lastSeen.put(id, now);
        return id;
    }

    /** Whether {@code id} is a live session; a live one is touched, an idle one forgotten. */
    public synchronized boolean valid(String id, long now) {
        if (id == null) return false;
        Long seen = lastSeen.get(id);
        if (seen == null) return false;
        if (now - seen > IDLE_MILLIS) {
            lastSeen.remove(id);
            return false;
        }
        lastSeen.put(id, now);
        // Now and then, the idle others too, so the map does not grow with clients that left.
        if (lastSeen.size() > 64) prune(now);
        return true;
    }

    public synchronized void close(String id) {
        if (id != null) lastSeen.remove(id);
    }

    /** Everyone out: the password or the user changed. */
    public synchronized void closeAll() {
        lastSeen.clear();
    }

    private void prune(long now) {
        for (Iterator<Long> it = lastSeen.values().iterator(); it.hasNext(); )
            if (now - it.next() > IDLE_MILLIS) it.remove();
    }
}
