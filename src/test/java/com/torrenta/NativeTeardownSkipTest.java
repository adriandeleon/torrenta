package com.torrenta;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTeardownSkipTest {

    /** Only macOS, where OpenSSL's exit-time cleanup inside libtorrent4j was seen to abort. */
    @Test
    void onlyMacOsSkipsTheNativeTeardown() {
        assertTrue(NativeTeardownSkip.applies("Mac OS X"));
        assertFalse(NativeTeardownSkip.applies("Linux"));
        assertFalse(NativeTeardownSkip.applies("Windows 11"));
        assertFalse(NativeTeardownSkip.applies(null));
    }

    /** A hook the JDK has not started yet is waited for, not skipped: JaCoCo's lost its file that way. */
    @Test
    void aHookStartedLaterIsWaitedForUntilItEnds() throws InterruptedException {
        AtomicBoolean written = new AtomicBoolean();
        Thread hook = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            written.set(true);
        });
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            hook.start();
        });
        starter.start();
        NativeTeardownSkip.awaitOthers(List.of(Thread.currentThread(), hook), 5_000, 5_000);
        assertTrue(written.get());
    }

    /** A hook removed after the tests ended never starts; that costs the start wait, not the finish wait. */
    @Test
    void aHookThatNeverStartsIsGivenUpOnAfterTheStartWait() throws InterruptedException {
        long before = System.nanoTime();
        NativeTeardownSkip.awaitOthers(List.of(new Thread(() -> {})), 50, 60_000);
        assertTrue(System.nanoTime() - before < 5_000_000_000L);
    }
}
