package com.torrenta.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SizeTextTest {

    @Test
    void unitsAreBinaryWhateverTheLetter() {
        assertEquals(Math.round(1.4 * 1024 * 1024 * 1024), SizeText.parse("1.4 GiB"));
        assertEquals(Math.round(1.4 * 1024 * 1024 * 1024), SizeText.parse("1.4 GB"));
        assertEquals(Math.round(1.4 * 1024 * 1024 * 1024), SizeText.parse("1.4GB"));
        assertEquals(700L * 1024 * 1024, SizeText.parse("700 MB"));
        assertEquals(700L * 1024 * 1024, SizeText.parse("700 MiB"));
        assertEquals(700L * 1024 * 1024, SizeText.parse("700M"));
        assertEquals(3L * 1024 * 1024 * 1024 * 1024, SizeText.parse("3 TB"));
        assertEquals(512L * 1024, SizeText.parse("512 kB"));
        assertEquals(Math.round(2.5 * 1024 * 1024 * 1024 * 1024 * 1024), SizeText.parse("2.5 PiB"));
    }

    @Test
    void plainNumbersAreBytesAndCommasAreGroupsOrDecimals() {
        assertEquals(1_234_567L, SizeText.parse("1,234,567"));
        assertEquals(1_234_567L, SizeText.parse("1234567"));
        assertEquals(1_234_567L, SizeText.parse("1234567 bytes"));
        assertEquals(96_504_312L, SizeText.parse(" 96504312 "));
        assertEquals(Math.round(1.4 * 1024 * 1024 * 1024), SizeText.parse("1,4 GB"), "a decimal comma");
        assertEquals(1_234L * 1024 * 1024, SizeText.parse("1,234 MB"), "a thousands comma");
        assertEquals(1_234_567L, SizeText.parse("1'234'567"), "Swiss groups");
        assertEquals(1_234_567L, SizeText.parse("1 234 567"), "French groups");
    }

    @Test
    void anythingElseIsMinusOne() {
        assertEquals(-1, SizeText.parse(null));
        assertEquals(-1, SizeText.parse(""));
        assertEquals(-1, SizeText.parse("—"));
        assertEquals(-1, SizeText.parse("n/a"));
        assertEquals(-1, SizeText.parse("big"));
        assertEquals(-1, SizeText.parse("1.2.3 GB"));
        assertEquals(-1, SizeText.parse("-5 MB"));
        assertEquals(-1, SizeText.parse("5 lightyears"));
        assertEquals(Math.round(631.0 * 1024 * 1024), SizeText.parse("631.0\u00a0MB"), "a non-breaking space");
    }

    @Test
    void countsAreTheDigitsInTheText() {
        assertEquals(1234, SizeText.count("1,234"));
        assertEquals(42, SizeText.count(" 42 "));
        assertEquals(7, SizeText.count("7 seeders"));
        assertEquals(0, SizeText.count("0"));
        assertEquals(-1, SizeText.count("—"));
        assertEquals(-1, SizeText.count(""));
        assertEquals(-1, SizeText.count(null));
        assertEquals(-1, SizeText.count("12345678901"), "too many digits for a count");
    }
}
