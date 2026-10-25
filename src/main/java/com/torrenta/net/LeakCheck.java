package com.torrenta.net;

import java.net.InetAddress;
import java.util.Optional;

/**
 * Whether the binding keeps the torrents' traffic where it was meant to go, from three addresses:
 * where traffic through the bound interface leaves for the internet, where traffic by the system's
 * default route leaves, and what the swarm says this machine is. Pure. "Exposed" needs proof: the
 * swarm sees the default route's address, and the bound path, checked, leaves somewhere else. A
 * full-tunnel VPN, where both leave through it, is therefore never a leak, and nothing unknown is
 * ever called one.
 */
public final class LeakCheck {

    private LeakCheck() {}

    public enum Verdict {
        /** Traffic leaves through the binding's exit, and the swarm sees that one if it says. */
        PROTECTED,
        /** The swarm sees the default route's address, which the bound path does not leave by. */
        EXPOSED,
        /** The bound interface is down or gone: nothing goes out at all, so nothing leaks. */
        HALTED,
        /** Nothing is bound: there is no binding to check. */
        UNBOUND,
        /** Bound, but not enough is known to say: the exit check is off, failed, or disagrees. */
        UNKNOWN
    }

    /**
     * Where one path leaves for the internet, as the exit check's server saw it: an address, or
     * why there is none ({@code problem}), or neither when it was not asked.
     */
    public record Exit(String address, String problem) {
        public Exit {
            address = address == null ? "" : address;
            problem = problem == null ? "" : problem;
        }

        public static final Exit NOT_ASKED = new Exit("", "");

        public static Exit seen(String address) {
            return new Exit(address, "");
        }

        public static Exit failed(String problem) {
            return new Exit("", problem);
        }

        public boolean known() {
            return !address.isEmpty();
        }
    }

    /**
     * @param bound the binding as {@link InterfaceWatch} found it
     * @param swarmSees libtorrent's external address, empty until it has settled on one
     * @param boundExit where traffic through the binding leaves
     * @param defaultExit where traffic by the default route leaves
     */
    public static Verdict verdict(InterfaceWatch.State bound, String swarmSees, Exit boundExit, Exit defaultExit) {
        if (bound.presence() == InterfaceWatch.Presence.UNBOUND) return Verdict.UNBOUND;
        if (bound.reachesNoOne()) return Verdict.HALTED;
        Optional<InetAddress> seen = InterfaceWatch.literal(swarmSees == null ? "" : swarmSees);
        Optional<InetAddress> through = InterfaceWatch.literal(boundExit.address());
        Optional<InetAddress> around = InterfaceWatch.literal(defaultExit.address());
        if (seen.isPresent() && around.isPresent() && through.isPresent()) {
            if (seen.equals(around) && !through.equals(around)) return Verdict.EXPOSED;
        }
        if (through.isPresent() && (seen.isEmpty() || seen.equals(through))) return Verdict.PROTECTED;
        return Verdict.UNKNOWN;
    }
}
