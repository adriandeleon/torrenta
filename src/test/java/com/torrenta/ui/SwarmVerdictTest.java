package com.torrenta.ui;

import com.torrenta.engine.SwarmPreview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwarmVerdictTest {

    private static final long T0 = 1_000_000;

    /** Metadata in at T0 + 5 s; 100 pieces, the ticked files touching 40. */
    private static SwarmPreview seen(int seeds, int peers, int connected, int seen, int wantedSeen) {
        return new SwarmPreview(T0, T0 + 5_000, seeds, peers, connected, 100, seen, 40, wantedSeen);
    }

    private static SwarmVerdict at(SwarmPreview p, long afterStart) {
        return SwarmVerdict.of(p, T0 + afterStart);
    }

    @Test
    void seedsDecideFirstSinceASeedHoldsEveryPiece() {
        SwarmVerdict many = at(seen(124, 30, 2, 0, 0), 1_000);
        assertEquals(SwarmVerdict.Kind.HEALTHY, many.kind());
        assertEquals("Healthy · 124 seeds, 30 downloading", many.sentence());
        assertEquals("swarm-healthy", many.styleClass());
        SwarmVerdict one = at(seen(1, 0, 1, 0, 0), 1_000);
        assertEquals(SwarmVerdict.Kind.THIN, one.kind());
        assertEquals("Thin · 1 seed, no one else downloading: slow, and stuck if it leaves", one.sentence());
        assertEquals(
                "Thin · 2 seeds, 1 downloading: slow, and stuck if they leave",
                at(seen(2, 1, 0, 0, 0), 1_000).sentence());
    }

    @Test
    void withNoSeedThePeersPiecesDecideOnceTheyHaveHadTimeToShow() {
        assertEquals(
                "Thin · no seed, but the peers hold every piece between them",
                at(seen(0, 5, 3, 70, 40), 6_000).sentence(),
                "every wanted piece is somewhere, though not the whole torrent");
        SwarmVerdict early = at(seen(0, 5, 3, 70, 30), 10_000);
        assertEquals(SwarmVerdict.Kind.LOOKING, early.kind(), "five seconds of bitfields prove nothing");
        assertEquals("Looking · 5 peers so far", early.sentence());
        SwarmVerdict settled = at(seen(0, 5, 3, 70, 30), 5_000 + SwarmVerdict.SETTLE_MS);
        assertEquals(SwarmVerdict.Kind.INCOMPLETE, settled.kind());
        assertEquals("Incomplete · no seed, and the peers hold 75% of the chosen files", settled.sentence());
        assertEquals(
                "Incomplete · no seed, and the peers hold 99% of the chosen files",
                at(new SwarmPreview(T0, T0, 0, 1, 1, 1000, 999, 1000, 999), SwarmVerdict.SETTLE_MS)
                        .sentence(),
                "floored: one piece short is never all of it");
    }

    @Test
    void nothingTickedReadsAsTheWholeTorrent() {
        SwarmPreview none = new SwarmPreview(T0, T0, 0, 2, 2, 100, 100, 0, 0);
        assertEquals(SwarmVerdict.Kind.THIN, at(none, 1_000).kind());
    }

    @Test
    void noOneAtAllIsDeadOnlyAfterLongEnoughToHaveFoundSomeone() {
        SwarmPreview nobody = SwarmPreview.nothingYet(T0);
        assertEquals("Looking · no one yet", at(nobody, 10_000).sentence());
        SwarmVerdict dead = at(nobody, 65_000);
        assertEquals(SwarmVerdict.Kind.DEAD, dead.kind());
        assertEquals("Dead · no seeds or peers found in 1m 05s", dead.sentence());
        assertEquals(
                SwarmVerdict.Kind.LOOKING,
                at(new SwarmPreview(T0, 0, 0, 0, 1, 0, 0, 0, 0), 65_000).kind(),
                "a peer connected, the file list not in yet: still looking");
    }
}
