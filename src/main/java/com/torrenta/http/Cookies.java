package com.torrenta.http;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Cookies both ways: a request's {@code Cookie} header read into names and values, and a {@code
 * Set-Cookie} written. A session cookie is always {@code HttpOnly}, {@code SameSite=Strict} and
 * {@code Path=/}; {@code Secure} over HTTPS. Pure.
 */
public final class Cookies {

    private Cookies() {}

    /** {@code a=1; b=2} into its pairs, the first of a repeated name kept. */
    public static Map<String, String> parse(@Nullable String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null) return out;
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                value = value.substring(1, value.length() - 1);
            out.putIfAbsent(name, value);
        }
        return out;
    }

    /**
     * A {@code Set-Cookie} value. {@code maxAgeSeconds} below zero leaves it a session cookie;
     * zero tells the client to forget it.
     */
    public static String set(String name, String value, long maxAgeSeconds, boolean secure) {
        if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("cookie name: " + name);
        if (!value.matches("[A-Za-z0-9_.~+/=-]*")) throw new IllegalArgumentException("cookie value");
        StringBuilder sb =
                new StringBuilder(name).append('=').append(value).append("; Path=/; HttpOnly; SameSite=Strict");
        if (maxAgeSeconds >= 0) sb.append("; Max-Age=").append(maxAgeSeconds);
        if (secure) sb.append("; Secure");
        return sb.toString();
    }
}
