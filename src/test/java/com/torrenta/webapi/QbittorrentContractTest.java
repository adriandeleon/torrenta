package com.torrenta.webapi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.Category;
import com.torrenta.engine.EngineEvent;
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

/**
 * Torrenta's answers against a real qBittorrent's, recorded (roadmap 1.2, item 6): the same
 * scenario played against qBittorrent 5.1.0 by {@code scripts/webapi-record.py} (a seeding
 * single-file torrent, a stopped two-file one in a category with a tag, a magnet nobody serves)
 * and here against Torrenta through its router. Values differ, so what is compared is the shape a
 * client parses: every field qBittorrent writes is there, nothing Torrenta invents is, and each is
 * the same kind of value (number, string, boolean, array, object); and the statuses and words of
 * the answers clients test for. Record again with a newer qBittorrent into a folder of its own.
 */
class QbittorrentContractTest {

    private static final String RECORDED = "/com/torrenta/webapi/qbittorrent-5.1/";

    private final FakeBackend backend = new FakeBackend();
    private Router router;
    private String sid;

    // ---- the scenario ---------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        Torrent seed = torrent("1b42db6c7991aeb563129eb63e5c764351c03836", "Film.mkv", TorrentState.SEEDING, 1, 0, 0);
        Torrent pack = torrent(
                "0b6f939dc753294dbdf34348f7eff24f7b967d9b",
                "Episode.Pack",
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                true,
                false,
                "");
        pack = new Torrent(
                pack.id(),
                pack.name(),
                pack.size(),
                pack.done(),
                pack.progress(),
                pack.state(),
                0,
                0,
                0,
                0,
                0,
                0,
                pack.added(),
                0,
                "",
                pack.savePath(),
                true,
                false,
                0,
                "tv",
                List.of("keep"),
                "",
                List.of(),
                false,
                false,
                0,
                false,
                false);
        Torrent magnet = torrent(
                "233640c560f1c7062595a16289846e079d074172",
                "Nobody.Serves.This",
                TorrentState.FETCHING_METADATA,
                0,
                0,
                0,
                true,
                false,
                "");
        backend.torrents.addAll(List.of(pack, seed, magnet));
        backend.details.put(
                seed.id(),
                FakeBackend.detail(
                        seed.id(),
                        seed.id(),
                        "",
                        List.of(new TorrentDetail.FileStatus(0, "Film.mkv", 250000, 250000, Priority.NORMAL, 0, 15))));
        TorrentDetail.General g =
                FakeBackend.detail(pack.id(), pack.id(), "", List.of()).general();
        backend.details.put(
                pack.id(),
                new TorrentDetail(
                        g,
                        List.of(
                                new TorrentDetail.FileStatus(
                                        0, "Episode.Pack/e1.mkv", 300000, 0, Priority.NORMAL, 0, 18),
                                new TorrentDetail.FileStatus(
                                        1, "Episode.Pack/e2.mkv", 200000, 0, Priority.NORMAL, 18, 30)),
                        List.of(new TorrentDetail.Peer(
                                "10.0.0.7:51413", "qBittorrent 5.1.0", "DIP", 0.5, 10, 0, 100, 0)),
                        List.of(new TorrentDetail.Tracker("http://tracker.example/announce", 0, "working", "", 0)),
                        new boolean[31]));
        backend.details.put(magnet.id(), FakeBackend.detail(magnet.id(), magnet.id(), "", List.of()));
        backend.categories.add(new Category("tv", ""));
        backend.tags.addAll(List.of("keep", "old"));
        backend.log.add(EngineEvent.info(seed.id(), seed.name(), "Added"));
        backend.transfer =
                new WebApi.Transfer(0, 0, 0, 0, 0, 0, 0, "firewalled", 0, 0, 0, 62_000_000_000L, false, true, "", 0, 0);
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

