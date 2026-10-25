package com.torrenta.net;

import java.util.List;

import com.torrenta.net.LeakCheck.Exit;
import com.torrenta.net.LeakCheck.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeakCheckTest {

    private static final InterfaceWatch.State TUN =
            InterfaceWatch.of("tun0", List.of(new InterfaceWatch.Nic("tun0", true, List.of("10.8.0.2"))));
    private static final String VPN = "198.51.100.7";
    private static final String HOME = "203.0.113.20";

    private static Verdict verdict(String swarm, Exit through, Exit around) {
        return LeakCheck.verdict(TUN, swarm, through, around);
    }

    @Test
    void theBindingItselfDecidesFirst() {
        assertEquals(
                Verdict.UNBOUND,
                LeakCheck.verdict(InterfaceWatch.State.UNBOUND, HOME, Exit.seen(HOME), Exit.seen(HOME)));
        InterfaceWatch.State down =
                InterfaceWatch.of("tun0", List.of(new InterfaceWatch.Nic("tun0", false, List.of("10.8.0.2"))));
        assertEquals(
                Verdict.HALTED, LeakCheck.verdict(down, HOME, Exit.seen(VPN), Exit.seen(HOME)), "nothing goes out");
        assertEquals(
                Verdict.HALTED,
                LeakCheck.verdict(InterfaceWatch.of("tun0", List.of()), "", Exit.NOT_ASKED, Exit.NOT_ASKED));
    }

    @Test
    void exposedNeedsTheSwarmToSeeTheDefaultRouteWhichTheBindingDoesNotLeaveBy() {
        assertEquals(Verdict.EXPOSED, verdict(HOME, Exit.seen(VPN), Exit.seen(HOME)));
        assertEquals(
                Verdict.PROTECTED,
                verdict(VPN, Exit.seen(VPN), Exit.seen(VPN)),
                "a full-tunnel VPN: both leave through it, and that is no leak");
        assertEquals(
                Verdict.UNKNOWN,
                verdict(HOME, Exit.NOT_ASKED, Exit.seen(HOME)),
                "the bound exit unknown: a full tunnel looks the same, so nothing is called a leak");
        assertEquals(Verdict.UNKNOWN, verdict(HOME, Exit.failed("timed out"), Exit.seen(HOME)));
    }

    @Test
    void protectedNeedsTheBoundExitAndNoDisagreementFromTheSwarm() {
        assertEquals(Verdict.PROTECTED, verdict(VPN, Exit.seen(VPN), Exit.seen(HOME)));
        assertEquals(Verdict.PROTECTED, verdict("", Exit.seen(VPN), Exit.seen(HOME)), "the swarm has not said yet");
        assertEquals(Verdict.PROTECTED, verdict("", Exit.seen(VPN), Exit.failed("no route")));
        assertEquals(
                Verdict.UNKNOWN,
                verdict("192.0.2.44", Exit.seen(VPN), Exit.seen(HOME)),
                "the swarm sees a third address: neither proof");
        assertEquals(Verdict.UNKNOWN, verdict(VPN, Exit.NOT_ASKED, Exit.NOT_ASKED), "the check is off");
    }

    @Test
    void addressesAreComparedAsAddresses() {
        assertEquals(
                Verdict.EXPOSED,
                verdict("2001:db8::20", Exit.seen("2001:db8:0:0:0:0:0:7"), Exit.seen("2001:DB8:0:0:0:0:0:20")));
    }
}
