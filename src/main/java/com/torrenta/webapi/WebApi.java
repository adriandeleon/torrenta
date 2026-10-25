package com.torrenta.webapi;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.torrenta.engine.Category;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.http.Cookies;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.http.Router;

/**
 * qBittorrent's WebUI API at {@code /api/v2}, as far as Torrenta speaks it (roadmap 1.2, item 4:
 * Tier 1, the *arr apps). The routes are thin over a {@link Backend}; this class holds the doors:
 *
 * <ul>
 *   <li>the {@code Host} header must name this server ({@link Hosts#hostAllowed}), or a page on a
 *       rebound name could reach it;
 *   <li>a browser's {@code Origin}/{@code Referer} must be this server ({@link Hosts#sameOrigin}),
 *       or a page elsewhere could post with the person's cookie;
 *   <li>every endpoint but {@code auth/login} wants a live {@code SID} cookie ({@link Sessions}),
 *       from any address, loopback included: there is no bypass;
 *   <li>a login is checked against the PBKDF2 hash ({@link Passwords}), and five failures in a row
 *       ban the address for an hour ({@link LoginGuard}).
 * </ul>
 *
 * Answers are qBittorrent 5.0's, word for word where a client reads them: {@code Ok.}/{@code
 * Fails.}, 403 for a request without a session and for a banned address, 401 for a failed host
 * or origin check.
 */
public final class WebApi {

    /** What the routes read and do; the running session's, or a test's. */
    public interface Backend {
        /** The engine's settings as this machine has them: the preferences come from here. */
        EngineSettings engineSettings();

        /** Where a torrent added without a save path goes. */
        Path defaultSavePath();

        /** The torrents now, the last poll's; empty while no engine is up. */
        List<Torrent> torrents();

        /** One torrent's detail; empty when it is gone or no engine is up. */
        Optional<TorrentDetail> detail(String id);

        /** The torrent as a magnet link; empty when the engine cannot say. */
        String magnet(String id);

        /** Its own rate caps; {@link TorrentLimits#NONE} when it is gone. */
        TorrentLimits limits(String id);

        /** The share limit set on this torrent alone, if one is. */
        Optional<ShareLimit> ownShare(String id);

        /** The share limit in force for it: its own, else the global one. */
        ShareLimit share(Torrent t);

        /** When it last moved a byte either way, epoch seconds; its completion or addition before that. */
        long lastActivity(Torrent t);

        List<Category> categories();

        List<String> tags();

        /** {@code transfer/info}'s facts. */
        Transfer transfer();
    }

    /**
     * The session's traffic as {@code transfer/info} writes it: rates and limits in bytes per
     * second (0 for no limit), this session's totals in bytes, and qBittorrent's word for the
     * connection ({@code connected}, {@code firewalled}, {@code disconnected}).
     */
    public record Transfer(
            long downloadRate,
            long uploadRate,
            long downloadedSession,
            long uploadedSession,
            long downloadLimit,
            long uploadLimit,
            long dhtNodes,
            String connection) {}

    /** qBittorrent's own version, the one the *arr apps check against; 5.0 is the level spoken. */
    public static final String VERSION = "v5.0.0";

    public static final String API_VERSION = "2.11";

    static final String PREFIX = "/api/v2/";
    static final String COOKIE = "SID";

    private static final JsonFactory JSON = new JsonFactory();

    private final Backend backend;
    private final LongSupplier clock;
    private final Sessions sessions;
    private final LoginGuard guard = new LoginGuard();
    private volatile WebApiConfig config;

