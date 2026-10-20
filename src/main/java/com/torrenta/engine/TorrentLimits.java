package com.torrenta.engine;

/**
 * One torrent's own limits, as the Limits dialog edits them. Engine-neutral; the engine keeps them
 * with the torrent's resume data.
 *
 * @param downloadLimit bytes per second, 0 for none
 * @param uploadLimit bytes per second, 0 for none
 * @param maxConnections peers at once, 0 for the engine's own limit
 * @param maxUploads unchoked peers at once, 0 for the engine's own limit
 * @param firstAndLastFirst the first and last piece of every wanted file go to the front of the
 *     queue, so a media file plays (and a player seeks to its end) before the rest is there.
 *     Needs the metadata; false until it has arrived.
 */
public record TorrentLimits(
        int downloadLimit, int uploadLimit, int maxConnections, int maxUploads, boolean firstAndLastFirst) {

    public static final TorrentLimits NONE = new TorrentLimits(0, 0, 0, 0, false);

    public TorrentLimits {
        downloadLimit = Math.max(0, downloadLimit);
        uploadLimit = Math.max(0, uploadLimit);
        maxConnections = Math.max(0, maxConnections);
        maxUploads = Math.max(0, maxUploads);
    }
}
