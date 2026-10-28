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

    private static void git(java.nio.file.Path dir, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        assertTrue(
                Proc.run(null, java.time.Duration.ofSeconds(30), argv, java.util.Map.of())
                        .ok(),
                argv.toString());
    }

    /**
     * The search against a remote that answers: git's {@code insteadOf} sends the https URL to a bare
     * repository in the test's folder, so the account's ls-remote is let in without any credential.
     */
    @Test
    void theFirstAccountLetInIsRememberedAndOneGhNoLongerHasIsForgotten(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(QuietGit.available(), "git on the PATH");
        String url = "https://127.0.0.1/me/r.git";
        java.nio.file.Path repo = java.nio.file.Files.createDirectories(dir.resolve("repo"));
        java.nio.file.Path bare = dir.resolve("bare.git");
        git(dir, "init", "-q", "--bare", bare.toString());
        git(repo, "init", "-q");
        git(repo, "config", "remote.origin.url", url);
        String insteadOf = "url." + bare.toUri() + ".insteadOf";
        git(repo, "config", insteadOf, url);
        QuietGit quiet = new QuietGit(repo, false);

        assertEquals("", SyncAccount.choose(quiet, List.of("gh"), url, h -> List.of("me"), false, false), "no search");
        assertEquals("me", SyncAccount.choose(quiet, List.of("gh"), url, h -> List.of("bad login", "me"), false, true));
        assertEquals("me", SyncAccount.stored(quiet, url));
        assertEquals("", SyncAccount.stored(quiet, "https://127.0.0.1/other.git"), "a clone of another remote");
        assertEquals(
                "me",
                SyncAccount.choose(
                        quiet,
                        List.of("gh"),
                        url,
                        h -> {
                            throw new AssertionError("a remembered account is not looked for");
                        },
                        false,
                        true));
        assertEquals("me", SyncAccount.choose(quiet, List.of("gh"), url, h -> List.of(), true, true), "gh silent");
        assertEquals("other", SyncAccount.choose(quiet, List.of("gh"), url, h -> List.of("other"), true, true));
        assertEquals("other", SyncAccount.stored(quiet, url));

        // The remote no longer answers, and gh no longer has the remembered account.
        git(repo, "config", "--unset", insteadOf);
        assertEquals("", SyncAccount.choose(quiet, List.of("gh"), url, h -> List.of("third"), true, true));
        assertEquals("", SyncAccount.stored(quiet, url), "forgotten");
        assertEquals("", SyncAccount.choose(quiet, List.of("gh"), url, h -> List.of("third"), true, true));
    }
}
