package com.torrenta.net;

import java.util.List;
import java.util.Optional;

import com.torrenta.net.InterfaceWatch.Change;
import com.torrenta.net.InterfaceWatch.Nic;
import com.torrenta.net.InterfaceWatch.Presence;
import com.torrenta.net.InterfaceWatch.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterfaceWatchTest {

    private static final Nic LO = new Nic("lo", true, List.of("127.0.0.1", "0:0:0:0:0:0:0:1"));
    private static final Nic ETH = new Nic("eth0", true, List.of("192.168.1.68", "fe80:0:0:0:67c:16ff:fe74:1ff8"));
    private static final Nic TUN_UP = new Nic("tun0", true, List.of("10.8.0.2"));
    private static final Nic TUN_DOWN = new Nic("tun0", false, List.of("10.8.0.2"));

    @Test
    void anInterfaceIsFoundByNameExactlyFirstThenIgnoringCase() {
        State up = InterfaceWatch.of("tun0", List.of(LO, ETH, TUN_UP));
        assertEquals(new State("tun0", Presence.UP, "tun0", List.of("10.8.0.2")), up);
        assertEquals(
                Presence.DOWN, InterfaceWatch.of("tun0", List.of(LO, TUN_DOWN)).presence());
        assertEquals("eth0", InterfaceWatch.of("ETH0", List.of(ETH)).name(), "Windows names are not case-bound");
        State gone = InterfaceWatch.of(" tun0 ", List.of(LO, ETH));
        assertEquals(new State("tun0", Presence.MISSING, "", List.of()), gone);
        assertTrue(gone.reachesNoOne());
        assertEquals("tun0", gone.label());
    }

    @Test
    void anAddressIsFoundOnWhicheverInterfaceHoldsItComparedAsAnAddress() {
        assertEquals("tun0", InterfaceWatch.of("10.8.0.2", List.of(LO, TUN_UP)).name());
        assertEquals(
                Presence.DOWN, InterfaceWatch.of("10.8.0.2", List.of(TUN_DOWN)).presence());
        assertEquals(
                "eth0",
                InterfaceWatch.of("fe80::67c:16ff:fe74:1ff8", List.of(LO, ETH)).name(),
                "short form");
        assertEquals("lo", InterfaceWatch.of("[::1]", List.of(ETH, LO)).name(), "brackets as in the listen string");
        assertEquals(
                Presence.MISSING, InterfaceWatch.of("10.8.0.3", List.of(TUN_UP)).presence());
        assertEquals(Presence.UNBOUND, InterfaceWatch.of("  ", List.of(LO)).presence());
        assertFalse(InterfaceWatch.State.UNBOUND.reachesNoOne());
    }

    @Test
    void javasSpelledOutIpv6IsWrittenAsPeopleWriteIt() {
        assertEquals("::1", InterfaceWatch.compact("0:0:0:0:0:0:0:1"));
        assertEquals("fe80::67c:16ff:fe74:1ff8", InterfaceWatch.compact("fe80:0:0:0:67c:16ff:fe74:1ff8"));
        assertEquals("2001:db8::1:0:0:1", InterfaceWatch.compact("2001:db8:0:0:1:0:0:1"), "the first of two runs");
        assertEquals("2001:db8:0:1:1:1:1:1", InterfaceWatch.compact("2001:db8:0:1:1:1:1:1"), "one zero is no run");
        assertEquals("::", InterfaceWatch.compact("0:0:0:0:0:0:0:0"));
        assertEquals("10.8.0.2", InterfaceWatch.compact("10.8.0.2"));
    }

    @Test
    void onlyLiteralsAreAddressesAndNothingIsEverResolved() {
        assertTrue(InterfaceWatch.literal("10.8.0.2").isPresent());
        assertTrue(InterfaceWatch.literal("fe80::1%eth0").isPresent(), "a zone id is dropped");
        assertTrue(InterfaceWatch.literal("tun0").isEmpty());
        assertTrue(InterfaceWatch.literal("beef").isEmpty(), "hex letters alone are a name");
        assertTrue(InterfaceWatch.literal("localhost").isEmpty());
        assertTrue(InterfaceWatch.literal("1.2.3").isEmpty());
        assertTrue(InterfaceWatch.literal("300.1.1.1").isEmpty());
    }

    @Test
    void theLogHearsOfABindingThatReachesNoOneOnceAndOfItsReturn() {
        State up = InterfaceWatch.of("tun0", List.of(TUN_UP));
        State down = InterfaceWatch.of("tun0", List.of(TUN_DOWN));
        State gone = InterfaceWatch.of("tun0", List.of(LO));
        assertEquals(Optional.empty(), InterfaceWatch.change(null, up), "a working binding at start says nothing");
        assertEquals(
                Optional.of(new Change(true, "tun0 is down: no peer is reached or dialled until it is back")),
                InterfaceWatch.change(up, down));
        assertEquals(Optional.empty(), InterfaceWatch.change(down, down));
        assertEquals(
                Optional.of(
                        new Change(true, "There is no interface tun0: no peer is reached or dialled until it is back")),
                InterfaceWatch.change(down, gone),
                "down, then gone altogether");
        assertEquals(
                Optional.of(new Change(false, "tun0 is back (10.8.0.2): peers are reached through it again")),
                InterfaceWatch.change(gone, up));
        assertEquals(
                Optional.of(new Change(false, "tun0 has new addresses: 10.8.0.6")),
                InterfaceWatch.change(
                        up, InterfaceWatch.of("tun0", List.of(new Nic("tun0", true, List.of("10.8.0.6"))))));
        assertEquals(
                Optional.of(new Change(
                        true,
                        "No interface has the address 10.8.0.9: no peer is reached or dialled until one has it again")),
                InterfaceWatch.change(null, InterfaceWatch.of("10.8.0.9", List.of(TUN_UP))),
                "already lost at start");
    }

    @Test
    void aBindingChangedInTheSettingsSpeaksOnlyWhenItReachesNoOne() {
        State tun = InterfaceWatch.of("tun0", List.of(TUN_UP, ETH));
        assertEquals(Optional.empty(), InterfaceWatch.change(tun, InterfaceWatch.of("eth0", List.of(TUN_UP, ETH))));
        assertEquals(Optional.empty(), InterfaceWatch.change(tun, State.UNBOUND));
        assertTrue(InterfaceWatch.change(tun, InterfaceWatch.of("wg0", List.of(TUN_UP)))
                .orElseThrow()
                .warning());
    }
}
