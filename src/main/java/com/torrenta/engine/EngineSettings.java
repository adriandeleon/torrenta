package com.torrenta.engine;

import java.util.Locale;

import static com.torrenta.i18n.Messages.tr;

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
 * @param protocol the protocol page: discovery, transport, encryption, default trackers
 * @param speed the speed page: limits, alternative limits, their schedule
 * @param queue the queue and share limits page: active slots, the slow-torrent rule, share limits
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
        boolean proxyPeers,
        Protocol protocol,
        Speed speed,
        Queue queue) {

    /**
     * The Queue and share limits page. Active counts are 0 for no limit (libtorrent says -1).
     *
     * @param activeDownloads torrents downloading at once; the rest wait in the queue
     * @param activeSeeds torrents seeding at once
     * @param activeLimit torrents active at once, downloading and seeding together
     * @param ignoreSlowTorrents a torrent moving under a few KiB/s does not take a slot
     * @param share the global share limit, which a torrent's own override replaces
     */
    public record Queue(
            int activeDownloads, int activeSeeds, int activeLimit, boolean ignoreSlowTorrents, ShareLimit share) {

        public Queue {
            if (share == null) share = ShareLimit.NONE;
        }

        /** libtorrent's defaults: 3 downloading, 5 seeding, 15 in all, slow ones not counted. */
        public static Queue defaults() {
            return new Queue(3, 5, 15, true, ShareLimit.NONE);
        }

        public Queue withShare(ShareLimit share) {
            return new Queue(activeDownloads, activeSeeds, activeLimit, ignoreSlowTorrents, share);
        }
    }

    /**
     * The Speed page. Limits are KiB/s, 0 meaning none. The alternative pair is used while
     * {@code alternativeOn} is set by hand or the schedule says so.
     *
     * @param scheduleFrom minutes after midnight; a window that ends before it starts spans midnight
     * @param scheduleDays the days the window applies, by {@link java.time.DayOfWeek}
     * @param countOverhead rate-limit IP overhead too, not only payload
     */
    public record Speed(
            int download,
            int upload,
            int alternativeDownload,
            int alternativeUpload,
            boolean alternativeOn,
            boolean scheduleOn,
            int scheduleFrom,
            int scheduleTo,
            java.util.Set<java.time.DayOfWeek> scheduleDays,
            boolean countOverhead) {

        public static Speed defaults() {
            return new Speed(
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    22 * 60,
                    8 * 60,
                    java.util.EnumSet.allOf(java.time.DayOfWeek.class),
                    false);
        }

        /**
         * Whether the schedule puts the alternative limits in force at this moment. Pure.
         *
         * <p>A window that ends before it starts spans midnight and belongs to the day it started
         * on: Monday 22:00–08:00 covers Tuesday 03:00 but not Monday 03:00, which was Sunday's.
         */
        public boolean scheduleActive(java.time.LocalDateTime now) {
            if (!scheduleOn) return false;
            int minute = now.getHour() * 60 + now.getMinute();
            java.time.DayOfWeek today = now.getDayOfWeek();
            if (scheduleFrom <= scheduleTo) {
                return scheduleDays.contains(today) && minute >= scheduleFrom && minute < scheduleTo;
            }
            return (scheduleDays.contains(today) && minute >= scheduleFrom)
                    || (scheduleDays.contains(today.minus(1)) && minute < scheduleTo);
        }

        /** The alternative pair is in force now, by hand or by schedule. */
        public boolean alternativeActive(java.time.LocalDateTime now) {
            return alternativeOn || scheduleActive(now);
        }

        /** The download limit in force now, bytes per second, 0 for none. */
        public int effectiveDownloadBytes(java.time.LocalDateTime now) {
            return (alternativeActive(now) ? alternativeDownload : download) * 1024;
        }

        public int effectiveUploadBytes(java.time.LocalDateTime now) {
            return (alternativeActive(now) ? alternativeUpload : upload) * 1024;
        }

        public Speed withAlternativeOn(boolean on) {
            return new Speed(
                    download,
                    upload,
                    alternativeDownload,
                    alternativeUpload,
                    on,
                    scheduleOn,
                    scheduleFrom,
                    scheduleTo,
                    scheduleDays,
                    countOverhead);
        }
    }

    /**
     * The Protocol page.
     *
     * @param dht find peers through the distributed hash table (magnets need it)
     * @param pex swap peer lists with connected peers; a per-torrent flag in libtorrent
     * @param lsd find peers on the local network
     * @param utp use uTP as well as TCP, which keeps a home connection responsive
     * @param encryption require, prefer, or never encrypt peer connections
     * @param anonymous send no client name or version, and nothing a tracker could use to tell
     *     clients apart
     * @param defaultTrackers appended to every torrent as it is added
     */
    public record Protocol(
            boolean dht,
            boolean pex,
            boolean lsd,
            boolean utp,
            Encryption encryption,
            boolean anonymous,
            java.util.List<String> defaultTrackers) {

        public enum Encryption {
            REQUIRE("require"),
            PREFER("prefer"),
            OFF("off");

            private final String label;

            Encryption(String label) {
                this.label = label;
            }

            public String label() {
                return tr("encryption." + label);
            }
        }

        public static Protocol defaults() {
            return new Protocol(true, true, true, true, Encryption.PREFER, false, java.util.List.of());
        }
    }

    public enum ProxyType {
        NONE("none"),
        SOCKS5("socks5"),
        HTTP("http");

        private final String label;

        ProxyType(String label) {
            this.label = label;
        }

        public String label() {
            return tr("proxyType." + label);
        }
    }

    public static EngineSettings defaults() {
        return new EngineSettings(
                6881,
                false,
                "",
                true,
                true,
                500,
                100,
                20,
                ProxyType.NONE,
                "",
                1080,
                "",
                "",
                true,
                Protocol.defaults(),
                Speed.defaults(),
                Queue.defaults());
    }

    public EngineSettings withProtocol(Protocol protocol) {
        return new EngineSettings(
                listenPort,
                randomPort,
                listenInterface,
                ipv6,
                upnp,
                maxConnections,
                maxConnectionsPerTorrent,
                uploadSlots,
                proxy,
                proxyHost,
                proxyPort,
                proxyUsername,
                proxyPassword,
                proxyPeers,
                protocol,
                speed,
                queue);
    }

    public EngineSettings withSpeed(Speed speed) {
        return new EngineSettings(
                listenPort,
                randomPort,
                listenInterface,
                ipv6,
                upnp,
                maxConnections,
                maxConnectionsPerTorrent,
                uploadSlots,
                proxy,
                proxyHost,
                proxyPort,
                proxyUsername,
                proxyPassword,
                proxyPeers,
                protocol,
                speed,
                queue);
    }

    public EngineSettings withQueue(Queue queue) {
        return new EngineSettings(
                listenPort,
                randomPort,
                listenInterface,
                ipv6,
                upnp,
                maxConnections,
                maxConnectionsPerTorrent,
                uploadSlots,
                proxy,
                proxyHost,
                proxyPort,
                proxyUsername,
                proxyPassword,
                proxyPeers,
                protocol,
                speed,
                queue);
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
                "listen=%s upnp=%s conns=%d/%d slots=%d proxy=%s active=%d/%d/%d share=%s",
                listenInterfaces(),
                upnp,
                maxConnections,
                maxConnectionsPerTorrent,
                uploadSlots,
                proxy,
                queue.activeDownloads(),
                queue.activeSeeds(),
                queue.activeLimit(),
                queue.share());
    }
}
