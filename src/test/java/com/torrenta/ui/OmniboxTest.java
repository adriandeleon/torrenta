package com.torrenta.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmniboxTest {

    private static final long NOW = 1_800_000_000L;

    @Test
    void plainWordsStayTextAndFilterAsTyped() {
        Omnibox box = Omnibox.EMPTY.typed("ubuntu ", NOW);
        assertEquals(List.of(), box.chips());
        assertEquals("ubuntu ", box.text());
        assertEquals("ubuntu", box.query());
        assertEquals(Omnibox.Mode.FILTER, box.mode());
    }

    @Test
    void aKeyedTermBecomesAChipWhenASpaceFollowsIt() {
        Omnibox typing = Omnibox.EMPTY.typed("state:seed", NOW);
        assertEquals(List.of(), typing.chips(), "not yet: still being typed");
        Omnibox box = typing.typed("state:seeding ", NOW);
        assertEquals(List.of("state:seeding"), box.chips());
        assertEquals("", box.text());
        assertEquals("state:seeding", box.query());
        assertEquals("state is Seeding", box.chipViews(NOW).getFirst().label());
        assertTrue(box.chipViews(NOW).getFirst().valid());
    }

    @Test
    void wordsBeforeAChipAreKeptAndTheQueryHoldsBoth() {
        Omnibox box = Omnibox.EMPTY.typed("ubuntu ratio<1 ", NOW);
        assertEquals(List.of("ratio<1"), box.chips());
        assertEquals("ubuntu ", box.text());
        assertEquals("ratio<1 ubuntu", box.query());
    }

    @Test
    void anUnreadableTermIsARedChipNotLostText() {
        Omnibox box = Omnibox.EMPTY.typed("state:flying ", NOW);
        assertEquals(List.of("state:flying"), box.chips());
        FilterQuery.Chip chip = box.chipViews(NOW).getFirst();
        assertFalse(chip.valid());
        assertEquals("state:flying", chip.term());
    }

    @Test
    void aQuotedValueKeepsItsSpaces() {
        Omnibox box = Omnibox.EMPTY.typed("cat:\"Linux ISOs\" ", NOW);
        assertEquals(List.of("cat:\"Linux ISOs\""), box.chips());
        assertEquals("", box.text());
    }

    @Test
    void backspaceOnEmptyHandsTheLastChipBackForEditing() {
        Omnibox box = Omnibox.EMPTY.typed("state:seeding tag:keep ", NOW);
        assertEquals(2, box.chips().size());
        Omnibox back = box.backspaceOnEmpty();
        assertEquals(List.of("state:seeding"), back.chips());
        assertEquals("tag:keep", back.text());
        assertEquals(back, back.backspaceOnEmpty(), "text present: nothing to hand back");
        assertEquals(Omnibox.EMPTY, Omnibox.EMPTY.backspaceOnEmpty());
    }

    @Test
    void aChipCanBeRemovedByIndex() {
        Omnibox box = Omnibox.EMPTY.typed("state:seeding tag:keep ", NOW);
        assertEquals(List.of("tag:keep"), box.removeChip(0).chips());
        assertEquals(box, box.removeChip(5));
        assertEquals(box, box.removeChip(-1));
    }

    @Test
    void theCommandPrefixSwitchesModesAndIsNotAFilter() {
        Omnibox box = Omnibox.EMPTY.typed("state:seeding ", NOW).typed("> pau", NOW);
        assertEquals(Omnibox.Mode.COMMAND, box.mode());
        assertEquals("pau", box.commandQuery());
        assertEquals("state:seeding", box.query(), "the chips still filter; the command text does not");
        assertEquals(Omnibox.Mode.COMMAND, Omnibox.mode("›settings"));
        assertEquals("settings", Omnibox.EMPTY.withText("›settings").commandQuery());
    }

    @Test
    void aMagnetOrTorrentLinkOrPathIsAnAdd() {
        assertEquals(Omnibox.Mode.ADD, Omnibox.mode("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        assertEquals(Omnibox.Mode.ADD, Omnibox.mode("https://example.org/x.torrent"));
        assertEquals(Omnibox.Mode.ADD, Omnibox.mode("/home/adl/Downloads/ubuntu.torrent"));
        assertEquals(Omnibox.Mode.FILTER, Omnibox.mode("https://example.org/page"));
        assertEquals(Omnibox.Mode.FILTER, Omnibox.mode("magnet"));
        Omnibox box = Omnibox.EMPTY.typed("state:seeding ", NOW).withText("https://example.org/x.torrent");
        assertEquals("state:seeding", box.query(), "an add is not a name filter");
    }

    @Test
    void aPasteIsMoreThanOneCharacterAtOnce() {
        assertTrue(Omnibox.pasted("", "magnet:?xt=urn:btih:abc"));
        assertTrue(Omnibox.pasted("ab", "abcd"));
        assertFalse(Omnibox.pasted("ab", "abc"));
        assertFalse(Omnibox.pasted("abc", "ab"));
        assertTrue(Omnibox.pasted(null, "ab"));
    }

    @Test
    void emptyMeansNoChipsAndNoText() {
        assertTrue(Omnibox.EMPTY.isEmpty());
        assertTrue(Omnibox.EMPTY.withText("   ").isEmpty());
        assertFalse(Omnibox.EMPTY.typed("state:seeding ", NOW).isEmpty());
        assertFalse(Omnibox.EMPTY.typed("x", NOW).isEmpty());
    }

    @Test
    void tokensRespectQuotes() {
        assertEquals(List.of("a", "cat:\"Linux ISOs\"", "b"), Omnibox.tokens("  a cat:\"Linux ISOs\"   b "));
        assertEquals(List.of(), Omnibox.tokens("   "));
    }
}
