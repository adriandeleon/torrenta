package com.torrenta.ui;

import java.util.Locale;
import java.util.Optional;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

import static com.torrenta.i18n.Messages.tr;

/**
 * The sidebar's Status section: qBittorrent's shape, decided from the snapshot alone (state,
 * the user's pause, the rates, the error). Pure, so the rule is tested; the counts and the
 * palette's "Show: …" commands come from the same list.
 *
 * <p>A torrent is in several at once: one that is seeding at speed is Seeding, Completed and
 * Active. A paused one is neither Downloading nor Seeding, and never Stalled: stalled is wanting
 * to move and not moving.
 */
public enum SidebarStatus {
    DOWNLOADING,
    SEEDING,
    COMPLETED,
    PAUSED,
    ACTIVE,
    INACTIVE,
    STALLED,
    CHECKING,
    ERRORED;

    /** The entry's text in the sidebar, in the UI language; read at call time, never cached. */
    public String title() {
        return tr("sidebar.status." + key());
    }

    /** The name in the settings file and the filter's format: lower case. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The inverse of {@link #key}, ignoring case and blanks; empty for a name that is not one. */
    public static Optional<SidebarStatus> parse(String key) {
        if (key == null) return Optional.empty();
        String k = key.trim().toUpperCase(Locale.ROOT);
        for (SidebarStatus s : values()) if (s.name().equals(k)) return Optional.of(s);
        return Optional.empty();
    }

    public boolean matches(Torrent t) {
        boolean finished = t.state() == TorrentState.SEEDING || t.state() == TorrentState.FINISHED;
        boolean fetching = t.state() == TorrentState.DOWNLOADING || t.state() == TorrentState.FETCHING_METADATA;
        boolean moving = t.downloadRate() > 0 || t.uploadRate() > 0;
        // A finished download from a link has nothing left to do: it neither seeds nor stalls.
        boolean seeds = finished && !t.direct();
        return switch (this) {
            case DOWNLOADING -> fetching && !t.paused();
            case SEEDING -> seeds && !t.paused();
            case COMPLETED -> finished;
            case PAUSED -> t.paused();
            case ACTIVE -> moving;
            case INACTIVE -> !moving;
            case STALLED ->
                (fetching || seeds) && !t.paused() && !moving && t.error().isEmpty();
            case CHECKING -> t.state() == TorrentState.CHECKING;
            case ERRORED -> !t.error().isEmpty();
        };
    }
}
