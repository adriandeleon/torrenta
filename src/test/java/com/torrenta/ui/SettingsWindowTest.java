package com.torrenta.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search and reset rules; the window itself is driven by the capture hook. */
class SettingsWindowTest {

    @Test
    void blankMatchesEverythingAndTheRestIsASubstringIgnoringCase() {
        assertTrue(SettingsWindow.matches("", "Download folder | where files go"));
        assertTrue(SettingsWindow.matches(null, "anything"));
        assertTrue(SettingsWindow.matches("FOLDER", "Download folder | where files go"));
        assertTrue(SettingsWindow.matches("  files ", "Download folder | where files go"));
        assertFalse(SettingsWindow.matches("peers", "Download folder | where files go"));
    }

    @Test
    void aRowRanksByWhereTheWordsAre() {
        String row = "Proxy port | The port the proxy listens on. | socks http";
        assertEquals(3, SettingsWindow.rank("PROXY", row));
        assertEquals(2, SettingsWindow.rank("listens", row));
        assertEquals(1, SettingsWindow.rank("socks", row));
        assertEquals(0, SettingsWindow.rank("tracker", row));
        assertEquals(0, SettingsWindow.rank(" ", row));
        assertEquals(1, SettingsWindow.rank("subscribe", "RSS feeds | feed subscribe"), "no description: keywords");
        assertEquals(3, SettingsWindow.rank("rss", "RSS feeds | feed subscribe"));
    }

    /** "proxy" opened Sync, whose description names the proxy password, ahead of Connection. */
    @Test
    void theBestRankedRowChoosesThePageAndATieGoesToTheEarlierPage() {
        java.util.List<Map.Entry<String, String>> rows = java.util.List.of(
                Map.entry("SYNC", "Sync | Not this machine's folders or proxy password. | git"),
                Map.entry("SYNC", "Repository | A Git URL. | remote"),
                Map.entry("CONNECTION", "Type | SOCKS5 or HTTP. | proxy socks"),
                Map.entry("CONNECTION", "Proxy port | The port it listens on. | proxy"),
                Map.entry("SPEED", "Upload limit | Kept under the proxy. | rate"));
        assertEquals(java.util.Optional.of("CONNECTION"), SettingsWindow.bestPage("proxy", rows));
        assertEquals(java.util.Optional.of("SYNC"), SettingsWindow.bestPage("git", rows), "keywords alone still find");
        assertEquals(
                java.util.Optional.of("SYNC"),
                SettingsWindow.bestPage(
                        "port",
                        java.util.List.of(Map.entry("SYNC", "Port | x | y"), Map.entry("CONNECTION", "Port | x | y"))));
        assertEquals(java.util.Optional.empty(), SettingsWindow.bestPage("nothing here", rows));
        assertEquals(java.util.Optional.empty(), SettingsWindow.bestPage("", rows));
    }

    @Test
    void escapeClearsASearchBeforeItCloses() {
        assertTrue(SettingsWindow.escapeClears("proxy"));
        assertFalse(SettingsWindow.escapeClears(""));
        assertFalse(SettingsWindow.escapeClears(null));
    }

    /** A Reset touches the desktop (stylesheet, tray, handlers, badge) only for a key it changed. */
    @Test
    void resetRunsOnlyTheHooksWhoseKeysChanged() {
        Map<String, String> before = new HashMap<>();
        before.put(Settings.THEME, "nord-light");
        before.put(Settings.UI_TRAY, "true");
        before.put(Settings.OS_BADGE, "false");
        Map<String, String> after = new HashMap<>();
        assertEquals(
                Set.of(SettingsWindow.ResetHook.THEME, SettingsWindow.ResetHook.TRAY, SettingsWindow.ResetHook.BADGE),
                SettingsWindow.resetHooks(before, after));
        assertEquals(Set.of(), SettingsWindow.resetHooks(after, new HashMap<>()));
        Map<String, String> closeOnly = Map.of(Settings.UI_CLOSE_TO_TRAY, "false");
        assertEquals(Set.of(SettingsWindow.ResetHook.TRAY), SettingsWindow.resetHooks(closeOnly, after));
        assertEquals(
                Set.of(SettingsWindow.ResetHook.HANDLER),
                SettingsWindow.resetHooks(Map.of(Settings.OS_HANDLER, "true"), after));
    }
}
