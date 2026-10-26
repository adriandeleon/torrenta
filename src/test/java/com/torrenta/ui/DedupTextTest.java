package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.torrenta.engine.ContentIndex;
import com.torrenta.engine.DedupMode;
import com.torrenta.engine.DedupPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupTextTest {

    private static DedupPlan.Match match(int index, String torrent, long size, DedupPlan.Method method) {
        Path p = Path.of("/x/" + index).toAbsolutePath();
        return new DedupPlan.Match(
                index, "f" + index, size, new ContentIndex.Entry("5".repeat(64), torrent, 0, p, size), p, method);
    }

    @Test
    void theLinesCountByWayAndNameTheTorrent() {
        Map<String, String> names = Map.of("a", "Debian 13.1", "b", "Debian 13.0");
        DedupPlan plan = new DedupPlan(List.of(
                match(0, "a", 3L << 30, DedupPlan.Method.LINK),
                match(1, "a", 1L << 30, DedupPlan.Method.LINK),
                match(2, "b", 1L << 20, DedupPlan.Method.COPY)));
        DedupText.Offer offer = DedupText.offer(plan, names::get);
        assertEquals(2, offer.links());
        assertEquals(1, offer.copies());
        String link = DedupText.linkLine(offer);
        assertTrue(link.startsWith("Link 2 files ("), link);
        assertTrue(link.endsWith("from “Debian 13.1”"), link);
        assertEquals("Copy one file (1.0 MiB) from “Debian 13.0”, on another drive", DedupText.copyLine(offer));
        DedupText.Offer two = DedupText.offer(
                new DedupPlan(
                        List.of(match(0, "a", 5, DedupPlan.Method.LINK), match(1, "b", 5, DedupPlan.Method.LINK))),
                names::get);
        assertTrue(DedupText.linkLine(two).endsWith("from 2 torrents"));
        assertTrue(DedupText.offer(DedupPlan.NONE, names::get).isEmpty());
    }

    @Test
    void theModeDecidesWhatIsOfferedAndMadeWithoutAsking() {
        assertFalse(DedupMode.OFF.links());
        assertTrue(DedupMode.LINK.links() && !DedupMode.LINK.offersCopies());
        assertTrue(DedupMode.ASK.offersCopies() && !DedupMode.ASK.copiesWithoutAsking());
        assertTrue(DedupMode.COPY.copiesWithoutAsking());
        assertEquals(DedupMode.ASK, DedupMode.byName("nonsense"), "the default");
        assertEquals(DedupMode.COPY, DedupMode.byName(" copy "));
    }
}
