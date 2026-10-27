package com.torrenta.rss;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** A feed could not be fetched or read; the message is what the feed's status line shows. */
public final class FeedException extends Exception {

    public FeedException(String message) {
        super(message);
    }

    /** A null {@code message} (an exception's own, passed on) says the cause instead. */
    public FeedException(@Nullable String message, Throwable cause) {
        super(message != null ? message : String.valueOf(cause), cause);
    }

    /** Never null: it is shown. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), "");
    }
}
