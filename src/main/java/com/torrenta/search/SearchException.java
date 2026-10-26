package com.torrenta.search;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** A provider could not answer; the message is what a status line says, "timed out" and the like. */
public final class SearchException extends Exception {

    public SearchException(String message) {
        super(message);
    }

    /** A null {@code message} (an exception's own, passed on) says the cause instead. */
    public SearchException(@Nullable String message, Throwable cause) {
        super(message != null ? message : String.valueOf(cause), cause);
    }

    /** Never null: it is shown. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), "");
    }
}
