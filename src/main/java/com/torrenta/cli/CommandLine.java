package com.torrenta.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line: {@code --help}, {@code --version}, {@code --config-dir}, {@code --headless},
 * {@code --set-remote-password} (a headless server has no Settings window to set it in), and
 * anything else is a torrent to add — a {@code .torrent} path or a {@code magnet:} link.
 *
 * <p>Pure parsing with no side effects, so it is fully testable and can run before the toolkit.
 */
public record CommandLine(
        boolean help,
        boolean version,
        boolean headless,
        boolean setRemotePassword,
        String configDir,
        List<String> inputs,
        String error) {

    public static CommandLine parse(String... args) {
        boolean help = false;
        boolean version = false;
        boolean headless = false;
        boolean setRemotePassword = false;
        String configDir = null;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "-V", "--version" -> version = true;
                case "--headless" -> headless = true;
                case "--set-remote-password" -> setRemotePassword = true;
                case "--config-dir" -> {
                    if (i + 1 >= args.length) return error("--config-dir needs a directory");
                    configDir = args[++i];
                }
                default -> {
                    if (arg.startsWith("--config-dir=")) {
                        configDir = arg.substring("--config-dir=".length());
                        if (configDir.isEmpty()) return error("--config-dir needs a directory");
                    } else if (arg.startsWith("-") && !isMagnet(arg)) {
                        return error("unknown option '" + arg + "'");
                    } else {
                        inputs.add(arg);
                    }
                }
            }
        }
        return new CommandLine(help, version, headless, setRemotePassword, configDir, List.copyOf(inputs), null);
    }

    private static CommandLine error(String message) {
        return new CommandLine(false, false, false, false, null, List.of(), message);
    }

    /** True when the application should come up; false when an option answered and stopped. */
    public boolean shouldRun() {
        return !help && !version && error == null;
    }

    /** The packaged image's headless launcher, beside the window's: {@code bin/torrenta-nox}. */
    public static final String HEADLESS_LAUNCHER = "torrenta-nox";

    /**
     * Whether the run is headless: asked for, or started by the headless launcher. jpackage hands a
     * launcher's own {@code arguments} only when the person gives none, so {@code torrenta-nox
     * --config-dir …} arrives without {@code --headless}; the launcher's path ({@code
     * jpackage.app-path}, set by every jpackage launcher) says which one it was. Pure.
     */
    public boolean headless(String launcherPath) {
        if (headless) return true;
        if (launcherPath == null || launcherPath.isBlank()) return false;
        // Either separator, so the rule reads the same whatever the OS it is tested on.
        int slash = Math.max(launcherPath.lastIndexOf('/'), launcherPath.lastIndexOf('\\'));
        String name = launcherPath.substring(slash + 1).toLowerCase(java.util.Locale.ROOT);
        return name.equals(HEADLESS_LAUNCHER) || name.equals(HEADLESS_LAUNCHER + ".exe");
    }

    public static boolean isMagnet(String input) {
        return input.regionMatches(true, 0, "magnet:", 0, "magnet:".length());
    }

    public static String usage(String launchName) {
        return """
                Usage: %s [options] [torrent-file | magnet-link]...

                Options:
                  -h, --help             Show this help and exit
                  -v, --version          Print the version and exit
                      --config-dir DIR   Keep settings and resume data in DIR (default ~/.torrenta)
                      --headless         Run without a window: add what arrives without asking, log
                                         to stdout, stop on SIGTERM
                      --set-remote-password
                                         Read a password for Remote access from the terminal (or
                                         a line of stdin), keep its hash in the settings and exit
                """.formatted(launchName).stripTrailing();
    }
}
