package com.torrenta.sync;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs git in the clone settings sync owns, on the application's own initiative; Editora's runner.
 *
 * <p>Every command keeps the repository's programs off (hooks, fsmonitor, the pager, external
 * protocols) and applies no end-of-line conversion or commit signing (a signing key's pinentry
 * would be a prompt). A non-interactive instance also never asks for anything: no terminal, no
 * askpass program, no credential-manager window, ssh in batch mode, so a remote that needs a
 * sign-in fails with git's message instead. An interactive one keeps the user's askpass and
 * credential helper, for the one command the user is waiting on.
 */
public final class QuietGit {

    /** Ceiling for a command that talks to the remote. */
    static final Duration NETWORK = Duration.ofMinutes(2);
    /** Ceiling for a local command. */
    static final Duration LOCAL = Duration.ofSeconds(30);

    private static final List<String> OWN_CONFIG = List.of(
            "-c", "core.fsmonitor=false",
            "-c", "core.hooksPath=" + Proc.nullDevice(),
            "-c", "core.pager=cat",
            "-c", "protocol.ext.allow=never",
            "-c", "core.autocrlf=false",
            "-c", "core.safecrlf=false",
            // A link in the repository is checked out as a small text file, never followed.
            "-c", "core.symlinks=false",
            "-c", "commit.gpgsign=false",
            "-c", "tag.gpgsign=false",
            "-c", "advice.detachedHead=false");
    private static final List<String> SILENT_CONFIG =
            List.of("-c", "core.askPass=", "-c", "credential.interactive=false");

    private final Path dir;
    private final boolean interactive;
    private final List<String> extraConfig;

    public QuietGit(Path dir, boolean interactive) {
        this(dir, interactive, List.of());
    }

    private QuietGit(Path dir, boolean interactive, List<String> extraConfig) {
        this.dir = dir;
        this.interactive = interactive;
        this.extraConfig = List.copyOf(extraConfig);
    }

    /** The same runner with {@code -c key=value} pairs added last, so they outrank the user's own. */
    public QuietGit with(List<String> config) {
        return config.isEmpty() ? this : new QuietGit(dir, interactive, config);
    }

    /** The same runner, but one that never asks for anything. */
    public QuietGit silent() {
        return interactive ? new QuietGit(dir, false, extraConfig) : this;
    }

    public Path dir() {
        return dir;
    }

    /** Whether git starts at all. */
    public static boolean available() {
        return Proc.run(null, Duration.ofSeconds(10), List.of("git", "--version"), env(false, false))
                .ok();
    }

    /** A local command. */
    Proc.Result run(String... args) {
        return Proc.run(dir, LOCAL, argv(interactive, extraConfig, args), env(interactive, ownSsh()));
    }

    /** A command that talks to the remote. */
    Proc.Result network(String... args) {
        return Proc.run(dir, NETWORK, argv(interactive, extraConfig, args), env(interactive, ownSsh()));
    }

    /** The whole command line; pure, for the tests. */
    static List<String> argv(boolean interactive, List<String> extraConfig, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(OWN_CONFIG);
        if (!interactive) argv.addAll(SILENT_CONFIG);
        argv.addAll(extraConfig);
        argv.addAll(List.of(args));
        return argv;
    }

    private boolean ownSsh() {
        if (interactive) return true;
        String exported = System.getenv("GIT_SSH_COMMAND");
        String exported2 = System.getenv("GIT_SSH");
        if ((exported != null && !exported.isBlank()) || (exported2 != null && !exported2.isBlank())) return true;
        return Proc.run(
                        dir,
                        Duration.ofSeconds(10),
                        List.of("git", "config", "--get", "core.sshCommand"),
                        env(true, true))
                .ok();
    }

    /**
     * The environment additions. Pure.
     *
     * @param ownSsh the user has an ssh command of their own ({@code GIT_SSH_COMMAND} outranks
     *     {@code core.sshCommand}, so batch mode is only forced when they have none)
     */
    static Map<String, String> env(boolean interactive, boolean ownSsh) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LC_ALL", "C");
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_NO_LAZY_FETCH", "1");
        if (!interactive) {
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put("GIT_ASKPASS", "");
            env.put("SSH_ASKPASS", "");
            env.put("SSH_ASKPASS_REQUIRE", "never");
            env.put("GCM_INTERACTIVE", "never");
            if (!ownSsh) env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
        }
        return env;
    }
}
