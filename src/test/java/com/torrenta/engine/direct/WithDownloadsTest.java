package com.torrenta.engine.direct;

import com.torrenta.engine.EngineSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithDownloadsTest {

    private static EngineSettings proxy(EngineSettings.ProxyType type, String host, String user) {
        EngineSettings d = EngineSettings.defaults();
        return new EngineSettings(
                d.listenPort(),
                d.randomPort(),
                d.listenInterface(),
                d.ipv6(),
                d.upnp(),
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                type,
                host,
                1080,
                user,
                "pw",
                false,
                d.protocol(),
                d.speed(),
                d.queue());
    }

    @Test
    void curlUsesTheEnginesProxyResolvingNamesThroughSocks() {
        assertEquals(
                new Curl.Proxy("socks5h", "proxy.lan", 1080, "me", "pw"),
                WithDownloads.proxyOf(proxy(EngineSettings.ProxyType.SOCKS5, " proxy.lan ", "me")));
        assertEquals(
                "http",
                WithDownloads.proxyOf(proxy(EngineSettings.ProxyType.HTTP, "p", ""))
                        .scheme());
        assertTrue(WithDownloads.proxyOf(proxy(EngineSettings.ProxyType.NONE, "p", ""))
                .isNone());
        assertTrue(WithDownloads.proxyOf(proxy(EngineSettings.ProxyType.SOCKS5, "  ", ""))
                .isNone());
        assertTrue(WithDownloads.proxyOf(EngineSettings.defaults()).isNone());
    }
}
