package com.torrenta.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedViewTest {

    @Test
    void theKeysReadBackAsTheSameViewsInTheSameOrder() {
        List<SavedView> views = SavedView.added(
                SavedView.added(List.of(), "Needs seeding", "state:seeding ratio<1 added<30d"), "Big ones", "size>4g");
        Map<String, String> keys = SavedView.keys(views);
        assertEquals("needs-seeding,big-ones", keys.get(SavedView.ORDER));
        assertEquals("Needs seeding", keys.get("ui.view.needs-seeding.name"));
        assertEquals("size>4g", keys.get("ui.view.big-ones.query"));
        assertEquals(views, SavedView.read(keys));
    }

    @Test
    void aSlugIsTheNamePlainAndUnused() {
        assertEquals("needs-seeding", SavedView.slug("  Needs seeding! ", Set.of()));
        assertEquals("seriees-a-voir", SavedView.slug("Sériées à voir", Set.of()), "accents dropped");
        assertEquals("view", SavedView.slug("★★★", Set.of()), "nothing left");
        assertEquals("films-3", SavedView.slug("Films", Set.of("films", "films-2")));
        assertTrue(SavedView.slug("a.b,c d", Set.of()).matches("[a-z0-9-]+"), "no dot or comma ever");
    }

    @Test
    void viewsTheOrderDoesNotNameComeAfterItByName() {
        // Another computer added "Zeta" and "Alpha"; this one's order won the merge.
        Map<String, String> keys = new HashMap<>(SavedView.keys(SavedView.added(List.of(), "Mine", "state:seeding")));
        keys.put("ui.view.zeta.name", "Zeta");
        keys.put("ui.view.zeta.query", "tag:z");
        keys.put("ui.view.alpha.name", "Alpha");
        keys.put("ui.view.alpha.query", "tag:a");
        assertEquals(
                List.of("Mine", "Alpha", "Zeta"),
                SavedView.read(keys).stream().map(SavedView::name).toList());
    }

    @Test
    void aViewMissingItsNameOrQueryIsLeftOutAndTheOrderIsNoAuthority() {
        Map<String, String> keys = new HashMap<>();
        keys.put(SavedView.ORDER, "ghost,half,whole,whole");
        keys.put("ui.view.half.name", "Half");
        keys.put("ui.view.whole.name", "Whole");
        keys.put("ui.view.whole.query", "is:sequential");
        assertEquals(List.of(new SavedView("whole", "Whole", "is:sequential")), SavedView.read(keys));
    }

    @Test
    void renamingKeepsTheSlugAndDeletingRemovesTheView() {
        List<SavedView> views = SavedView.added(SavedView.added(List.of(), "A", "tag:a"), "B", "tag:b");
        List<SavedView> renamed = SavedView.renamed(views, "a", "Alpha");
        assertEquals(new SavedView("a", "Alpha", "tag:a"), renamed.getFirst());
        assertEquals(
                new SavedView("b", "B", "size>1g"),
                SavedView.requeried(renamed, "b", "size>1g").get(1));
        assertEquals(List.of(new SavedView("b", "B", "tag:b")), SavedView.removed(views, "a"));
    }

    @Test
    void settingsWriteTheViewsAndForgetTheOnesRemoved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        List<SavedView> two = SavedView.added(SavedView.added(List.of(), "A", "tag:a"), "B", "tag:b");
        settings.setSavedViews(two);
        assertEquals(two, settings.savedViews());
        settings.setSavedViews(SavedView.removed(two, "a"));
        assertEquals(List.of(new SavedView("b", "B", "tag:b")), settings.savedViews());
        settings.save();
        String file = java.nio.file.Files.readString(dir.resolve("settings.properties"));
        assertTrue(!file.contains("ui.view.a."), "a removed view leaves no keys behind: " + file);
    }
}
