package com.torrenta.engine;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import static com.torrenta.i18n.Messages.tr;

/**
 * Which engine the window drives: libtorrent in this process, or a Transmission daemon reached
 * over RPC.
 *
 * <p>Kept in {@code profiles.json} in the config folder (owner-readable, never synced: the
 * password is a secret and the daemon's address is this machine's business), chosen by
 * {@code engine.profile} in the settings. {@link #LOCAL} is always there and is never written.
 * {@link #toString()} leaves the password out, so a profile may be logged.
 *
 * @param id stable, for the setting; {@code local} for the built-in one
 * @param name what the person called it; the window title says it when remote
 * @param kind local or remote
 * @param url the daemon's RPC URL, or empty for the local engine; {@code http://host:9091} is
 *     enough, the {@code /transmission/rpc} path is added when missing
 * @param username basic-auth user, or empty for a daemon without authentication
 * @param password basic-auth password, or empty
 */
public record EngineProfile(String id, String name, Kind kind, String url, String username, String password) {

    public static final String LOCAL_ID = "local";
    public static final EngineProfile LOCAL = new EngineProfile(LOCAL_ID, "", Kind.LOCAL, "", "", "");

    /** Transmission's conventional RPC path. */
    public static final String RPC_PATH = "/transmission/rpc";

    public enum Kind {
        LOCAL("local"),
        REMOTE("remote");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** The kind's word in the interface language, read at call time. */
        public String label() {
            return tr("profile.kind." + label);
        }
    }

    public EngineProfile {
        id = id == null ? "" : id.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("a profile needs an id");
        name = name == null ? "" : name.trim();
        if (kind == null) kind = Kind.REMOTE;
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
    }

    public boolean isLocal() {
        return kind == Kind.LOCAL;
    }

    /** The name as shown: the person's, or the local engine's word when it has none. */
    public String displayName() {
        if (!name.isEmpty()) return name;
        return isLocal() ? tr("profile.local.name") : url;
    }

    /** The RPC endpoint: the URL as typed, with {@value #RPC_PATH} added when it names only a host. */
    public URI rpcUri() {
        URI u = URI.create(url);
        String path = u.getRawPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) + RPC_PATH : url + RPC_PATH);
        }
        return u;
    }

    /** The host and port, for titles and messages. */
    public String hostLine() {
        try {
            URI u = URI.create(url);
            if (u.getHost() == null) return url;
            return u.getPort() < 0 ? u.getHost() : u.getHost() + ":" + u.getPort();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /**
     * Why a URL cannot be a daemon's, in the interface language, or empty when it can: the
     * dialog's rule. Pure.
     */
    public static Optional<String> problem(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return Optional.of(tr("profile.problem.empty"));
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
            return Optional.of(tr("profile.problem.scheme"));
        try {
            URI parsed = URI.create(u);
            if (parsed.getHost() == null || parsed.getHost().isEmpty()) return Optional.of(tr("profile.problem.host"));
        } catch (IllegalArgumentException e) {
            return Optional.of(tr("profile.problem.invalid"));
        }
        return Optional.empty();
    }

    public EngineProfile withPassword(String secret) {
        return new EngineProfile(id, name, kind, url, username, secret);
    }

    /** A fresh id for a new remote profile. */
    public static String newId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(
                        java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 20), 36);
    }

    /** Never the password. */
    @Override
    public String toString() {
        return isLocal()
                ? "local"
                : name + " <" + url + ">" + (username.isEmpty() ? "" : " as " + username)
                        + (password.isEmpty() ? "" : " (password set)");
    }
}
