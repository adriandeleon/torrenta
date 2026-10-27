package com.torrenta.webapi;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.torrenta.net.InterfaceWatch;
import org.jspecify.annotations.Nullable;

/**
 * Two checks a request passes before anything else. The {@code Host} header must name this server
 * as an address, {@code localhost}, or a domain the person listed: a page on an attacker's domain
 * that resolves to 127.0.0.1 (DNS rebinding) sends its own name, and is refused. And a browser's
 * {@code Origin}, or its {@code Referer} when there is no {@code Origin}, must be this same host
 * and port, so a page elsewhere cannot post to the API with the person's cookie; a client that
 * sends neither (the *arr apps, a phone app) is not a browser on another page. Pure.
 */
public final class Hosts {

    private Hosts() {}

    /**
     * Whether {@code hostHeader} names this server.
     *
     * @param domains extra names it answers to, lower case; {@code *} for any
     */
    public static boolean hostAllowed(@Nullable String hostHeader, Set<String> domains) {
        if (hostHeader == null || hostHeader.isBlank()) return false;
        String host = hostOf(hostHeader.trim()).toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return false;
        if (domains.contains("*") || domains.contains(host)) return true;
        return host.equals("localhost") || InterfaceWatch.literal(host).isPresent();
    }

    /** Whether the browser's page, when it says, is this server itself. */
    public static boolean sameOrigin(Optional<String> origin, Optional<String> referer, @Nullable String hostHeader) {
        Optional<String> from = origin.isPresent() ? origin : referer;
        if (from.isEmpty()) return true;
        if (hostHeader == null) return false;
        try {
            URI uri = URI.create(from.get().trim());
            if (uri.getHost() == null) return false;
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return host.equals(hostOf(hostHeader.trim()).toLowerCase(Locale.ROOT))
                    && port == portOf(hostHeader.trim(), scheme.equals("https") ? 443 : 80);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The name in {@code host[:port]}, an IPv6 literal without its brackets. */
    static String hostOf(String hostHeader) {
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf(']');
            return close < 0 ? "" : hostHeader.substring(1, close);
        }
        int colon = hostHeader.lastIndexOf(':');
        // More than one colon without brackets is a bare IPv6 address, all of it the host.
        if (colon < 0 || hostHeader.indexOf(':') != colon) return hostHeader;
        return hostHeader.substring(0, colon);
    }

    static int portOf(String hostHeader, int fallback) {
        int close = hostHeader.startsWith("[") ? hostHeader.indexOf(']') : -1;
        int colon = hostHeader.lastIndexOf(':');
        if (colon < 0 || colon < close || (close < 0 && hostHeader.indexOf(':') != colon)) return fallback;
        try {
            return Integer.parseInt(hostHeader.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
