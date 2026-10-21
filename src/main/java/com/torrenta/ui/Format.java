package com.torrenta.ui;

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

    /**
     * The state column: an error outranks everything (the torrent is going nowhere until it is
     * seen to), then a user pause outranks whatever the engine's state machine says.
     */
    public static String stateLabel(Torrent t) {
        if (!t.error().isEmpty()) return "Error";
        return t.paused() ? "Paused" : t.state().label();
    }

    /** The Tags column: comma-separated, as the dialogs take them. */
    public static String tags(java.util.List<String> tags) {
        return String.join(", ", tags);
    }

    /** A rate column: a dash for nothing moving, so the eye finds the rows that are. */
    public static String rate(long bytesPerSecond) {
        return bytesPerSecond <= 0 ? "—" : Bytes.rate(bytesPerSecond);
    }

    public static String percent(double progress) {
        return String.format(Locale.ROOT, "%.1f %%", progress * 100);
    }

    /**
     * A whole percentage for a sentence: {@code 62%}. Floored, so 99.97% is {@code 99%} and only a
     * finished torrent says {@code 100%}.
     */
    public static String wholePercent(double progress) {
        return (int) Math.floor(Math.max(0, Math.min(1, progress)) * 100 + 1e-9) + "%";
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

    /**
     * How long, for a sentence that is redrawn every second and should not tick: {@code 3 min},
     * {@code 2 h 10 min}, {@code 3 d}. Under a minute is {@code under a minute}.
     */
    public static String coarse(long seconds) {
        if (seconds < 60) return "under a minute";
        if (seconds < 3600) return (seconds / 60) + " min";
        if (seconds < 86400) {
            long minutes = seconds % 3600 / 60;
            return (seconds / 3600) + " h" + (minutes == 0 ? "" : " " + minutes + " min");
        }
        return (seconds / 86400) + " d";
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
        String host = com.torrenta.engine.TrackerHost.of(trackerUrl);
        return host.isEmpty() ? "—" : host;
    }

    public static String dateTime(long epochSeconds) {
        return dateTime(epochSeconds, ZoneId.systemDefault());
    }

    static String dateTime(long epochSeconds, ZoneId zone) {
        if (epochSeconds <= 0) return "—";
        return DATE_TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(zone));
    }
}