    private Object ours(String path, String query) throws Exception {
        Response r = send("GET", path, query);
        assertEquals(200, r.status(), path);
        return parse(new String(r.body(), StandardCharsets.UTF_8));
    }

    // ---- what is compared -----------------------------------------------------------------------

    private static Object recorded(String name) {
        try (InputStream in = QbittorrentContractTest.class.getResourceAsStream(RECORDED + name + ".json")) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A JSON value's kind as a client's parser sees it: a 0 and a 0.0 are both numbers. */
    static String kind(Object v) {
        return switch (v) {
            case null -> "null";
            case Number n -> "number";
            case String s -> "string";
            case Boolean b -> "bool";
            case Map<?, ?> m -> "object";
            case List<?> l -> "array";
            default -> v.getClass().getSimpleName();
        };
    }

    /** The two objects' differences: missing, invented, or of another kind. Empty when they agree. */
    static Map<String, String> differences(Map<?, ?> theirs, Map<?, ?> ours) {
        Map<String, String> out = new TreeMap<>();
        for (Object k : theirs.keySet()) {
            if (!ours.containsKey(k)) out.put(String.valueOf(k), "missing");
            else if (!kind(theirs.get(k)).equals(kind(ours.get(k))))
                out.put(String.valueOf(k), kind(theirs.get(k)) + " there, " + kind(ours.get(k)) + " here");
        }
        for (Object k : ours.keySet()) if (!theirs.containsKey(k)) out.put(String.valueOf(k), "not qBittorrent's");
        return out;
    }

    private static void sameShape(String what, Object theirs, Object ours) {
        assertEquals(kind(theirs), kind(ours), what);
        if (theirs instanceof Map<?, ?> t && ours instanceof Map<?, ?> o)
            assertEquals(Map.of(), differences(t, o), what);
    }

    private static Map<?, ?> first(Object list) {
        return (Map<?, ?>) ((List<?>) list).getFirst();
    }

    // ---- the contract ---------------------------------------------------------------------------

    @Test
    void theListsRowsAreQbittorrentsRows() throws Exception {
        List<?> theirs = (List<?>) recorded("info");
        List<?> ours = (List<?>) ours("torrents/info", "sort=name");
        assertEquals(3, ours.size());
        for (int i = 0; i < 3; i++) sameShape("torrents/info row " + i, theirs.get(i), ours.get(i));
        // The fields the *arr apps import by, row for row: same name order, same states.
        for (int i = 0; i < 3; i++) {
            Map<?, ?> t = (Map<?, ?>) theirs.get(i);
            Map<?, ?> o = (Map<?, ?>) ours.get(i);
            assertEquals(t.get("name"), o.get("name"));
            assertEquals(t.get("hash"), o.get("hash"));
            assertEquals(t.get("state"), o.get("state"), "the state of " + t.get("name"));
            assertEquals(((String) t.get("content_path")).isEmpty(), ((String) o.get("content_path")).isEmpty());
            assertEquals(((String) t.get("root_path")).isEmpty(), ((String) o.get("root_path")).isEmpty());
            assertEquals(t.get("dl_limit"), o.get("dl_limit"), "no limit in the list");
            assertEquals(((Number) t.get("ratio_limit")).intValue(), ((Number) o.get("ratio_limit")).intValue());
            assertEquals(t.get("has_metadata"), o.get("has_metadata"));
            assertEquals(t.get("tags"), o.get("tags"));
            assertEquals(t.get("category"), o.get("category"));
        }
    }

    @Test
    void aTorrentsPropertiesFilesTrackersAndPieces() throws Exception {
        String seed = "1b42db6c7991aeb563129eb63e5c764351c03836";
        String pack = "0b6f939dc753294dbdf34348f7eff24f7b967d9b";
        Map<?, ?> props = (Map<?, ?>) ours("torrents/properties", "hash=" + seed);
        sameShape("torrents/properties", recorded("properties"), props);
        assertEquals(
                ((Number) ((Map<?, ?>) recorded("properties")).get("dl_limit")).intValue(),
                ((Number) props.get("dl_limit")).intValue(),
                "no limit in properties is -1, unlike the list");
        sameShape("torrents/files", first(recorded("files")), first(ours("torrents/files", "hash=" + pack)));
        List<?> trackers = (List<?>) ours("torrents/trackers", "hash=" + pack);
        List<?> theirTrackers = (List<?>) recorded("trackers");
        for (int i = 0; i < 3; i++) {
            sameShape("tracker " + i, theirTrackers.get(i), trackers.get(i));
            assertEquals(((Map<?, ?>) theirTrackers.get(i)).get("url"), ((Map<?, ?>) trackers.get(i)).get("url"));
        }
        sameShape("a real tracker", theirTrackers.getFirst(), trackers.get(3));
        assertEquals(kind(recorded("webseeds")), kind(ours("torrents/webseeds", "hash=" + pack)));
        assertEquals(kind(recorded("piece-states")), kind(ours("torrents/pieceStates", "hash=" + seed)));
        Object limit = ours("torrents/downloadLimit", "hashes=" + pack);
        assertEquals(recorded("torrent-download-limit"), limit, "{hash: 0}");
    }

    @Test
    void theSessionsAnswers() throws Exception {
        sameShape("transfer/info", recorded("transfer"), ours("transfer/info", ""));
        sameShape("torrents/categories", recorded("categories"), ours("torrents/categories", ""));
        sameShape(
                "a category",
                ((Map<?, ?>) recorded("categories")).get("tv"),
                ((Map<?, ?>) ours("torrents/categories", "")).get("tv"));
        assertEquals(kind(recorded("tags")), kind(ours("torrents/tags", "")));
        sameShape("a log line", first(recorded("log")), first(ours("log/main", "")));
        long theirTime = ((Number) first(recorded("log")).get("timestamp")).longValue();
        long ourTime = ((Number) first(ours("log/main", "")).get("timestamp")).longValue();
        assertEquals(String.valueOf(theirTime).length(), String.valueOf(ourTime).length(), "seconds, not milliseconds");
    }

    @Test
    void maindataWholeAndPeers() throws Exception {
        Map<?, ?> theirs = (Map<?, ?>) recorded("maindata");
        Map<?, ?> ours = (Map<?, ?>) ours("sync/maindata", "rid=0");
        assertEquals(
                new TreeSet<>(theirs.keySet().stream().map(String::valueOf).toList()),
                new TreeSet<>(ours.keySet().stream().map(String::valueOf).toList()));
        sameShape("server_state", theirs.get("server_state"), ours.get("server_state"));
        Map<?, ?> theirRows = (Map<?, ?>) theirs.get("torrents");
        Map<?, ?> ourRows = (Map<?, ?>) ours.get("torrents");
        for (Object hash : theirRows.keySet())
            sameShape("maindata row " + hash, theirRows.get(hash), ourRows.get(hash));
        Map<?, ?> theirPeers = (Map<?, ?>) recorded("peers");
        Map<?, ?> ourPeers =
                (Map<?, ?>) ours("sync/torrentPeers", "rid=0&hash=0b6f939dc753294dbdf34348f7eff24f7b967d9b");
        assertEquals(theirPeers.keySet(), ourPeers.keySet());
    }

    /** No key Torrenta writes is one qBittorrent does not have, and each is the same kind. */
    @Test
    void thePreferencesTorrentaHasAreQbittorrentsKeys() throws Exception {
        Map<?, ?> theirs = (Map<?, ?>) recorded("preferences");
        Map<?, ?> ours = (Map<?, ?>) ours("app/preferences", "");
        Map<String, String> wrong = new TreeMap<>();
        for (Object k : ours.keySet()) {
            if (!theirs.containsKey(k)) wrong.put(String.valueOf(k), "not qBittorrent's");
            else if (!kind(theirs.get(k)).equals(kind(ours.get(k))))
                wrong.put(String.valueOf(k), kind(theirs.get(k)) + " there, " + kind(ours.get(k)) + " here");
        }
        assertEquals(Map.of(), wrong);
        for (String read : List.of(
                "save_path",
                "queueing_enabled",
                "max_ratio_enabled",
                "max_ratio",
                "max_seeding_time_enabled",
                "max_seeding_time",
                "max_ratio_act",
                "dht")) assertTrue(ours.containsKey(read), "the *arr apps read " + read);
    }

    /** The words and statuses clients test for. */
    @Test
    void theAnswersClientsTestFor() throws Exception {
        Map<?, ?> statuses = (Map<?, ?>) recorded("statuses");
        Map<String, Response> ourAnswers = new LinkedHashMap<>();
        Response again = send("POST", "auth/login", "username=admin&password=pw");
        ourAnswers.put("login", again);
        // A new login ends the session its cookie named, as qBittorrent's does: carry on with the new one.
        sid = again.headers().stream()
                .filter(e -> e.getKey().equals("Set-Cookie"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .split(";")[0];
        ourAnswers.put("add-nothing", send("POST", "torrents/add", "category=tv"));
        ourAnswers.put("create-category-again", send("POST", "torrents/createCategory", "category=tv&savePath="));
        ourAnswers.put("set-category-unknown", send("POST", "torrents/setCategory", "hashes=all&category=nope"));
        ourAnswers.put(
                "file-prio-bad",
                send("POST", "torrents/filePrio", "hash=0b6f939dc753294dbdf34348f7eff24f7b967d9b&id=0&priority=4"));
        ourAnswers.put(
                "properties-unknown",
                send("GET", "torrents/properties", "hash=ffffffffffffffffffffffffffffffffffffffff"));
        ourAnswers.put("properties-no-hash", send("GET", "torrents/properties", ""));
        ourAnswers.put("unknown-endpoint", send("GET", "torrents/noSuchThing", ""));
        ourAnswers.put("speed-mode", send("GET", "transfer/speedLimitsMode", ""));
        ourAnswers.put("download-limit", send("GET", "transfer/downloadLimit", ""));
        ourAnswers.put("logout", send("POST", "auth/logout", ""));
        ourAnswers.put("login-wrong", send("POST", "auth/login", "username=admin&password=nope"));
        sid = null;
        ourAnswers.put("no-session", send("GET", "app/version", ""));
        List<String> wrong = new ArrayList<>();
        ourAnswers.forEach((name, r) -> {
            Map<?, ?> theirs = (Map<?, ?>) statuses.get(name);
            int status = ((Number) theirs.get("status")).intValue();
            String body = new String(r.body(), StandardCharsets.UTF_8);
            if (status != r.status()) wrong.add(name + ": " + status + " there, " + r.status() + " here");
            else if (List.of("Ok.", "Fails.", "Forbidden", "0").contains(theirs.get("body"))
                    && !theirs.get("body").equals(body))
                wrong.add(name + ": '" + theirs.get("body") + "' there, '" + body + "' here");
        });
        assertEquals(List.of(), wrong);
    }

    // ---- JSON -----------------------------------------------------------------------------------

    private static final JsonFactory JSON = new JsonFactory();

    /** A JSON text as maps (in order), lists, strings, longs, doubles, booleans and null. */
    static Object parse(String text) {
        try (JsonParser p = JSON.createParser(text)) {
            p.nextToken();
            return value(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object value(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case START_OBJECT -> {
                Map<String, Object> m = new LinkedHashMap<>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String key = p.currentName();
                    p.nextToken();
                    m.put(key, value(p));
                }
                yield m;
            }
            case START_ARRAY -> {
                List<Object> l = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) l.add(value(p));
                yield l;
            }
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            default -> null;
        };
    }
}
