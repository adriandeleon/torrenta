package com.torrenta.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * When a finished torrent has seeded enough, and what to do then. Pure, so it is tested: "it
 * paused my seed too early" and "it never stops seeding" are both rules here.
 *
 * <p>libtorrent knows {@code share_ratio_limit} and {@code seed_time_limit} only as a ranking of
 * seeds for the queue; it pauses nothing by them. The window applies this rule from its poll.
 *
 * @param ratio uploaded over downloaded at which the torrent is done seeding; 0 for no limit
 * @param seedingMinutes minutes of seeding after which it is done; 0 for no limit
 * @param action what happens when either limit is reached
 */
public record ShareLimit(double ratio, int seedingMinutes, Action action) {

    public enum Action {
        PAUSE("Pause"),
        REMOVE("Remove");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** No limit of either kind. */
    public static final ShareLimit NONE = new ShareLimit(0, 0, Action.PAUSE);

    public ShareLimit {
        if (ratio < 0 || Double.isNaN(ratio)) ratio = 0;
        if (seedingMinutes < 0) seedingMinutes = 0;
        if (action == null) action = Action.PAUSE;
    }

    /** Whether either limit is set at all. */
    public boolean limited() {
        return ratio > 0 || seedingMinutes > 0;
    }

    /**
     * Whether a torrent with these counters has seeded enough. The ratio is measured against what
     * was downloaded, or against the torrent's size when nothing was (the files were there already,
     * so the download never happened): a seed added complete would otherwise never reach any ratio.
     */
    public boolean reached(long uploaded, long downloaded, long size, long seedingSeconds) {
        if (ratio > 0) {
            long base = downloaded > 0 ? downloaded : size;
            if (base > 0 && uploaded >= ratio * base) return true;
        }
        return seedingMinutes > 0 && seedingSeconds >= seedingMinutes * 60L;
    }

    /**
     * The action due for a torrent now, if any: only one that has finished downloading and is not
     * paused by hand, against its own override when it has one, else the global limit.
     */
    public static Optional<Action> decide(Torrent torrent, ShareLimit global, ShareLimit override) {
        ShareLimit limit = override != null ? override : global;
        if (limit == null || !limit.limited()) return Optional.empty();
        boolean finished = torrent.state() == TorrentState.SEEDING || torrent.state() == TorrentState.FINISHED;
        if (!finished || torrent.paused()) return Optional.empty();
        return limit.reached(torrent.uploaded(), torrent.downloaded(), torrent.size(), torrent.seedingTime())
                ? Optional.of(limit.action())
                : Optional.empty();
    }

    /** {@code 2.0,60,PAUSE}: the one line a per-torrent override is stored as. */
    public String format() {
        return formatRatio(ratio) + "," + seedingMinutes + "," + action.name();
    }

    /** The inverse of {@link #format()}; anything unreadable is empty. */
    public static Optional<ShareLimit> parse(String text) {
        if (text == null) return Optional.empty();
        String[] parts = text.trim().split(",");
        if (parts.length != 3) return Optional.empty();
        try {
            double ratio = Double.parseDouble(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            Action action = Action.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
            if (ratio < 0 || minutes < 0 || Double.isNaN(ratio)) return Optional.empty();
            return Optional.of(new ShareLimit(ratio, minutes, action));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A ratio as a person writes it: two decimals at most, no trailing zeros ({@code 1.5}, {@code 2}). */
    public static String formatRatio(double ratio) {
        return BigDecimal.valueOf(ratio)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    @Override
    public String toString() {
        if (!limited()) return "none";
        StringBuilder sb = new StringBuilder();
        if (ratio > 0) sb.append("ratio ").append(formatRatio(ratio));
        if (seedingMinutes > 0) {
            if (sb.length() > 0) sb.append(" or ");
            sb.append(seedingMinutes).append(" min");
        }
        return sb.append(" → ").append(action.label().toLowerCase(Locale.ROOT)).toString();
    }
}
