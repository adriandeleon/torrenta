package com.torrenta.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagInputTest {

    private static final List<String> EXISTING = List.of("hd", "Films", "keep", "linux", "4k-hdr", "anime");

    @Test
    void theTokenIsTheCommaSeparatedPartAroundTheCaret() {
        assertEquals(new TagInput.Token(0, 0, ""), TagInput.at("", 0));
        assertEquals(new TagInput.Token(0, 4, "keep"), TagInput.at("keep", 4));
        assertEquals(new TagInput.Token(5, 8, "hd"), TagInput.at("keep, hd", 8));
        assertEquals(new TagInput.Token(5, 8, "hd"), TagInput.at("keep, hd, x", 6));
        assertEquals(new TagInput.Token(0, 4, "keep"), TagInput.at("keep, hd", 2));
        assertEquals(new TagInput.Token(5, 6, ""), TagInput.at("keep, ", 6));
        // A caret out of range is held to the text.
        assertEquals(new TagInput.Token(0, 2, "hd"), TagInput.at("hd", 9));
    }

    @Test
    void enteredKeepsTheFieldsOrderAndDropsEmptyParts() {
        assertEquals(List.of("keep", "hd"), TagInput.entered(" keep ,, hd, "));
        assertEquals(List.of(), TagInput.entered(""));
        assertTrue(TagInput.has("Keep, hd", "keep"));
        assertFalse(TagInput.has("keeper", "keep"));
    }

    @Test
    void prefixesComeFirstThenContainsEachAlphabetical() {
        assertEquals(List.of("hd", "4k-hdr"), TagInput.suggest("h", 1, EXISTING));
        assertEquals(List.of("linux"), TagInput.suggest("keep, LI", 8, EXISTING));
        assertEquals(List.of("anime", "Films"), TagInput.suggest("i", 1, List.of("Films", "anime")));
        assertEquals(List.of(), TagInput.suggest("zzz", 3, EXISTING));
    }

    @Test
    void aTagAlreadyInTheFieldOrTypedOutInFullIsNotOffered() {
        assertEquals(List.of("4k-hdr"), TagInput.suggest("hd, h", 5, EXISTING));
        // "hd" typed in full: nothing left to complete it to but the longer one.
        assertEquals(List.of("4k-hdr"), TagInput.suggest("hd", 2, EXISTING));
        assertEquals(List.of(), TagInput.suggest("keep", 4, EXISTING));
        // The tag being edited does not count against itself.
        assertEquals(List.of("hd", "4k-hdr"), TagInput.suggest("h, keep", 1, EXISTING));
    }

    @Test
    void anEmptyTokenOffersEveryTagNotInTheFieldUpToTheLimit() {
        assertEquals(List.of("4k-hdr", "anime", "Films", "hd", "linux"), TagInput.suggest("keep, ", 6, EXISTING));
        List<String> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "t" + (char) ('a' + i))
                .toList();
        assertEquals(TagInput.LIMIT, TagInput.suggest("", 0, many).size());
    }

    @Test
    void pickingReplacesTheTokenAndAtTheEndLeavesASeparator() {
        assertEquals(new TagInput.Edit("Films, ", 7), TagInput.complete("fi", 2, "Films"));
        assertEquals(new TagInput.Edit("keep, hd, ", 10), TagInput.complete("keep, h", 7, "hd"));
        assertEquals(new TagInput.Edit("keep, hd, ", 10), TagInput.complete("keep,h  ", 6, "hd"));
        // In the middle: the rest stays and the caret sits after the tag.
        assertEquals(new TagInput.Edit("hd, keep", 2), TagInput.complete("h, keep", 1, "hd"));
        assertEquals(new TagInput.Edit("keep, linux, hd", 11), TagInput.complete("keep, li, hd", 8, "linux"));
    }

    @Test
    void aChipAddsItsTagOrTakesItOut() {
        assertEquals("hd", TagInput.toggle("", "hd"));
        assertEquals("keep, hd", TagInput.toggle("keep, ", "hd"));
        assertEquals("keep", TagInput.toggle("keep, HD", "hd"));
        assertEquals("keep, partial, hd", TagInput.toggle("keep, partial", "hd"));
    }
}
