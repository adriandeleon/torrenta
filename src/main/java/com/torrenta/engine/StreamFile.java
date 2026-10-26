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

    /** Where the file starts in the torrent, in bytes: its pieces are counted from there. */
    long offset();

    /** The torrent's piece length. */
    long pieceLength();

    /** Which of the torrent's pieces {@code first} to {@code last} are there. */
    java.util.BitSet have(int first, int last) throws EngineException;

    /**
     * The read-ahead: each piece of the torrent wanted within so many milliseconds, ahead of what
     * is downloading otherwise. A piece already there is left alone.
     */
    void deadlines(java.util.Map<Integer, Integer> millisByPiece) throws EngineException;

    /** No read-ahead any more over pieces {@code first} to {@code last}: the last player has gone. */
    void clearDeadlines(int first, int last) throws EngineException;
}
