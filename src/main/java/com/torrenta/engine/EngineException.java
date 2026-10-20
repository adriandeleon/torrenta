package com.torrenta.engine;

/** The engine could not do what was asked; the message is fit to show the user. */
public class EngineException extends Exception {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
