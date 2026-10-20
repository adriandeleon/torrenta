package com.torrenta.engine.libtorrent;

import com.torrenta.engine.EngineException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform → resource mapping, which has to be right on machines this build never runs on. */
class NativeLibraryTest {

    @Test
    void theThreeShippedPlatforms() throws EngineException {
        assertEquals("lib/x86_64/libtorrent4j.so", NativeLibrary.resourcePath("Linux", "amd64"));
        assertEquals("lib/arm64/libtorrent4j.dylib", NativeLibrary.resourcePath("Mac OS X", "aarch64"));
        assertEquals("lib/x86_64/libtorrent4j.dll", NativeLibrary.resourcePath("Windows 11", "amd64"));
    }

    @Test
    void archSpellingsAreNormalised() throws EngineException {
        assertEquals("lib/x86_64/libtorrent4j.so", NativeLibrary.resourcePath("Linux", "x86_64"));
        assertEquals("lib/arm64/libtorrent4j.dylib", NativeLibrary.resourcePath("Mac OS X", "arm64"));
    }

    @Test
    void unknownPlatformsSaySoInsteadOfGuessing() {
        EngineException cpu = assertThrows(EngineException.class, () -> NativeLibrary.resourcePath("Linux", "riscv64"));
        assertTrue(cpu.getMessage().contains("riscv64"));
        EngineException os = assertThrows(EngineException.class, () -> NativeLibrary.resourcePath("FreeBSD", "amd64"));
        assertTrue(os.getMessage().contains("FreeBSD"));
    }
}
