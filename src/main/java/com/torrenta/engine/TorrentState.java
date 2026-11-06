package com.torrenta.engine;

/** Where a torrent is in its life, reduced to what the list needs to show; {@code ui.Format} has the words. */
public enum TorrentState {
    CHECKING,
    FETCHING_METADATA,
    DOWNLOADING,
    FINISHED,
    SEEDING,
    UNKNOWN
}
