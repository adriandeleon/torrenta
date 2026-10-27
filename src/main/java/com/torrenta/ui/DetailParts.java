package com.torrenta.ui;

import java.util.Set;

import com.torrenta.engine.TorrentDetail.Part;

/**
 * What each detail tab needs the engine to read besides the general facts: the poll asks for the
 * showing tab's parts alone, since a torrent of 50,000 files costs a call per file for its list.
 *
 * <p>The Overview needs the pieces and their availability for its map, the files for the map's
 * skipped pieces and for {@link Diagnosis} (the file the unavailable pieces fall in, a locked
 * archive, files to choose), and the peers and trackers for the stall rules. Log and Speed read
 * nothing but the row and the history.
 */
final class DetailParts {

    private DetailParts() {}

    static final Set<Part> OVERVIEW = Set.of(Part.FILES, Part.PEERS, Part.TRACKERS, Part.PIECES);

    /** The parts tab {@code tab} (one of {@link DetailPane#TABS}) shows; none for one it does not know. */
    static Set<Part> of(String tab) {
        return switch (tab) {
            case "overview", "general", "pieces" -> OVERVIEW;
            case "files" -> Set.of(Part.FILES);
            case "peers" -> Set.of(Part.PEERS);
            case "trackers" -> Set.of(Part.TRACKERS, Part.WEB_SEEDS);
            default -> Part.NONE;
        };
    }
}
