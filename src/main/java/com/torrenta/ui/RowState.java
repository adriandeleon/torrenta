package com.torrenta.ui;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

/**
 * What a row is doing, as the list shows it: one glyph, one colour (by CSS pseudo-class) and the
 * lead of the status sentence. Coarser than libtorrent's states in some places (queued and paused
 * are both "paused" to libtorrent) and finer in others (stalled is a downloading torrent that has
 * received nothing for a while).
 *
 * <p>Declared in the order a sort by state should show them: what needs a person first, then what
 * is moving, then what is waiting, then what is finished.
 */
public enum RowState {
    ERROR("×", "error"),
    STALLED("!", "stalled"),
    DOWNLOADING("↓", "downloading"),
    METADATA("◌", "metadata"),
    CHECKING("⟳", "checking"),
    MOVING("→", "moving"),
    QUEUED("…", "queued"),
    SEEDING("↑", "seeding"),
    PAUSED("‖", "paused"),
    DONE("✓", "done");

    /** A download this long without a byte is stalled; shorter dips are just the swarm breathing. */
    public static final long STALL_AFTER_SECONDS = 60;

    private final String glyph;
    private final String pseudoClass;

    RowState(String glyph, String pseudoClass) {
        this.glyph = glyph;
        this.pseudoClass = pseudoClass;
    }

    public String glyph() {
        return glyph;
    }

    /** The row's CSS pseudo-class, {@code st-} plus this; app.css colours by it. */
    public String pseudoClass() {
        return "st-" + pseudoClass;
    }

    /** Paused, queued and done rows are dimmed: nothing is happening and nothing is wrong. */
    public boolean inactive() {
        return this == PAUSED || this == QUEUED || this == DONE;
    }

    /**
     * The first rule that fits wins. {@code quietSeconds} is how long the row has been downloading
     * without receiving anything ({@link TorrentRow} counts it); 0 when it is receiving.
     */
    public static RowState of(Torrent t, long quietSeconds) {
        if (!t.error().isEmpty()) return ERROR;
        if (t.moving()) return MOVING;
        if (t.state() == TorrentState.CHECKING) return CHECKING;
        if (t.paused()) return complete(t) ? DONE : PAUSED;
        if (t.queued()) return QUEUED;
        return switch (t.state()) {
            case FETCHING_METADATA -> METADATA;
            case DOWNLOADING -> quietSeconds >= STALL_AFTER_SECONDS ? STALLED : DOWNLOADING;
            case FINISHED, SEEDING -> SEEDING;
            default -> complete(t) ? SEEDING : DOWNLOADING;
        };
    }

    /** Every wanted byte is there: finished, or seeding, or a full progress bar. */
    static boolean complete(Torrent t) {
        return t.state() == TorrentState.FINISHED || t.state() == TorrentState.SEEDING || t.progress() >= 1;
    }

    /**
     * Whether this snapshot counts as quiet for the stall clock: meant to be downloading and
     * receiving nothing. Paused and queued torrents are not quiet, they are stopped.
     */
    public static boolean quiet(Torrent t) {
        return t.state() == TorrentState.DOWNLOADING
                && t.downloadRate() <= 0
                && !t.paused()
                && !t.queued()
                && t.error().isEmpty();
    }
}
