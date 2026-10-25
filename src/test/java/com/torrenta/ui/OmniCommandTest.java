package com.torrenta.ui;

import java.util.Optional;

import com.torrenta.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmniCommandTest {

    private static OmniCommand ready(String text) {
        Optional<OmniCommand.Parsed> parsed = OmniCommand.parse(text);
        assertTrue(parsed.isPresent(), () -> text + " is a command");
        return assertInstanceOf(OmniCommand.Ready.class, parsed.get(), text).command();
    }

    private static String problem(String text) {
        return assertInstanceOf(
                        OmniCommand.Problem.class, OmniCommand.parse(text).orElseThrow(), text)
                .message();
    }

    @Test
    void aFirstWordThatIsNoVerbIsLeftToThePalettesOwnSearch() {
        assertTrue(OmniCommand.parse("").isEmpty());
        assertTrue(OmniCommand.parse("settings").isEmpty());
        assertTrue(OmniCommand.parse("pau").isEmpty(), "a verb half typed is still a search");
    }

    @Test
    void namesAndPathsTakeTheRestOfTheLineQuotesOrNot() {
        OmniCommand films = ready("set-category Films");
        assertEquals(OmniCommand.Verb.SET_CATEGORY, films.verb());
        assertEquals("Films", films.text());
        assertEquals("Linux ISOs", ready("SET-CATEGORY \"Linux ISOs\"").text(), "case aside, quotes off");
        assertEquals("Linux ISOs", ready("add-tag Linux ISOs").text(), "the rest of the line is the name");
        assertEquals("/srv/media/films", ready("set-location /srv/media/films").text());
        assertEquals("set-category needs a name", problem("set-category"));
        assertEquals("set-location needs a folder", problem("set-location   "));
    }

    @Test
    void ratesReadAsSizesDoAndNoneLiftsTheLimit() {
        assertEquals(500 * 1024, ready("limit-down 500k").bytes());
        assertEquals(2L * 1024 * 1024, ready("limit-up 2m").bytes());
        assertEquals(0, ready("limit-down none").bytes());
        assertEquals(0, ready("limit-down 0").bytes());
        assertEquals("limit-down needs a rate such as 500k, or none", problem("limit-down fast"));
        assertEquals("limit-up needs a rate such as 500k, or none", problem("limit-up 9t"), "past what a limit holds");
        assertEquals("Limit downloads to 500.0 KiB/s", ready("limit-down 500k").describe());
        assertEquals("Lift the upload limit", ready("limit-up none").describe());
    }

    @Test
    void switchesAndQueueMovesTakeTheirOwnWords() {
        assertTrue(ready("sequential on").on());
        assertFalse(ready("sequential off").on());
        assertEquals("sequential takes on or off", problem("sequential maybe"));
        assertEquals(Engine.QueueMove.TOP, ready("queue top").move());
        assertEquals(Engine.QueueMove.DOWN, ready("queue Down").move());
        assertEquals("queue takes top, up, down or bottom", problem("queue first"));
        assertEquals("Move to the top of the queue", ready("queue top").describe());
    }

    @Test
    void verbsWithoutArgumentsRefuseOne() {
        for (String verb : new String[] {"pause", "resume", "recheck", "reannounce", "remove"}) {
            assertEquals(verb, ready(verb).verb().word());
        }
        assertEquals("pause takes nothing after it", problem("pause now"));
    }

    @Test
    void aRemovalAlwaysAsksAndAnythingElseAboveTwenty() {
        assertTrue(ready("remove").asks(1));
        assertFalse(ready("pause").asks(OmniCommand.ASK_ABOVE));
        assertTrue(ready("pause").asks(OmniCommand.ASK_ABOVE + 1));
    }
}
