package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Command-palette ranking. */
class CommandMatcherTest {

    private static void ranksAbove(String query, String better, String worse) {
        int a = CommandMatcher.score(query, better);
        int b = CommandMatcher.score(query, worse);
        assertTrue(a > b, "\"" + query + "\": " + better + " (" + a + ") should beat " + worse + " (" + b + ")");
    }

    @Test
    void initialsFindTheirCommand() {
        // The whole reason for a palette: two keys, no full word typed.
        assertTrue(CommandMatcher.score("ct", "Close Tab") > CommandMatcher.NO_MATCH);
        assertTrue(CommandMatcher.score("nw", "New Window") > CommandMatcher.NO_MATCH);
    }

    @Test
    void initialsBeatALetterBuriedInAWord() {
        // "nt" is a subsequence of both, but only one is the initials.
        ranksAbove("nt", "New Tab", "Next Tab");
    }

    @Test
    void initialsWinEvenWhenAnEarlierLetterCouldHaveMatched() {
        // "nw": the w of "New" comes first, so a matcher that takes the leftmost match for each
        // character never reaches the w that starts "Window" and scores the two identically.
        ranksAbove("nw", "New Window", "New Tab");
        ranksAbove("ct", "Close Tab", "Actual Size");
    }

    @Test
    void aRunOfCharactersBeatsAScatteredMatch() {
        ranksAbove("tab", "Close Tab", "Terminal About Box");
    }

    @Test
    void aTightMatchBeatsASprawlingOne() {
        // Both are the initials of their two words, so only compactness separates them.
        ranksAbove("nt", "New Tab", "Next Tab");
    }

    @Test
    void aMatchNearTheFrontWins() {
        ranksAbove("c", "Copy", "Actual Size");
    }

    @Test
    void aQueryThatIsNotASubsequenceDoesNotMatch() {
        assertEquals(CommandMatcher.NO_MATCH, CommandMatcher.score("zz", "Close Tab"));
        // Order matters: the letters are all there, in the wrong sequence.
        assertEquals(CommandMatcher.NO_MATCH, CommandMatcher.score("bat", "Tab"));
    }

    @Test
    void caseIsIgnoredBothWays() {
        assertTrue(CommandMatcher.score("CT", "Close Tab") > CommandMatcher.NO_MATCH);
        assertTrue(CommandMatcher.score("ct", "CLOSE TAB") > CommandMatcher.NO_MATCH);
    }

    @Test
    void spacesInTheQueryAreIgnored() {
        // Typing the words out should not do worse than typing the initials.
        assertTrue(CommandMatcher.score("close tab", "Close Tab") > CommandMatcher.NO_MATCH);
    }

    @Test
    void anEmptyQueryMatchesEverythingEqually() {
        // Equal, so the caller's own ordering survives — for the palette that is the menu order.
        assertEquals(0, CommandMatcher.score("", "Copy"));
        assertEquals(0, CommandMatcher.score("   ", "Close Tab"));
    }

    @Test
    void aNullLabelIsNotAMatchRatherThanACrash() {
        assertEquals(CommandMatcher.NO_MATCH, CommandMatcher.score("c", null));
    }

    @Test
    void positionsAreTheRankedPlacement() {
        assertEquals(java.util.List.of(0, 6), CommandMatcher.positions("ct", "Close Tab"));
        assertEquals(
                java.util.List.of(6, 7, 8), CommandMatcher.positions("tab", "Close Tab"), "the run, not scattered");
        assertEquals(
                java.util.List.of(0, 4),
                CommandMatcher.positions("nw", "New Window"),
                "the initials, not the w of New");
        assertEquals(java.util.List.of(0, 1), CommandMatcher.positions("c t", "Ct"), "spaces in the query are nothing");
        assertEquals(java.util.List.of(), CommandMatcher.positions("", "Close Tab"));
        assertEquals(java.util.List.of(), CommandMatcher.positions("zz", "Close Tab"));
    }
}
