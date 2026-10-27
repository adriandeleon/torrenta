package com.torrenta.webapi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.torrenta.engine.ShareLimit;
import com.torrenta.http.Form;
import com.torrenta.http.Limits;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.http.Router;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API's doors and its {@code auth}/{@code app} answers, through the router and over a real socket. */
class WebApiTest {

    /** A cheap hash: the iterations are read off the stored text, so a test need not pay 600,000. */
    private static String hash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Passwords.encode(1000, salt, Passwords.derive(password.toCharArray(), salt, 1000));
    }

    private static final String HASH = hash("s3cret");

    private static WebApiConfig config(String passwordHash) {
        return new WebApiConfig(true, "127.0.0.1", 0, "admin", passwordHash, null, "", Set.of());
    }

    private static final WebApi.Backend BACKEND = backend();

    private static FakeBackend backend() {
        FakeBackend b = new FakeBackend();
        b.store.set(com.torrenta.store.Settings.SHARE_RATIO, "2.0");
        b.store.set(com.torrenta.store.Settings.SHARE_ACTION, "REMOVE");
        return b;
    }

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final WebApi api = new WebApi(BACKEND, config(HASH), new Sessions(new SecureRandom()), now::get);
    private final Router router = api.router();

    private static Request request(String method, String path, String body, Map<String, String> headers) {
        return request(method, path, body, headers, "127.0.0.1");
    }

    private static Request request(String method, String path, String body, Map<String, String> headers, String from) {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("127.0.0.1:8080"));
        if (body != null) h.put("content-type", List.of("application/x-www-form-urlencoded"));
        headers.forEach((k, v) -> h.put(k, List.of(v)));
        try {
            return new Request(
                    method,
                    path,
                    Form.EMPTY,
                    h,
                    new InetSocketAddress(InetAddress.getByName(from), 40000),
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                    Limits.DEFAULT,
                    false);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Response login(String user, String password, String from) throws Exception {
        return router.dispatch(
                request("POST", "/api/v2/auth/login", "username=" + user + "&password=" + password, Map.of(), from));
    }

    private static String text(Response r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static Optional<String> header(Response r, String name) {
        return r.headers().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** The {@code SID=…} pair a login set, for the next request's {@code Cookie}. */
    private static String sid(Response r) {
        return header(r, "Set-Cookie").orElseThrow().split(";")[0];
    }

    @Test
    void aGoodLoginHandsOutAStrictCookieThatOpensTheRest() throws Exception {
        Response ok = login("admin", "s3cret", "127.0.0.1");
        assertEquals(200, ok.status());
        assertEquals("Ok.", text(ok));
        String cookie = header(ok, "Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith("SID="), cookie);
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
        Response version = router.dispatch(request("GET", "/api/v2/app/version", null, Map.of("cookie", sid(ok))));
        assertEquals(200, version.status());
        assertEquals("v5.0.0", text(version));
        assertEquals(
                "2.11",
                text(router.dispatch(request("POST", "/api/v2/app/webapiVersion", null, Map.of("cookie", sid(ok))))));
        assertEquals(
                FakeBackend.abs("/srv/downloads").toString(),
                text(router.dispatch(request("GET", "/api/v2/app/defaultSavePath", null, Map.of("cookie", sid(ok))))));
    }

    /** No localhost bypass: a request from this very machine without a session is refused. */
    @Test
    void everyEndpointButLoginWantsASessionEvenFromLoopback() throws Exception {
        for (String path : List.of("app/version", "app/preferences", "app/defaultSavePath", "auth/logout")) {
            String method = path.startsWith("auth") ? "POST" : "GET";
            Response r = router.dispatch(request(method, "/api/v2/" + path, null, Map.of()));
            assertEquals(403, r.status(), path);
        }
        Response forged = router.dispatch(request("GET", "/api/v2/app/version", null, Map.of("cookie", "SID=made-up")));
        assertEquals(403, forged.status());
    }

    @Test
    void aWrongNameOrPasswordFailsAndTheFifthBansTheAddressForAnHour() throws Exception {
        assertEquals("Fails.", text(login("admin", "wrong", "192.0.2.7")));
        assertEquals("Fails.", text(login("root", "s3cret", "192.0.2.7")), "the right password under another name");
        for (int i = 0; i < 3; i++) login("admin", "wrong", "192.0.2.7");
        Response banned = login("admin", "s3cret", "192.0.2.7");
        assertEquals(403, banned.status(), "even the right password, once banned");
        assertTrue(text(banned).contains("banned"));
        assertEquals("Ok.", text(login("admin", "s3cret", "192.0.2.8")), "another address is not touched");
        now.addAndGet(LoginGuard.BAN_MILLIS);
        assertEquals("Ok.", text(login("admin", "s3cret", "192.0.2.7")), "an hour later");
    }

    /** DNS rebinding: a page on a name that resolves to 127.0.0.1 sends its own name, and gets nothing. */
    @Test
    void aRequestForAnotherNameIsRefusedBeforeLogin() throws Exception {
        Response r = router.dispatch(request(
                "POST",
                "/api/v2/auth/login",
                "username=admin&password=s3cret",
                Map.of("host", "attacker.example:8080")));
        assertEquals(401, r.status());
        assertTrue(header(r, "Set-Cookie").isEmpty());
    }

    /** A page on another site posting with the person's cookie: its Origin gives it away. */
    @Test
    void aBrowserPageElsewhereCannotUseTheSession() throws Exception {
        String sid = sid(login("admin", "s3cret", "127.0.0.1"));
        Response r = router.dispatch(request(
                "GET", "/api/v2/app/version", null, Map.of("cookie", sid, "origin", "https://attacker.example")));
        assertEquals(401, r.status());
        Response referred = router.dispatch(request(
                "POST",
                "/api/v2/auth/login",
                "username=admin&password=s3cret",
                Map.of("referer", "http://attacker.example/page")));
        assertEquals(401, referred.status());
        Response same = router.dispatch(
                request("GET", "/api/v2/app/version", null, Map.of("cookie", sid, "origin", "http://127.0.0.1:8080")));
        assertEquals(200, same.status(), "the server's own page is fine");
    }

    @Test
    void logoutEndsTheSessionAndANewPasswordEndsThemAll() throws Exception {
        String a = sid(login("admin", "s3cret", "127.0.0.1"));
        Response out = router.dispatch(request("POST", "/api/v2/auth/logout", null, Map.of("cookie", a)));
        assertEquals(200, out.status());
        assertTrue(header(out, "Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(
                403,
                router.dispatch(request("GET", "/api/v2/app/version", null, Map.of("cookie", a)))
                        .status());

        String b = sid(login("admin", "s3cret", "127.0.0.1"));
        api.update(config(HASH));
        assertEquals(
                200,
                router.dispatch(request("GET", "/api/v2/app/version", null, Map.of("cookie", b)))
                        .status(),
                "the same credentials keep their sessions");
        api.update(config(hash("another")));
        assertEquals(
                403,
                router.dispatch(request("GET", "/api/v2/app/version", null, Map.of("cookie", b)))
                        .status());
        assertEquals("Fails.", text(login("admin", "s3cret", "127.0.0.1")));
        assertEquals("Ok.", text(login("admin", "another", "127.0.0.1")));
    }

    @Test
    void loginIsAPostOnly() throws Exception {
        Response r = router.dispatch(request("GET", "/api/v2/auth/login", null, Map.of()));
        assertEquals(405, r.status());
    }

    /** The keys the *arr apps read, in qBittorrent's shapes: -1 and false for a limit that is not set. */
    @Test
    void thePreferencesAreTheEngineSettingsInQbittorrentsWords() throws Exception {
        String sid = sid(login("admin", "s3cret", "127.0.0.1"));
        Response r = router.dispatch(request("GET", "/api/v2/app/preferences", null, Map.of("cookie", sid)));
        assertEquals(200, r.status());
        assertEquals("application/json", header(r, "Content-Type").orElseThrow());
        String json = text(r);
        String saved = FakeBackend.abs("/srv/downloads").toString().replace("\\", "\\\\");
        assertTrue(json.contains("\"save_path\":\"" + saved + "\""), json);
        assertTrue(json.contains("\"queueing_enabled\":true"), json);
        assertTrue(json.contains("\"max_ratio_enabled\":true"), json);
        assertTrue(json.contains("\"max_ratio\":2.0"), json);
        assertTrue(json.contains("\"max_seeding_time_enabled\":false"), json);
        assertTrue(json.contains("\"max_seeding_time\":-1"), json);
        assertTrue(json.contains("\"max_ratio_act\":1"), json);
        assertTrue(json.contains("\"dht\":true"), json);

        com.torrenta.store.Settings unqueued = new FakeBackend().store;
        unqueued.set(com.torrenta.store.Settings.QUEUE_ACTIVE_DOWNLOADS, "0");
        unqueued.set(com.torrenta.store.Settings.QUEUE_ACTIVE_SEEDS, "0");
        unqueued.set(com.torrenta.store.Settings.QUEUE_ACTIVE_LIMIT, "0");
        Map<String, Object> none = Preferences.read(unqueued, Path.of("/x"));
        assertEquals(false, none.get("queueing_enabled"));
        assertEquals(false, none.get("max_ratio_enabled"));
        assertEquals(-1.0, none.get("max_ratio"), "a ratio is a double");
    }

    @Test
    void theRatioActionsAreQbittorrentsNumbers() {
        assertEquals(0, WebApi.ratioAction(ShareLimit.Action.PAUSE));
        assertEquals(1, WebApi.ratioAction(ShareLimit.Action.REMOVE));
        assertEquals(2, WebApi.ratioAction(ShareLimit.Action.SUPER_SEED));
        assertEquals(3, WebApi.ratioAction(ShareLimit.Action.REMOVE_DATA));
    }

    /** The service end to end: a socket on loopback, a login through the JDK's client, the cookie kept. */
    @Test
    void theServiceServesOverARealSocketAndStopsWhenSwitchedOff() throws Exception {
        try (WebApiService service = new WebApiService(BACKEND, System::currentTimeMillis)) {
            WebApiService.Status status = service.configure(config(HASH));
            assertEquals(WebApiService.State.RUNNING, status.state(), status.text());
            int port = service.address().orElseThrow().getPort();
            assertTrue(status.text().contains("http://127.0.0.1:" + port + "/"), status.text());

            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(new java.net.CookieManager())
                    .build();
            URI base = URI.create("http://127.0.0.1:" + port + "/api/v2/");
            HttpResponse<String> ok = client.send(
                    HttpRequest.newBuilder(base.resolve("auth/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=s3cret"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("Ok.", ok.body());
            HttpResponse<String> version = client.send(
                    HttpRequest.newBuilder(base.resolve("app/version")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, version.statusCode());
            assertEquals("v5.0.0", version.body());

            assertEquals(
                    WebApiService.State.RUNNING, service.configure(config(HASH)).state(), "the same: left running");
            assertEquals(port, service.address().orElseThrow().getPort(), "and not rebound");

            WebApiService.Status off = service.configure(WebApiConfig.OFF);
            assertEquals(WebApiService.State.OFF, off.state());
            assertTrue(service.address().isEmpty());
            assertTrue(free(port), "and the port let go");
        }
    }

    @Test
    void withoutAPasswordTheServerDoesNotStart() {
        try (WebApiService service = new WebApiService(BACKEND, System::currentTimeMillis)) {
            WebApiService.Status status = service.configure(config(""));
            assertEquals(WebApiService.State.FAILED, status.state());
            assertTrue(service.address().isEmpty());
        }
    }

    /** qBittorrent's 8080 is often taken; the page offers the next free port rather than a stack trace. */
    @Test
    void aPortInUseIsSaidWithAFreeOneOffered() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                WebApiService service = new WebApiService(BACKEND, System::currentTimeMillis)) {
            WebApiConfig c =
                    new WebApiConfig(true, "127.0.0.1", taken.getLocalPort(), "admin", HASH, null, "", Set.of());
            WebApiService.Status status = service.configure(c);
            assertEquals(WebApiService.State.FAILED, status.state());
            assertTrue(status.text().contains(String.valueOf(taken.getLocalPort())), status.text());
            assertTrue(status.freePort() > 0 && status.freePort() != taken.getLocalPort());
            assertTrue(free(status.freePort()));
        }
    }

    @Test
    void anAddressOthersReachOverPlainHttpIsExposed() {
        assertFalse(config(HASH).exposed(), "loopback");
        WebApiConfig lan = new WebApiConfig(true, "0.0.0.0", 8080, "admin", HASH, null, "", Set.of());
        assertTrue(lan.exposed());
        WebApiConfig tls = new WebApiConfig(true, "0.0.0.0", 8080, "admin", HASH, Path.of("cert.p12"), "", Set.of());
        assertFalse(tls.exposed(), "over HTTPS");
        assertEquals("https://localhost:8080/", tls.url());
        WebApiConfig named = new WebApiConfig(true, "nas.lan", 8080, "admin", HASH, null, "", Set.of());
        assertTrue(named.problem().isPresent(), "a name is not resolved to bind");
        assertFalse(config(HASH).toString().contains(HASH), "the hash never in a log line");
    }

    private static boolean free(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
