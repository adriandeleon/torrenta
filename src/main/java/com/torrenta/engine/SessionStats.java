package com.torrenta.engine;

/**
 * The session's counters, as of the engine's last reading: what the Statistics window shows.
 *
 * <p>Bytes are payload unless the name says otherwise. "Session" is since {@link Engine#start()};
 * "all time" adds what earlier runs left in the stats store. A counter the engine could not read
 * (the library has no metric of that name) is {@code -1}, and the window shows a dash for it.
 *
 * @param sessionDownload payload bytes received this session
 * @param sessionUpload payload bytes sent this session
 * @param allTimeDownload payload bytes received over every run, this one included
 * @param allTimeUpload payload bytes sent over every run, this one included
 * @param sessionSeconds how long the engine has been running
 * @param allTimeSeconds how long it has run over every run, this one included
 * @param totalReceived every byte received, protocol and payload
 * @param totalSent every byte sent, protocol and payload
 * @param wasted payload bytes received and thrown away: duplicates and failed hash checks
 * @param peersConnected peer connections open now
 * @param peersHalfOpen connections being opened
 * @param dhtNodes nodes in the DHT routing table
 * @param diskBlocksRead 16 KiB blocks read from disk this session
 * @param diskBlocksWritten 16 KiB blocks written to disk this session
 * @param diskBlocksInUse 16 KiB buffers held in memory now
 * @param queuedDiskJobs disk jobs waiting
 * @param torrents how many torrents are in each state
 */
public record SessionStats(
        long sessionDownload,
        long sessionUpload,
        long allTimeDownload,
        long allTimeUpload,
        long sessionSeconds,
        long allTimeSeconds,
        long totalReceived,
        long totalSent,
        long wasted,
        long peersConnected,
        long peersHalfOpen,
        long dhtNodes,
        long diskBlocksRead,
        long diskBlocksWritten,
        long diskBlocksInUse,
        long queuedDiskJobs,
        Torrents torrents) {

    /** libtorrent's block: what the disk counters count in. */
    public static final long BLOCK_BYTES = 16 * 1024;

    /** A counter the library does not have. */
    public static final long UNKNOWN = -1;

    /** Before the engine has started, or when nothing has been read yet. */
    public static final SessionStats NONE =
            new SessionStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Torrents.NONE);

    /** Torrents by state, from the engine's own gauges; {@code -1} where it has none. */
    public record Torrents(long checking, long downloading, long seeding, long queued, long stopped, long error) {
        public static final Torrents NONE = new Torrents(0, 0, 0, 0, 0, 0);
    }

    /** Uploaded over downloaded this session; -1 when nothing has moved, infinity when only up. */
    public double sessionRatio() {
        return ratio(sessionUpload, sessionDownload);
    }

    public double allTimeRatio() {
        return ratio(allTimeUpload, allTimeDownload);
    }

    static double ratio(long up, long down) {
        if (down <= 0) return up > 0 ? Double.POSITIVE_INFINITY : -1;
        return (double) up / down;
    }
}
