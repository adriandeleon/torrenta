package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BytesTest {

    @Test
    void unitsStepAt1024() {
        assertEquals("0 B", Bytes.human(0));
        assertEquals("1023 B", Bytes.human(1023));
        assertEquals("1.0 KiB", Bytes.human(1024));
        assertEquals("1.5 KiB", Bytes.human(1536));
        assertEquals("12.3 MiB", Bytes.human(12_897_000));
        assertEquals("4.0 GiB", Bytes.human(4L << 30));
        assertEquals("2048.0 TiB", Bytes.human(2048L << 40));
    }

    @Test
    void ratesAreBytesPerSecond() {
        assertEquals("0 B/s", Bytes.rate(0));
        assertEquals("1.0 MiB/s", Bytes.rate(1 << 20));
    }
}
