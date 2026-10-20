package com.torrenta.engine;

import java.util.Locale;

/**
 * The engine's connection settings, engine-neutral: what the Connection page edits.
 *
 * @param listenPort the port to listen on; ignored when {@code randomPort}
 * @param randomPort let the operating system pick a free port at start
 * @param listenInterface an address or interface name to bind to, or empty for all
 * @param ipv6 listen on IPv6 as well
 * @param upnp map the port with UPnP and NAT-PMP
 * @param maxConnections session-wide connection limit
 * @param maxConnectionsPerTorrent per-torrent limit, applied to each torrent as it is added
 * @param uploadSlots how many peers are unchoked at once
 * @param proxy the proxy, or {@link ProxyType#NONE}
 * @param proxyPeers send peer connections through the proxy too, not only trackers
 */
public record EngineSettings(
        int listenPort,
        boolean randomPort,
        String listenInterface,
        boolean ipv6,
        boolean upnp,
        int maxConnections,
        int maxConnectionsPerTorrent,
        int uploadSlots,
        ProxyType proxy,
        String proxyHost,
        int proxyPort,
        String proxyUsername,
        String proxyPassword,
        boolean proxyPeers) {

    public enum ProxyType {
        NONE("None"),
        SOCKS5("SOCKS5"),
        HTTP("HTTP");

        private final String label;

        ProxyType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static EngineSettings defaults() {
        return new EngineSettings(6881, false, "", true, true, 500, 100, 20, ProxyType.NONE, "", 1080, "", "", true);
    }

    /**
     * libtorrent's {@code listen_interfaces}: {@code 0.0.0.0:6881,[::]:6881} for everything, a
     * single {@code address:port} when bound, port {@code 0} for a random one. Pure, so it is
     * tested: a wrong string here is a client that listens on nothing and says nothing.
     */
    public String listenInterfaces() {
        int port = randomPort ? 0 : listenPort;
        String bound = listenInterface == null ? "" : listenInterface.trim();
        if (!bound.isEmpty()) {
            boolean literalV6 = bound.contains(":") && !bound.startsWith("[");
            return (literalV6 ? "[" + bound + "]" : bound) + ":" + port;
        }
        String v4 = "0.0.0.0:" + port;
        return ipv6 ? v4 + ",[::]:" + port : v4;
    }

    /** True when the proxy needs credentials, which libtorrent treats as a distinct type. */
    public boolean proxyAuthenticated() {
        return proxy != ProxyType.NONE && proxyUsername != null && !proxyUsername.isBlank();
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "listen=%s upnp=%s conns=%d/%d slots=%d proxy=%s",
                listenInterfaces(),
                upnp,
                maxConnections,
                maxConnectionsPerTorrent,
                uploadSlots,
                proxy);
    }
}
