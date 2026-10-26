package com.torrenta.webapi;

import java.util.Locale;
import java.util.Optional;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

/**
 * A torrent's {@code state} as qBittorrent 5.0 writes it in {@code torrents/info}, and the {@code
 * filter} values that select by it. The *arr apps decide by these words whether a download is done
 * (any {@code …UP} with progress 1), stuck ({@code stalledDL}, {@code metaDL}) or failed ({@code
 * error}), so every case is tested.
 *
 * <p>qBittorrent's own rule, not the list's: a download is stalled the moment nothing arrives,
 * where the window waits a minute ({@code RowState.STALL_AFTER_SECONDS}); the API has no clock per
 * torrent, and qBittorrent's clients expect its words. A torrent forced past the queue is reported
 * as an ordinary one: the snapshot does not say it was forced.
 */
public enum QbitState {
    ERROR("error"),
    MOVING("moving"),
    CHECKING_DL("checkingDL"),
    CHECKING_UP("checkingUP"),
    STOPPED_DL("stoppedDL"),
    STOPPED_UP("stoppedUP"),
    QUEUED_DL("queuedDL"),
    QUEUED_UP("queuedUP"),
    META_DL("metaDL"),
    DOWNLOADING("downloading"),
    STALLED_DL("stalledDL"),
    UPLOADING("uploading"),
    STALLED_UP("stalledUP"),
    UNKNOWN("unknown");

    private final String wire;

    QbitState(String wire) {
        this.wire = wire;
    }

    /** The word on the wire. */
    public String wire() {
        return wire;
    }

    /** The first rule that fits wins, in the window's order: a person's attention first. */
    public static QbitState of(Torrent t) {
        boolean complete = complete(t);
        if (!t.error().isEmpty()) return ERROR;
        if (t.moving()) return MOVING;
        if (t.state() == TorrentState.CHECKING) return complete ? CHECKING_UP : CHECKING_DL;
        if (t.paused()) return complete ? STOPPED_UP : STOPPED_DL;
        if (t.queued()) return complete ? QUEUED_UP : QUEUED_DL;
        return switch (t.state()) {
            case FETCHING_METADATA -> META_DL;
            case DOWNLOADING -> t.downloadRate() > 0 ? DOWNLOADING : STALLED_DL;
            case FINISHED, SEEDING -> t.uploadRate() > 0 ? UPLOADING : STALLED_UP;
            default -> UNKNOWN;
        };
    }

    /** Every wanted byte is there. */
    static boolean complete(Torrent t) {
        return t.state() == TorrentState.FINISHED || t.state() == TorrentState.SEEDING || t.progress() >= 1;
    }

    /** A download side: what qBittorrent's {@code downloading} filter counts, stopped ones included. */
    boolean downloadSide() {
        return switch (this) {
            case CHECKING_DL, STOPPED_DL, QUEUED_DL, META_DL, DOWNLOADING, STALLED_DL -> true;
            default -> false;
        };
    }

    /** Done: what qBittorrent's {@code completed} filter counts, stopped and checking ones included. */
    boolean uploadSide() {
        return switch (this) {
            case CHECKING_UP, STOPPED_UP, QUEUED_UP, UPLOADING, STALLED_UP -> true;
            default -> false;
        };
    }

    /**
     * {@code torrents/info}'s {@code filter}: qBittorrent's names, 4.x's {@code paused}/{@code resumed}
     * beside 5.0's {@code stopped}/{@code running}. Empty for a name it does not know, which
     * qBittorrent treats as {@code all}.
     */
    public static Optional<Filter> filter(String name) {
        if (name == null) return Optional.empty();
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "all", "" -> Optional.of(Filter.ALL);
            case "downloading" -> Optional.of(Filter.DOWNLOADING);
            case "seeding" -> Optional.of(Filter.SEEDING);
            case "completed" -> Optional.of(Filter.COMPLETED);
            case "paused", "stopped" -> Optional.of(Filter.STOPPED);
            case "resumed", "running" -> Optional.of(Filter.RUNNING);
            case "active" -> Optional.of(Filter.ACTIVE);
            case "inactive" -> Optional.of(Filter.INACTIVE);
            case "stalled" -> Optional.of(Filter.STALLED);
            case "stalled_uploading" -> Optional.of(Filter.STALLED_UPLOADING);
            case "stalled_downloading" -> Optional.of(Filter.STALLED_DOWNLOADING);
            case "checking" -> Optional.of(Filter.CHECKING);
            case "moving" -> Optional.of(Filter.MOVING);
            case "errored" -> Optional.of(Filter.ERRORED);
            default -> Optional.empty();
        };
    }

    /** One of qBittorrent's status filters, decided from the state and the rates. */
    public enum Filter {
        ALL,
        DOWNLOADING,
        SEEDING,
        COMPLETED,
        STOPPED,
        RUNNING,
        ACTIVE,
        INACTIVE,
        STALLED,
        STALLED_UPLOADING,
        STALLED_DOWNLOADING,
        CHECKING,
        MOVING,
        ERRORED;

        public boolean matches(Torrent t) {
            QbitState s = of(t);
            boolean active = t.downloadRate() > 0 || t.uploadRate() > 0;
            return switch (this) {
                case ALL -> true;
                case DOWNLOADING -> s.downloadSide();
                case SEEDING -> s == UPLOADING || s == STALLED_UP || s == QUEUED_UP || s == CHECKING_UP;
                case COMPLETED -> s.uploadSide();
                case STOPPED -> s == STOPPED_DL || s == STOPPED_UP;
                case RUNNING -> s != STOPPED_DL && s != STOPPED_UP;
                case ACTIVE -> active;
                case INACTIVE -> !active;
                case STALLED -> s == STALLED_DL || s == STALLED_UP;
                case STALLED_UPLOADING -> s == STALLED_UP;
                case STALLED_DOWNLOADING -> s == STALLED_DL;
                case CHECKING -> s == CHECKING_DL || s == CHECKING_UP;
                case MOVING -> s == QbitState.MOVING;
                case ERRORED -> s == QbitState.ERROR;
            };
        }
    }
}
