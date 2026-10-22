package com.torrenta.engine;

import java.util.Locale;

/**
 * What the session knows about being reachable, as of its last alerts: the listen port, whether
 * the router mapped it, whether a peer from outside this network has connected to it, and whether
 * the DHT has bootstrapped. Kept on the alert thread, so reading it costs no session call; the
 * status bar's words are {@code ui.NetworkStatus}'s, not the engine's.
 *
 * @param port the TCP port listened on, or the one that failed; 0 before the first listen alert
 * @param listening some interface listens on {@code port}
 * @param listenFailed some interface could not; with {@code listening} false, none could
 * @param mapping what UPnP or NAT-PMP made of the port
 * @param incoming a peer outside this network connected in since the port was last opened
 * @param dhtEnabled the DHT is switched on in the settings
 * @param dhtBootstrapped the DHT has finished its bootstrap since the session started
 */
public record Connectivity(
        int port,
        boolean listening,
        boolean listenFailed,
        Mapping mapping,
        boolean incoming,
        boolean dhtEnabled,
        boolean dhtBootstrapped) {

    /** The router's answer to the port mapping, the best of UPnP's and NAT-PMP's. */
    public enum Mapping {
        NONE,
        MAPPED,
        FAILED
    }

    /** Before the engine has started. */
    public static final Connectivity NONE = new Connectivity(0, false, false, Mapping.NONE, false, false, false);

    /**
     * The facts after a listen alert. A different port opens a new question: no peer has reached
     * it yet and no router has mapped it.
     */
    public Connectivity listened(int newPort, boolean succeeded) {
        boolean same = newPort == port;
        return new Connectivity(
                newPort,
                succeeded || (same && listening),
                !succeeded || (same && listenFailed),
                same ? mapping : Mapping.NONE,
                same && incoming,
                dhtEnabled,
                dhtBootstrapped);
    }

    /** One transport mapped the port, or failed to; a success from either stands. */
    public Connectivity mapped(boolean succeeded) {
        Mapping next = succeeded || mapping == Mapping.MAPPED ? Mapping.MAPPED : Mapping.FAILED;
        return new Connectivity(port, listening, listenFailed, next, incoming, dhtEnabled, dhtBootstrapped);
    }

    /** A peer connected in from {@code address}; one on this network proves nothing about the router. */
    public Connectivity connectedFrom(String address) {
        if (incoming || isLocal(address)) return this;
        return new Connectivity(port, listening, listenFailed, mapping, true, dhtEnabled, dhtBootstrapped);
    }

    public Connectivity dht(boolean enabled) {
        return new Connectivity(port, listening, listenFailed, mapping, incoming, enabled, dhtBootstrapped);
    }

    public Connectivity bootstrapped() {
        return new Connectivity(port, listening, listenFailed, mapping, incoming, dhtEnabled, true);
    }

    /**
     * An address on this machine or this network: loopback, the private IPv4 ranges, link-local,
     * carrier-grade NAT ({@code 100.64.0.0/10}, the ISP's side of the same router problem), IPv6
     * unique-local ({@code fc00::/7}). Read off the literal, never resolved; anything that is
     * not a literal counts as local, so it can never make the port look open.
     */
    static boolean isLocal(String address) {
        if (address == null) return true;
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        if (a.startsWith("::ffff:") && a.indexOf('.') > 0) a = a.substring("::ffff:".length());
        if (a.indexOf(':') >= 0) {
            if (a.equals("::1") || a.equals("::")) return true;
            int colon = a.indexOf(':');
            String first = colon == 0 ? "0" : a.substring(0, colon);
            int head;
            try {
                head = Integer.parseInt(first, 16);
            } catch (NumberFormatException e) {
                return true;
            }
            return (head & 0xfe00) == 0xfc00 || (head & 0xffc0) == 0xfe80;
        }
        String[] parts = a.split("\\.", -1);
        if (parts.length != 4) return true;
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                b[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return true;
            }
            if (b[i] < 0 || b[i] > 255) return true;
        }
        return b[0] == 127
                || b[0] == 10
                || b[0] == 0
                || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                || (b[0] == 192 && b[1] == 168)
                || (b[0] == 169 && b[1] == 254)
                || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
    }
}
