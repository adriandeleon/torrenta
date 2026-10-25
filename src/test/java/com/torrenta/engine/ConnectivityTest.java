package com.torrenta.engine;

import com.torrenta.engine.Connectivity.Mapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure: no session. */
class ConnectivityTest {

    private static final Connectivity UP = Connectivity.NONE.listened(6881, true);

    @Test
    void aListenOpensThePort() {
        assertEquals(6881, UP.port());
        assertTrue(UP.listening());
        assertFalse(UP.listenFailed());
    }

    @Test
    void anotherInterfaceFailingOnTheSamePortLeavesItListening() {
        Connectivity c = UP.listened(6881, false);
        assertTrue(c.listening());
        assertTrue(c.listenFailed());
    }

    @Test
    void aFailureAloneIsNotListening() {
        Connectivity c = Connectivity.NONE.listened(6881, false);
        assertFalse(c.listening());
        assertTrue(c.listenFailed());
        assertEquals(6881, c.port());
    }

    @Test
    void aNewPortForgetsWhatWasProvedAboutTheOld() {
        Connectivity reached = UP.mapped(true).connectedFrom("203.0.113.9");
        assertTrue(reached.incoming());
        Connectivity moved = reached.listened(51413, true);
        assertEquals(51413, moved.port());
        assertFalse(moved.incoming());
        assertEquals(Mapping.NONE, moved.mapping());
        assertTrue(reached.listened(6881, true).incoming(), "the same port again keeps it");
    }

    @Test
    void oneTransportMappingIsEnoughWhicheverAnswersFirst() {
        assertEquals(Mapping.MAPPED, UP.mapped(false).mapped(true).mapping());
        assertEquals(Mapping.MAPPED, UP.mapped(true).mapped(false).mapping());
        assertEquals(Mapping.FAILED, UP.mapped(false).mapping());
    }

    @Test
    void onlyAPeerFromOutsideProvesThePortOpen() {
        for (String local : new String[] {
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.68",
            "169.254.3.4",
            "100.64.0.1",
            "0.0.0.0",
            "::1",
            "fe80::1",
            "fd12:3456::1",
            "fc00::5",
            "::ffff:192.168.1.2",
            "[::1]",
            "not an address",
            "",
            "1.2.3",
            "1.2.3.400"
        }) {
            assertFalse(UP.connectedFrom(local).incoming(), local);
        }
        for (String outside : new String[] {
            "203.0.113.9",
            "172.32.0.1",
            "100.128.0.1",
            "8.8.8.8",
            "2001:db8::1",
            "2806:108e:29:cc53::2",
            "::ffff:8.8.8.8",
            "[2001:db8::7]"
        }) {
            assertTrue(UP.connectedFrom(outside).incoming(), outside);
        }
    }

    @Test
    void theDhtSwitchAndBootstrapAreKept() {
        Connectivity c = UP.dht(true).bootstrapped();
        assertTrue(c.dhtEnabled());
        assertTrue(c.dhtBootstrapped());
        assertTrue(c.listened(7000, true).dhtBootstrapped(), "a port change is not a DHT restart");
        assertSame(c, c.connectedFrom("10.0.0.1"));
    }

    /** The swarm's view of this machine is the network's, not the socket's: a new port keeps it. */
    @Test
    void theAddressTheSwarmSeesOutlivesANewPort() {
        Connectivity seen = Connectivity.NONE.listened(6881, true).seenAs("198.51.100.7");
        assertEquals("198.51.100.7", seen.externalAddress());
        assertEquals(
                "198.51.100.7",
                seen.listened(6882, true).mapped(true).bootstrapped().externalAddress());
        assertEquals("", Connectivity.NONE.externalAddress(), "nobody has said yet");
    }
}
