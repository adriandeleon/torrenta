package com.torrenta.engine;

import java.util.Optional;

/**
 * What {@link Engine#create} made: the file's bytes and what the dialog shows about them.
 *
 * @param bytes the {@code .torrent}, bencoded, ready to be written as it is
 * @param id the torrent's id in the engine: the best hash, the v2 one cut to 20 bytes when there is one
 * @param name the torrent's name: the file's or the root folder's
 * @param v1 the SHA-1 info-hash, hex, or empty for a v2-only torrent
 * @param v2 the SHA-256 info-hash, hex, or empty for a v1-only torrent
 * @param pieceCount pieces in the torrent
 * @param pieceSize bytes per piece
 * @param totalSize the files' bytes, padding left out
 * @param magnet a magnet link with the trackers
 * @param seeding the torrent as added for seeding, when that was asked and it worked
 * @param problem why it was not added when seeding was asked, or empty: the torrent is still
 *     created, so the file and the link are not lost over a duplicate in the list
 */
public record CreatedTorrent(
        byte[] bytes,
        String id,
        String name,
        String v1,
        String v2,
        int pieceCount,
        int pieceSize,
        long totalSize,
        String magnet,
        Optional<Torrent> seeding,
        String problem) {

    public CreatedTorrent {
        if (seeding == null) seeding = Optional.empty();
        if (problem == null) problem = "";
    }
}
