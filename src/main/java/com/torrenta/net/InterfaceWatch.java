package com.torrenta.net;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.torrenta.i18n.Messages.tr;

/**
 * The interface the session is bound to, as the system has it now: there or not, up or down, and
 * its addresses. Read every few seconds on the window's poll thread, because a VPN that drops takes
 * its interface with it, and a session bound there then reaches no one without a word: which is
 * the point of binding, since nothing leaves by the default route instead, but the person should
 * know. Pure, apart from {@link #system()}.
 */
public final class InterfaceWatch {

    private InterfaceWatch() {}

    /** Where the binding stands. */
    public enum Presence {
        /** Nothing is bound: the session uses every interface. */
        UNBOUND,
        /** The interface is there and up. */
        UP,
        /** The interface is there and down: no peer is reached or dialled through it. */
        DOWN,
        /** No interface has that name, or holds that address. */
        MISSING
    }

    /** One network interface as the system lists it: its name, whether it is up, its addresses. */
    public record Nic(String name, boolean up, List<String> addresses) {
        public Nic {
            addresses = List.copyOf(addresses);
        }
    }

    /**
     * @param bound the setting as written: an interface name, an address, or empty
     * @param presence where it stands
     * @param name the interface the binding names, or the one holding its address; empty when
     *     missing or unbound
     * @param addresses that interface's addresses, in the system's order, without zone ids
     */
    public record State(String bound, Presence presence, String name, List<String> addresses) {
        public State {
            addresses = List.copyOf(addresses);
        }

        public static final State UNBOUND = new State("", Presence.UNBOUND, "", List.of());

        /** Bound, and through an interface that cannot carry anything now. */
        public boolean reachesNoOne() {
            return presence == Presence.DOWN || presence == Presence.MISSING;
        }

        /** The interface's name, or the binding as written when no interface answers to it. */
        public String label() {
            return name.isEmpty() ? bound : name;
        }
    }

    /**
     * The binding against the system's interfaces. An address (a literal, with or without
     * brackets) is looked for among every interface's addresses, compared as addresses, so
     * {@code fe80::1} finds {@code fe80:0:0:0:0:0:0:1}; anything else is an interface name,
     * matched exactly first and then ignoring case. Never resolves a name.
     */
    public static State of(String bound, List<Nic> nics) {
        String b = bound == null ? "" : bound.trim();
        if (b.isEmpty()) return State.UNBOUND;
        Optional<InetAddress> address = literal(b);
        Nic found = null;
        if (address.isPresent()) {
            for (Nic nic : nics) {
                if (nic.addresses().stream().map(InterfaceWatch::literal).anyMatch(address::equals)) {
                    found = nic;
                    break;
                }
            }
        } else {
            for (Nic nic : nics) if (nic.name().equals(b)) found = nic;
            if (found == null) for (Nic nic : nics) if (nic.name().equalsIgnoreCase(b)) found = nic;
        }
        if (found == null) return new State(b, Presence.MISSING, "", List.of());
        return new State(b, found.up() ? Presence.UP : Presence.DOWN, found.name(), found.addresses());
    }

    /** A line for the event log when the binding's state moved. */
    public record Change(boolean warning, String text) {}

    /**
     * What to say when the state went from {@code before} (null at the first look) to {@code
     * after}: a binding that reaches no one, one that came back, an interface whose addresses
     * changed. A binding changed in the settings says something only when the new one reaches no
     * one; nothing is said of being unbound, or of nothing having changed.
     */
    public static Optional<Change> change(State before, State after) {
        if (after.presence() == Presence.UNBOUND || after.equals(before)) return Optional.empty();
        boolean same = before != null && before.bound().equals(after.bound());
        if (after.reachesNoOne()) {
            if (same && before.presence() == after.presence()) return Optional.empty();
            String key = after.presence() == Presence.DOWN
                    ? "net.interface.down"
                    : literal(after.bound()).isPresent() ? "net.interface.noAddress" : "net.interface.missing";
            return Optional.of(new Change(true, tr(key, after.label())));
        }
        if (!same) return Optional.empty();
        String addresses = String.join(", ", after.addresses());
        if (before.reachesNoOne())
            return Optional.of(new Change(false, tr("net.interface.back", after.label(), addresses)));
        return Optional.of(new Change(false, tr("net.interface.addresses", after.label(), addresses)));
    }

    /** An address written as one, parsed without any lookup; empty for anything else. */
    static Optional<InetAddress> literal(String text) {
        String t = text.trim();
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
        int zone = t.indexOf('%');
        if (zone >= 0) t = t.substring(0, zone);
        // A name such as "eth0" or "tun0" is never a literal; only digits, dots, colons and hex.
        if (t.isEmpty() || !t.toLowerCase(Locale.ROOT).matches("[0-9a-f:.]+")) return Optional.empty();
        if (t.indexOf(':') < 0 && !t.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return Optional.empty();
        try {
            return Optional.of(InetAddress.ofLiteral(t));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * An IPv6 address as people write it: Java spells out every group ({@code 0:0:0:0:0:0:0:1}),
     * so the longest run of two or more zero groups becomes {@code ::}, the first if tied (RFC
     * 5952). Anything that is not eight groups is left as it is.
     */
    static String compact(String address) {
        String[] groups = address.split(":", -1);
        if (groups.length != 8) return address;
        int bestStart = -1, bestLength = 1, start = -1;
        for (int i = 0; i <= 8; i++) {
            boolean zero = i < 8 && groups[i].matches("0+");
            if (zero && start < 0) start = i;
            if (!zero && start >= 0) {
                if (i - start > bestLength) {
                    bestStart = start;
                    bestLength = i - start;
                }
                start = -1;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') out.append(':');
            out.append(groups[i].replaceFirst("^0+(?=.)", "").toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** The system's interfaces now; empty when they cannot be read. */
    public static List<Nic> system() {
        List<Nic> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                List<String> addresses = new ArrayList<>();
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    String text = a.getHostAddress();
                    int zone = text.indexOf('%');
                    addresses.add(compact(zone >= 0 ? text.substring(0, zone) : text));
                }
                out.add(new Nic(nic.getName(), nic.isUp(), addresses));
            }
        } catch (SocketException e) {
            return List.of();
        }
        return out;
    }
}
