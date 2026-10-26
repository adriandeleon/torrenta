package com.torrenta.engine;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** The engine could not do what was asked; the message is fit to show the user. */
public class EngineException extends Exception {

    public EngineException(String message) {
        super(message);
    }

    /** A null {@code message} (an exception's own, passed on) says the cause instead. */
    public EngineException(@Nullable String message, Throwable cause) {
        super(message != null ? message : String.valueOf(cause), cause);
    }

    /** Never null: it is shown. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), "");
    }
}
