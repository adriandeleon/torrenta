package com.torrenta.webapi;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.http.Form;
import com.torrenta.http.Limits;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.http.Router;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The speed mode, the global limits, setPreferences and log/main through the router, signed in. */
class SessionEditsTest {

    private final FakeBackend backend = new FakeBackend();
    private Router router;
    private String sid;

    @BeforeEach
    void setUp() throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String hash = Passwords.encode(1000, salt, Passwords.derive("pw".toCharArray(), salt, 1000));
        router = new WebApi(
                        backend,
                        new WebApiConfig(true, "127.0.0.1", 0, "admin", hash, null, "", Set.of()),
                        new Sessions(new SecureRandom()),
                        new AtomicLong(1_000_000)::get)
                .router();
        Response login = send("POST", "auth/login", "username=admin&password=pw");
        sid = login.headers().stream()
                .filter(e -> e.getKey().equals("Set-Cookie"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .split(";")[0];
    }

    private Response send(String method, String path, String form) throws Exception {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("127.0.0.1:8080"));
        h.put("content-type", List.of("application/x-www-form-urlencoded"));
        if (sid != null) h.put("cookie", List.of(sid));
        return router.dispatch(new Request(
                method,
                "/api/v2/" + path,
                method.equals("GET") ? Form.parse(form) : Form.EMPTY,
                h,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000),
                method.equals("GET") ? new byte[0] : form.getBytes(StandardCharsets.UTF_8),
                Limits.DEFAULT,
                false));
    }

    private String text(String method, String path, String form) throws Exception {
        return new String(send(method, path, form).body(), StandardCharsets.UTF_8);
    }

    @Test
    void theSpeedModeTogglesAndSetsAndEachChangeIsApplied() throws Exception {
        assertEquals("0", text("GET", "transfer/speedLimitsMode", ""));
        assertEquals(200, send("POST", "transfer/toggleSpeedLimitsMode", "").status());
        assertEquals("1", text("GET", "transfer/speedLimitsMode", ""));
        assertEquals(1, backend.applied.get(), "saved and applied, as the pill does");
        send("POST", "transfer/setSpeedLimitsMode", "mode=0");
        assertEquals("0", text("GET", "transfer/speedLimitsMode", ""));
        assertEquals(400, send("POST", "transfer/setSpeedLimitsMode", "mode=2").status());
    }

    /** qBittorrent sets the limit of the pair in force. */
    @Test
    void aGlobalLimitIsTheOneInForce() throws Exception {
        send("POST", "transfer/setDownloadLimit", "limit=1500");
        assertEquals(2, backend.store.engineSettings().speed().download());
        assertEquals("2048", text("GET", "transfer/downloadLimit", ""));
        send("POST", "transfer/setSpeedLimitsMode", "mode=1");
        send("POST", "transfer/setUploadLimit", "limit=10240");
        assertEquals(10, backend.store.engineSettings().speed().alternativeUpload(), "the alternative pair's");
        assertEquals(0, backend.store.engineSettings().speed().upload(), "the normal one untouched");
        assertEquals("10240", text("GET", "transfer/uploadLimit", ""));
        assertEquals(
                400, send("POST", "transfer/setDownloadLimit", "limit=lots").status());
    }

    @Test
    void preferencesAreWrittenAppliedAndRefusedWhole() throws Exception {
        String json = URLEncoder.encode("{\"dht\":false,\"max_active_downloads\":2}", StandardCharsets.UTF_8);
        assertEquals(200, send("POST", "app/setPreferences", "json=" + json).status());
        assertEquals(1, backend.applied.get());
        String read = text("GET", "app/preferences", "");
        assertTrue(read.contains("\"dht\":false") && read.contains("\"max_active_downloads\":2"), read);

        String outside = URLEncoder.encode("{\"save_path\":\"/etc\",\"dht\":true}", StandardCharsets.UTF_8);
        Response refused = send("POST", "app/setPreferences", "json=" + outside);
        assertEquals(400, refused.status());
        assertTrue(text("GET", "app/preferences", "").contains("\"dht\":false"), "nothing of it written");
        assertEquals(400, send("POST", "app/setPreferences", "json=not-json").status());
        assertEquals(1, backend.applied.get(), "neither applied");

        String ignored = URLEncoder.encode("{\"web_ui_password\":\"x\"}", StandardCharsets.UTF_8);
        assertEquals(200, send("POST", "app/setPreferences", "json=" + ignored).status());
        assertEquals(1, backend.applied.get(), "nothing it has: nothing to apply");
    }

    @Test
    void theEnginesRefusalIsTheAnswer() throws Exception {
        backend.refuseSettings = new EngineException("The daemon refused the port");
        Response r = send("POST", "transfer/toggleSpeedLimitsMode", "");
        assertEquals(409, r.status());
        assertEquals("The daemon refused the port", new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    void theLogIsTheSessionsEventsSinceTheLastIdByType() throws Exception {
        backend.log.add(EngineEvent.info("aaaa", "Film", "Added"));
        backend.log.add(EngineEvent.info("aaaa", "Film", "Moved Film to /srv"));
        backend.log.add(EngineEvent.warning("aaaa", "Film", "Tracker error"));
        backend.log.add(EngineEvent.session(EngineEvent.Level.ERROR, "Disk full"));
        String all = text("GET", "log/main", "");
        assertTrue(all.contains("\"id\":0,\"message\":\"Film: Added\""), all);
        assertTrue(all.contains("\"type\":4") && all.contains("\"message\":\"Disk full\",\"timestamp\":"), all);
        assertTrue(all.contains("\"message\":\"Moved Film to /srv\""), "no name twice: " + all);
        String later = text("GET", "log/main", "last_known_id=2");
        assertTrue(later.startsWith("[{\"id\":3,") && !later.contains("\"id\":2"), later);
        String noWarnings = text("GET", "log/main", "warning=false");
        assertTrue(!noWarnings.contains("Tracker error") && noWarnings.contains("Disk full"), noWarnings);
    }
}
