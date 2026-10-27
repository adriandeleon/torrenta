package com.torrenta.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line: {@code --help}, {@code --version}, {@code --report}, {@code --config-dir}, and
 * anything else is a torrent to add — a {@code .torrent} path or a {@code magnet:} link.
 *
 * <p>Pure parsing with no side effects, so it is fully testable and can run before the toolkit.
 */
public record CommandLine(
        boolean help, boolean version, boolean report, String configDir, List<String> inputs, String error) {

    public static CommandLine parse(String... args) {
        boolean help = false;
        boolean version = false;
        boolean report = false;
        String configDir = null;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "-V", "--version" -> version = true;
                case "--report" -> report = true;
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
        return new CommandLine(help, version, report, configDir, List.copyOf(inputs), null);
    }

    private static CommandLine error(String message) {
        return new CommandLine(false, false, false, null, List.of(), message);
    }

    /** True when the application should come up; false when an option answered and stopped. */
    public boolean shouldRun() {
        return !help && !version && !report && error == null;
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
                      --report           Write a problem report (system details and the last two
                                         session logs, redacted) to the current folder and open
                                         a GitHub issue for it, without starting Torrenta
                      --config-dir DIR   Keep settings and resume data in DIR (default ~/.torrenta)
                """.formatted(launchName).stripTrailing();
    }
}
