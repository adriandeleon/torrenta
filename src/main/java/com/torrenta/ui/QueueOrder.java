package com.torrenta.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.torrenta.engine.Engine.QueueMove;
import com.torrenta.engine.Torrent;

/**
 * In what order a queue move is applied to a selection so the selected torrents keep their
 * order among themselves, as qBittorrent keeps it.
 *
 * <p>The engine moves one torrent at a time, and each move shifts the others. "To the top" applied
 * first to the torrent nearest the top would be undone by the next one landing above it, so the
 * selection is walked from the bottom up; "up" is walked from the top down, so a torrent moves
 * into the place its neighbour above has just left; "down" and "to the bottom" are the mirror
 * images. The selection is first sorted by queue position, since the table may be sorted by
 * anything; torrents the engine keeps out of its queue (position -1, libtorrent's seeds) go last
 * and the engine ignores the move for them.
 */
final class QueueOrder {

    private QueueOrder() {}

    /** The selected torrents in the order their moves are to be sent. Pure. */
    static List<Torrent> plan(List<Torrent> selected, QueueMove move) {
        List<Torrent> out = new ArrayList<>(selected);
        out.sort(Comparator.comparingInt(QueueOrder::rank));
        if (move == QueueMove.TOP || move == QueueMove.DOWN) Collections.reverse(out);
        return List.copyOf(out);
    }

    private static int rank(Torrent t) {
        return t.queuePosition() < 0 ? Integer.MAX_VALUE : t.queuePosition();
    }
}
