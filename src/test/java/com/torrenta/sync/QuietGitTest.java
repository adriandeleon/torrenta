package com.torrenta.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietGitTest {

    @Test
    void aSilentRunNeverAsksAndKeepsTheRepositorysProgramsOff() {
        List<String> argv = QuietGit.argv(false, List.of("-c", "x=y"), "fetch", "origin");
        assertEquals("git", argv.get(0));
        assertTrue(argv.contains("core.hooksPath=" + Proc.nullDevice()));
        assertTrue(argv.contains("commit.gpgsign=false"));
        assertTrue(argv.contains("core.askPass="));
        assertTrue(argv.contains("credential.interactive=false"));
        assertEquals(List.of("x=y", "fetch", "origin"), argv.subList(argv.size() - 3, argv.size()), "extras last");
        Map<String, String> env = QuietGit.env(false, false);
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("", env.get("GIT_ASKPASS"));
        assertEquals("never", env.get("SSH_ASKPASS_REQUIRE"));
        assertEquals("ssh -o BatchMode=yes", env.get("GIT_SSH_COMMAND"));
        assertFalse(QuietGit.env(false, true).containsKey("GIT_SSH_COMMAND"), "the user's ssh command stays");
    }

    @Test
    void anInteractiveRunKeepsTheUsersHelpers() {
        List<String> argv = QuietGit.argv(true, List.of(), "push");
        assertFalse(argv.contains("core.askPass="));
        Map<String, String> env = QuietGit.env(true, true);
        assertFalse(env.containsKey("GIT_ASKPASS"));
        assertEquals("C", env.get("LC_ALL"), "messages the engine reads stay in English");
    }
}
