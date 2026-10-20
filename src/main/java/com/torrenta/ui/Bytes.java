package com.torrenta.ui;

import java.util.Locale;

/** Byte counts and rates as a person reads them. Pure, so it is tested rather than eyeballed. */
public final class Bytes {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private Bytes() {}

    /** {@code 0 B}, {@code 512 B}, {@code 1.5 KiB}, {@code 12.3 MiB}, {@code 4.0 GiB}. */
    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    public static String rate(long bytesPerSecond) {
        return human(bytesPerSecond) + "/s";
    }
}
