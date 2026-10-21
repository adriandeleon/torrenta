package com.torrenta.rss;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * One subscribed feed: a stable id (the articles file is named after it), its URL, what to call it
 * (blank: the feed's own title once fetched, else its host), and how often to ask it, in minutes,
 * 0 meaning the global interval.
 */
public record Feed(String id, String url, String name, int refreshMinutes) {

    /** The shortest interval a feed may ask for; the Settings page's floor too. */
    public static final int MIN_MINUTES = 5;

    public static final int MAX_MINUTES = 1440;

    private static final SecureRandom RANDOM = new SecureRandom();

    public Feed {
        id = id == null ? "" : id.trim();
        url = url == null ? "" : url.trim();
        name = name == null ? "" : name.trim();
        refreshMinutes = refreshMinutes <= 0 ? 0 : Math.clamp(refreshMinutes, MIN_MINUTES, MAX_MINUTES);
    }

    /** A new feed with a fresh id. */
    public static Feed of(String url, String name, int refreshMinutes) {
        return new Feed(newId(), url, name, refreshMinutes);
    }

    /** Twelve hex characters: unguessable enough, short enough for a file name. */
    public static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The name, else the URL's host, else the URL. */
    public String displayName() {
        if (!name.isEmpty()) return name;
        String host = host(url);
        return host.isEmpty() ? url : host;
    }

    /** The interval in force: this feed's own, else the global one. */
    public int effectiveMinutes(int globalMinutes) {
        return refreshMinutes > 0 ? refreshMinutes : Math.clamp(globalMinutes, MIN_MINUTES, MAX_MINUTES);
    }

    public Feed withName(String newName) {
        return new Feed(id, url, newName, refreshMinutes);
    }

    /** Why {@code url} cannot be a feed's, in a sentence; empty when it can. The dialog's rule. */
    public static Optional<String> problem(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return Optional.of("a URL is needed");
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            return Optional.of("not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            return Optional.of("the URL must start with http:// or https://");
        if (uri.getHost() == null || uri.getHost().isEmpty()) return Optional.of("the URL names no host");
        return Optional.empty();
    }

    static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
