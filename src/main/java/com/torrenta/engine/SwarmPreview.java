package com.torrenta.engine;

/**
 * What a probe of a torrent's swarm has seen before anything is downloaded: who is there, by the
 * trackers' counts and by the peers met, and which pieces some peer was seen to hold. The probe
 * downloads nothing, so nothing here is a rate, and nothing is an estimate of time.
 *
 * @param started epoch millis the probe began
 * @param metadataAt epoch millis the file list was in; 0 before
 * @param seeds the most seeds known: the highest of the trackers' scrapes, the seeds in the peer
 *     list (a seed that dropped the probe is still in it) and the seeds met
 * @param peers the most downloaders known: the trackers' scrapes, or the peers connected that are
 *     not seeds; never every address heard of, which the DHT inflates with ones never reached
 * @param connected peers connected now
 * @param pieces the torrent's pieces; 0 before the file list
 * @param seen of those, held by a peer met over the probe's life (every one, once a seed was met)
 * @param wanted pieces the ticked files touch
 * @param wantedSeen of those, held by a peer met
 */
public record SwarmPreview(
        long started,
        long metadataAt,
        int seeds,
        int peers,
        int connected,
        int pieces,
        int seen,
        int wanted,
        int wantedSeen) {

    /** Nothing seen yet: the probe began at {@code started}. */
    public static SwarmPreview nothingYet(long started) {
        return new SwarmPreview(started, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** The file list is in, so the pieces mean something. */
    public boolean hasPieces() {
        return metadataAt > 0 && pieces > 0;
    }
}
