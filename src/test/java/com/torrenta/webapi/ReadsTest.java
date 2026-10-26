package com.torrenta.webapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.Category;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** torrents/info, properties, files, categories, tags and transfer/info through the router, signed in. */
class ReadsTest {

    private final FakeBackend backend = new FakeBackend();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private Router router;
    private String sid;

    private static TorrentDetail.FileStatus file(int index, String path, Priority priority, double progress) {
        return new TorrentDetail.FileStatus(index, path, 100, (long) (100 * progress), priority, index, index + 1);
    }

    private static Torrent labelled(Torrent t, String category, List<String> tags) {
        return new Torrent(
                t.id(),
                t.name(),
                t.size(),
                t.done(),
                t.progress(),
                t.state(),
                t.downloadRate(),
                t.uploadRate(),
                t.peers(),
                t.seeds(),
                t.uploaded(),
                t.downloaded(),
                t.added(),
                t.completed(),
                t.tracker(),
                t.savePath(),
                t.paused(),
                t.sequential(),
                t.seedingTime(),
                category,
                tags,
                t.error(),
                t.trackers(),
                t.trackerError(),
                t.queued(),
                t.queuePosition(),
                t.moving(),
                t.superSeeding());
    }

    @BeforeEach
    void setUp() throws Exception {
        // A hybrid seeding in "tv" with a tag, a v1 download, and a download from a link.
        backend.torrents.add(
                labelled(torrent("bbbb", "Show S01", TorrentState.SEEDING, 1, 0, 0), "tv", List.of("keep")));
        backend.torrents.add(torrent("cccc", "Film", TorrentState.DOWNLOADING, 0.5, 100, 0));
        backend.torrents.add(torrent("url-" + "0".repeat(40), "file.iso", TorrentState.DOWNLOADING, 0.2, 10, 0));
        backend.details.put(
                "bbbb",
                FakeBackend.detail(
                        "bbbb",
                        "aaaa",
                        "bbbbbbbb",
                        List.of(
                                file(0, "Show S01/e1.mkv", Priority.NORMAL, 1),
                                file(1, "Show S01/e2.mkv", Priority.HIGH, 1))));
        backend.details.put(
                "cccc", FakeBackend.detail("cccc", "cccc", "", List.of(file(0, "Film.mkv", Priority.NORMAL, 0.5))));
        backend.categories.add(new Category("tv", "/srv/tv"));
        backend.tags.add("keep");
        backend.transfer = new WebApi.Transfer(100, 7, 5000, 600, 1024, 0, 42, "firewalled");

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String hash = Passwords.encode(1000, salt, Passwords.derive("pw".toCharArray(), salt, 1000));
        WebApi api = new WebApi(
                backend,
                new WebApiConfig(true, "127.0.0.1", 0, "admin", hash, null, "", Set.of()),
                new Sessions(new SecureRandom()),
                now::get);
        router = api.router();
        Response login = dispatch("POST", "auth/login", "username=admin&password=pw");
        sid = login.headers().stream()
                .filter(e -> e.getKey().equals("Set-Cookie"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .split(";")[0];
    }

    private Response dispatch(String method, String path, String body) throws Exception {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("127.0.0.1:8080"));
        if (sid != null) h.put("cookie", List.of(sid));
        Form query = Form.EMPTY;
        if (method.equals("GET")) query = Form.parse(body);
        else if (body != null) h.put("content-type", List.of("application/x-www-form-urlencoded"));
        return router.dispatch(new Request(
                method,
                "/api/v2/" + path,
                query,
                h,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000),
                method.equals("GET") || body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                Limits.DEFAULT,
                false));
    }

