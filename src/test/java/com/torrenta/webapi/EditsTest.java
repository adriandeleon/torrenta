package com.torrenta.webapi;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
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

/** Tier 2's torrent edits through the router, signed in, against a recording engine. */
class EditsTest {

    private final FakeBackend backend = new FakeBackend();
    private Router router;
    private String sid;

    private static Torrent queued(String id, int position, String category, List<String> tags) {
        Torrent t = torrent(id, id, TorrentState.DOWNLOADING, 0.5, 0, 0, false, true, "");
        return new Torrent(
                t.id(),
                t.name(),
                t.size(),
                t.done(),
                t.progress(),
                t.state(),
                0,
                0,
                0,
                0,
                0,
                0,
                t.added(),
                0,
                "",
                t.savePath(),
                false,
                false,
                0,
                category,
                tags,
                "",
                List.of(),
                false,
                true,
                position,
                false,
                false);
    }

    @BeforeEach
    void setUp() throws Exception {
        backend.torrents.add(queued("aaaa", 0, "tv", List.of("keep", "old")));
        backend.torrents.add(queued("bbbb", 1, "", List.of("old")));
        backend.torrents.add(queued("cccc", 2, "tv", List.of()));
        TorrentDetail.Tracker tracker = new TorrentDetail.Tracker("http://t.example/announce", 0, "working", "", 0);
        TorrentDetail.General g =
                FakeBackend.detail("aaaa", "aaaa", "", List.of()).general();
        backend.details.put(
                "aaaa",
                new TorrentDetail(
                        g,
                        List.of(
                                new TorrentDetail.FileStatus(0, "Show/e1.mkv", 100, 0, Priority.NORMAL, 0, 1),
                                new TorrentDetail.FileStatus(1, "Show/e2.mkv", 100, 0, Priority.NORMAL, 1, 2)),
                        List.of(),
                        List.of(tracker),
                        new boolean[] {true, false, true},
                        new int[0],
                        List.of("https://seed.example/x")));
        backend.details.put("bbbb", FakeBackend.detail("bbbb", "bbbb", "", List.of()));
        backend.details.put("cccc", FakeBackend.detail("cccc", "cccc", "", List.of()));
        backend.categories.add(new Category("tv", ""));
        backend.tags.addAll(List.of("keep", "old"));
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String hash = Passwords.encode(1000, salt, Passwords.derive("pw".toCharArray(), salt, 1000));
        router = new WebApi(
                        backend,
                        new WebApiConfig(true, "127.0.0.1", 0, "admin", hash, null, "", Set.of()),
                        new Sessions(new SecureRandom()),
                        new AtomicLong(1_000_000)::get)
                .router();
        Response login = post("auth/login", "username=admin&password=pw");
        sid = login.headers().stream()
                .filter(e -> e.getKey().equals("Set-Cookie"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .split(";")[0];
        backend.calls.clear();
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

    private Response post(String path, String form) throws Exception {
        return send("POST", path, form);
    }

    private static String text(Response r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    /** The engine's calls, without the reads that every lookup makes. */
    private List<String> calls() {
        return backend.calls.stream()
                .filter(c -> !c.equals("torrents")
                        && !c.startsWith("capabilities")
                        && !c.startsWith("detail")
                        && !c.startsWith("limits ")
                        && !c.startsWith("tags "))
                .toList();
    }

    @Test
    void filePrioritiesInQbittorrentsNumbers() throws Exception {
        assertEquals(
                200, post("torrents/filePrio", "hash=aaaa&id=0|1&priority=0").status());
        assertEquals(200, post("torrents/filePrio", "hash=aaaa&id=1&priority=7").status());
        assertEquals(
                List.of(
                        "setFilePriority aaaa 0 IGNORE",
                        "setFilePriority aaaa 1 IGNORE",
                        "setFilePriority aaaa 1 HIGH"),
                calls());
        assertEquals(400, post("torrents/filePrio", "hash=aaaa&id=0&priority=4").status(), "not one of qBittorrent's");
        assertEquals(409, post("torrents/filePrio", "hash=aaaa&id=9&priority=1").status(), "no such file");
        assertEquals(409, post("torrents/filePrio", "hash=bbbb&id=0&priority=1").status(), "no metadata yet");
        assertEquals(404, post("torrents/filePrio", "hash=ffff&id=0&priority=1").status());
    }

    /** A move is a write anywhere the files go: inside the allowed roots or not at all. */
    @Test
    void aNewLocationMustBeInsideTheAllowedFolders() throws Exception {
        String inside = FakeBackend.abs("/srv/downloads/moved").toString();
        assertEquals(
                200,
                post("torrents/setLocation", "hashes=aaaa|bbbb&location=" + inside)
                        .status());
        assertEquals(List.of("moveStorage aaaa " + inside + " true", "moveStorage bbbb " + inside + " true"), calls());
        Response out = post("torrents/setLocation", "hashes=aaaa&location=/etc");
        assertEquals(400, out.status());
        assertEquals(400, post("torrents/setLocation", "hashes=aaaa&location=").status());
        backend.capabilities = Engine.Capabilities.of(Engine.Capability.LOCAL_FILES);
        assertEquals(
                409,
                post("torrents/setLocation", "hashes=aaaa&location=" + inside).status(),
                "a daemon that cannot");
    }

    @Test
    void namesAreChangedInPlace() throws Exception {
        assertEquals(200, post("torrents/rename", "hash=aaaa&name=Show S01").status());
        assertEquals(409, post("torrents/rename", "hash=aaaa&name=").status());
        assertEquals(
                200,
                post("torrents/renameFile", "hash=aaaa&oldPath=Show/e1.mkv&newPath=Show/Episode 1.mkv")
                        .status());
        assertEquals(
                200,
                post("torrents/renameFolder", "hash=aaaa&oldPath=Show&newPath=Show S01")
                        .status());
        assertEquals(
                409,
                post("torrents/renameFile", "hash=aaaa&oldPath=Show/e1.mkv&newPath=Other/e1.mkv")
                        .status(),
                "a move to another folder is not a rename");
        assertEquals(
                List.of(
                        "rename aaaa Show S01",
                        "renamePath aaaa Show/e1.mkv Episode 1.mkv",
                        "renamePath aaaa Show Show S01"),
                calls());
        backend.capabilities = Engine.Capabilities.of(Engine.Capability.LOCAL_FILES);
        assertEquals(409, post("torrents/rename", "hash=aaaa&name=x").status(), "a daemon without its own names");
    }

    @Test
    void trackersAreAddedRemovedAndReplaced() throws Exception {
        post("torrents/addTrackers", "hash=aaaa&urls=http://a.example/announce%0Audp://b.example:80");
        post("torrents/removeTrackers", "hash=aaaa&urls=http://a.example/announce|udp://b.example:80");
        assertEquals(
                200,
                post(
                                "torrents/editTracker",
                                "hash=aaaa&origUrl=http://t.example/announce&newUrl=http://u.example/announce")
                        .status());
        assertEquals(
                409,
                post("torrents/editTracker", "hash=aaaa&origUrl=http://none.example/&newUrl=http://u.example/")
                        .status());
        assertEquals(
                List.of(
                        "addTrackers aaaa [http://a.example/announce, udp://b.example:80]",
                        "removeTracker aaaa http://a.example/announce",
                        "removeTracker aaaa udp://b.example:80",
                        "removeTracker aaaa http://t.example/announce",
                        "addTrackers aaaa [http://u.example/announce]"),
                calls());
        String listed = text(send("GET", "torrents/trackers", "hash=aaaa"));
        assertTrue(listed.contains("\"status\":2") && listed.contains("\"url\":\"http://t.example/announce\""), listed);
        assertEquals("[{\"url\":\"https://seed.example/x\"}]", text(send("GET", "torrents/webseeds", "hash=aaaa")));
        assertEquals("[2,0,2]", text(send("GET", "torrents/pieceStates", "hash=aaaa")));
    }

    @Test
    void limitsAndSwitchesKeepTheRestOfTheLimits() throws Exception {
        backend.engineLimits.put("aaaa", new TorrentLimits(0, 500, 20, 4, true));
        post("torrents/setDownloadLimit", "hashes=aaaa&limit=2048");
        assertEquals(new TorrentLimits(2048, 500, 20, 4, true), backend.engineLimits.get("aaaa"));
        post("torrents/setUploadLimit", "hashes=aaaa&limit=0");
        assertEquals(new TorrentLimits(2048, 0, 20, 4, true), backend.engineLimits.get("aaaa"), "0: none");
        assertEquals("{\"aaaa\":2048}", text(post("torrents/downloadLimit", "hashes=aaaa")));
        assertEquals("{\"aaaa\":-1}", text(post("torrents/uploadLimit", "hashes=aaaa")));
        post("torrents/toggleFirstLastPiecePrio", "hashes=aaaa");
        assertFalse(backend.engineLimits.get("aaaa").firstAndLastFirst());
        post("torrents/toggleSequentialDownload", "hashes=aaaa");
        post("torrents/setSuperSeeding", "hashes=aaaa&value=true");
        assertEquals(
                200,
                post("torrents/setAutoManagement", "hashes=aaaa&enable=true").status());
        assertTrue(
                calls().containsAll(List.of("setSequential aaaa true", "setSuperSeeding aaaa true")),
                calls().toString());
    }

    /** The list's limits are kept a minute; an edit through the API shows at the next poll. */
    @Test
    void anEditShowsInTheListAtOnce() throws Exception {
        assertTrue(text(send("GET", "torrents/info", "hashes=aaaa")).contains("\"f_l_piece_prio\":false"));
        post("torrents/toggleFirstLastPiecePrio", "hashes=aaaa");
        post("torrents/setUploadLimit", "hashes=aaaa&limit=1000");
        String after = text(send("GET", "torrents/info", "hashes=aaaa"));
        assertTrue(after.contains("\"f_l_piece_prio\":true") && after.contains("\"up_limit\":1000"), after);
    }

    /** As the window does: the named torrents keep their order among themselves. */
    @Test
    void queueMovesGoInTheOrderThatKeepsTheSelectionsOrder() throws Exception {
        post("torrents/increasePrio", "hashes=cccc|bbbb");
        post("torrents/decreasePrio", "hashes=aaaa|bbbb");
        post("torrents/bottomPrio", "hashes=aaaa");
        assertEquals(
                List.of(
                        "queueMove bbbb UP",
                        "queueMove cccc UP",
                        "queueMove bbbb DOWN",
                        "queueMove aaaa DOWN",
                        "queueMove aaaa BOTTOM"),
                calls());
    }

    @Test
    void categoriesAreEditedAndRemovedWithTheirTorrentsCleared() throws Exception {
        String films = FakeBackend.abs("/srv/downloads/tv").toString();
        assertEquals(
                200,
                post("torrents/editCategory", "category=TV&savePath=" + films).status());
        assertEquals(new Category("tv", films), backend.categories.getFirst());
        assertEquals(
                400, post("torrents/editCategory", "category=tv&savePath=/etc").status(), "the folder is a root to be");
        assertEquals(
                409, post("torrents/editCategory", "category=nope&savePath=").status());
        backend.calls.clear();
        assertEquals(
                200, post("torrents/removeCategories", "categories=tv%0Anope").status());
        assertTrue(backend.categories.isEmpty());
        assertEquals(
                List.of("setCategory aaaa ", "setCategory cccc "), calls(), "the torrents under it are under none");
    }

    @Test
    void tagsAreCreatedDeletedAndTakenOff() throws Exception {
        backend.engineTags.put("aaaa", List.of("keep", "old"));
        backend.engineTags.put("bbbb", List.of("old"));
        post("torrents/createTags", "tags=new, other");
        assertTrue(backend.tags.containsAll(List.of("new", "other")));
        post("torrents/removeTags", "hashes=aaaa&tags=keep");
        assertEquals(List.of("old"), backend.engineTags.get("aaaa"));
        post("torrents/deleteTags", "tags=old");
        assertFalse(backend.tags.contains("old"));
        assertEquals(List.of(), backend.engineTags.get("aaaa"));
        assertEquals(List.of(), backend.engineTags.get("bbbb"), "every torrent carrying it");
        backend.engineTags.put("cccc", List.of("a", "b"));
        post("torrents/removeTags", "hashes=cccc");
        assertEquals(List.of(), backend.engineTags.get("cccc"), "no tags named: all of them");
    }

    @Test
    void withoutAnEngineAnEditIsRefusedPlainly() throws Exception {
        backend.engineUp = false;
        assertEquals(
                409,
                post("torrents/setLocation", "hashes=all&location=" + FakeBackend.abs("/srv/downloads"))
                        .status());
        assertEquals(409, post("torrents/rename", "hash=aaaa&name=x").status());
    }
}
