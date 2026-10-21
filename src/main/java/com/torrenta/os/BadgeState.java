package com.torrenta.os;

import java.util.List;
import java.util.Locale;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

/**
 * What the badge says: how many torrents are downloading and how fast, in all. Pure, value-equal,
 * so the poll pushes only changes; {@link #idle} clears the badge.
 *
 * @param downloading torrents downloading right now (the Linux launcher's count)
 * @param downloadRate bytes per second coming in (the macOS dock badge's text)
 */
public record BadgeState(int downloading, long downloadRate) {

    public static BadgeState idle() {
        return new BadgeState(0, 0);
    }

    /** Counts what is actually downloading; a torrent paused, queued, checking or seeding is not. */
    public static BadgeState of(List<Torrent> torrents, long downloadRate) {
        int downloading = 0;
        for (Torrent t : torrents) {
            if (t.error().isEmpty() && !t.paused() && !t.queued() && t.state() == TorrentState.DOWNLOADING) {
                downloading++;
            }
        }
        return downloading == 0 ? idle() : new BadgeState(downloading, Math.max(0, downloadRate));
    }

    /** Whether there is anything to show. */
    public boolean active() {
        return downloading > 0;
    }

    /**
     * The rate as a dock badge can fit it: {@code 850K}, {@code 1.2M}, {@code 12M}, {@code 1.1G}.
     * Binary units like the rest of the window, the unit letter alone. A trickle under a kibibyte
     * still shows {@code 1K}: a visible badge with nothing on it would look broken.
     */
    public String text() {
        if (!active()) return "";
        double kib = downloadRate / 1024.0;
        if (kib < 1000) return Math.max(1, Math.round(kib)) + "K";
        double mib = kib / 1024;
        if (mib < 10) return String.format(Locale.ROOT, "%.1fM", mib);
        if (mib < 1000) return Math.round(mib) + "M";
        double gib = mib / 1024;
        return gib < 10 ? String.format(Locale.ROOT, "%.1fG", gib) : Math.round(gib) + "G";
    }
}
