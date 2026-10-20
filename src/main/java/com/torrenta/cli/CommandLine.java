package com.torrenta.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line: {@code --help}, {@code --version}, and anything else is a torrent to add — a
 * {@code .torrent} path or a {@code magnet:} link.
 *
 * <p>Pure parsing with no side effects, so it is fully testable and can run before the toolkit.
 */
public record CommandLine(boolean help, boolean version, List<String> inputs, String error) {

    public static CommandLine parse(String... args) {
        boolean help = false;
        boolean version = false;
        List<String> inputs = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "-V", "--version" -> version = true;
                default -> {
                    if (arg.startsWith("-") && !isMagnet(arg)) {
                        return new CommandLine(false, false, List.of(), "unknown option '" + arg + "'");
                    }
                    inputs.add(arg);
                }
            }
        }
        return new CommandLine(help, version, List.copyOf(inputs), null);
    }

    /** True when the application should come up; false when an option answered and stopped. */
    public boolean shouldRun() {
        return !help && !version && error == null;
    }

    public static boolean isMagnet(String input) {
        return input.regionMatches(true, 0, "magnet:", 0, "magnet:".length());
    }

    public static String usage(String launchName) {
        return """
                Usage: %s [options] [torrent-file | magnet-link]...

                Options:
                  -h, --help       Show this help and exit
                  -v, --version    Print the version and exit
                """.formatted(launchName).stripTrailing();
    }
}
