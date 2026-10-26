package com.torrenta.http;

/**
 * A request the server answers with a status of its own rather than a handler's: a body past the
 * limit (413), a body or header that does not read (400). Thrown by the parsers, caught by {@link
 * WebServer}.
 */
public final class HttpProblem extends RuntimeException {

    private final int status;

    public HttpProblem(int status, String message) {
        super(message);
        this.status = status;
    }

    /** The message given, never null: it is the answer's body. */
    @Override
    public String getMessage() {
        return java.util.Objects.requireNonNullElse(super.getMessage(), "");
    }

    public int status() {
        return status;
    }

    static HttpProblem badRequest(String message) {
        return new HttpProblem(400, message);
    }

    static HttpProblem tooLarge(String message) {
        return new HttpProblem(413, message);
    }
}
