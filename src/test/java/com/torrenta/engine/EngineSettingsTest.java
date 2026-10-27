package com.torrenta.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSettingsTest {

    private static EngineSettings with(int port, boolean random, String iface, boolean v6) {
        EngineSettings d = EngineSettings.defaults();
        return new EngineSettings(
                port,
                random,
                iface,
                v6,
                d.upnp(),
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                d.proxyHost(),
                d.proxyPort(),
                d.proxyUsername(),
                d.proxyPassword(),
                d.proxyPeers(),
                EngineSettings.Protocol.defaults(),
                EngineSettings.Speed.defaults(),
                EngineSettings.Queue.defaults());
    }

    @Test
    void listenInterfacesString() {
        assertEquals("0.0.0.0:6881,[::]:6881", with(6881, false, "", true).listenInterfaces());
        assertEquals("0.0.0.0:6881", with(6881, false, "", false).listenInterfaces());
        assertEquals("0.0.0.0:0,[::]:0", with(6881, true, "", true).listenInterfaces(), "random = port 0");
        assertEquals(
                "192.168.1.5:7000", with(7000, false, " 192.168.1.5 ", true).listenInterfaces());
        assertEquals("tun0:7000", with(7000, false, "tun0", true).listenInterfaces(), "an interface name");
        assertEquals(
                "[fe80::1]:7000",
                with(7000, false, "fe80::1", true).listenInterfaces(),
                "a bare v6 literal gets brackets");
        assertEquals(
                "[fe80::1]:7000", with(7000, false, "[fe80::1]", true).listenInterfaces(), "and keeps the ones it has");
    }

    @Test
    void outgoingInterfacesString() {
        assertEquals("", with(6881, false, "", true).outgoingInterfaces(), "unbound: the system's route");
        assertEquals("", with(6881, false, null, true).outgoingInterfaces());
        assertEquals("192.168.1.5", with(7000, false, " 192.168.1.5 ", true).outgoingInterfaces(), "no port");
        assertEquals("tun0", with(7000, true, "tun0", true).outgoingInterfaces(), "an interface name");
        assertEquals("fe80::1", with(7000, false, "fe80::1", true).outgoingInterfaces(), "no brackets");
        assertEquals("fe80::1", with(7000, false, "[fe80::1]", true).outgoingInterfaces(), "taken off");
    }

    @Test
    void proxyAuthentication() {
        EngineSettings d = EngineSettings.defaults();
        assertFalse(d.proxyAuthenticated());
        EngineSettings socks = new EngineSettings(
                d.listenPort(),
                false,
                "",
                true,
                true,
                500,
                100,
                20,
                EngineSettings.ProxyType.SOCKS5,
                "proxy",
                1080,
                "me",
                "pw",
                true,
                EngineSettings.Protocol.defaults(),
                EngineSettings.Speed.defaults(),
                EngineSettings.Queue.defaults());
        assertTrue(socks.proxyAuthenticated());
        EngineSettings anon = new EngineSettings(
                d.listenPort(),
                false,
                "",
                true,
                true,
                500,
                100,
                20,
                EngineSettings.ProxyType.SOCKS5,
                "proxy",
                1080,
                " ",
                "",
                true,
                EngineSettings.Protocol.defaults(),
                EngineSettings.Speed.defaults(),
                EngineSettings.Queue.defaults());
        assertFalse(anon.proxyAuthenticated(), "a blank user is no login");
    }
}
