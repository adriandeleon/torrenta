package com.torrenta.ui;

import java.util.Set;

import com.torrenta.engine.TorrentDetail.Part;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailPartsTest {

    @Test
    void eachTabAsksForWhatItShows() {
        assertEquals(Set.of(Part.FILES, Part.PEERS, Part.TRACKERS, Part.PIECES), DetailParts.of("overview"));
        assertEquals(Set.of(Part.FILES), DetailParts.of("files"));
        assertEquals(Set.of(Part.PEERS), DetailParts.of("peers"));
        assertEquals(Set.of(Part.TRACKERS, Part.WEB_SEEDS), DetailParts.of("trackers"));
        assertTrue(DetailParts.of("log").isEmpty());
        assertTrue(DetailParts.of("speed").isEmpty());
        // A remembered tab from before the Overview took them in opens the Overview.
        assertEquals(DetailParts.OVERVIEW, DetailParts.of("general"));
        assertEquals(DetailParts.OVERVIEW, DetailParts.of("pieces"));
    }

    /** Every tab the pane has is known to the rule; a new tab would otherwise read nothing. */
    @Test
    void everyTabHasARule() {
        for (String tab : DetailPane.TABS)
            assertTrue(
                    !DetailParts.of(tab).isEmpty() || tab.equals("log") || tab.equals("speed"), tab + " reads nothing");
    }

    /** The Overview's card reads files, peers and trackers ({@link Diagnosis}); its map, the pieces. */
    @Test
    void theOverviewHasWhatTheDiagnosisReads() {
        assertTrue(DetailParts.of("overview").containsAll(Set.of(Part.FILES, Part.PEERS, Part.TRACKERS, Part.PIECES)));
        assertTrue(!DetailParts.of("overview").contains(Part.WEB_SEEDS));
    }
}
