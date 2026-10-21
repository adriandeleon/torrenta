package com.torrenta.engine;

import static com.torrenta.i18n.Messages.tr;

/** Where a torrent is in its life, reduced to what the list needs to show. */
public enum TorrentState {
    CHECKING("format.state.checking"),
    FETCHING_METADATA("format.state.fetchingMetadata"),
    DOWNLOADING("format.state.downloading"),
    FINISHED("format.state.finished"),
    SEEDING("format.state.seeding"),
    UNKNOWN("common.unknown");

    private final String label;

    TorrentState(String label) {
        this.label = label;
    }

    /** The state's word in the interface language, read at call time. */
    public String label() {
        return tr(label);
    }
}
