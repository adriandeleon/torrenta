package com.torrenta.automation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the person's program, run by the tests as a child JVM so it exists on every
 * platform: writes its arguments to the file named first, one per line, says hello on standard
 * output, and exits as told. {@code --exit N} sets the code; {@code --sleep} hangs for a minute,
 * for the ceiling.
 */
public final class ArgsToFile {

    private ArgsToFile() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int code = 0;
        boolean sleep = false;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--exit") && i + 1 < args.length) {
                code = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--sleep")) {
                sleep = true;
            } else {
                rest.add(args[i]);
            }
        }
        Files.write(out, rest, StandardCharsets.UTF_8);
        System.out.println("hello from ArgsToFile");
        System.out.println("second line");
        System.out.println("third line");
        System.out.println("fourth line, not logged");
        if (sleep) Thread.sleep(60_000);
        System.exit(code);
    }

    /** The command line that runs this class in a child JVM, with the file first; a test's prefix. */
    static List<String> command(Path out) {
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, "-cp", System.getProperty("java.class.path"), ArgsToFile.class.getName(), out.toString());
    }

    /** The same, as a line {@code EventCommand.tokens} splits: single quotes keep every path whole. */
    static String line(Path out, String placeholders) {
        List<String> parts = command(out);
        return "'" + parts.get(0) + "' -cp '" + parts.get(2) + "' " + parts.get(3) + " '" + parts.get(4) + "' "
                + placeholders;
    }
}
