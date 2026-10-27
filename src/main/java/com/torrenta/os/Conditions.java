package com.torrenta.os;

import java.util.Locale;

/**
 * What the machine is running on, as far as the speed rules care: the battery, and a network the
 * person pays for by the byte (a phone's hotspot, a capped plan). Each platform has its own way to
 * read them ({@link SystemConditions}); this is the value they read into.
 *
 * @param onBattery drawing from a battery rather than the mains
 * @param metered the network in use is marked metered (NetworkManager, Windows' cost)
 */
public record Conditions(boolean onBattery, boolean metered) {

    /** Mains and an unmetered network: what a machine that cannot say is taken to be on. */
    public static final Conditions NORMAL = new Conditions(false, false);

    /** Where the conditions come from: read when asked, and a nudge when they change by themselves. */
    public interface Source extends AutoCloseable {
        /** The conditions now. Cheap enough for once a minute; never throws. */
        Conditions read();

        /** Called (on any thread) when a condition may have changed; a source that only polls never calls it. */
        default void onChange(Runnable listener) {}

        @Override
        default void close() {}

        /** Nothing to read: always {@link #NORMAL}. */
        Source NONE = () -> NORMAL;
    }

    /**
     * Which conditions this kind of machine can read at all, so Settings shows a rule only where it
     * can hold: the battery everywhere; a metered network on Linux (NetworkManager) and Windows,
     * not on macOS, which has no command for Low Data Mode. Pure.
     */
    public static boolean seesMetered(String osName) {
        return !osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Capture only ({@code -Dtorrenta.conditions=battery,metered}): conditions as given, so a
     * screenshot can show the pill on battery without unplugging. Unknown words are ignored.
     */
    public static Conditions parse(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return new Conditions(t.contains("battery"), t.contains("metered"));
    }
}
