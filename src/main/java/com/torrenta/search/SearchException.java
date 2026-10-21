package com.torrenta.search;

/** A provider could not answer; the message is what a status line says, "timed out" and the like. */
public final class SearchException extends Exception {

    public SearchException(String message) {
        super(message);
    }

    public SearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
