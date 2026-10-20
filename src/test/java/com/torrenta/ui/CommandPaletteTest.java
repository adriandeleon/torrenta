package com.torrenta.ui;

import java.util.List;

import javafx.beans.property.SimpleBooleanProperty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ranking, on plain commands; the matcher's own scoring has its own test. */
class CommandPaletteTest {

    private static Command c(String label) {
        return new Command(label, label, null, null, new SimpleBooleanProperty(true), () -> {});
    }

    private static List<String> rank(String query, String... labels) {
        List<Command> commands =
                java.util.Arrays.stream(labels).map(CommandPaletteTest::c).toList();
        return CommandPalette.rank(query, commands).stream().map(Command::label).toList();
    }

    @Test
    void emptyQueryKeepsMenuOrder() {
        assertEquals(
                List.of("Pause / Resume", "Force Resume", "Remove…"),
                rank("", "Pause / Resume", "Force Resume", "Remove…"));
    }

    @Test
    void aTypedPrefixFindsTheCommandThatStartsWithIt() {
        assertEquals(
                "Force Resume",
                rank("resu", "Remove…", "Force Recheck", "Force Resume").get(0));
        assertEquals(
                "Copy Name",
                rank("cn", "Copy Magnet Link", "Copy Name", "Copy Info-Hash").get(0));
    }

    @Test
    void nonMatchesDropOut() {
        assertEquals(List.of("Copy Magnet Link"), rank("magnet", "Pause / Resume", "Copy Magnet Link", "Remove…"));
        assertEquals(List.of(), rank("zzz", "Pause / Resume"));
    }

    @Test
    void hintDefaultsToTheAcceleratorText() {
        Command withHint = new Command("x", "X", null, "Space", new SimpleBooleanProperty(true), () -> {});
        assertEquals("Space", withHint.hint());
        Command none = c("Y");
        assertEquals("", none.hint());
    }

    @Test
    void titleRunsEmboldenExactlyTheMatchedCharacters() {
        var runs = MatchText.runs("Force Recheck", "frec");
        assertEquals(
                List.of("F|palette-match", "orce ", "Rec|palette-match", "heck"),
                runs.stream()
                        .map(t -> t.getText() + (t.getStyleClass().contains(MatchText.MATCHED) ? "|palette-match" : ""))
                        .toList());
        var plain = MatchText.runs("Remove…", "");
        assertEquals(1, plain.size());
        assertEquals("Remove…", plain.get(0).getText());
        assertTrue(plain.get(0).getStyleClass().contains(MatchText.PLAIN));
        assertEquals(1, MatchText.runs("", "x").size(), "never an empty flow");
    }
}
