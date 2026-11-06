package com.torrenta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Writes a line as each test starts and ends to the file named by {@code -Dtorrenta.testProgress},
 * so a JVM that dies inside a test still says which one. Surefire names only the class of a crashed
 * fork, and the native engine's crashes kill the fork without a failure report. Each line is its
 * own write, closed at once, so it is in the kernel's hands before the next native call; the last
 * {@code start} without an {@code end} is the test that was running. After each class a {@code
 * heap} line has what is still reachable once a full collection has run, so a fork that runs out of
 * memory shows which classes left the most behind (a macOS runner's default heap is a quarter of
 * its 7 GB, the others' of 16). Registered for every test class through {@code META-INF/services}
 * (autodetection is on in {@code junit-platform.properties}); without the property (an IDE run) it
 * does nothing.
 */
public final class TestProgress implements BeforeTestExecutionCallback, AfterTestExecutionCallback, AfterAllCallback {

    private static final String PROPERTY = "torrenta.testProgress";

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        write("start " + name(context));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        write("end   " + name(context) + (context.getExecutionException().isPresent() ? " FAILED" : ""));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (file() == null) return;
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long used = runtime.totalMemory() - runtime.freeMemory();
        write("heap  " + context.getRequiredTestClass().getName() + " " + (used >> 20) + " MiB of "
                + (runtime.maxMemory() >> 20) + " MiB");
    }

    private static String name(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getDisplayName();
    }

    private static @org.jspecify.annotations.Nullable String file() {
        String file = System.getProperty(PROPERTY);
        return file == null || file.isBlank() ? null : file;
    }

    private static synchronized void write(String line) {
        String file = file();
        if (file == null) return;
        try {
            Path path = Path.of(file);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    Instant.now() + " " + line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A breadcrumb that cannot be written must not fail the test it describes.
        }
    }
}
