package com.torrenta.search;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultRankerTest {

    private static SearchResult result(String name, int seeds, Instant published, String hash) {
        return new SearchResult(
                name,
                100,
                seeds,
                0,
                published,
                hash.isEmpty()
                        ? new SearchResult.Link.TorrentUrl("https://x.test/" + name.replace(' ', '-'))
                        : new SearchResult.Link.Magnet("magnet:?xt=urn:btih:" + hash),
                hash,
                "P",
                "");
    }

    @Test
    void seedsFirstThenCoverageThenRecencyThenName() {
        Instant older = Instant.parse("2024-01-01T00:00:00Z");
        Instant newer = Instant.parse("2024-06-01T00:00:00Z");
        SearchResult popular = result("Something else", 500, older, "");
        SearchResult both = result("Ubuntu 24.04 desktop", 10, older, "");
        SearchResult one = result("Ubuntu 23.10", 10, newer, "");
        SearchResult oneNewer = result("Ubuntu 22.04", 10, newer, "");
        SearchResult undated = result("Ubuntu 20.04", 10, null, "");
        List<SearchResult> ranked = ResultRanker.rank(List.of(undated, one, oneNewer, both, popular), "ubuntu 24.04");
        assertEquals(List.of(popular, both, oneNewer, one, undated), ranked);
    }

    @Test
    void coverageCountsTheQuerysWordsInTheName() {
        assertEquals(2, ResultRanker.coverage("Ubuntu 24.04 LTS", "ubuntu 24.04"));
        assertEquals(1, ResultRanker.coverage("Ubuntu 23.10", "ubuntu 24.04"));
        assertEquals(0, ResultRanker.coverage("Debian", "ubuntu 24.04"));
        assertEquals(0, ResultRanker.coverage("Debian", "   "));
        assertEquals(List.of("a", "b"), ResultRanker.terms(" A  b a "), "distinct, lower-cased");
    }

    @Test
    void duplicatesKeepTheBestSeededCopyAndBorrowWhatItLacked() {
        Instant when = Instant.parse("2024-06-01T00:00:00Z");
        String hash = "2aa4f5a7e209e54b32803d43670971c4c8caaa05";
        SearchResult fromA = new SearchResult(
                "ubuntu.iso",
                6114656256L,
                10,
                2,
                when,
                new SearchResult.Link.Magnet("magnet:?xt=urn:btih:" + hash),
                hash,
                "A",
                "https://a.test/1");
        SearchResult fromB = new SearchResult(
                "ubuntu.iso",
                -1,
                50,
                -1,
                null,
                new SearchResult.Link.TorrentUrl("https://b.test/ubuntu.torrent"),
                hash.toUpperCase(),
                "B",
                "");
        SearchResult other = result("other", 1, null, "");
        List<SearchResult> merged = ResultRanker.merge(List.of(List.of(fromA, other), List.of(fromB)), "ubuntu");
        assertEquals(2, merged.size());
        SearchResult kept = merged.get(0);
        assertEquals("B", kept.provider(), "the copy with more seeds");
        assertEquals(50, kept.seeds());
        assertEquals(6114656256L, kept.size(), "borrowed from the other copy");
        assertEquals(when, kept.published());
        assertEquals(2, kept.leechers());
        assertEquals("https://a.test/1", kept.detailUrl());
        assertEquals(fromB.link(), kept.link(), "the winner's own link");
        assertEquals(other, merged.get(1));
    }

    @Test
    void withoutAHashTheLinkIsTheIdentity() {
        SearchResult a = result("a", 5, null, "");
        SearchResult sameLink = result("a", 3, null, "");
        SearchResult otherLink = result("b", 5, null, "");
        assertEquals(List.of(a, otherLink), ResultRanker.dedupe(List.of(a, sameLink, otherLink)));
        assertEquals("link:https://x.test/a", a.identity());
    }
}