    private String get(String path, String query) throws Exception {
        Response r = dispatch("GET", path, query);
        assertEquals(200, r.status(), path + "?" + query);
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    /** One string field from each object of a JSON array, in order. */
    private static List<String> field(String json, String name) {
        List<String> out = new ArrayList<>();
        try (JsonParser p = new JsonFactory().createParser(json)) {
            int depth = 0;
            for (JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) depth++;
                else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) depth--;
                else if (t == JsonToken.FIELD_NAME
                        && depth == 2
                        && p.currentName().equals(name)) {
                    p.nextToken();
                    out.add(p.getValueAsString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Test
    void theListHasTheTorrentsByTheHashesClientsKnowAndNoLinks() throws Exception {
        String json = get("torrents/info", "");
        assertEquals(List.of("aaaa", "cccc"), field(json, "hash"), "the hybrid by its v1 hash; the link left out");
        assertEquals(List.of("stalledUP", "downloading"), field(json, "state"));
        assertEquals(List.of("tv", ""), field(json, "category"));
        assertTrue(
                json.contains("\"content_path\":\""
                        + java.nio.file.Path.of("/srv/downloads", "Show S01")
                                .toString()
                                .replace("\\", "\\\\") + "\""),
                json);
    }

    @Test
    void theListFiltersSortsAndPagesAsQbittorrentDoes() throws Exception {
        assertEquals(List.of("aaaa"), field(get("torrents/info", "filter=completed"), "hash"));
        assertEquals(List.of("cccc"), field(get("torrents/info", "filter=downloading"), "hash"));
        assertEquals(List.of("aaaa"), field(get("torrents/info", "category=tv"), "hash"));
        assertEquals(List.of("cccc"), field(get("torrents/info", "category="), "hash"), "empty: uncategorised");
        assertEquals(List.of("aaaa"), field(get("torrents/info", "tag=keep"), "hash"));
        assertEquals(List.of("cccc"), field(get("torrents/info", "tag="), "hash"), "empty: untagged");
        assertEquals(List.of("cccc"), field(get("torrents/info", "hashes=CCCC"), "hash"), "any case");
        assertEquals(List.of("aaaa"), field(get("torrents/info", "hashes=bbbb"), "hash"), "Torrenta's own id too");
        assertEquals(List.of("aaaa", "cccc"), field(get("torrents/info", "hashes=aaaa%7Ccccc"), "hash"));
        assertEquals(List.of("cccc", "aaaa"), field(get("torrents/info", "sort=name"), "hash"));
        assertEquals(List.of("aaaa", "cccc"), field(get("torrents/info", "sort=name&reverse=true"), "hash"));
        assertEquals(List.of("cccc"), field(get("torrents/info", "sort=name&limit=1"), "hash"));
        assertEquals(List.of("aaaa"), field(get("torrents/info", "sort=name&offset=-1"), "hash"));
        assertEquals(List.of("aaaa"), field(get("torrents/info", "sort=progress&reverse=true&limit=1"), "hash"));
    }

    /** The detail is a session call with the pieces; the apps list every minute. */
    @Test
    void theFactsAreReadOnceAndAgainAfterAMinuteOrARename() throws Exception {
        get("torrents/info", "");
        int first = backend.detailReads.get();
        assertEquals(2, first);
        get("torrents/info", "");
        assertEquals(first, backend.detailReads.get(), "kept");
        Torrent film = backend.torrents.get(1);
        backend.torrents.set(
                1,
                new Torrent(
                        film.id(),
                        "Film (renamed)",
                        film.size(),
                        film.done(),
                        film.progress(),
                        film.state(),
                        film.downloadRate(),
                        film.uploadRate(),
                        film.peers(),
                        film.seeds(),
                        film.uploaded(),
                        film.downloaded(),
                        film.added(),
                        film.completed(),
                        film.tracker(),
                        film.savePath(),
                        film.paused(),
                        film.sequential(),
                        film.seedingTime(),
                        film.category(),
                        film.tags(),
                        film.error(),
                        film.trackers(),
                        film.trackerError()));
        get("torrents/info", "");
        assertEquals(first + 1, backend.detailReads.get(), "a rename reads that one again");
        now.addAndGet(FactsCache.MAX_AGE_MILLIS);
        get("torrents/info", "");
        assertEquals(first + 3, backend.detailReads.get(), "a minute later, all of them");
    }

    @Test
    void propertiesAndFilesAreFoundByEitherHash() throws Exception {
        String props = get("torrents/properties", "hash=aaaa");
        assertTrue(props.contains("\"save_path\":\"/srv/downloads\""), props);
        assertTrue(props.contains("\"hash\":\"aaaa\"") && props.contains("\"infohash_v2\":\"bbbbbbbb\""), props);
        assertTrue(props.contains("\"pieces_num\":4") && props.contains("\"piece_size\":16384"), props);
        assertEquals(400, dispatch("GET", "torrents/properties", "").status(), "no hash");
        assertEquals(404, dispatch("GET", "torrents/properties", "hash=ffff").status(), "an unknown one");

        String files = get("torrents/files", "hash=bbbb");
        assertEquals(List.of("Show S01/e1.mkv", "Show S01/e2.mkv"), field(files, "name"));
        assertEquals(List.of("1", "6"), field(files, "priority"), "normal 1, high 6");
        assertTrue(files.contains("\"piece_range\":[0,1]"), files);
        assertEquals(List.of("Show S01/e2.mkv"), field(get("torrents/files", "hash=aaaa&indexes=1"), "name"));
        assertEquals(
                404,
                dispatch("GET", "torrents/files", "hash=url-" + "0".repeat(40)).status(),
                "a link is no torrent");
    }

    @Test
    void categoriesTagsAndTransferInQbittorrentsShapes() throws Exception {
        assertEquals("{\"tv\":{\"name\":\"tv\",\"savePath\":\"/srv/tv\"}}", get("torrents/categories", ""));
        assertEquals("[\"keep\"]", get("torrents/tags", ""));
        String transfer = get("transfer/info", "");
        assertTrue(transfer.contains("\"connection_status\":\"firewalled\""), transfer);
        assertTrue(transfer.contains("\"dl_info_speed\":100") && transfer.contains("\"dl_info_data\":5000"), transfer);
        assertTrue(transfer.contains("\"dl_rate_limit\":1024") && transfer.contains("\"dht_nodes\":42"), transfer);
    }

    @Test
    void aPostReadsItsFormAsAGetReadsItsQuery() throws Exception {
        Response r = dispatch("POST", "torrents/info", "filter=completed");
        assertEquals(List.of("aaaa"), field(new String(r.body(), StandardCharsets.UTF_8), "hash"));
    }

    @Test
    void withoutASessionNothingIsRead() throws Exception {
        sid = null;
        for (String path : List.of("torrents/info", "torrents/categories", "transfer/info", "sync/maindata"))
            assertEquals(403, dispatch("GET", path, "").status(), path);
    }

    /** A web UI's poll: everything once, then only what moved, against this session's last answer. */
    @Test
    void maindataIsWholeOnceAndThenOnlyWhatMoved() throws Exception {
        String first = get("sync/maindata", "rid=0");
        assertTrue(first.contains("\"full_update\":true"), first);
        assertTrue(first.contains("\"aaaa\":{") && first.contains("\"cccc\":{"), first);
        assertTrue(
                first.contains("\"server_state\":{") && first.contains("\"connection_status\":\"firewalled\""), first);
        String rid = first.replaceAll("^\\{\"rid\":(\\d+).*$", "$1");

        assertEquals(
                "{\"rid\":" + (Long.parseLong(rid) + 1) + "}", get("sync/maindata", "rid=" + rid), "nothing moved");

        Torrent film = backend.torrents.get(1);
        backend.torrents.set(1, torrent(film.id(), film.name(), TorrentState.DOWNLOADING, 0.75, 300, 0));
        backend.torrents.remove(0);
        backend.tags.add("new");
        long next = Long.parseLong(rid) + 1;
        String diff = get("sync/maindata", "rid=" + next);
        assertTrue(diff.contains("\"cccc\":{\"amount_left\":250,\"completed\":750,\"dlspeed\":300"), diff);
        assertTrue(diff.contains("\"torrents_removed\":[\"aaaa\"]"), diff);
        assertTrue(diff.contains("\"tags\":[\"new\"]"), diff);
        assertTrue(!diff.contains("full_update") && !diff.contains("\"name\""), "only the moved fields: " + diff);

        String stale = get("sync/maindata", "rid=" + rid);
        assertTrue(stale.contains("\"full_update\":true"), "a rid this session was not last answered with");
    }

    @Test
    void torrentPeersArePeersByAddress() throws Exception {
        TorrentDetail d = backend.details.get("cccc");
        backend.details.put(
                "cccc",
                new TorrentDetail(
                        d.general(),
                        d.files(),
                        List.of(new TorrentDetail.Peer(
                                "10.0.0.7:51413", "qBittorrent 5.0.0", "DIP", 0.25, 1000, 0, 5000, 0)),
                        d.trackers(),
                        d.pieces()));
        String peers = get("sync/torrentPeers", "hash=cccc&rid=0");
        assertTrue(
                peers.contains("\"10.0.0.7:51413\":{\"client\":\"qBittorrent 5.0.0\",\"connection\":\"μTP\""), peers);
        assertTrue(peers.contains("\"flags\":\"D I P\"") && peers.contains("\"port\":51413"), peers);
        assertTrue(peers.contains("\"show_flags\":true") && peers.contains("\"full_update\":true"), peers);
        assertEquals(
                404, dispatch("GET", "sync/torrentPeers", "hash=ffff&rid=0").status());
    }

    /** VueTorrent's login page loads before there is a session; the API never answers with a file. */
    @Test
    void theRootFolderIsServedAndUnknownEndpointsAre404() throws Exception {
        java.nio.file.Path ui = java.nio.file.Files.createTempDirectory("torrenta-ui");
        java.nio.file.Files.createDirectories(ui.resolve("public"));
        java.nio.file.Files.writeString(ui.resolve("public/index.html"), "<html>vue</html>");
        backend.rootFolder = ui;
        String signedIn = sid;
        sid = null;
        Response index = router.dispatch(new Request(
                "GET",
                "/",
                Form.EMPTY,
                Map.of("host", List.of("127.0.0.1:8080")),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000),
                new byte[0],
                Limits.DEFAULT,
                false));
        assertEquals(200, index.status(), "no session needed for the public half");
        assertTrue(index.file().toString().endsWith("index.html"));
        assertTrue(index.headers().stream().anyMatch(h -> h.getKey().equals("Content-Security-Policy")));
        assertTrue(index.headers().stream().anyMatch(h -> h.getValue().equals("nosniff")));
        sid = signedIn;
        assertEquals(404, dispatch("GET", "torrents/nothing", "").status());
        assertEquals(
                404,
                dispatch("POST", "torrents/setLocation", "hashes=all&location=/x")
                        .status(),
                "not 405");
        backend.rootFolder = null;
        assertEquals(
                404,
                router.dispatch(new Request(
                                "GET",
                                "/",
                                Form.EMPTY,
                                Map.of("host", List.of("127.0.0.1:8080")),
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000),
                                new byte[0],
                                Limits.DEFAULT,
                                false))
                        .status(),
                "no folder set: nothing at /");
    }
}
