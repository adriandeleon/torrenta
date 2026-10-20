package com.torrenta.engine;

import java.time.Instant;

/**
 * Something the engine wants a person to know: a torrent added, finished or in trouble, a port
 * that could not be opened. Engine-neutral; a Transmission backend would raise the same kinds.
 *
 * @param at when it happened
 * @param level how much it matters
 * @param torrentId the info-hash, or null for a session-wide event
 * @param torrent the torrent's name, or empty
 * @param message one line, fit for the status bar
 * @param kind what happened, for the automation that runs on some kinds; {@link Kind#OTHER} for
 *     the rest
 */
public record EngineEvent(Instant at, Level level, String torrentId, String torrent, String message, Kind kind) {

    public enum Level {
        INFO,
        WARNING,
        ERROR
    }

    /**
     * The events a person can act on automatically. {@code ADDED} is a torrent new to the session,
     * never one restored at start; {@code FINISHED} is the download complete; {@code SEEDING_DONE}
     * is the share limit acting; {@code ERROR} is a torrent, file or tracker error.
     */
    public enum Kind {
        ADDED,
        FINISHED,
        SEEDING_DONE,
        ERROR,
        OTHER
    }

    public EngineEvent(Instant at, Level level, String torrentId, String torrent, String message) {
        this(at, level, torrentId, torrent, message, Kind.OTHER);
    }

    public EngineEvent {
        if (kind == null) kind = Kind.OTHER;
    }

    public static EngineEvent info(String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), Level.INFO, torrentId, torrent, message);
    }

    public static EngineEvent warning(String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), Level.WARNING, torrentId, torrent, message);
    }

    public static EngineEvent error(String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), Level.ERROR, torrentId, torrent, message, Kind.ERROR);
    }

    /** A session-wide event: no torrent. */
    public static EngineEvent session(Level level, String message) {
        return new EngineEvent(Instant.now(), level, null, "", message);
    }

    /** An event of a kind the automation acts on. */
    public static EngineEvent of(Kind kind, Level level, String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), level, torrentId, torrent, message, kind);
    }
}
