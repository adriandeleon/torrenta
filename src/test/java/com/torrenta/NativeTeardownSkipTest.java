package com.torrenta;

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
}
