package com.torrenta.engine;

import java.util.List;

/**
 * The BitTorrent engine, as the interface sees it.
 *
 * <p>Deliberately small: start, add, look, stop. The first implementation is libtorrent through
 * libtorrent4j; a second one could be a Transmission daemon over RPC, which is why nothing here
 * leaks a libtorrent type. Every method may block for a few milliseconds and none is safe on the FX
 * thread.
 */
public interface Engine extends AutoCloseable {

    /** Loads whatever the engine needs and opens the session. Slow; call off the FX thread. */
    void start() throws EngineException;

    /** Engine name and version, for the status bar and bug reports. Valid after {@link #start()}. */
    String description();

    /** Adds a {@code .torrent} file (a path) or a {@code magnet:} link. */
    Torrent add(String input) throws EngineException;

    /** A snapshot of every torrent in the session. */
    List<Torrent> torrents();

    /** Session-wide payload rates. */
    Rates rates();

    void stop();

    @Override
    default void close() {
        stop();
    }

    /** Bytes per second, session-wide. */
    record Rates(long download, long upload) {
        public static final Rates ZERO = new Rates(0, 0);
    }
}
