package com.torrenta.sync;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.torrenta.search.TorznabEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncSearchTest {

    private static final String PROWLARR = "http://nas:9696/1/api";
    private static final String JACKETT = "http://nas:9117/api/v2.0/indexers/all/results/torznab/api";

    private static Map<String, String> torznab(TorznabEndpoint... endpoints) {
        return SyncSearch.entries(List.of(endpoints), Map.of(), Map.of());
    }

    @Test
    void anEndpointTravelsWithoutItsKeyOrItsCount() {
        Map<String, String> entries = torznab(
                new TorznabEndpoint("Prowlarr", PROWLARR, "s3cret", true, 42), new TorznabEndpoint("", JACKETT, ""));
        assertEquals(
                Map.of(
                        "torznab." + PROWLARR,
                        "{\"type\":\"torznab\",\"name\":\"Prowlarr\",\"url\":\"" + PROWLARR
                                + "\",\"enabled\":true,\"keyNeeded\":true}",
                        "torznab." + JACKETT,
                        "{\"type\":\"torznab\",\"name\":\"nas\",\"url\":\"" + JACKETT
                                + "\",\"enabled\":true,\"keyNeeded\":false}"),
                entries);
        assertFalse(entries.toString().contains("s3cret"));
        assertEquals(
                torznab(new TorznabEndpoint("Prowlarr", PROWLARR, "s3cret", true, 42)),
                torznab(new TorznabEndpoint("Prowlarr", PROWLARR, "", true, -1, true)),
                "a machine still waiting for the key sends what the one with it sends");
    }

    @Test
    void anArrivalThatTakesAKeyWaitsForItAndOneHereKeepsItsOwn() throws IOException {
        Map<String, String> merged = torznab(
                new TorznabEndpoint("Renamed", PROWLARR, "theirs", false, 9),
                new TorznabEndpoint("Jackett", JACKETT, "theirs-too"));
        List<TorznabEndpoint> local = List.of(new TorznabEndpoint("Prowlarr", PROWLARR, "mine", true, 22));
        List<TorznabEndpoint> after = SyncSearch.endpoints(local, merged);
        assertEquals(
                List.of(
                        new TorznabEndpoint("Renamed", PROWLARR, "mine", false, 22),
                        new TorznabEndpoint("Jackett", JACKETT, "", true, -1, true)),
                after,
                "the name and switch are the merge's; the key and the count stay; the arrival comes last, waiting");
        assertEquals(merged, SyncSearch.entries(after, Map.of(), Map.of()), "applying gives the merge back");
        assertEquals(1, SyncSearch.newlyWaiting(local, after));
        assertEquals(0, SyncSearch.newlyWaiting(after, after), "said once");
    }

    @Test
    void aServerThatNoLongerTakesAKeyDropsTheOneHere() throws IOException {
        Map<String, String> merged = torznab(new TorznabEndpoint("Prowlarr", PROWLARR, ""));
        List<TorznabEndpoint> after =
                SyncSearch.endpoints(List.of(new TorznabEndpoint("Prowlarr", PROWLARR, "old")), merged);
        assertEquals(List.of(new TorznabEndpoint("Prowlarr", PROWLARR, "")), after);
        assertEquals(merged, SyncSearch.entries(after, Map.of(), Map.of()));
    }

    @Test
    void anEndpointGoneFromTheMergeIsRemovedAndTheOrderHereStays() throws IOException {
        TorznabEndpoint a = new TorznabEndpoint("A", "http://a.test/api", "k");
        TorznabEndpoint b = new TorznabEndpoint("B", "http://b.test/api", "k");
        TorznabEndpoint c = new TorznabEndpoint("C", "http://c.test/api", "k");
        assertEquals(List.of(c, a), SyncSearch.endpoints(List.of(c, b, a), torznab(a, c)));
    }

    @Test
    void aTypedKeyEndsTheWait() {
        TorznabEndpoint waiting = new TorznabEndpoint("P", PROWLARR, "", true, -1, true);
        assertTrue(waiting.keyNeeded() && waiting.takesKey());
        TorznabEndpoint typed = new TorznabEndpoint("P", PROWLARR, "k", true, -1, true);
        assertFalse(typed.keyNeeded(), "never waiting beside a key");
        assertEquals(new TorznabEndpoint("P", PROWLARR, "k", true, -1), typed);
        assertTrue(waiting.tested("x", 3).keyNeeded(), "a Test keeps the wait");
    }

    @Test
    void definitionsTravelAsTheirTextUnlessTheyAreTheShippedOnes() {
        Map<String, String> entries = SyncSearch.entries(
                List.of(),
                Map.of("mine", "{ mine }", "example", "{ shipped }", "edited", "{ edited }", "Bad Name", "{ x }"),
                Map.of("example", "{ shipped }", "edited", "{ shipped }"));
        assertEquals(Map.of("definition.mine", "{ mine }", "definition.edited", "{ edited }"), entries);
    }

    @Test
    void aDefinitionChangeSaysWhatToWriteAndWhatToTakeAway() {
        Map<String, String> before = Map.of("definition.a", "1", "definition.b", "2", "torznab.x", "{}");
        Map<String, String> after = Map.of("definition.a", "1", "definition.c", "3", "torznab.y", "{}");
        SyncSearch.DefinitionChange change = SyncSearch.definitionChange(before, after);
        assertEquals(Map.of("c", "3"), change.put());
        assertEquals(List.of("b"), change.removed());
        assertTrue(SyncSearch.definitionChange(after, after).isEmpty());
    }

    /** A tree in memory. */
    private static RepoTree tree(Map<String, String> files) {
        return new RepoTree() {
            @Override
            public String file(String path) {
                return files.get(path);
            }

            @Override
            public List<String> list(String folder) {
                return files.keySet().stream()
                        .filter(p -> p.startsWith(folder) && p.indexOf('/', folder.length()) < 0)
                        .map(p -> p.substring(folder.length()))
                        .sorted()
                        .toList();
            }
        };
    }

    @Test
    void theRepositoryFilesReadBackAsTheEntriesTheyWereWrittenFrom() throws IOException {
        Map<String, String> entries = new TreeMap<>(torznab(
                new TorznabEndpoint("Prowlarr", PROWLARR, "s3cret"), new TorznabEndpoint("Jackett", JACKETT, "")));
        entries.put("definition.mine", "{ \"id\": \"mine\" }\n");
        Map<String, String> files = new TreeMap<>();
        files.put(SyncSearch.TORZNAB_FILE, SyncSearch.torznabFile(entries));
        SyncSearch.definitionFiles(entries).forEach((name, text) -> files.put(SyncSearch.DEFINITIONS + name, text));
        files.put(SyncSearch.DEFINITIONS + "Not An Id.json", "{}");
        files.put(SyncSearch.DEFINITIONS + "notes.txt", "hello");
        assertEquals(entries, SyncSearch.read(tree(files)));
        assertFalse(files.get(SyncSearch.TORZNAB_FILE).contains("s3cret"));
        assertNull(SyncSearch.torznabFile(Map.of("definition.x", "{}")), "no endpoints, no file");
        assertEquals(Map.of(), SyncSearch.read(tree(Map.of())));
    }

    @Test
    void anUnreadableEndpointsFileStopsTheSyncRatherThanRemovingEverything() {
        IOException e = assertThrows(
                IOException.class, () -> SyncSearch.read(tree(Map.of(SyncSearch.TORZNAB_FILE, "{ not a list"))));
        assertTrue(e.getMessage().startsWith(SyncSearch.TORZNAB_FILE), e.getMessage());
    }
}
