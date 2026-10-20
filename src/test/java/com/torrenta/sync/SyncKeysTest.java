package com.torrenta.sync;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncKeysTest {

    @Test
    void whatStaysOnThisMachine() {
        assertTrue(SyncKeys.isSynced("ui.theme"));
        assertTrue(SyncKeys.isSynced("connection.port"));
        assertTrue(SyncKeys.isSynced("protocol.defaultTrackers"));
        assertTrue(SyncKeys.isSynced("ipfilter.bans"));
        assertFalse(SyncKeys.isSynced("sync.url"));
        assertFalse(SyncKeys.isSynced("sync.enabled"));
        assertFalse(SyncKeys.isSynced("download.dir"), "a folder of this machine");
        assertFalse(SyncKeys.isSynced("connection.interface"));
        assertFalse(SyncKeys.isSynced("proxy.password"), "a secret");
        assertFalse(SyncKeys.isSynced("updates.lastCheckEpochMs"), "a clock");
        assertFalse(SyncKeys.isSynced("torrent.0123abcd.share"), "a torrent of this machine");
        assertTrue(SyncKeys.isSynced("share.ratio"), "the global share limit travels");
        assertFalse(SyncKeys.isSynced(""));
        assertFalse(SyncKeys.isSynced(null));
    }

    @Test
    void renderIsSortedCanonicalAndRoundTrips() {
        Map<String, String> entries = Map.of(
                "z.last", "1",
                "a.first", "with spaces and = and : and #",
                "m.unicode", "año ñ 日本",
                "n.newline", "two\nlines",
                "e.empty", "");
        String text = SyncKeys.render(entries);
        assertTrue(text.startsWith("a.first="), text);
        assertTrue(text.lines().noneMatch(l -> l.startsWith("#")), "no date comment: " + text);
        assertTrue(text.contains("\\#"), "the hash is escaped: " + text);
        assertEquals(text, SyncKeys.render(entries), "deterministic");
        assertEquals(entries, SyncKeys.parse(text));
    }

    @Test
    void parseDropsLocalKeysAndSurvivesGarbage() {
        assertEquals(
                Map.of("ui.theme", "nord-dark"), SyncKeys.parse("ui.theme=nord-dark\nsync.url=x\ndownload.dir=/tmp\n"));
        assertEquals(Map.of(), SyncKeys.parse(null));
        assertEquals(Map.of(), SyncKeys.parse(""));
    }

    @Test
    void syncedOfPropertiesIsSorted() {
        Properties p = new Properties();
        p.setProperty("ui.theme", "x");
        p.setProperty("add.sequential", "true");
        p.setProperty("sync.enabled", "true");
        assertEquals("[add.sequential, ui.theme]", SyncKeys.synced(p).keySet().toString());
    }
}