    public WebApi(Backend backend, WebApiConfig config, Sessions sessions, LongSupplier clock) {
        this.backend = backend;
        this.config = config;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** New credentials or names: whoever is logged in under the old ones is logged out. */
    public void update(WebApiConfig next) {
        WebApiConfig before = config;
        config = next;
        if (next.credentialsChanged(before)) sessions.closeAll();
    }

    /** The routes with the doors in front of them. */
    public Router router() {
        Router router =
                new Router().filter(this::checkHost).filter(this::checkOrigin).filter(this::checkSession);
        router.route("POST", PREFIX + "auth/login", this::login);
        router.route("POST", PREFIX + "auth/logout", this::logout);
        read(router, "app/version", r -> Response.text(200, VERSION));
        read(router, "app/webapiVersion", r -> Response.text(200, API_VERSION));
        read(
                router,
                "app/defaultSavePath",
                r -> Response.text(200, backend.defaultSavePath().toString()));
        read(
                router,
                "app/preferences",
                r -> Response.json(preferences(backend.engineSettings(), backend.defaultSavePath())));
        Reads reads = new Reads(backend, clock);
        read(router, "torrents/info", reads::info);
        read(router, "torrents/properties", reads::properties);
        read(router, "torrents/files", reads::files);
        read(router, "torrents/categories", reads::categories);
        read(router, "torrents/tags", reads::tags);
        read(router, "transfer/info", reads::transfer);
        return router;
    }

    /** qBittorrent answers its reading endpoints to GET and POST alike; clients use both. */
    private static void read(Router router, String path, Router.Handler handler) {
        router.route("GET", PREFIX + path, handler);
        router.route("POST", PREFIX + path, handler);
    }

    private Optional<Response> checkHost(Request request) {
        if (Hosts.hostAllowed(request.header("host").orElse(null), config.domains())) return Optional.empty();
        return Optional.of(Response.text(401, "Unauthorized"));
    }

    private Optional<Response> checkOrigin(Request request) {
        if (Hosts.sameOrigin(
                request.header("origin"),
                request.header("referer"),
                request.header("host").orElse(null))) return Optional.empty();
        return Optional.of(Response.text(401, "Unauthorized"));
    }

    private Optional<Response> checkSession(Request request) {
        if (request.path().equals(PREFIX + "auth/login")) return Optional.empty();
        if (sessions.valid(request.cookies().get(COOKIE), clock.getAsLong())) return Optional.empty();
        return Optional.of(Response.text(403, "Forbidden"));
    }

    private Response login(Request request) {
        String address = request.remote().getAddress().getHostAddress();
        long now = clock.getAsLong();
        if (!guard.allowed(address, now))
            return Response.text(403, "Your IP address has been banned after too many failed authentication attempts.");
        WebApiConfig c = config;
        String username = request.form().first("username").orElse("");
        char[] password = request.form().first("password").orElse("").toCharArray();
        // The password is checked whatever the name, so a wrong name takes as long as a wrong password.
        boolean passwordOk = Passwords.verify(password, c.passwordHash());
        java.util.Arrays.fill(password, '\0');
        boolean nameOk = MessageDigest.isEqual(
                username.getBytes(StandardCharsets.UTF_8), c.username().getBytes(StandardCharsets.UTF_8));
        if (!passwordOk || !nameOk) {
            guard.failed(address, now);
            return Response.text(200, "Fails.");
        }
        guard.succeeded(address);
        sessions.close(request.cookies().get(COOKIE));
        return Response.text(200, "Ok.").withCookie(Cookies.set(COOKIE, sessions.open(now), -1, request.secure()));
    }

    private Response logout(Request request) {
        sessions.close(request.cookies().get(COOKIE));
        return Response.empty(200).withCookie(Cookies.set(COOKIE, "", 0, request.secure()));
    }

    /**
     * {@code app/preferences}: the keys the *arr apps read, from the engine's settings. A limit
     * that is not set is qBittorrent's {@code -1} with its {@code _enabled} false; queueing is on
     * when any active count is limited.
     */
    static String preferences(EngineSettings settings, Path savePath) {
        EngineSettings.Queue queue = settings.queue();
        ShareLimit share = queue.share();
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("save_path", savePath.toString());
            g.writeBooleanField(
                    "queueing_enabled",
                    queue.activeDownloads() > 0 || queue.activeSeeds() > 0 || queue.activeLimit() > 0);
            g.writeNumberField("max_active_downloads", orUnlimited(queue.activeDownloads()));
            g.writeNumberField("max_active_uploads", orUnlimited(queue.activeSeeds()));
            g.writeNumberField("max_active_torrents", orUnlimited(queue.activeLimit()));
            g.writeBooleanField("max_ratio_enabled", share.ratio() > 0);
            g.writeNumberField("max_ratio", share.ratio() > 0 ? share.ratio() : -1);
            g.writeBooleanField("max_seeding_time_enabled", share.seedingMinutes() > 0);
            g.writeNumberField("max_seeding_time", share.seedingMinutes() > 0 ? share.seedingMinutes() : -1);
            g.writeBooleanField("max_inactive_seeding_time_enabled", false);
            g.writeNumberField("max_inactive_seeding_time", -1);
            g.writeNumberField("max_ratio_act", ratioAction(share.action()));
            g.writeBooleanField("dht", settings.protocol().dht());
            g.writeBooleanField("pex", settings.protocol().pex());
            g.writeBooleanField("lsd", settings.protocol().lsd());
            g.writeNumberField("listen_port", settings.listenPort());
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static int orUnlimited(int count) {
        return count > 0 ? count : -1;
    }

    /** qBittorrent's numbers: 0 stop, 1 remove, 2 super seeding, 3 remove with the files. */
    static int ratioAction(ShareLimit.Action action) {
        return switch (action) {
            case PAUSE -> 0;
            case REMOVE -> 1;
            case SUPER_SEED -> 2;
            case REMOVE_DATA -> 3;
        };
    }
}
