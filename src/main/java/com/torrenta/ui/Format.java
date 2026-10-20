package com.torrenta.ui;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

/** Column text. Pure, so every rule here is tested rather than eyeballed. */
public final class Format {

    /** {@link #etaSeconds} when there is nothing to estimate: not downloading, or already done. */
    public static final long NO_ETA = -1;
    /** {@link #etaSeconds} when downloading at no speed: it would never finish. */
    public static final long INFINITE_ETA = Long.MAX_VALUE;

    /** Past this, an estimate says nothing a person can use. */
    private static final long ETA_CEILING_SECONDS = 100L * 24 * 3600;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Format() {}

    public static String percent(double progress) {
        return String.format(Locale.ROOT, "%.1f %%", progress * 100);
    }

    /** Seconds left, {@link #NO_ETA} or {@link #INFINITE_ETA}. Sortable; {@link #eta} prints it. */
    public static long etaSeconds(Torrent t) {
        if (t.state() != TorrentState.DOWNLOADING) return NO_ETA;
        long remaining = t.size() - t.done();
        if (remaining <= 0) return NO_ETA;
        if (t.downloadRate() <= 0) return INFINITE_ETA;
        long seconds = remaining / t.downloadRate();
        return seconds > ETA_CEILING_SECONDS ? INFINITE_ETA : seconds;
    }

    public static String eta(long seconds) {
        if (seconds == NO_ETA) return "—";
        if (seconds == INFINITE_ETA) return "∞";
        return duration(seconds);
    }

    /** {@code 45s}, {@code 3m 4s}, {@code 2h 10m}, {@code 3d 2h}: two units, the two that matter. */
    public static String duration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 86400) return (seconds / 3600) + "h " + (seconds % 3600 / 60) + "m";
        return (seconds / 86400) + "d " + (seconds % 86400 / 3600) + "h";
    }

    /** Uploaded over downloaded; -1 when nothing has moved either way, infinity when only up. */
    public static double ratioValue(long uploaded, long downloaded) {
        if (downloaded <= 0) return uploaded > 0 ? Double.POSITIVE_INFINITY : -1;
        return (double) uploaded / downloaded;
    }

    public static String ratio(double ratio) {
        if (ratio < 0) return "—";
        if (Double.isInfinite(ratio)) return "∞";
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    /** The tracker's host, which is what identifies it; the full URL goes in a tooltip one day. */
    public static String host(String trackerUrl) {
        if (trackerUrl == null || trackerUrl.isBlank()) return "—";
        try {
            String host = URI.create(trackerUrl).getHost();
            return host == null || host.isEmpty() ? trackerUrl : host;
        } catch (IllegalArgumentException e) {
            return trackerUrl;
        }
    }

    public static String dateTime(long epochSeconds) {
        return dateTime(epochSeconds, ZoneId.systemDefault());
    }

    static String dateTime(long epochSeconds, ZoneId zone) {
        if (epochSeconds <= 0) return "—";
        return DATE_TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(zone));
    }
}
