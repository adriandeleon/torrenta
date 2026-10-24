package com.torrenta.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncLabelsTest {

    @Test
    void namesAreKeyedInLowerCaseAndKeepTheirSpelling() {
        Map<String, String> entries = SyncLabels.entries(List.of("Movies", " TV shows "), List.of("HD", "hd", "4k"));
        assertEquals(
                Map.of(
                        "category.movies", "Movies",
                        "category.tv shows", "TV shows",
                        "tag.hd", "HD",
                        "tag.4k", "4k"),
                entries);
        assertEquals(List.of("Movies", "TV shows"), SyncLabels.categories(entries));
        assertEquals(List.of("4k", "HD"), SyncLabels.tags(entries));
    }

    @Test
    void theFileRoundTripsAndIgnoresAnythingElse() {
        Map<String, String> entries = SyncLabels.entries(List.of("a=b: c"), List.of("é"));
        String text = SyncKeys.render(entries) + "ui.theme=nord\n";
        assertEquals(entries, SyncLabels.parse(text));
        assertEquals(Map.of(), SyncLabels.parse("\\u12"));
    }

    @Test
    void theChangeSaysWhatToAddRespellAndRemove() {
        Map<String, String> before = SyncLabels.entries(List.of("Movies", "Music"), List.of("hd", "old"));
        Map<String, String> after = SyncLabels.entries(List.of("MOVIES", "Books"), List.of("hd", "new"));
        SyncLabels.Change change = SyncLabels.change(before, after);
        assertEquals(List.of("Books", "MOVIES"), change.putCategories(), "added, and respelled");
        assertEquals(List.of("Music"), change.removedCategories());
        assertEquals(List.of("new"), change.putTags());
        assertEquals(List.of("old"), change.removedTags());
        assertTrue(SyncLabels.change(before, before).isEmpty());
    }

    @Test
    void aSpellingOnTwoMachinesIsOneEntryNotADeletion() {
        Map<String, String> base = SyncLabels.entries(List.of("Movies"), List.of());
        Map<String, String> mine = SyncLabels.entries(List.of("Movies"), List.of());
        Map<String, String> theirs = SyncLabels.entries(List.of("movies"), List.of());
        SyncMerge.Result merged = SyncMerge.merge(base, mine, theirs);
        assertEquals(Map.of("category.movies", "movies"), merged.merged(), "only they changed it");
        SyncLabels.Change change = SyncLabels.change(mine, merged.merged());
        assertEquals(List.of("movies"), change.putCategories());
        assertEquals(List.of(), change.removedCategories());
    }
}
