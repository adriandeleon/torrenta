package com.torrenta.ui;

import java.time.Instant;
import java.util.List;

import com.torrenta.search.SearchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SearchRowsTest {

    private static final String H1 = "2aa4f5a7e209e54b32803d43670971c4c8caaa05";
    private static final String H2 = "4e3d2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e";

    private static SearchResult hit(String name, int seeds, String hash, Instant published) {
        return new SearchResult(
                name,
                1,
                seeds,
                0,
                published,
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:" + hash),
                hash,
                "P",
                "");
    }

    @Test
    void aResultKeepsItsRowAcrossProvidersAndTheBetterCopyRefreshesIt() {
        SearchRows rows = new SearchRows();
        rows.apply(List.of(hit("a", 5, H1, null)));
        SearchRow first = rows.list().get(0);
        // The second provider's merge: the same torrent with a date, and a new one in front.
        rows.apply(List.of(hit("b", 50, H2, null), hit("a", 5, H1, Instant.EPOCH)));
        assertEquals(2, rows.list().size());
        assertSame(first, rows.get("hash:" + H1), "the row object survived");
        assertEquals(Instant.EPOCH, first.result().published(), "and shows the merged copy");
        assertEquals(
                List.of("a", "b"),
                rows.list().stream().map(r -> r.result().name()).toList(),
                "appended, not reordered");
    }

    @Test
    void aNewSearchRemovesWhatItDidNotFind() {
        SearchRows rows = new SearchRows();
        rows.apply(List.of(hit("a", 5, H1, null), hit("b", 1, H2, null)));
        rows.apply(List.of(hit("b", 1, H2, null)));
        assertEquals(
                List.of("b"), rows.list().stream().map(r -> r.result().name()).toList());
        assertEquals(null, rows.get("hash:" + H1));
        rows.clear();
        assertEquals(0, rows.list().size());
    }

    @Test
    void aDuplicateIdentityInOneListIsOneRow() {
        SearchRows rows = new SearchRows();
        rows.apply(List.of(hit("a", 5, H1, null), hit("a again", 9, H1, null)));
        assertEquals(1, rows.list().size());
        assertEquals("a", rows.list().get(0).result().name(), "the first copy, as the merge put it");
    }
}
