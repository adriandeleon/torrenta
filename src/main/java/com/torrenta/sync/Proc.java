package com.torrenta.sync;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs one child process to completion, with a ceiling; blocking, the caller picks the thread. */
final class Proc {

    /** What the child said and how it ended; {@code timedOut} means it was killed at the ceiling. */
    record Result(int code, String out, String err, boolean timedOut) {
        boolean ok() {
            return code == 0 && !timedOut;
        }

        /** The error output, else the standard output, trimmed: what a status line can show. */
        String transcript() {
            String text = err.isBlank() ? out : err;
            return timedOut ? "timed out" : text.strip();
        }
    }

    private Proc() {}

    static Result run(Path dir, Duration timeout, List<String> argv, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().putAll(env);
        // No terminal and nothing on standard input: a child that asks gets end-of-file.
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())));
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(127, "", String.valueOf(e.getMessage()), false);
        }
        Future<String> out = drain(process.getInputStream());
        Future<String> err = drain(process.getErrorStream());
        boolean timedOut = false;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            return new Result(timedOut ? -1 : process.exitValue(), out.get(), err.get(), timedOut);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", "interrupted", true);
        } catch (ExecutionException e) {
            return new Result(-1, "", String.valueOf(e.getCause()), timedOut);
        }
    }

    private static Future<String> drain(InputStream stream) {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
                .submit(() -> new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }

    static String nullDevice() {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "NUL"
                : "/dev/null";
    }
}
