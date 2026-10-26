package com.torrenta.webapi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sync/maindata's partial updates: a web UI polls every second and redraws only what moved. */
class MainDataTest {

    private static Map<String, Object> row(String state, double progress, long dlspeed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "x");
        m.put("state", state);
        m.put("progress", progress);
        m.put("dlspeed", dlspeed);
        return m;
    }

    private static MainData.Snapshot snapshot(
            Map<String, Map<String, Object>> rows,
            Map<String, Map<String, Object>> categories,
            List<String> tags,
            long speed) {
        return new MainData.Snapshot(rows, categories, tags, Map.of("dl_info_speed", speed, "dht_nodes", 10L));
    }

    @Test
    void theFirstAnswerIsWhole() {
        MainData.Snapshot s = snapshot(Map.of("a", row("downloading", 0.5, 100)), Map.of(), List.of("keep"), 100);
        Map<String, Object> out = MainData.maindata(null, s, 7);
        assertEquals(7L, out.get("rid"));
        assertEquals(true, out.get("full_update"));
        assertEquals(s.rows(), out.get("torrents"));
        assertEquals(List.of("keep"), out.get("tags"));
        assertEquals(s.server(), out.get("server_state"));
    }

    @Test
    void afterThatOnlyWhatChangedDownToTheField() {
        Map<String, Map<String, Object>> cats = Map.of("tv", Map.of("name", "tv", "savePath", ""));
        MainData.Snapshot before = snapshot(
                Map.of("a", row("downloading", 0.5, 100), "b", row("stalledUP", 1, 0), "c", row("metaDL", 0, 0)),
                cats,
                List.of("keep", "old"),
                100);
        MainData.Snapshot after = snapshot(
                Map.of("a", row("downloading", 0.6, 120), "b", row("stalledUP", 1, 0), "d", row("metaDL", 0, 0)),
                Map.of(
                        "tv",
                        Map.of("name", "tv", "savePath", "/srv/tv"),
                        "films",
                        Map.of("name", "films", "savePath", "")),
                List.of("keep", "new"),
                100);
        Map<String, Object> out = MainData.maindata(before, after, 8);
        assertFalse(out.containsKey("full_update"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> torrents = (Map<String, Map<String, Object>>) out.get("torrents");
        assertEquals(Map.of("progress", 0.6, "dlspeed", 120L), torrents.get("a"), "its two moved fields alone");
        assertFalse(torrents.containsKey("b"), "an unchanged torrent is not sent");
        assertEquals(row("metaDL", 0, 0), torrents.get("d"), "a new one whole");
        assertEquals(List.of("c"), out.get("torrents_removed"));
        assertEquals(
                2, ((Map<?, ?>) out.get("categories")).size(), "a category that changed is sent whole, a new one too");
        assertEquals(List.of("new"), out.get("tags"));
        assertEquals(List.of("old"), out.get("tags_removed"));
        assertFalse(out.containsKey("server_state"), "nothing moved there");
        assertFalse(out.containsKey("categories_removed"));
    }

    @Test
    void nothingChangedIsJustTheRid() {
        MainData.Snapshot s = snapshot(Map.of("a", row("downloading", 0.5, 100)), Map.of(), List.of(), 100);
        assertEquals(Map.of("rid", 9L), MainData.maindata(s, s, 9));
    }

    /** One snapshot per session, as qBittorrent keeps: a rid is good only for the session it was given to. */
    @Test
    void theMemoryAnswersOnlyTheLatestRidOfASession() {
        MainData.Memory memory = new MainData.Memory();
        MainData.Snapshot s = snapshot(Map.of(), Map.of(), List.of(), 1);
        long rid = memory.remember("alice", s);
        assertTrue(memory.since("alice", rid).isPresent());
        assertTrue(memory.since("bob", rid).isEmpty(), "another session's rid is a full update");
        assertTrue(memory.since("alice", 0).isEmpty(), "0 asks for everything");
        long next = memory.remember("alice", s);
        assertTrue(memory.since("alice", rid).isEmpty(), "an older rid is forgotten");
        assertTrue(memory.since("alice", next).isPresent());
        for (int i = 0; i < MainData.Memory.KEEP; i++) memory.remember("client" + i, s);
        assertTrue(memory.since("alice", next).isEmpty(), "the least recently answered let go past the cap");
    }

    @Test
    void peersCarryTheirFlagsSwitch() {
        Map<String, Map<String, Object>> peers = Map.of("1.2.3.4:51413", Map.of("dl_speed", 10L));
        Map<String, Object> full = MainData.peers(null, new MainData.Snapshot(peers, Map.of(), List.of(), Map.of()), 3);
        assertEquals(true, full.get("full_update"));
        assertEquals(true, full.get("show_flags"));
        Map<String, Object> gone = MainData.peers(
                new MainData.Snapshot(peers, Map.of(), List.of(), Map.of()),
                new MainData.Snapshot(Map.of(), Map.of(), List.of(), Map.of()),
                4);
        assertEquals(List.of("1.2.3.4:51413"), gone.get("peers_removed"));
    }

    @Test
    void anAddressSplitsIntoIpAndPort() {
        assertEquals(List.of("1.2.3.4", "51413"), List.of(Reads.ipAndPort("1.2.3.4:51413")));
        assertEquals(List.of("2001:db8::1", "6881"), List.of(Reads.ipAndPort("[2001:db8::1]:6881")));
        assertEquals(List.of("2001:db8::1", ""), List.of(Reads.ipAndPort("2001:db8::1")));
        assertEquals(List.of("1.2.3.4", ""), List.of(Reads.ipAndPort("1.2.3.4")));
    }
}
