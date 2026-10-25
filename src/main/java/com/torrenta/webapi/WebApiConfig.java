package com.torrenta.webapi;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import com.torrenta.net.InterfaceWatch;

import static com.torrenta.i18n.Messages.tr;

/**
 * Remote access as Settings → Remote access sets it, the {@code webapi.*} keys: all of them this
 * machine's, never synced. Off until switched on, and on {@code 127.0.0.1} until an address is
 * chosen; the address is a literal (an interface's, or {@code 0.0.0.0}/{@code ::} for every one),
 * never a name to resolve. Pure.
 *
 * @param passwordHash {@link Passwords#hash}'s text; empty when no password was set, and then no
 *     one can log in, so the server does not start
 * @param httpsFile a PKCS#12 file, or null for plain HTTP
 * @param domains the names besides addresses and {@code localhost} that the {@code Host} header may
 *     carry, lower case; {@code *} for any
 */
public record WebApiConfig(
        boolean enabled,
        String address,
        int port,
        String username,
        String passwordHash,
        Path httpsFile,
        String httpsPassword,
        Set<String> domains) {

    public static final String DEFAULT_ADDRESS = "127.0.0.1";
    /** qBittorrent's, so the *arr apps and the phone apps connect with no edits. */
    public static final int DEFAULT_PORT = 8080;

    public static final String DEFAULT_USERNAME = "admin";

    public static final WebApiConfig OFF =
            new WebApiConfig(false, DEFAULT_ADDRESS, DEFAULT_PORT, DEFAULT_USERNAME, "", null, "", Set.of());

    public WebApiConfig {
        address = address == null || address.isBlank() ? DEFAULT_ADDRESS : address.trim();
        username = username == null ? "" : username;
        passwordHash = passwordHash == null ? "" : passwordHash;
        httpsPassword = httpsPassword == null ? "" : httpsPassword;
        domains = Set.copyOf(domains);
    }

    /** The address to bind, when it reads as one. */
    public Optional<InetAddress> bindAddress() {
        return InterfaceWatch.literal(address);
    }

    public boolean https() {
        return httpsFile != null;
    }

    /**
     * Reachable from other machines over plain HTTP: the password and the session cookie cross
     * the network readable. The page warns.
     */
    public boolean exposed() {
        return !https() && bindAddress().map(a -> !a.isLoopbackAddress()).orElse(false);
    }

    /** Why it cannot start as set; empty when it can. */
    public Optional<String> problem() {
        if (bindAddress().isEmpty()) return Optional.of(tr("webapi.problem.address", address));
        if (username.isBlank()) return Optional.of(tr("webapi.problem.username"));
        if (!Passwords.isSet(passwordHash)) return Optional.of(tr("webapi.problem.password"));
        if (httpsFile != null && !Files.isRegularFile(httpsFile))
            return Optional.of(tr("webapi.problem.httpsFile", httpsFile));
        return Optional.empty();
    }

    /** Whether going from {@code before} to this needs a new listening socket. */
    boolean rebinds(WebApiConfig before) {
        return before == null
                || !address.equals(before.address)
                || port != before.port
                || !java.util.Objects.equals(httpsFile, before.httpsFile)
                || !httpsPassword.equals(before.httpsPassword);
    }

    /** Whether the people who may log in changed, so those logged in are logged out. */
    boolean credentialsChanged(WebApiConfig before) {
        return before == null || !username.equals(before.username) || !passwordHash.equals(before.passwordHash);
    }

    /** Where a client finds it: the address as a browser would write it, any-address as localhost. */
    public String url() {
        String host = bindAddress()
                .map(a -> a.isAnyLocalAddress() ? "localhost" : a.getHostAddress())
                .orElse(address);
        if (host.contains(":")) host = "[" + host + "]";
        return (https() ? "https" : "http") + "://" + host + ":" + port + "/";
    }

    /** Never the password's hash or the HTTPS password. */
    @Override
    public String toString() {
        return "WebApiConfig[enabled=" + enabled + ", address=" + address + ", port=" + port + ", username=" + username
                + ", https=" + https() + ", domains=" + domains + "]";
    }
}
