package com.torrenta.search;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import static com.torrenta.i18n.Messages.tr;

/**
 * One Torznab server as the Settings page lists it: Prowlarr's {@code http://host:9696/1/api},
 * Jackett's {@code http://host:9117/api/v2.0/indexers/all/results/torznab/api}, with its API key.
 *
 * <p>Kept in {@code search-providers.json} by the store. The key is a secret, so the file is this
 * machine's and never syncs. Pure; the validation the dialog applies is {@link #problem}.
 *
 * @param name what the results and the failure lines call it; empty falls back to the URL's host
 * @param url the API endpoint, {@code http} or {@code https}
 * @param apiKey the key, or empty for a server that asks none (Jackett with no key set)
 * @param enabled asked when searching; a disabled one is kept but left alone
 * @param categories how many categories the last Test found, or {@code -1} when never tested
 */
public record TorznabEndpoint(String name, String url, String apiKey, boolean enabled, int categories) {

    public TorznabEndpoint {
        url = url == null ? "" : url.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) name = hostOf(url);
        if (categories < 0) categories = -1;
    }

    /** A new, enabled, untested entry. */
    public TorznabEndpoint(String name, String url, String apiKey) {
        this(name, url, apiKey, true, -1);
    }

    /** The same entry with the count the Test found. */
    public TorznabEndpoint tested(String foundName, int foundCategories) {
        return new TorznabEndpoint(name.isEmpty() ? foundName : name, url, apiKey, enabled, foundCategories);
    }

    /** The key shown without giving it away: {@code ••••} or "none". */
    public String maskedKey() {
        return apiKey.isEmpty() ? tr("search.endpoint.noKey") : "••••••••";
    }

    /**
     * What is wrong with the fields as typed, or empty when they will do: the URL must be an
     * {@code http} or {@code https} URL with a host. A blank key is allowed (Jackett lets a server
     * run without one); a blank name takes the host.
     */
    public static Optional<String> problem(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return Optional.of(tr("search.endpoint.urlNeeded"));
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            return Optional.of(tr("search.endpoint.notAUrl"));
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
            return Optional.of(tr("search.endpoint.scheme"));
        if (uri.getHost() == null || uri.getHost().isEmpty()) return Optional.of(tr("search.endpoint.noHost"));
        return Optional.empty();
    }

    /** The URL's host, or empty when it has none. */
    public static String hostOf(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
