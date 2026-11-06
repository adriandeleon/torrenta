package com.torrenta.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.Engine.QueueMove;

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
public final class QueueOrder {

    private QueueOrder() {}

    /** The selected torrents in the order their moves are to be sent. Pure. */
    public static List<Torrent> plan(List<Torrent> selected, QueueMove move) {
        List<Torrent> out = new ArrayList<>(selected);
        out.sort(Comparator.comparingInt(QueueOrder::rank));
        if (move == QueueMove.TOP || move == QueueMove.DOWN) Collections.reverse(out);
        return List.copyOf(out);
    }

    /**
     * The torrents' ids in queue order: the ones the engine keeps out of its queue (position -1)
     * and downloads from links, which queue apart, left out. What an undo of a move puts back.
     */
    public static List<String> order(List<Torrent> torrents) {
        return torrents.stream()
                .filter(t -> t.queuePosition() >= 0 && !t.direct())
                .sorted(Comparator.comparingInt(Torrent::queuePosition))
                .map(Torrent::id)
                .toList();
    }

    /** One {@link Engine#setQueuePosition} call. */
    public record Step(String id, int position) {}

    /**
     * The calls that give the queue back the order {@code before} had, from the order it has
     * {@code now}. The torrents in both get back their old order in the places they hold now as a
     * set, so one added since keeps its place and one gone since is simply missing. Each call
     * moves one torrent to a place and shifts the ones in between, as both engines do, and they
     * go from the top down, so a call never disturbs the places already put back; a torrent
     * already in its place gets none. Pure.
     */
    public static List<Step> restore(List<String> before, List<String> now) {
        Set<String> wasThere = new HashSet<>(before);
        Set<String> isThere = new HashSet<>(now);
        Iterator<String> old =
                before.stream().filter(isThere::contains).toList().iterator();
        List<String> wanted = new ArrayList<>(now.size());
        for (String id : now) wanted.add(wasThere.contains(id) ? old.next() : id);

        List<String> queue = new ArrayList<>(now);
        List<Step> steps = new ArrayList<>();
        for (int place = 0; place < wanted.size(); place++) {
            String id = wanted.get(place);
            if (queue.get(place).equals(id)) continue;
            queue.remove(id);
            queue.add(place, id);
            steps.add(new Step(id, place));
        }
        return List.copyOf(steps);
    }

    private static int rank(Torrent t) {
        return t.queuePosition() < 0 ? Integer.MAX_VALUE : t.queuePosition();
    }
}
