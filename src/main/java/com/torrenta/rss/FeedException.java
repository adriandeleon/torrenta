package com.torrenta.rss;

/** A feed could not be fetched or read; the message is what the feed's status line shows. */
public final class FeedException extends Exception {

    public FeedException(String message) {
        super(message);
    }

    public FeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
