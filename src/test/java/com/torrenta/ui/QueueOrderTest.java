package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.Engine.QueueMove;
import com.torrenta.engine.QueueOrder;
import com.torrenta.engine.Torrent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The order a move is sent to the selection, proven against a model of the queue: the engine
 * moves one torrent at a time and shifts the others, and after the whole selection has moved the
 * selected torrents must still stand in their original order among themselves.
 */
class QueueOrderTest {

    /** A queue of ids by position, moving one at a time as libtorrent and Transmission do. */
    private static final class Queue {
        private final List<String> order = new ArrayList<>();

        Queue(String... ids) {
            order.addAll(List.of(ids));
        }

        void move(String id, QueueMove move) {
            int at = order.indexOf(id);
            order.remove(at);
            int to =
                    switch (move) {
                        case TOP -> 0;
                        case UP -> Math.max(0, at - 1);
                        case DOWN -> Math.min(order.size(), at + 1);
                        case BOTTOM -> order.size();
                    };
            order.add(to, id);
        }

        Torrent torrent(String id) {
            return T.downloading().id(id).queued(order.indexOf(id)).build();
        }

        List<String> of(List<String> ids) {
            return order.stream().filter(ids::contains).toList();
        }

        /** {@code setQueuePosition}: to that place, the ones in between shifting. */
        void set(String id, int position) {
            order.remove(id);
            order.add(position, id);
        }

        void undo(List<String> before) {
            for (QueueOrder.Step step : QueueOrder.restore(before, List.copyOf(order))) set(step.id(), step.position());
        }
    }

    private static void apply(Queue queue, List<String> selected, QueueMove move) {
        List<Torrent> chosen = selected.stream().map(queue::torrent).toList();
        for (Torrent t : QueueOrder.plan(chosen, move)) queue.move(t.id(), move);
    }

    @Test
    void aSelectionMovedToTheTopKeepsItsOrder() {
        Queue q = new Queue("a", "b", "c", "d", "e");
        apply(q, List.of("b", "d"), QueueMove.TOP);
        assertEquals(List.of("b", "d", "a", "c", "e"), q.order);
    }

    @Test
    void aSelectionMovedToTheBottomKeepsItsOrder() {
        Queue q = new Queue("a", "b", "c", "d", "e");
        apply(q, List.of("b", "d"), QueueMove.BOTTOM);
        assertEquals(List.of("a", "c", "e", "b", "d"), q.order);
    }

    @Test
    void aSelectionMovedUpOrDownMovesAsABlock() {
        Queue up = new Queue("a", "b", "c", "d", "e");
        apply(up, List.of("c", "d"), QueueMove.UP);
        assertEquals(List.of("a", "c", "d", "b", "e"), up.order);

        Queue down = new Queue("a", "b", "c", "d", "e");
        apply(down, List.of("b", "c"), QueueMove.DOWN);
        assertEquals(List.of("a", "d", "b", "c", "e"), down.order);
    }

    @Test
    void theSelectionsOrderInTheTableDoesNotMatter() {
        // The table may be sorted by name or rate; the plan sorts by queue position first.
        Queue q = new Queue("a", "b", "c", "d", "e");
        apply(q, List.of("d", "b"), QueueMove.TOP);
        assertEquals(List.of("b", "d", "a", "c", "e"), q.order);
        assertEquals(List.of("b", "d"), q.of(List.of("b", "d")));
    }

    @Test
    void torrentsOutOfTheQueueGoLast() {
        // A seed has no queue position (-1); it is sent after the queued ones and the engine ignores it.
        Torrent seed = T.seeding().id("s").build();
        Torrent third = T.downloading().id("c").queued(2).build();
        Torrent first = T.downloading().id("a").queued(0).build();
        assertEquals(
                List.of("a", "c", "s"),
                QueueOrder.plan(List.of(seed, third, first), QueueMove.UP).stream()
                        .map(Torrent::id)
                        .toList());
        assertEquals(
                List.of("s", "c", "a"),
                QueueOrder.plan(List.of(seed, third, first), QueueMove.TOP).stream()
                        .map(Torrent::id)
                        .toList());
    }

    @Test
    void undoingAMovePutsTheWholeQueueBackForEverySelectionAndMove() {
        List<String> ids = List.of("a", "b", "c", "d", "e");
        for (int mask = 1; mask < 1 << ids.size(); mask++) {
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) if ((mask & 1 << i) != 0) selected.add(ids.get(i));
            for (QueueMove move : QueueMove.values()) {
                Queue q = new Queue(ids.toArray(String[]::new));
                apply(q, selected, move);
                q.undo(ids);
                assertEquals(ids, q.order, selected + " " + move + " undone");
            }
        }
    }

    @Test
    void undoingSeveralMovesInTurnPutsEachOrderBack() {
        Queue q = new Queue("a", "b", "c", "d", "e", "f");
        List<String> first = List.copyOf(q.order);
        apply(q, List.of("e", "b"), QueueMove.TOP);
        List<String> second = List.copyOf(q.order);
        apply(q, List.of("a", "f"), QueueMove.UP);
        q.undo(second);
        assertEquals(second, q.order);
        q.undo(first);
        assertEquals(first, q.order);
    }

    @Test
    void aQueueAlreadyInOrderNeedsNoCall() {
        assertEquals(List.of(), QueueOrder.restore(List.of("a", "b", "c"), List.of("a", "b", "c")));
        assertEquals(
                List.of(new QueueOrder.Step("a", 0)),
                QueueOrder.restore(List.of("a", "b", "c"), List.of("b", "c", "a")),
                "one call puts the one moved back; the others shift with it");
    }

    @Test
    void aTorrentAddedSinceKeepsItsPlaceAndOneGoneIsLeftOut() {
        // "x" arrived after the move, "c" was removed: the others get their old order back around x.
        Queue q = new Queue("d", "x", "a", "b");
        q.undo(List.of("a", "b", "c", "d"));
        assertEquals(List.of("a", "x", "b", "d"), q.order);
    }

    @Test
    void theOrderIsByPositionWithoutSeedsOrLinks() {
        Torrent seed = T.seeding().id("s").build();
        Torrent link = T.downloading().id("url-0123").queued(0).build();
        Torrent second = T.downloading().id("b").queued(1).build();
        Torrent first = T.downloading().id("a").queued(0).build();
        assertEquals(List.of("a", "b"), QueueOrder.order(List.of(seed, second, link, first)));
    }
}
