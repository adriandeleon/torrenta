package com.torrenta.ui;

import java.util.List;
import java.util.Set;

import com.torrenta.engine.Torrent;
import com.torrenta.net.LeakCheck.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExposurePauseTest {

    private static final Torrent RUNNING = T.downloading().id("a").build();
    private static final Torrent SEEDING = T.seeding().id("b").build();
    private static final Torrent MINE = T.downloading().id("c").paused().build();
    private static final Torrent LINK =
            T.downloading().id("url-d").direct("example.org").build();
    private static final List<Torrent> ALL = List.of(RUNNING, SEEDING, MINE, LINK);

    @Test
    void exposedPausesWhatRunsAndHoldsItLeavingThePersonsPausesAndLinksAlone() {
        ExposurePause.Step step = ExposurePause.next(Verdict.EXPOSED, true, ALL, Set.of());
        assertEquals(List.of("a", "b"), step.pause());
        assertEquals(Set.of("a", "b"), step.held());
        assertTrue(step.resume().isEmpty());
    }

    @Test
    void protectedAgainResumesTheHeldOnesAndOnlyThose() {
        List<Torrent> paused = List.of(
                T.downloading().id("a").paused().build(),
                T.seeding().id("b").paused().build(),
                MINE);
        ExposurePause.Step step = ExposurePause.next(Verdict.PROTECTED, true, paused, Set.of("a", "b"));
        assertEquals(List.of("a", "b"), step.resume().stream().sorted().toList());
        assertEquals(Set.of(), step.held());
        assertEquals(
                List.of("a"),
                ExposurePause.next(Verdict.PROTECTED, true, List.of(RUNNING), Set.of("a", "gone"))
                        .resume(),
                "one removed meanwhile is not resumed");
    }

    @Test
    void haltedAndUnknownKeepWhatIsHeld() {
        for (Verdict v : List.of(Verdict.HALTED, Verdict.UNKNOWN)) {
            ExposurePause.Step step = ExposurePause.next(v, true, ALL, Set.of("a"));
            assertTrue(step.nothing(), v.name());
            assertEquals(Set.of("a"), step.held());
        }
    }

    @Test
    void turningTheSwitchOffOrUnbindingLetsGo() {
        assertEquals(
                List.of("a"),
                ExposurePause.next(Verdict.EXPOSED, false, ALL, Set.of("a")).resume());
        assertTrue(ExposurePause.next(Verdict.EXPOSED, false, ALL, Set.of()).nothing(), "off: never pauses");
        assertEquals(
                List.of("a"),
                ExposurePause.next(Verdict.UNBOUND, true, ALL, Set.of("a")).resume());
    }
}
