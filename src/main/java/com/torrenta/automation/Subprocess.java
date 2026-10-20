package com.torrenta.automation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs one program to completion with a ceiling, keeping the first part of what it printed. No
 * shell: the arguments reach the program as they are given. Blocking; the caller picks the thread,
 * and it is never the FX thread.
 */
final class Subprocess {

    /**
     * How it went. {@code failure} is set when the program could not be started at all (not
     * installed, not executable); {@code output} is standard output and error interleaved, cut at
     * the limit asked for.
     */
    record Result(int code, String output, boolean timedOut, String failure) {
        boolean started() {
            return failure == null;
        }

        boolean ok() {
            return started() && !timedOut && code == 0;
        }

        /** The output's first lines, at most {@code max}, blank ones left out. */
        List<String> firstLines(int max) {
            return output.lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty())
                    .limit(max)
                    .toList();
        }
    }

    private Subprocess() {}

    static Result run(List<String> argv, Duration ceiling, int keepChars) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        // No terminal and nothing on standard input: a program that asks gets end-of-file.
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())));
        Process process;
        try {
            process = pb.start();
        } catch (IOException | IllegalArgumentException e) {
            return new Result(
                    127,
                    "",
                    false,
                    e.getMessage() == null ? e.toString() : e.getMessage().strip());
        }
        Future<String> out = drain(process.getInputStream(), keepChars);
        boolean timedOut = false;
        try {
            if (!process.waitFor(ceiling.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String text;
            try {
                text = out.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                text = ""; // a grandchild still holds the pipe; the program itself is done
            }
            return new Result(timedOut ? -1 : process.exitValue(), text, timedOut, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", true, null);
        } catch (ExecutionException e) {
            return new Result(-1, "", timedOut, null);
        }
    }

    /** Keeps the first {@code keep} characters and swallows the rest, so the child never blocks. */
    private static Future<String> drain(InputStream stream, int keep) {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            StringBuilder kept = new StringBuilder();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (kept.length() < keep) {
                    kept.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (kept.length() > keep) kept.setLength(keep);
                }
            }
            return kept.toString();
        });
    }

    static String nullDevice() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
    }
}
