package com.torrenta.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.stage.Window;

import com.torrenta.store.Settings;

/**
 * The FX thread for a test: {@link #call} and {@link #run} hand it work and wait, bounded, with
 * what it threw rethrown here; {@link #pulse} waits for a frame, so layout and CSS have run;
 * {@link #waitUntil} looks once a pulse, never sleeps. The toolkit is {@link FxToolkit}'s, started
 * headless once per JVM; a test class gets it with {@code @ExtendWith(FxToolkit.class)}.
 */
public final class Fx {

    /** Long enough for a slow CI machine to build the Settings window, short enough to fail plainly. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    @FunctionalInterface
    public interface Work<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    private Fx() {}

    /** Runs on the FX thread and returns what it returned; on the FX thread already, runs inline. */
    public static <T> T call(Work<T> work) {
        if (Platform.isFxApplicationThread()) {
            try {
                return work.get();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw new AssertionError(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("the FX thread did not answer in " + TIMEOUT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public static void run(Action action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** Waits for the next pulse, after which the scene has had its CSS and layout pass. */
    public static void pulse() {
        CompletableFuture<Void> framed = new CompletableFuture<>();
        run(() -> {
            AnimationTimer timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    stop();
                    framed.complete(null);
                }
            };
            timer.start();
            Platform.requestNextPulse();
        });
        try {
            framed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("no pulse in " + TIMEOUT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        // The timer runs early in the pulse; one more hop lands after its layout.
        run(() -> {});
    }

    /** Asks {@code condition} on the FX thread once a pulse until it holds; fails after the timeout. */
    public static void waitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!call(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new AssertionError("waited " + TIMEOUT + " for " + what);
            pulse();
        }
    }

    /** Hides every window, which also ends a dialog's nested event loop; then waits a pulse. */
    public static void closeAll() {
        run(() -> {
            for (Window window : List.copyOf(Window.getWindows())) window.hide();
        });
        pulse();
    }

    /**
     * Settings in {@code dir} with everything that reaches outside the test off: no update check
     * against GitHub, no tray or badge on the session bus, no file-type handler registered with
     * the desktop. Saved, so a window that reloads them reads the same.
     */
    public static Settings quietSettings(Path dir) {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.setUpdateCheck(false);
        settings.setTray(false);
        settings.setCloseToTray(false);
        settings.setOsBadge(false);
        settings.setOsHandler(false);
        settings.saveQuietly();
        return settings;
    }
}
