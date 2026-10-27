package com.torrenta.ui;

import java.util.List;
import java.util.Set;

import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleModeTest {

    @Test
    void theSavedSwitchOrTheSessionFlagTurnsItOn() {
        assertFalse(SimpleMode.active(false, false));
        assertTrue(SimpleMode.active(true, false));
        assertTrue(SimpleMode.active(false, true), "--simple, the saved switch untouched");
        assertTrue(SimpleMode.active(true, true));
    }

    /** A session started with --simple and the switch off is turned off by one toggle. */
    @Test
    void aToggleIsTheOppositeOfWhatShows() {
        boolean shows = SimpleMode.active(false, true);
        assertFalse(SimpleMode.toggled(shows));
        assertTrue(SimpleMode.toggled(SimpleMode.active(false, false)));
    }

    @Test
    void outsideTheModeEveryCommandIsKept() {
        for (String id : List.of("create", "search", "rss", "statistics", "limits", "toggle-sidebar"))
            assertTrue(SimpleMode.keeps(id, false), id);
    }

    @Test
    void inTheModeOnlyTheEssentialsAreKept() {
        for (String id : List.of("add-file", "add-magnet", "pause-resume", "remove", "settings", "quit"))
            assertTrue(SimpleMode.keeps(id, true), id);
        for (String id :
                List.of("create", "search", "rss", "statistics", "limits", "toggle-sidebar", "queue-top", "edit-tags"))
            assertFalse(SimpleMode.keeps(id, true), id);
    }

    /** A command added later is out of the mode until it is named; never in by accident. */
    @Test
    void anUnknownCommandIsLeftOut() {
        assertFalse(SimpleMode.keeps("some-command-added-next-year", true));
        assertFalse(SimpleMode.keeps("show-seeding", true), "the sidebar's filters go with it");
    }

    /** The mode is never a one-way door: its own switch is kept, in the mode and out of it. */
    @Test
    void theSwitchIsKept() {
        assertTrue(SimpleMode.keeps("toggle-simple", true));
        assertTrue(SimpleMode.keeps("toggle-simple", false));
    }

    /**
     * The detail header's buttons and the diagnosis card's remedies run commands by id: each of
     * theirs is kept, or a card would offer a remedy that does nothing.
     */
    @Test
    void everyRemedyAndHeaderButtonStillWorks() {
        for (Diagnosis.Remedy r : Diagnosis.Remedy.values())
            r.command().ifPresent(id -> assertTrue(SimpleMode.keeps(id, true), r + " runs " + id));
        for (String id : List.of("pause-resume", "reannounce", "open-folder"))
            assertTrue(SimpleMode.keeps(id, true), id);
    }

    @Test
    void theToolbarKeepsFewerThanTheMenus() {
        assertTrue(SimpleMode.COMMANDS.containsAll(SimpleMode.TOOLBAR), "a button for a command the mode hides");
        assertFalse(SimpleMode.onToolbar("force-resume", true));
        assertTrue(SimpleMode.onToolbar("force-resume", false));
        assertTrue(SimpleMode.onToolbar("toggle-simple", true));
    }

    @Test
    void theCommandsKeepTheirOrder() {
        Command a = command("remove");
        Command b = command("create");
        Command c = command("add-file");
        assertEquals(List.of(a, c), SimpleMode.commands(List.of(a, b, c), true));
        assertEquals(List.of(a, b, c), SimpleMode.commands(List.of(a, b, c), false));
    }

    @Test
    void onlyTheTransfersShowInTheMode() {
        assertTrue(SimpleMode.viewShown(View.TRANSFERS, true));
        assertFalse(SimpleMode.viewShown(View.SEARCH, true));
        assertFalse(SimpleMode.viewShown(View.RSS, true));
        for (View v : View.values()) assertTrue(SimpleMode.viewShown(v, false), v.toString());
    }

    @Test
    void theDetailPaneKeepsOverviewAndFiles() {
        assertEquals(List.of("overview", "files"), SimpleMode.detailTabs(DetailPane.TABS, true));
        assertEquals(DetailPane.TABS, SimpleMode.detailTabs(DetailPane.TABS, false));
    }

    @Test
    void theChromeGoesInTheMode() {
        assertFalse(SimpleMode.chrome(true));
        assertTrue(SimpleMode.chrome(false));
    }

    @Test
    void theModeDrawsComfortableRowsAndLeavesTheSavedDensityOutsideIt() {
        for (Density saved : Density.values()) {
            assertEquals(Density.COMFORTABLE, SimpleMode.density(saved, true), saved.toString());
            assertEquals(saved, SimpleMode.density(saved, false), saved.toString());
        }
    }

    @Test
    void eachPlaceRemembersItsColumnsUnderItsOwnKey() {
        assertEquals(Settings.TABLE_COLUMNS_SIMPLE, SimpleMode.columnsKey(Density.COMFORTABLE, true));
        assertEquals(Settings.TABLE_COLUMNS_COMFORTABLE, SimpleMode.columnsKey(Density.COMFORTABLE, false));
        assertEquals(Settings.TABLE_COLUMNS, SimpleMode.columnsKey(Density.COMPACT, false));
        assertEquals(
                3,
                Set.of(
                                SimpleMode.columnsKey(Density.COMFORTABLE, true),
                                SimpleMode.columnsKey(Density.COMFORTABLE, false),
                                SimpleMode.columnsKey(Density.COMPACT, false))
                        .size());
    }

    /** Every column is named, so a column hidden in the mode stays hidden, wherever it was. */
    @Test
    void theModesColumnsNameEveryColumnAndShowFew() {
        var simple = ColumnLayout.parse(SimpleMode.COLUMNS);
        var comfortable = ColumnLayout.parse(Density.COMFORTABLE.defaultLayout());
        assertEquals(ids(comfortable), ids(simple));
        assertEquals(
                List.of("name", "size", "down", "up", "eta"),
                simple.stream()
                        .filter(ColumnLayout.Column::visible)
                        .map(ColumnLayout.Column::id)
                        .toList());
    }

    private static Set<String> ids(List<ColumnLayout.Column> columns) {
        return columns.stream().map(ColumnLayout.Column::id).collect(java.util.stream.Collectors.toSet());
    }

    private static Command command(String id) {
        return new Command(id, id, null, new javafx.beans.property.SimpleBooleanProperty(true), () -> {});
    }
}
