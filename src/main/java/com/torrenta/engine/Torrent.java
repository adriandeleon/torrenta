package com.torrenta.engine;

/**
 * One torrent, as of the moment it was read. Immutable: the window gets a fresh list each tick and
 * updates its rows in place from it.
 *
 * @param id the best info-hash (v2 if there is one, else v1), hex
 * @param name the display name; a magnet's {@code dn} until the metadata arrives
 * @param size bytes wanted, or 0 before the metadata is known
 * @param done bytes of the wanted ones that are downloaded
 * @param progress 0..1
 * @param downloadRate payload bytes per second
 * @param uploadRate payload bytes per second
 * @param uploaded payload bytes uploaded over the torrent's life
 * @param downloaded payload bytes downloaded over the torrent's life
 * @param added when the torrent was added, epoch seconds
 * @param completed when the download finished, epoch seconds; 0 if it has not
 * @param tracker the tracker last announced to, as a URL; empty if none
 * @param savePath where the files are
 * @param paused stopped by the user (not by the queue)
 * @param sequential pieces are fetched in order
 * @param seedingTime seconds spent finished (every wanted piece there), over the torrent's life;
 *     what a seeding-time limit measures
 */
public record Torrent(
        String id,
        String name,
        long size,
        long done,
        double progress,
        TorrentState state,
        long downloadRate,
        long uploadRate,
        int peers,
        int seeds,
        long uploaded,
        long downloaded,
        long added,
        long completed,
        String tracker,
        String savePath,
        boolean paused,
        boolean sequential,
        long seedingTime) {}
