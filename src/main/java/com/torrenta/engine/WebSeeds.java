package com.torrenta.engine;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for a torrent's web seeds (BEP 19's {@code url-list}): what counts as one, and which
 * of a batch are new. Pure, so the engines and the window agree.
 */
public final class WebSeeds {

    private WebSeeds() {}

    /** Whether the text, trimmed, is a web seed: an absolute http or https URL with a host. */
    public static boolean valid(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        try {
            URI uri = new URI(u);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            return uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The candidates that are not web seeds, trimmed; blank ones are no candidates and left out. */
    public static List<String> invalid(List<String> candidates) {
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            String u = c == null ? "" : c.trim();
            if (!u.isEmpty() && !valid(u)) out.add(u);
        }
        return out;
    }

    /**
     * The candidates to add, in the order given: trimmed, blank and invalid ones left out, none
     * the torrent already has and none twice. Compared as written, as libtorrent compares them.
     */
    public static List<String> fresh(List<String> existing, List<String> candidates) {
        Set<String> have = Set.copyOf(existing);
        Set<String> out = new LinkedHashSet<>();
        for (String c : candidates) {
            String u = c == null ? "" : c.trim();
            if (valid(u) && !have.contains(u)) out.add(u);
        }
        return List.copyOf(out);
    }
}
