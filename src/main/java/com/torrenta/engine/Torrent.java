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
 * @param category the category it is filed under, or empty
 * @param tags its tags, sorted; empty for none
 * @param error what stopped the torrent (a missing file, a full disk), or empty while nothing has
 * @param trackers the hosts of its trackers, each once, sorted; empty for a trackerless torrent
 * @param trackerError some tracker of its last answered with an error and has not answered since
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
        long seedingTime,
        String category,
        java.util.List<String> tags,
        String error,
        java.util.List<String> trackers,
        boolean trackerError) {

    public Torrent {
        category = category == null ? "" : category;
        tags = tags == null ? java.util.List.of() : java.util.List.copyOf(tags);
        error = error == null ? "" : error;
        trackers = trackers == null ? java.util.List.of() : java.util.List.copyOf(trackers);
    }
}
