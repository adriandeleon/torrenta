package com.torrenta.engine;

/** Where a torrent is in its life, reduced to what the list needs to show. */
public enum TorrentState {
    CHECKING("Checking"),
    FETCHING_METADATA("Fetching metadata"),
    DOWNLOADING("Downloading"),
    FINISHED("Finished"),
    SEEDING("Seeding"),
    UNKNOWN("Unknown");

    private final String label;

    TorrentState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
