package com.torrenta.session;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FollowedHistoriesTest {

    @Test
    void arrowingDownAThousandRowsKeepsOnlyTheLastFew() {
        FollowedHistories f = new FollowedHistories();
        for (int i = 0; i < 1000; i++) f.follow("t" + i, SpeedHistory::new);
        assertEquals(FollowedHistories.KEPT, f.ids().size());
        assertEquals("t999", f.ids().getLast(), "the selection is the latest");
        assertNull(f.get("t0"));
        assertNotNull(f.get("t999"));
    }

    @Test
    void followingAgainKeepsTheHistoryAndMovesItToTheEnd() {
        FollowedHistories f = new FollowedHistories(3);
        AtomicInteger started = new AtomicInteger();
        f.follow("a", () -> {
            started.incrementAndGet();
            return new SpeedHistory();
        });
        SpeedHistory a = f.get("a");
        f.follow("b", SpeedHistory::new);
        f.follow("c", SpeedHistory::new);
        f.follow("a", () -> {
            started.incrementAndGet();
            return new SpeedHistory();
        });
        assertEquals(1, started.get(), "a kept history is not started again");
        assertSame(a, f.get("a"));
        f.follow("d", SpeedHistory::new);
        assertEquals(List.of("c", "a", "d"), f.ids(), "b, the one chosen longest ago, went");
    }

    @Test
    void readingAndRecordingAreNotFollowing() {
        FollowedHistories f = new FollowedHistories(2);
        f.follow("a", SpeedHistory::new);
        f.follow("b", SpeedHistory::new);
        f.add("a", 100, 5, 6);
        f.get("a");
        f.add("x", 100, 1, 1);
        f.follow("c", SpeedHistory::new);
        assertEquals(List.of("b", "c"), f.ids(), "a was recorded and read, not followed, so it went");
        assertNull(f.get("x"), "an id never followed gets no history from a sample");
    }

    @Test
    void aTorrentThatGoesTakesItsHistory() {
        FollowedHistories f = new FollowedHistories();
        f.follow("a", SpeedHistory::new);
        f.follow("b", SpeedHistory::new);
        f.add("b", 100, 7, 8);
        f.retainAll(Set.of("b"));
        assertEquals(List.of("b"), f.ids());
        assertEquals(7, f.get("b").latest().orElseThrow().download());
    }
}
