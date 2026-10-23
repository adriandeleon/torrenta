package com.torrenta;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Ends the macOS test JVM with {@code _exit} once the tests and every other shutdown hook are done,
 * so the C library's exit handlers never run.
 *
 * <p>libtorrent4j 2.1.0-39 links OpenSSL 3.5.5 statically, and OpenSSL registers its own cleanup
 * with {@code atexit}. On macOS that cleanup sometimes frees a pointer that is not allocated, and
 * libmalloc aborts the process: the crash report shows the VM thread in {@code exit()} →
 * {@code __cxa_finalize_ranges} → libtorrent4j's OpenSSL code → {@code
 * ___BUG_IN_CLIENT_OF_LIBMALLOC_POINTER_BEING_FREED_WAS_NOT_ALLOCATED}, with no libtorrent thread
 * left alive. It comes after the last test has reported, prints nothing and leaves no hs_err, and
 * surefire calls the build failed ("Error occurred in starting fork"). It hit about one macOS run
 * in twenty. The exit handlers only free memory the OS takes back anyway.
 *
 * <p>Only after the launcher session closed normally (every test ran and reported), only after
 * the other shutdown hooks finished (JaCoCo writes its data in one), and only on macOS, where the
 * crash was seen. Registered through {@code META-INF/services}; a JVM that exits any other way
 * exits as usual.
 */
public final class NativeTeardownSkip implements LauncherSessionListener {

    private static final long HOOK_WAIT_MS = 30_000;
    /** The JDK starts the hooks one after another in a loop; one still unstarted after this was removed. */
    private static final long HOOK_START_WAIT_MS = 2_000;

    private static volatile boolean registered;
    /** The other hooks, read when the tests end: the JDK empties its table before running them. */
    private static volatile List<Thread> hooksAtEnd;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (!applies(System.getProperty("os.name"))) return;
        synchronized (NativeTeardownSkip.class) {
            if (registered) return;
            registered = true;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(NativeTeardownSkip::exitQuietly, "native-teardown-skip"));
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (!registered) return;
        try {
            hooksAtEnd = otherHooks();
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("native-teardown-skip: cannot see the shutdown hooks, exiting as usual: " + e);
        }
    }

    static boolean applies(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    private static void exitQuietly() {
        List<Thread> hooks = hooksAtEnd;
        if (hooks == null) return;
        try {
            awaitOthers(hooks, HOOK_START_WAIT_MS, HOOK_WAIT_MS);
            System.out.flush();
            System.err.flush();
            Linker linker = Linker.nativeLinker();
            MethodHandle exit = linker.downcallHandle(
                    linker.defaultLookup().find("_exit").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
            exit.invokeExact(0);
        } catch (Throwable e) {
            // Anything unexpected: fall back to the ordinary exit, which is what ran before.
            System.err.println("native-teardown-skip: " + e);
        }
    }

    /**
     * Waits for each hook but this thread to finish. {@code Thread.join} returns at once for a thread
     * not yet started, and the JDK starts the hooks one by one in no set order, so a hook started
     * after this one is first waited for until it has started: joining it straight away let
     * {@code _exit} cut JaCoCo's {@code jacoco.exec} short (an {@code EOFException} in the report)
     * in eight of twelve macOS loops of six runs.
     */
    static void awaitOthers(List<Thread> hooks, long startWaitMs, long finishWaitMs) throws InterruptedException {
        for (Thread hook : hooks) {
            if (hook == Thread.currentThread()) continue;
            long startBy = System.nanoTime() + startWaitMs * 1_000_000;
            while (hook.getState() == Thread.State.NEW && System.nanoTime() < startBy) Thread.sleep(1);
            hook.join(finishWaitMs);
        }
    }

    /** The application shutdown hooks registered so far; at exit they run at once, in no set order. */
    private static List<Thread> otherHooks() throws ReflectiveOperationException {
        Class<?> table = Class.forName("java.lang.ApplicationShutdownHooks");
        var field = table.getDeclaredField("hooks");
        field.setAccessible(true); // --add-opens java.base/java.lang in the surefire argLine
        synchronized (table) {
            Map<?, ?> hooks = (Map<?, ?>) field.get(null);
            return hooks.keySet().stream().map(Thread.class::cast).toList();
        }
    }
}
