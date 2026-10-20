package com.torrenta.sync;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncAccountTest {

    @Test
    void onlyAnHttpsHostCounts() {
        assertEquals("github.com", SyncAccount.httpsHost("https://GitHub.com/me/r.git"));
        assertEquals("", SyncAccount.httpsHost("git@github.com:me/r.git"));
        assertEquals("", SyncAccount.httpsHost("ssh://git@github.com/me/r.git"));
        assertEquals("", SyncAccount.httpsHost("https://"));
        assertEquals("", SyncAccount.httpsHost(null));
    }

    @Test
    void theHelperPrintsGhsTokenForThatAccountAndRefusesUnsafeParts() {
        List<String> config = SyncAccount.credentialConfig(List.of("gh"), "github.com", "adriandeleon");
        assertEquals(4, config.size());
        assertEquals("credential.https://github.com.helper=", config.get(1), "the user's helpers are dropped first");
        assertTrue(config.get(3).contains("'gh' auth token --hostname github.com --user adriandeleon"), config.get(3));
        assertTrue(config.get(3).contains("echo username=adriandeleon"));
        assertFalse(config.get(3).contains("\""), "no double quote: a Windows command line would mangle it");
        assertTrue(SyncAccount.credentialConfig(List.of("gh"), "github.com", "bad login")
                .isEmpty());
        assertTrue(
                SyncAccount.credentialConfig(List.of("g'h"), "github.com", "x").isEmpty());
        assertTrue(SyncAccount.credentialConfig(List.of(), "github.com", "x").isEmpty());
        assertTrue(SyncAccount.credentialConfig(List.of("gh"), "bad host", "x").isEmpty());
    }

    @Test
    void chooseLeavesNonHttpsRemotesAlone(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        QuietGit git = new QuietGit(dir.resolve("repo"), false);
        assertEquals(
                "", SyncAccount.choose(git, List.of("gh"), "git@github.com:me/r.git", h -> List.of("me"), true, true));
        assertEquals(
                "",
                SyncAccount.choose(git, List.of("gh"), "https://github.com/me/r.git", h -> List.of("me"), true, true),
                "no clone yet: nothing to remember in");
    }
}
