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
 */
public record EngineEvent(Instant at, Level level, String torrentId, String torrent, String message) {

    public enum Level {
        INFO,
        WARNING,
        ERROR
    }

    public static EngineEvent info(String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), Level.INFO, torrentId, torrent, message);
    }

    public static EngineEvent warning(String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), Level.WARNING, torrentId, torrent, message);
    }

    public static EngineEvent error(String torrentId, String torrent, String message) {
        return new EngineEvent(Instant.now(), Level.ERROR, torrentId, torrent, message);
    }

    /** A session-wide event: no torrent. */
    public static EngineEvent session(Level level, String message) {
        return new EngineEvent(Instant.now(), level, null, "", message);
    }
}
