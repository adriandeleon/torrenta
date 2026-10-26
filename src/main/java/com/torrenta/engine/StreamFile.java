package com.torrenta.engine;

import java.time.Duration;

/**
 * One file of a torrent, read while the torrent downloads ({@link Engine#stream}): what the
 * stream server serves a player from. Before a range is read, {@link #await} makes sure its pieces
 * are there, asking for them first, ahead of everything else; then {@link #read} hands the bytes
 * over through the engine, not from the file on disk: a piece is said to be there once it is
 * verified, which can be before its bytes reach the file (measured on Windows: zeros right after
 * the piece's alert).
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

    /**
     * Bytes {@code from} to {@code to} (inclusive), which lie in one piece ({@link #chunkEnd}) that
     * {@link #await} has said is there.
     */
    byte[] read(long from, long to) throws EngineException, InterruptedException;
}
