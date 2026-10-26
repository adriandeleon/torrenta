package com.torrenta.engine;

import java.nio.file.Path;
import java.time.Duration;

/**
 * One file of a torrent, read while the torrent downloads ({@link Engine#stream}): what the
 * stream server serves a player from. The bytes are read from the file on disk, where the engine
 * writes them; before a range is read, {@link #await} makes sure its pieces are there, asking for
 * them first, ahead of everything else.
 */
public interface StreamFile {

    /** The file's own name, without its folders: for the URL, and the content type. */
    String name();

    /** Its size in bytes. */
    long size();

    /** How many of its bytes from {@code from} on are worth waiting for at once: up to a piece's end. */
    long chunkEnd(long from);

    /**
     * Blocks until bytes {@code from} to {@code to} (inclusive) are on disk, the missing pieces
     * asked for first with a deadline; false when they are not there within {@code timeout}.
     */
    boolean await(long from, long to, Duration timeout) throws EngineException, InterruptedException;

    /** Where the file is on disk now: its recorded name, with the {@code .part} suffix while it has one. */
    Path path() throws EngineException;
}
