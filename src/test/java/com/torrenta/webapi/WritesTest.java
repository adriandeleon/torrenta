package com.torrenta.webapi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.TorrentState;
import com.torrenta.http.Form;
import com.torrenta.http.Limits;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.http.Router;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.torrenta.webapi.FakeBackend.torrent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** torrents/add and the actions through the router, signed in, against a recording engine. */
class WritesTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:233640c560f1c7062595a16289846e079d074172";

    private final FakeBackend backend = new FakeBackend();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private Router router;
    private String sid;
    private byte[] torrentFile;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/com/torrenta/engine/transmission/payload.torrent")) {
            torrentFile = in.readAllBytes();
        }
        backend.torrents.add(torrent("bbbb", "Show", TorrentState.SEEDING, 1, 0, 0));
        backend.torrents.add(torrent("cccc", "Film", TorrentState.DOWNLOADING, 0.5, 10, 0));
        backend.details.put("bbbb", FakeBackend.detail("bbbb", "aaaa", "bbbbbbbb", List.of()));
        backend.details.put("cccc", FakeBackend.detail("cccc", "cccc", "", List.of()));
        backend.categories.add(new Category("tv", "/srv/tv"));
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String hash = Passwords.encode(1000, salt, Passwords.derive("pw".toCharArray(), salt, 1000));
        router = new WebApi(
                        backend,
                        new WebApiConfig(true, "127.0.0.1", 0, "admin", hash, null, "", Set.of()),
                        new Sessions(new SecureRandom()),
                        now::get)
                .router();
        sid = cookie(post("auth/login", "username=admin&password=pw"));
    }

    private static String cookie(Response r) {
        return r.headers().stream()
                .filter(e -> e.getKey().equals("Set-Cookie"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .split(";")[0];
    }

    private Response send(String path, String contentType, byte[] body) throws Exception {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("127.0.0.1:8080"));
        h.put("content-type", List.of(contentType));
        if (sid != null) h.put("cookie", List.of(sid));
        return router.dispatch(new Request(
                "POST",
                "/api/v2/" + path,
                Form.EMPTY,
                h,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000),
                body,
                Limits.DEFAULT,
                false));
    }

    private Response post(String path, String form) throws Exception {
        return send(path, "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8));
    }

    /** A multipart body as the *arr apps send it: the fields, then the files as {@code torrents}. */
    private Response multipart(Map<String, String> fields, Map<String, byte[]> files) throws Exception {
        String boundary = "----torrenta-test";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> f : fields.entrySet())
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                            + f.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\""
                            + f.getKey() + "\"\r\nContent-Type: application/x-bittorrent\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.writeBytes(f.getValue());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send("torrents/add", "multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    private static String text(Response r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void aMagnetIsAddedWithTheDefaults() throws Exception {
        Response r = post("torrents/add", "urls=" + java.net.URLEncoder.encode(MAGNET, StandardCharsets.UTF_8));
        assertEquals("Ok.", text(r));
        AddRequest added = backend.adds.getFirst();
        assertEquals(new AddRequest.Magnet(MAGNET), added.source());
        assertEquals(FakeBackend.abs("/srv/downloads"), added.savePath());
        assertFalse(added.startPaused());
    }

    /** What Sonarr and Radarr send: the file, a category, stopped, a save path. */
    @Test
    void anUploadedTorrentIsAddedWithEveryOption() throws Exception {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("savepath", "/srv/tv/Show");
        fields.put("category", "tv");
        fields.put("tags", "sonarr, keep");
        fields.put("stopped", "true");
        fields.put("skip_checking", "true");
        fields.put("sequentialDownload", "true");
        fields.put("contentLayout", "NoSubfolder");
        fields.put("rename", "Show S01");
        fields.put("dlLimit", "2048");
        fields.put("firstLastPiecePrio", "true");
        fields.put("ratioLimit", "2");
        fields.put("seedingTimeLimit", "-1");
        Response r = multipart(fields, Map.of("show.torrent", torrentFile));
        assertEquals("Ok.", text(r));
        AddRequest a = backend.adds.getFirst();
        assertTrue(a.source() instanceof AddRequest.TorrentFile);
        assertEquals(FakeBackend.abs("/srv/tv/Show"), a.savePath());
        assertEquals("tv", a.labels().category());
        assertEquals(List.of("keep", "sonarr"), a.labels().tags());
        assertEquals("Show S01", a.labels().name());
        assertTrue(a.startPaused() && a.skipCheck() && a.sequential());
        assertEquals(FileRenames.Layout.NO_SUBFOLDER, a.storage().layout());
        assertTrue(
                backend.calls.stream().anyMatch(c -> c.startsWith("setLimits added1 TorrentLimits[downloadLimit=2048")),
                backend.calls.toString());
        assertEquals(new ShareLimit(2, 0, ShareLimit.Action.PAUSE), backend.own.get("added1"));
        assertTrue(backend.tags.containsAll(List.of("sonarr", "keep")), "the tags are kept in the list too");
    }

    @Test
    void aCategoryWithAFolderIsWhereItsTorrentsGoAndANewOneIsCreated() throws Exception {
        post("torrents/add", "category=tv&urls=" + java.net.URLEncoder.encode(MAGNET, StandardCharsets.UTF_8));
        assertEquals(FakeBackend.abs("/srv/tv"), backend.adds.getFirst().savePath());
        post("torrents/add", "category=films&urls=" + java.net.URLEncoder.encode(MAGNET, StandardCharsets.UTF_8));
        assertEquals(FakeBackend.abs("/srv/downloads"), backend.adds.get(1).savePath());
        assertTrue(backend.categories.stream().anyMatch(c -> c.name().equals("films")), "created, as qBittorrent does");
    }

    /** Anyone with the password could otherwise write anywhere this account can. */
    @Test
    void aSavePathOutsideTheAllowedFoldersIsRefusedBeforeAnythingIsAdded() throws Exception {
        Response r = post(
                "torrents/add",
                "savepath=/etc/cron.d&urls=" + java.net.URLEncoder.encode(MAGNET, StandardCharsets.UTF_8));
        assertEquals(400, r.status());
        assertTrue(text(r).contains("cron.d"), text(r));
        assertTrue(backend.adds.isEmpty());
        assertEquals(
                400,
                post("torrents/add", "savepath=../../etc&urls=" + MAGNET.replace(":", "%3A"))
                        .status());
    }

    /** urls are magnets and http(s) links: a path would read this machine's files. */
    @Test
    void aUrlThatIsAPathOrNotATorrentAddsNothing() throws Exception {
        backend.served.put("https://indexer.example/t/1", torrentFile);
        backend.served.put("https://indexer.example/t/2", "<html>login</html>".getBytes(StandardCharsets.UTF_8));
        assertEquals("Fails.", text(post("torrents/add", "urls=/home/someone/.ssh/id_rsa")));
        assertEquals("Fails.", text(post("torrents/add", "urls=file%3A%2F%2F%2Fetc%2Fpasswd")));
        assertEquals(
                "Fails.", text(post("torrents/add", "urls=https%3A%2F%2Findexer.example%2Ft%2F2")), "not a torrent");
        assertTrue(backend.adds.isEmpty());
        assertEquals("Ok.", text(post("torrents/add", "urls=https%3A%2F%2Findexer.example%2Ft%2F1")));
        assertEquals(1, backend.adds.size());
        assertEquals(
                "Ok.",
                text(post(
                        "torrents/add", "urls=https%3A%2F%2Findexer.example%2Ft%2F1%0A" + MAGNET.replace(":", "%3A"))),
                "one per line");
        assertEquals(3, backend.adds.size());
    }

    @Test
    void anUploadThatDoesNotReadAsATorrentIsRefused() throws Exception {
        assertEquals(
                "Fails.",
                text(multipart(Map.of(), Map.of("x.torrent", "d4:infoi1ee".getBytes(StandardCharsets.UTF_8)))));
        byte[] deep = ("l".repeat(200) + "e".repeat(200)).getBytes(StandardCharsets.UTF_8);
        assertEquals("Fails.", text(multipart(Map.of(), Map.of("deep.torrent", deep))), "nested past the bound");
        assertTrue(backend.adds.isEmpty());
        assertEquals("Fails.", text(post("torrents/add", "category=tv")), "nothing to add: qBittorrent's 200 Fails.");
    }

    @Test
    void theActionsReachTheEngineByEitherHashAndSkipUnknownOnes() throws Exception {
        post("torrents/stop", "hashes=aaaa|ffff");
        post("torrents/start", "hashes=CCCC");
        post("torrents/pause", "hashes=all");
        post("torrents/recheck", "hashes=bbbb");
        post("torrents/topPrio", "hashes=cccc");
        post("torrents/setForceStart", "hashes=cccc&value=true");
        post("torrents/setForceStart", "hashes=cccc&value=false");
        post("torrents/delete", "hashes=aaaa&deleteFiles=true");
        List<String> c =
                backend.calls.stream().filter(s -> !s.equals("torrents")).toList();
        assertEquals(
                List.of(
                        "pause bbbb",
                        "resume cccc",
                        "pause bbbb",
                        "pause cccc",
                        "recheck bbbb",
                        "queueMove cccc TOP",
                        "forceResume cccc",
                        "resume cccc",
                        "remove bbbb true"),
                c);
    }

    @Test
    void categoriesAndTagsAsQbittorrentFilesThem() throws Exception {
        assertEquals(
                409, post("torrents/setCategory", "hashes=aaaa&category=nope").status(), "it must exist");
        assertEquals(
                200, post("torrents/setCategory", "hashes=aaaa&category=TV").status());
        assertTrue(backend.calls.contains("setCategory bbbb tv"), "under the stored spelling");
        post("torrents/setCategory", "hashes=aaaa&category=");
        assertTrue(backend.calls.contains("setCategory bbbb "), "empty: none");

        assertEquals(
                200,
                post("torrents/createCategory", "category=films&savePath=/srv/downloads/films")
                        .status());
        assertEquals(
                new Category("films", FakeBackend.abs("/srv/downloads/films").toString()),
                backend.categories.getLast(),
                "kept as the absolute path it resolved to");
        assertEquals(409, post("torrents/createCategory", "category=Films").status(), "it exists");
        Response outside = post("torrents/createCategory", "category=sneaky&savePath=/etc");
        assertEquals(400, outside.status(), "a category's folder becomes a root: it must be inside one");
        assertTrue(backend.categories.stream().noneMatch(cat -> cat.name().equals("sneaky")));

        backend.engineTags.put("bbbb", List.of("old"));
        post("torrents/addTags", "hashes=aaaa&tags=new, also");
        assertEquals(List.of("old", "new", "also"), backend.engineTags.get("bbbb"));
        assertTrue(backend.tags.containsAll(List.of("new", "also")));
    }

    @Test
    void shareLimitsAreQbittorrentsNumbers() throws Exception {
        backend.global = new ShareLimit(3, 600, ShareLimit.Action.REMOVE);
        post("torrents/setShareLimits", "hashes=aaaa&ratioLimit=1.5&seedingTimeLimit=-1&inactiveSeedingTimeLimit=-2");
        assertEquals(
                new ShareLimit(1.5, 0, ShareLimit.Action.REMOVE), backend.own.get("bbbb"), "-1: none of that kind");
        post("torrents/setShareLimits", "hashes=aaaa&ratioLimit=-2&seedingTimeLimit=90");
        assertEquals(new ShareLimit(3, 90, ShareLimit.Action.REMOVE), backend.own.get("bbbb"), "-2: the global's");
        post("torrents/setShareLimits", "hashes=aaaa&ratioLimit=-2&seedingTimeLimit=-2");
        assertFalse(backend.own.containsKey("bbbb"), "both global: its own is dropped");
        assertEquals(
                400, post("torrents/setShareLimits", "hashes=aaaa&ratioLimit=1").status());
    }

    @Test
    void withoutAnEngineOrASessionNothingActs() throws Exception {
        backend.engineUp = false;
        assertEquals(409, post("torrents/stop", "hashes=all").status());
        assertEquals(
                409, post("torrents/add", "urls=" + MAGNET.replace(":", "%3A")).status());
        backend.engineUp = true;
        sid = null;
        assertEquals(403, post("torrents/delete", "hashes=all&deleteFiles=true").status());
        assertTrue(backend.calls.stream().noneMatch(c -> c.startsWith("remove")));
    }
}
