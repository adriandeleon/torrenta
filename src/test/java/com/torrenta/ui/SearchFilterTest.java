package com.torrenta.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;

import com.torrenta.search.SearchCategory;
import com.torrenta.search.SearchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchFilterTest {

    /** 2027-01-15T08:00:00Z. */
    private static final Instant NOW = Instant.parse("2027-01-15T08:00:00Z");

    private static final long GIB = 1L << 30;

    /** A result with dull values; {@code change} gives one of them another. */
    private static SearchResult hit(UnaryOperator<Fields> change) {
        Fields f = change.apply(new Fields("ubuntu-24.04-desktop.iso", 4 * GIB, 100, 10, NOW, "Jackett", null));
        return new SearchResult(
                f.name,
                f.size,
                f.seeds,
                f.leechers,
                f.published,
                new SearchResult.Link.TorrentUrl("http://indexer.test/1"),
                "",
                f.provider,
                "",
                f.category);
    }

    private record Fields(
            String name,
            long size,
            int seeds,
            int leechers,
            Instant published,
            String provider,
            SearchCategory category) {
        Fields name(String v) {
            return new Fields(v, size, seeds, leechers, published, provider, category);
        }

        Fields size(long v) {
            return new Fields(name, v, seeds, leechers, published, provider, category);
        }

        Fields seeds(int v) {
            return new Fields(name, size, v, leechers, published, provider, category);
        }

        Fields leechers(int v) {
            return new Fields(name, size, seeds, v, published, provider, category);
        }

        Fields published(Instant v) {
            return new Fields(name, size, seeds, leechers, v, provider, category);
        }

        Fields provider(String v) {
            return new Fields(name, size, seeds, leechers, published, v, category);
        }

        Fields category(SearchCategory v) {
            return new Fields(name, size, seeds, leechers, published, provider, v);
        }
    }

    private static SearchFilter q(String text) {
        return SearchFilter.parse(text, NOW, ZoneOffset.UTC);
    }

    @Test
    void blankMatchesEverything() {
        assertTrue(q("").isEmpty());
        assertTrue(q("   ").matches(hit(f -> f)));
        assertTrue(q(null).matches(hit(f -> f)));
    }

    @Test
    void wordsMatchTheNameAndAllMustHold() {
        assertTrue(q("UBUNTU desktop").matches(hit(f -> f)));
        assertFalse(q("ubuntu server").matches(hit(f -> f)));
        assertTrue(q("-server").matches(hit(f -> f)));
        assertTrue(q("\"24.04-desktop\"").matches(hit(f -> f)));
        assertTrue(q("ubuntu").chips().isEmpty(), "a word is no chip");
    }

    @Test
    void providerIsPartOfTheNameIgnoringCase() {
        assertTrue(q("provider:jack").matches(hit(f -> f)));
        assertFalse(q("provider:prowlarr").matches(hit(f -> f)));
        assertTrue(q("-provider:prowlarr").matches(hit(f -> f)));
        assertTrue(q("provider:\"Example Indexer\"").matches(hit(f -> f.provider("Example Indexer"))));
        assertEquals("provider jack", q("provider:jack").chips().get(0).label());
        assertEquals(
                "not provider prowlarr", q("-provider:prowlarr").chips().get(0).label());
    }

    @Test
    void categoryByItsEnglishNameOrItsLabelAndUnknownHasNone() {
        SearchResult movie = hit(f -> f.category(SearchCategory.MOVIES));
        assertTrue(q("cat:movies").matches(movie));
        assertTrue(q("category:Movies").matches(movie));
        assertFalse(q("cat:tv").matches(movie));
        assertFalse(q("cat:movies").matches(hit(f -> f)), "a result with no category is in none");
        assertTrue(q("-cat:movies").matches(hit(f -> f)));
        assertEquals("category Movies", q("cat:movies").chips().get(0).label());
    }

    @Test
    void aCategoryThatIsNotOneOfOursIsAnInvalidChipReadAsName() {
        FilterQuery.Chip chip = q("cat:podcasts").chips().get(0);
        assertFalse(chip.valid());
        assertEquals("no category podcasts", chip.label());
        assertFalse(q("cat:all").chips().get(0).valid(), "All is no category a result has");
        assertTrue(q("cat:podcasts").matches(hit(f -> f.name("cat:podcasts episode 1"))));
    }

    @Test
    void sizeComparesInBinaryUnitsAndUnknownMatchesNoComparison() {
        assertTrue(q("size>1g").matches(hit(f -> f)));
        assertTrue(q("size>1g size<5g").matches(hit(f -> f)), "a range is two terms");
        assertFalse(q("size>1g size<4g").matches(hit(f -> f)));
        assertTrue(q("size:4g").matches(hit(f -> f)));
        assertTrue(q("size<=4096m").matches(hit(f -> f)));
        assertFalse(q("size>1g").matches(hit(f -> f.size(-1))));
        assertFalse(q("size<1g").matches(hit(f -> f.size(-1))));
        assertTrue(q("-size>1g").matches(hit(f -> f.size(-1))), "the minus keeps the unknown ones");
        assertEquals("size over 1.0 GiB", q("size>1g").chips().get(0).label());
        assertFalse(q("size>big").chips().get(0).valid());
    }

    @Test
    void seedsAndLeechersAreWholeNumbersAndUnknownMatchesNone() {
        assertTrue(q("seeds>=100").matches(hit(f -> f)));
        assertFalse(q("seeds>100").matches(hit(f -> f)));
        assertFalse(q("seeds>=0").matches(hit(f -> f.seeds(-1))));
        assertTrue(q("leechers:10").matches(hit(f -> f)));
        assertTrue(q("leechers<11").matches(hit(f -> f)));
        assertFalse(q("leechers<11").matches(hit(f -> f.leechers(-1))));
        assertEquals("seeds at least 10", q("seeds>=10").chips().get(0).label());
        assertEquals("leechers under 3", q("leechers<3").chips().get(0).label());
        assertEquals(
                "seeds takes a whole number", q("seeds>lots").chips().get(0).label());
        assertEquals(
                "leechers takes a whole number",
                q("leechers>99999999999").chips().get(0).label());
    }

    @Test
    void dateAsAnAgeCountsBackFromNow() {
        SearchResult twoDaysOld = hit(f -> f.published(NOW.minusSeconds(2 * 86_400)));
        assertTrue(q("date<7d").matches(twoDaysOld));
        assertFalse(q("date<1d").matches(twoDaysOld));
        assertTrue(q("date>1d").matches(twoDaysOld));
        assertTrue(q("date<72h").matches(twoDaysOld));
        assertFalse(q("date<1w").matches(hit(f -> f.published(null))), "an unknown date matches no age");
        assertEquals("published in the last 7 days", q("date<7d").chips().get(0).label());
        assertEquals(
                "published more than 1 week ago", q("date>1w").chips().get(0).label());
        assertFalse(q("date:7d").chips().get(0).valid(), "an age needs < or >");
    }

    @Test
    void dateAsADayComparesDaysInTheZoneGiven() {
        SearchResult lateOnThe30th = hit(f -> f.published(Instant.parse("2024-08-30T23:30:00Z")));
        assertTrue(q("date:2024-08-30").matches(lateOnThe30th));
        assertTrue(q("date>=2024-08-01 date<2024-09-01").matches(lateOnThe30th), "a range is two terms");
        assertFalse(q("date>2024-08-30").matches(lateOnThe30th));
        assertTrue(q("date<=2024-08-30").matches(lateOnThe30th));
        assertFalse(q("date<2024-08-30").matches(lateOnThe30th));
        // In Tokyo it was already the 31st.
        assertTrue(SearchFilter.parse("date:2024-08-31", NOW, ZoneId.of("Asia/Tokyo"))
                .matches(lateOnThe30th));
        assertFalse(q("date>=2000-01-01").matches(hit(f -> f.published(null))));
        assertEquals(
                "published on or after 2024-08-01",
                q("date>=2024-08-01").chips().get(0).label());
        assertEquals(
                "published on 2024-08-30", q("date:2024-08-30").chips().get(0).label());
        assertFalse(q("date<2024-02-30").chips().get(0).valid(), "no such day");
        assertFalse(q("date<yesterday").chips().get(0).valid());
    }

    @Test
    void anUnknownKeyIsAnInvalidChipAndTheTermIsReadAsName() {
        FilterQuery.Chip chip = q("state:seeding").chips().get(0);
        assertFalse(chip.valid());
        assertEquals("unknown filter state", chip.label());
        assertEquals("state:seeding", chip.term());
        assertFalse(q("provider>a").chips().get(0).valid(), "provider takes a colon");
    }

    @Test
    void chipsAreTheKeyedTermsInOrder() {
        List<FilterQuery.Chip> chips =
                q("ubuntu provider:jack -seeds<5 size>1g").chips();
        assertEquals(
                List.of("provider:jack", "-seeds<5", "size>1g"),
                chips.stream().map(FilterQuery.Chip::term).toList());
    }

    @Test
    void withAddsATermOnceAndWithoutTakesOneOut() {
        assertEquals("provider:Jackett", SearchFilter.with("", "provider:Jackett"));
        assertEquals("ubuntu provider:Jackett", SearchFilter.with(" ubuntu ", "provider:Jackett"));
        assertEquals("provider:jackett", SearchFilter.with("provider:jackett", "provider:Jackett"));
        assertEquals("ubuntu size>1g", SearchFilter.without("ubuntu  provider:jack size>1g", "provider:jack"));
        assertEquals(
                "size>1g",
                SearchFilter.without("provider:\"Example Indexer\" size>1g", "provider:\"Example Indexer\""));
        assertEquals("ubuntu", SearchFilter.without("ubuntu", "provider:jack"), "nothing to take out");
    }

    @Test
    void theMenusTermsReadBackAsTheyMean() {
        String term = SearchFilter.providerTerm("Example Indexer");
        assertEquals("provider:\"Example Indexer\"", term);
        assertTrue(q(term).matches(hit(f -> f.provider("Example Indexer"))));
        assertFalse(q(term).matches(hit(f -> f)));
        assertEquals("cat:anime", SearchFilter.categoryTerm(SearchCategory.ANIME));
        assertTrue(
                q(SearchFilter.categoryTerm(SearchCategory.ANIME)).matches(hit(f -> f.category(SearchCategory.ANIME))));
    }
}
