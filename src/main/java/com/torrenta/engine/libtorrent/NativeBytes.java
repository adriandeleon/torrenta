package com.torrenta.engine.libtorrent;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Bytes libtorrent hands over by address: a {@code read_piece_alert}'s buffer has no Java view in
 * libtorrent4j, only {@code bufferPtr()} and {@code size()}. Copied at once, inside the alert's
 * callback, while the alert (and so the buffer) is alive.
 *
 * <p>{@code reinterpret} is a restricted method: the module needs {@code --enable-native-access}
 * (the launchers name {@code com.torrenta}; tests run on the classpath, as ALL-UNNAMED).
 */
final class NativeBytes {

    private NativeBytes() {}

    static byte[] copy(long address, int length) {
        if (address == 0 || length <= 0) return new byte[0];
        return MemorySegment.ofAddress(address).reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
    }
}
