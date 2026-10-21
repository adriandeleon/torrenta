package com.torrenta.rss;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeFilterTest {

    private static EpisodeFilter.Episode ep(int season, int episode) {
        return new EpisodeFilter.Episode(season, episode);
    }

    @Test
    void readsEveryDocumentedForm() {
        assertEquals(
                List.of(new EpisodeFilter.Term(1, 2, 2)),
                EpisodeFilter.parse("1x2").terms(),
                "one episode");
        assertEquals(
                List.of(new EpisodeFilter.Term(1, 2, 5)),
                EpisodeFilter.parse("1x2-5").terms(),
                "a range");
        assertEquals(
                List.of(new EpisodeFilter.Term(1, 2, Integer.MAX_VALUE)),
                EpisodeFilter.parse("1x2-;").terms(),
                "from episode 2 on, qBittorrent's trailing semicolon allowed");
        assertEquals(
                List.of(new EpisodeFilter.Term(1, 1, 4), new EpisodeFilter.Term(2, 1, 1)),
                EpisodeFilter.parse("1x1-4;2x1").terms(),
                "a list");
        assertEquals(
                List.of(new EpisodeFilter.Term(3, 10, 12), new EpisodeFilter.Term(4, 1, Integer.MAX_VALUE)),
                EpisodeFilter.parse(" 3X10-12 ; 4x01- ; ").terms(),
                "spaces, capitals and leading zeros are fine");
        assertTrue(EpisodeFilter.parse("").isEmpty());
        assertTrue(EpisodeFilter.parse(null).isEmpty());
        assertEquals("1x2;3x4-6;7x8-", EpisodeFilter.parse("1x02; 3x4-6;7x08-;").toString(), "written back short");
    }

    @Test
    void rejectsGarbageWithAMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EpisodeFilter.parse("S01E02"));
        assertTrue(e.getMessage().startsWith("\"S01E02\" is not season x episode"), e.getMessage());
        assertTrue(e.getMessage().contains("1x2-5"), "says what was expected: " + e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EpisodeFilter.parse("1x2-3-4"));
        assertThrows(IllegalArgumentException.class, () -> EpisodeFilter.parse("1x"));
        assertThrows(IllegalArgumentException.class, () -> EpisodeFilter.parse("x2"));
        assertThrows(
                IllegalArgumentException.class, () -> EpisodeFilter.parse("1x1-4,2x1"), "commas are not separators");
        assertEquals(
                "\"1x5-2\" runs backwards: the range ends before it starts",
                assertThrows(IllegalArgumentException.class, () -> EpisodeFilter.parse("1x5-2"))
                        .getMessage());
        assertTrue(EpisodeFilter.problem(";;").orElse("").startsWith("only separators"));
        assertEquals(Optional.empty(), EpisodeFilter.problem("1x1-;2x3"));
        assertEquals(Optional.empty(), EpisodeFilter.problem(""), "blank is no filter, not an error");
    }

    @Test
    void findsTheEpisodeInTheCommonSpellings() {
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some.Show.S01E02.720p.WEB-DL"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show s1e2"));
        assertEquals(Optional.of(ep(12, 345)), EpisodeFilter.find("Long Running S12E345 1080p"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show S01.E02"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show S01-E02"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show 1x02 [eztv]"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some.Show.1x2.HDTV"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show Season 1 Episode 2"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show - Season 1, Episode 2 - Title"));
        assertEquals(Optional.of(ep(3, 4)), EpisodeFilter.find("Some.Show.season.3.ep.4"));
        assertEquals(Optional.of(ep(1, 2)), EpisodeFilter.find("Some Show S01E02E03"), "a double episode is its first");
        assertEquals(
                Optional.of(ep(2, 3)), EpisodeFilter.find("Some Show S02E03 1920x1080"), "the resolution is not it");
        assertEquals(Optional.empty(), EpisodeFilter.find("Some Show 1920x1080"), "a resolution alone is no episode");
        assertEquals(Optional.empty(), EpisodeFilter.find("Debian 12.7.0 amd64 netinst"));
        assertEquals(Optional.empty(), EpisodeFilter.find("Ubuntu 24.04 desktop xenial"));
        assertEquals(Optional.empty(), EpisodeFilter.find("MIS01E02"), "part of a word is not an episode");
        assertEquals(Optional.empty(), EpisodeFilter.find(""));
        assertEquals("1x2", ep(1, 2).key());
        assertEquals(10, EpisodeFilter.tokenStart("Some Show S01E02 720p"));
        assertEquals(-1, EpisodeFilter.tokenStart("Debian 12.7.0"));
    }

    @Test
    void matchesATitleWhenItsEpisodeFallsInATerm() {
        EpisodeFilter from = EpisodeFilter.parse("1x2-");
        assertTrue(from.matches("Show S01E02"));
        assertTrue(from.matches("Show S01E99"));
        assertFalse(from.matches("Show S01E01"));
        assertFalse(from.matches("Show S02E05"), "another season");
        assertFalse(from.matches("Show with no episode"), "no episode matches no filter");
        EpisodeFilter list = EpisodeFilter.parse("1x1-4;2x1");
        assertTrue(list.matches("Show 1x03"));
        assertTrue(list.matches("Show Season 2 Episode 1"));
        assertFalse(list.matches("Show 1x05"));
        assertFalse(list.matches("Show 2x02"));
        assertFalse(EpisodeFilter.NONE.matches("Show S01E01"), "an empty filter is asked by nobody");
    }
}
