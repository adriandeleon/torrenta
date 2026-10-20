package com.torrenta.engine;

import java.net.URI;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The host of an announce URL, which is what identifies a tracker to a person: the sidebar lists
 * one entry per host, the Tracker column shows it. Pure.
 */
public final class TrackerHost {

    private TrackerHost() {}

    /**
     * {@code udp://tracker.example.org:6969/announce} is {@code tracker.example.org}; an IPv6
     * literal keeps its brackets. A URL with no host (unparseable, or no scheme) is taken whole,
     * minus any scheme and path, so a count still lands somewhere; blank is empty.
     */
    public static String of(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        try {
            String host = URI.create(u).getHost();
            if (host != null && !host.isEmpty()) return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            // fall through to the manual cut
        }
        int scheme = u.indexOf("://");
        String rest = scheme < 0 ? u : u.substring(scheme + 3);
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int i = rest.indexOf(stop);
            if (i >= 0 && i < end) end = i;
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (!authority.startsWith("[")) {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0 && authority.substring(colon + 1).chars().allMatch(Character::isDigit))
                authority = authority.substring(0, colon);
        }
        return authority.toLowerCase(Locale.ROOT);
    }

    /** The hosts of the URLs, each once, sorted, blanks left out. */
    public static java.util.List<String> hosts(java.util.Collection<String> urls) {
        TreeSet<String> out = new TreeSet<>();
        for (String url : urls) {
            String host = of(url);
            if (!host.isEmpty()) out.add(host);
        }
        return java.util.List.copyOf(out);
    }
}
