package com.torrenta.engine;

/**
 * One torrent, as of the moment it was read. Immutable: the window gets a fresh list each tick.
 *
 * @param id the info-hash, hex
 * @param name the display name; a magnet's {@code dn} until the metadata arrives
 * @param size bytes wanted, or 0 before the metadata is known
 * @param progress 0..1
 * @param downloadRate payload bytes per second
 * @param uploadRate payload bytes per second
 */
public record Torrent(
        String id,
        String name,
        long size,
        double progress,
        TorrentState state,
        long downloadRate,
        long uploadRate,
        int peers,
        int seeds) {}
