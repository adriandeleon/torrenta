package com.torrenta.webapi;

import java.util.HashMap;
import java.util.Map;

/**
 * Failed logins by address: the fifth in a row bans the address for an hour, a good login clears
 * its count. qBittorrent's rule. Thread-safe; the clock is the caller's, so it is tested.
 */
public final class LoginGuard {

    /** Failures in a row that ban. */
    public static final int MAX_FAILURES = 5;

    /** How long a ban lasts. */
    public static final long BAN_MILLIS = 60 * 60 * 1000;

    private record Count(int failures, long bannedUntil) {}

    private final Map<String, Count> counts = new HashMap<>();

    /** Whether {@code address} may try now. */
    public synchronized boolean allowed(String address, long now) {
        Count c = counts.get(address);
        if (c == null || c.bannedUntil() == 0) return true;
        if (now < c.bannedUntil()) return false;
        counts.remove(address);
        return true;
    }

    /** A wrong user or password from {@code address}; true when this one banned it. */
    public synchronized boolean failed(String address, long now) {
        Count c = counts.getOrDefault(address, new Count(0, 0));
        int failures = c.failures() + 1;
        boolean ban = failures >= MAX_FAILURES;
        counts.put(address, new Count(failures, ban ? now + BAN_MILLIS : 0));
        return ban;
    }

    /** A good login from {@code address}: its count starts over. */
    public synchronized void succeeded(String address) {
        counts.remove(address);
    }
}
