package com.torrenta.engine.transmission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.Engine.Capability;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.Priority;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine against a stand-in daemon answering with shapes recorded from transmission-daemon
 * 4.1.0-beta.2 (RPC 18): {@code session-get.json}, {@code torrent-get.json}, {@code
 * session-stats.json} beside this test, and {@code payload.torrent} as the daemon made it.
 */
class TransmissionEngineTest {

    static final String PAYLOAD = "aeed55437efbc498db0bcbd8cd0f5eb8f5d9906a";
    static final String MAGNET_ID = "0123456789abcdef0123456789abcdef01234567";

    private StubRpcServer server;
    private final Path localDefault = Path.of("local-downloads").toAbsolutePath();

    @BeforeEach
    void start() throws Exception {
        server = new StubRpcServer();
        server.answer("session-get", fixture("session-get.json"));
        server.answer("torrent-get", byIds(fixture("torrent-get.json")));
        server.answer("session-stats", fixture("session-stats.json"));
    }

    /** The recorded list, narrowed to the {@code ids} a request names, as the daemon narrows it. */
    static java.util.function.Function<Map<String, Object>, String> byIds(String recorded) {
        return args -> {
            try {
                Map<String, Object> answer = Json.obj(Json.parse(recorded));
                List<Object> torrents = Json.list(Json.obj(answer, "arguments"), "torrents");
                List<Object> ids = Json.list(args, "ids");
                if (ids.isEmpty()) return recorded;
                List<Object> kept = new ArrayList<>();
                for (Object t : torrents) {
                    if (ids.contains(Json.str(Json.obj(t), "hashString", ""))) kept.add(t);
                }
                return StubRpcServer.success(Map.of("torrents", kept));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @AfterEach
    void stop() {
        server.close();
    }

    static String fixture(String name) throws IOException {
        try (InputStream in = TransmissionEngineTest.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static byte[] payloadTorrent() throws IOException {
        try (InputStream in = TransmissionEngineTest.class.getResourceAsStream("payload.torrent")) {
            return in.readAllBytes();
        }
    }

    private TransmissionEngine engine() throws EngineException {
        return engine(EngineSettings.defaults());
    }

    private TransmissionEngine engine(EngineSettings settings) throws EngineException {
        EngineProfile profile =
                new EngineProfile("box", "Seedbox", EngineProfile.Kind.REMOTE, server.hostUrl(), "", "");
        TransmissionEngine e = new TransmissionEngine(profile, () -> localDefault, settings, RpcClient.defaultClient());
        e.start();
        return e;
    }

    // ---- start, description, capabilities -------------------------------------------------------

    @Test
    void startReadsTheDaemonAndSaysWhatItCanDo() throws Exception {
        TransmissionEngine e = engine();
        assertEquals(
                "Transmission 4.1.0-beta.2 (ac5c9e082d) (RPC 18) at 127.0.0.1:"
                        + server.uri().getPort(),
                e.description());
        assertTrue(e.downloadDir().endsWith("probe/dl"), "the daemon's download folder: " + e.downloadDir());
        Engine.Capabilities caps = e.capabilities();
        assertTrue(caps.has(Capability.SEQUENTIAL_DOWNLOAD), "RPC 18 has sequential download");
        assertTrue(caps.has(Capability.BLOCKLIST_URL));
        assertTrue(caps.has(Capability.LOCAL_FILES), "a daemon on this machine has the files here");
        for (Capability missing : List.of(
                Capability.CREATE_TORRENT,
                Capability.METADATA_FETCH,
                Capability.IP_FILTER,
                Capability.FIRST_LAST_PIECES,
                Capability.PROXY,
                Capability.LISTEN_DETAILS,
                Capability.UPLOAD_SLOTS,
                Capability.ANONYMOUS_MODE,
                Capability.OVERHEAD_IN_LIMITS,
                Capability.STORAGE_OPTIONS,
                Capability.ACTIVE_TOTAL_LIMIT,
                Capability.SEED_MODE,
                Capability.STOP_CONDITION)) {
            assertTrue(caps.lacks(missing), missing + " is not Transmission's");
        }
        assertThrows(EngineException.class, () -> e.create(null, null));
        assertThrows(EngineException.class, () -> e.fetchMetadata("magnet:?xt=urn:btih:" + MAGNET_ID));
        assertThrows(EngineException.class, () -> e.setIpFilter(List.of()));
        e.stop();
        assertTrue(e.torrents().isEmpty(), "a stopped engine lists nothing");
    }

    @Test
    void anOlderDaemonOnAnotherMachineHasLess() {
        Engine.Capabilities old = TransmissionEngine.capabilitiesFor(17, false);
        assertTrue(old.lacks(Capability.SEQUENTIAL_DOWNLOAD));
        assertTrue(old.lacks(Capability.LOCAL_FILES));
        assertTrue(old.has(Capability.BLOCKLIST_URL));
        assertTrue(TransmissionEngine.isLoopback(java.net.URI.create("http://localhost:9091/transmission/rpc")));
        assertTrue(TransmissionEngine.isLoopback(java.net.URI.create("http://[::1]:9091/")));
        assertFalse(TransmissionEngine.isLoopback(java.net.URI.create("http://seedbox.example:9091/")));
    }

    @Test
    void aDaemonThatIsNotThereFailsToStartWithTheReason() {
        server.force(500, "down");
        EngineProfile profile =
                new EngineProfile("box", "Seedbox", EngineProfile.Kind.REMOTE, server.hostUrl(), "", "");
        TransmissionEngine e = new TransmissionEngine(
                profile, () -> localDefault, EngineSettings.defaults(), RpcClient.defaultClient());
        EngineException failure = assertThrows(EngineException.class, e::start);
        assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        assertThrows(EngineException.class, () -> e.pause(PAYLOAD), "nothing works before a start");
    }

    // ---- the poll ---------------------------------------------------------------------------------

    @Test
    void thePollMapsTheRecordedListOntoTheRecord() throws Exception {
        TransmissionEngine e = engine();
        List<Torrent> all = e.torrents();
        assertEquals(2, all.size());
        StubRpcServer.Call poll = server.last("torrent-get");
        assertNull(poll.arguments().get("ids"), "the list poll asks for every torrent");
        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) poll.arguments().get("fields");
        assertTrue(fields.contains("sequential_download"), "RPC 18 is asked the sequential flag");
        assertFalse(fields.contains("pieces"), "the pieces bitfield is for detail() alone");
        assertFalse(fields.contains("trackerStats"), "the heavy tracker stats are for detail() alone");

        Torrent seed = all.get(0);
        assertEquals(PAYLOAD, seed.id());
        assertEquals("payload.bin", seed.name());
        assertEquals(307200, seed.size());
        assertEquals(307200, seed.done());
        assertEquals(1.0, seed.progress());
        assertEquals(TorrentState.SEEDING, seed.state());
        assertFalse(seed.paused());
        assertFalse(seed.queued());
        assertEquals(0, seed.queuePosition());
        assertEquals(1792573693L, seed.added());
        assertEquals(1792573693L, seed.completed());
        assertEquals(3, seed.seedingTime());
        assertEquals("udp://tracker.example.org:6969/announce", seed.tracker());
        assertEquals(List.of("tracker.example.org"), seed.trackers());
        assertEquals("", seed.category());
        assertEquals(List.of("tv", "x"), seed.tags(), "plain labels are tags");
        assertTrue(seed.savePath().endsWith("probe/src"));
        assertEquals("", seed.error());

        Torrent magnet = all.get(1);
        assertEquals(MAGNET_ID, magnet.id());
        assertEquals("Torrenta test", magnet.name());
        assertEquals(TorrentState.FETCHING_METADATA, magnet.state());
        assertTrue(magnet.paused(), "status 0 is stopped by a person");
        assertEquals(0, magnet.size());

        assertEquals(new Engine.Rates(0, 0), e.rates());
        SessionStats stats = e.stats();
        assertEquals(3, stats.sessionSeconds());
        assertEquals(SessionStats.UNKNOWN, stats.dhtNodes(), "a counter the daemon lacks is unknown");
        assertEquals(1, stats.torrents().seeding());
        assertEquals(1, stats.torrents().stopped());
    }

    @Test
    void statesFoldAsLibtorrentsDo() {
        assertEquals(TorrentState.CHECKING, TransmissionEngine.state(1, 1, 10, 5, 10));
        assertEquals(TorrentState.CHECKING, TransmissionEngine.state(2, 1, 10, 5, 10));
        assertEquals(TorrentState.DOWNLOADING, TransmissionEngine.state(4, 1, 10, 5, 10));
        assertEquals(TorrentState.DOWNLOADING, TransmissionEngine.state(3, 1, 10, 5, 10), "queued to download");
        assertEquals(TorrentState.FETCHING_METADATA, TransmissionEngine.state(4, 0, 0, 0, 0));
        assertEquals(TorrentState.FETCHING_METADATA, TransmissionEngine.state(0, 0, 0, 0, 0), "a stopped magnet");
        assertEquals(TorrentState.SEEDING, TransmissionEngine.state(6, 1, 10, 0, 10));
        assertEquals(
                TorrentState.FINISHED,
                TransmissionEngine.state(6, 1, 8, 0, 10),
                "files left out: finished, not seeding");
        assertEquals(TorrentState.SEEDING, TransmissionEngine.state(0, 1, 10, 0, 10), "a stopped seed keeps its state");
        assertEquals(TorrentState.DOWNLOADING, TransmissionEngine.state(0, 1, 10, 4, 10), "a stopped download too");
        assertEquals(TorrentState.UNKNOWN, TransmissionEngine.state(9, 1, 10, 4, 10));
    }

    @Test
    void eventsAreMadeByComparingPolls() throws Exception {
        TransmissionEngine e = engine();
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        e.addListener(events::add);
        e.torrents();
        assertTrue(events.isEmpty(), "the first poll restores, it announces nothing");

        // Another client removed the magnet and added a torrent; the seed fell into an error.
        Map<String, Object> answer = Json.obj(Json.parse(fixture("torrent-get.json")));
        List<Object> torrents = new ArrayList<>(Json.list(Json.obj(answer, "arguments"), "torrents"));
        Map<String, Object> seed = new LinkedHashMap<>(Json.obj(torrents.get(0)));
        seed.put("error", 3L);
        seed.put("errorString", "No data found! Ensure your drives are connected");
        Map<String, Object> fresh = new LinkedHashMap<>(Json.obj(torrents.get(1)));
        fresh.put("hashString", "ffffffffffffffffffffffffffffffffffffffff");
        fresh.put("name", "From the web UI");
        server.answer("torrent-get", StubRpcServer.success(Map.of("torrents", List.of(seed, fresh))));
        e.torrents();

        List<String> lines = events.stream().map(EngineEvent::message).toList();
        assertTrue(lines.contains("Removed Torrenta test"), lines.toString());
        assertTrue(lines.contains("Added From the web UI"), lines.toString());
        assertTrue(lines.contains("payload.bin: No data found! Ensure your drives are connected"), lines.toString());
        EngineEvent error = events.stream()
                .filter(ev -> ev.kind() == EngineEvent.Kind.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals(EngineEvent.Level.ERROR, error.level());
        assertEquals(PAYLOAD, error.torrentId());
        EngineEvent added = events.stream()
                .filter(ev -> ev.kind() == EngineEvent.Kind.ADDED)
                .findFirst()
                .orElseThrow();
        assertEquals("ffffffffffffffffffffffffffffffffffffffff", added.torrentId());

        // The same answer again: nothing new.
        events.clear();
        e.torrents();
        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    void aDownloadThatCompletesIsFinishedOnce() {
        Torrent before = torrent("a", 100, 40, false, "");
        Torrent after = torrent("a", 100, 100, false, "");
        List<EngineEvent> events = TransmissionEngine.diff(Map.of("a", before), Map.of("a", after), Set.of(), Set.of());
        assertEquals(1, events.size());
        assertEquals(EngineEvent.Kind.FINISHED, events.get(0).kind());
        assertEquals("a finished", events.get(0).message());
        assertTrue(TransmissionEngine.diff(Map.of("a", after), Map.of("a", after), Set.of(), Set.of())
                .isEmpty());
        // Our own add and remove are announced by the action, not by the poll.
        assertTrue(TransmissionEngine.diff(Map.of(), Map.of("a", after), Set.of("a"), Set.of())
                .isEmpty());
        assertTrue(TransmissionEngine.diff(Map.of("a", after), Map.of(), Set.of(), Set.of("a"))
                .isEmpty());
    }

    private static Torrent torrent(String id, long size, long done, boolean paused, String error) {
        return new Torrent(
                id,
                id,
                size,
                done,
                size == 0 ? 0 : (double) done / size,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "/dl",
                paused,
                false,
                0,
                "",
                List.of(),
                error,
                List.of(),
                false);
    }

    // ---- adding -----------------------------------------------------------------------------------

    @Test
    void addSendsTheMetainfoAndTheDialogsChoices() throws Exception {
        TransmissionEngine e = engine();
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        e.addListener(events::add);
        byte[] torrent = payloadTorrent();
        server.answer(
                "torrent-add",
                StubRpcServer.success(
                        Map.of("torrent-added", Map.of("hashString", PAYLOAD, "id", 1L, "name", "payload.bin"))));
        AddRequest request = new AddRequest(
                new AddRequest.TorrentFile(torrent, "payload.torrent"),
                Path.of("/srv/downloads/tv"),
                true,
                true,
                false,
                AddRequest.StopCondition.NONE,
                Map.of(0, Priority.IGNORE, 1, Priority.HIGH, 2, Priority.LOW),
                new TorrentLabels("Movies", List.of("tv", "x")));
        Torrent added = e.add(request);
        assertEquals(PAYLOAD, added.id());
        assertEquals("payload.bin", added.name());

        Map<String, Object> args = server.last("torrent-add").arguments();
        assertEquals(Base64.getEncoder().encodeToString(torrent), args.get("metainfo"));
        assertNull(args.get("filename"));
        assertEquals("/srv/downloads/tv", args.get("download-dir"));
        assertEquals(Boolean.TRUE, args.get("paused"));
        assertEquals(List.of("category:Movies", "tv", "x"), args.get("labels"));
        assertEquals(List.of(0L), args.get("files-unwanted"));
        assertEquals(List.of(1L), args.get("priority-high"));
        assertEquals(List.of(2L), args.get("priority-low"));
        assertEquals(Boolean.TRUE, args.get("sequential_download"));
        assertEquals(1, events.size());
        assertEquals(EngineEvent.Kind.ADDED, events.get(0).kind());
        assertEquals("Added payload.bin", events.get(0).message());

        // The next poll does not announce it again.
        e.torrents();
        e.torrents();
        assertEquals(
                1,
                events.stream()
                        .filter(ev -> ev.kind() == EngineEvent.Kind.ADDED)
                        .count());
    }

    @Test
    void aMagnetGoesAsFilenameAndTheDefaultFolderMeansTheDaemons() throws Exception {
        TransmissionEngine e = engine();
        server.answer(
                "torrent-add",
                StubRpcServer.success(
                        Map.of("torrent-added", Map.of("hashString", MAGNET_ID, "id", 2L, "name", "Torrenta test"))));
        String magnet = "magnet:?xt=urn:btih:" + MAGNET_ID + "&dn=Torrenta%20test";
        Torrent added = e.add(magnet);
        assertEquals(MAGNET_ID, added.id());
        Map<String, Object> args = server.last("torrent-add").arguments();
        assertEquals(magnet, args.get("filename"));
        assertNull(args.get("download-dir"), "this machine's default folder is not a path on the daemon");
        assertNull(args.get("labels"));
        assertEquals(Boolean.FALSE, args.get("paused"));

        // A drive path is sent as it is on every platform: on Windows Path.of normalises it to
        // single backslashes, elsewhere it is one odd name; either way it is the Path's own text.
        Path drive = Path.of("C:\\dl\\tv");
        Map<String, Object> explicit = TransmissionEngine.addArguments(
                AddRequest.defaults(new AddRequest.Magnet(magnet), drive), localDefault, e.capabilities());
        assertEquals(drive.toString(), explicit.get("download-dir"), "a Windows drive path is left alone");
        assertEquals(
                "/srv/dl",
                TransmissionEngine.remotePath(Path.of("/srv/dl")),
                "a POSIX path, with forward slashes on every platform");
        assertEquals("sub/dir", TransmissionEngine.remotePath(Path.of("sub", "dir")));

        Map<String, Object> noSequential = TransmissionEngine.addArguments(
                new AddRequest(
                        new AddRequest.Magnet(magnet),
                        Path.of("/x"),
                        false,
                        true,
                        false,
                        AddRequest.StopCondition.NONE,
                        Map.of(),
                        TorrentLabels.NONE),
                localDefault,
                TransmissionEngine.capabilitiesFor(17, false));
        assertNull(noSequential.get("sequential_download"), "an older daemon is not sent a key it does not know");
    }

    @Test
    void aDuplicateIsRefusedWithTheName() throws Exception {
        TransmissionEngine e = engine();
        server.answer(
                "torrent-add",
                StubRpcServer.success(
                        Map.of("torrent-duplicate", Map.of("hashString", PAYLOAD, "id", 1L, "name", "payload.bin"))));
        EngineException dup = assertThrows(EngineException.class, () -> e.add("magnet:?xt=urn:btih:" + PAYLOAD));
        assertEquals("payload.bin is already in the list", dup.getMessage());

        server.answer("torrent-add", "{\"arguments\":{},\"result\":\"unrecognized info\"}");
        EngineException bad = assertThrows(EngineException.class, () -> e.add("magnet:?xt=urn:btih:" + MAGNET_ID));
        assertEquals("unrecognized info", bad.getMessage());
        assertThrows(EngineException.class, () -> e.add("/no/such/file.torrent"));
    }

    @Test
    void inspectReadsTheTorrentWithoutTheDaemon() throws Exception {
        TransmissionEngine e = engine();
        TorrentPreview preview = e.inspect(payloadTorrent());
        assertEquals(PAYLOAD, preview.id(), "the v1 info-hash is the daemon's hashString");
        assertEquals("payload.bin", preview.name());
        assertEquals(307200, preview.size());
        assertEquals(1, preview.files().size());
        assertEquals("payload.bin", preview.files().get(0).path());
        assertEquals(List.of("udp://tracker.example.org:6969/announce"), preview.trackers());
        assertTrue(preview.v1());
        assertFalse(preview.v2());
        assertTrue(preview.createdBy().startsWith("Transmission/"));
        assertFalse(preview.hasRootFolder());
        assertThrows(EngineException.class, () -> e.inspect("not bencode".getBytes(StandardCharsets.UTF_8)));
        assertTrue(server.calls("torrent-get").isEmpty(), "inspect asks the daemon nothing");
    }

    // ---- per-torrent actions ----------------------------------------------------------------------

    @Test
    void actionsAreTheirRpcMethodsOnTheHash() throws Exception {
        TransmissionEngine e = engine();
        for (String m : List.of(
                "torrent-stop",
                "torrent-start",
                "torrent-start-now",
                "torrent-verify",
                "torrent-reannounce",
                "torrent-remove",
                "torrent-set")) {
            server.answer(m, StubRpcServer.success());
        }
        e.pause(PAYLOAD);
        e.resume(PAYLOAD);
        e.forceResume(PAYLOAD);
        e.recheck(PAYLOAD);
        e.reannounce(PAYLOAD);
        for (String m :
                List.of("torrent-stop", "torrent-start", "torrent-start-now", "torrent-verify", "torrent-reannounce")) {
            assertEquals(List.of(PAYLOAD), server.last(m).arguments().get("ids"), m);
        }
        e.setSequential(PAYLOAD, true);
        assertEquals(Boolean.TRUE, server.last("torrent-set").arguments().get("sequential_download"));

        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        e.addListener(events::add);
        e.torrents();
        e.remove(PAYLOAD, true);
        Map<String, Object> removal = server.last("torrent-remove").arguments();
        assertEquals(List.of(PAYLOAD), removal.get("ids"));
        assertEquals(Boolean.TRUE, removal.get("delete-local-data"));
        assertEquals(
                "Removed payload.bin and its files",
                events.get(events.size() - 1).message());
        // The poll that no longer lists it says nothing more.
        server.answer("torrent-get", StubRpcServer.success(Map.of("torrents", List.of())));
        events.clear();
        e.torrents();
        assertEquals(
                List.of("Removed Torrenta test"),
                events.stream().map(EngineEvent::message).toList(),
                "only the other one");
    }

    @Test
    void limitsConvertBetweenBytesAndTransmissionsKilobytes() throws Exception {
        TransmissionEngine e = engine();
        server.answer(
                "torrent-get",
                StubRpcServer.success(Map.of(
                        "torrents",
                        List.of(Map.of(
                                "downloadLimit",
                                100L,
                                "downloadLimited",
                                true,
                                "uploadLimit",
                                55L,
                                "uploadLimited",
                                false,
                                "peer-limit",
                                7L)))));
        TorrentLimits limits = e.limits(PAYLOAD);
        assertEquals(new TorrentLimits(100_000, 0, 7, 0, false), limits);
        assertEquals(List.of(PAYLOAD), server.last("torrent-get").arguments().get("ids"));

        server.answer("torrent-set", StubRpcServer.success());
        e.setLimits(PAYLOAD, new TorrentLimits(1500, 0, 9, 4, true));
        Map<String, Object> set = server.last("torrent-set").arguments();
        assertEquals(2L, set.get("downloadLimit"), "1500 B/s rounds up to 2 KB/s");
        assertEquals(Boolean.TRUE, set.get("downloadLimited"));
        assertEquals(Boolean.FALSE, set.get("uploadLimited"));
        assertNull(set.get("uploadLimit"));
        assertEquals(9L, set.get("peer-limit"));
        assertFalse(set.containsKey("uploadSlots"), "no such thing on a daemon");
        assertEquals(1, TransmissionEngine.kbps(1));
        assertEquals(1, TransmissionEngine.kbps(1000));
        assertEquals(2, TransmissionEngine.kbps(1001));
    }

    @Test
    void categoryAndTagsLiveInTheDaemonsLabels() throws Exception {
        TransmissionEngine e = engine();
        server.answer("torrent-set", StubRpcServer.success());
        assertEquals("", e.category(PAYLOAD));
        assertEquals(List.of("tv", "x"), e.tags(PAYLOAD));
        e.setCategory(PAYLOAD, "Movies");
        assertEquals(
                List.of("category:Movies", "tv", "x"),
                server.last("torrent-set").arguments().get("labels"));
        e.setTags(PAYLOAD, List.of("b", "a", "a"));
        assertEquals(
                List.of("a", "b"),
                server.last("torrent-set").arguments().get("labels"),
                "no category to keep, tags normalised");

        assertEquals(
                new TorrentLabels("Movies", List.of("linux")),
                TransmissionLabels.fromLabels(List.of("linux", "category:Movies")));
        assertEquals(
                new TorrentLabels("A", List.of("category:B")),
                TransmissionLabels.fromLabels(List.of("category:A", "category:B")),
                "a second category label is a tag");
        assertEquals(List.of(), TransmissionLabels.toLabels(TorrentLabels.NONE));
    }

    @Test
    void filePrioritiesAndTrackersAreTorrentSetFields() throws Exception {
        TransmissionEngine e = engine();
        server.answer("torrent-set", StubRpcServer.success());
        e.setFilePriority(PAYLOAD, 3, Priority.IGNORE);
        assertEquals(List.of(3L), server.last("torrent-set").arguments().get("files-unwanted"));
        e.setFilePriority(PAYLOAD, 3, Priority.HIGH);
        Map<String, Object> high = server.last("torrent-set").arguments();
        assertEquals(List.of(3L), high.get("files-wanted"));
        assertEquals(List.of(3L), high.get("priority-high"));

        server.answer(
                "torrent-get",
                StubRpcServer.success(Map.of(
                        "torrents",
                        List.of(Map.of("trackerList", "udp://a.example/announce\n\nudp://b.example/announce\n")))));
        e.addTrackers(PAYLOAD, List.of("udp://b.example/announce", "udp://c.example/announce"));
        assertEquals(
                "udp://a.example/announce\n\nudp://b.example/announce\n\nudp://c.example/announce\n",
                server.last("torrent-set").arguments().get("trackerList"),
                "the one it had already is not added twice; the new one is its own tier");
        e.removeTracker(PAYLOAD, "udp://a.example/announce");
        assertEquals(
                "udp://b.example/announce\n",
                server.last("torrent-set").arguments().get("trackerList"));
        assertEquals(
                List.of(List.of("a", "b"), List.of("c")),
                TransmissionEngine.tiers("a\nb\n\nc\n"),
                "a blank line separates tiers");

        server.answer(
                "torrent-get",
                StubRpcServer.success(
                        Map.of("torrents", List.of(Map.of("magnetLink", "magnet:?xt=urn:btih:" + PAYLOAD + "&dn=p")))));
        assertEquals("magnet:?xt=urn:btih:" + PAYLOAD + "&dn=p", e.magnetLink(PAYLOAD));
    }

    // ---- detail -----------------------------------------------------------------------------------

    @Test
    void detailDecodesThePiecesFilesPeersAndTrackers() throws Exception {
        TransmissionEngine e = engine();
        TorrentDetail detail = e.detail(PAYLOAD);
        StubRpcServer.Call call = server.last("torrent-get");
        assertEquals(List.of(PAYLOAD), call.arguments().get("ids"));
        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) call.arguments().get("fields");
        assertTrue(fields.contains("pieces") && fields.contains("trackerStats") && fields.contains("peers"));

        TorrentDetail.General g = detail.general();
        assertEquals(PAYLOAD, g.id());
        assertEquals(PAYLOAD, g.infoHashV1());
        assertEquals("", g.infoHashV2(), "Transmission 4 exposes no v2 hash");
        assertEquals(19, g.pieceCount());
        assertEquals(16384, g.pieceLength());
        assertEquals(19, g.piecesHave());
        assertEquals(19, detail.pieces().length);
        for (boolean p : detail.pieces()) assertTrue(p, "every piece of a seed is there");
        assertTrue(g.availability() >= 1.0, "a complete torrent is at least one copy: " + g.availability());
        assertEquals(3, g.activeSeconds());
        assertEquals(3, g.seedingSeconds());
        assertEquals("udp://tracker.example.org:6969/announce", g.currentTracker());
        assertFalse(g.isPrivate());

        assertEquals(1, detail.files().size());
        TorrentDetail.FileStatus f = detail.files().get(0);
        assertEquals(0, f.index());
        assertEquals("payload.bin", f.path());
        assertEquals(307200, f.size());
        assertEquals(307200, f.done());
        assertEquals(Priority.NORMAL, f.priority());

        assertEquals(1, detail.trackers().size());
        TorrentDetail.Tracker t = detail.trackers().get(0);
        assertEquals("udp://tracker.example.org:6969/announce", t.url());
        assertEquals(0, t.tier());
        assertEquals("updating", t.status(), "recorded mid-announce: announceState 3, nothing answered yet");
        assertTrue(detail.peers().isEmpty());
    }

    @Test
    void thePiecesBitfieldHasTheFirstPieceInTheHighBit() {
        // 0xA0 = 1010 0000: pieces 0 and 2 of five.
        boolean[] pieces = TransmissionEngine.pieces(Base64.getEncoder().encodeToString(new byte[] {(byte) 0xA0}), 5);
        assertArrayEquals(new boolean[] {true, false, true, false, false}, pieces);
        assertArrayEquals(new boolean[] {false, false}, TransmissionEngine.pieces("", 2));
        assertArrayEquals(new boolean[] {false, false}, TransmissionEngine.pieces("%%%", 2), "garbage is no pieces");
        assertEquals(0, TransmissionEngine.pieces("AA==", 0).length);
        // A bitfield shorter than the count leaves the rest false.
        assertArrayEquals(
                new boolean[] {true, true, true, true, true, true, true, true, false, false},
                TransmissionEngine.pieces(Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF}), 10));
    }

    @Test
    void trackerStatusIsWordedAsLibtorrentsIs() {
        assertEquals(
                "not contacted",
                TransmissionEngine.tracker(Map.of("announce", "u", "hasAnnounced", false))
                        .status());
        TorrentDetail.Tracker working = TransmissionEngine.tracker(
                Map.of("announce", "u", "hasAnnounced", true, "lastAnnounceSucceeded", true, "announceState", 1L));
        assertEquals("working", working.status());
        assertEquals("", working.message());
        TorrentDetail.Tracker updating = TransmissionEngine.tracker(Map.of("announce", "u", "announceState", 3L));
        assertEquals("updating", updating.status());
        TorrentDetail.Tracker failed = TransmissionEngine.tracker(Map.of(
                "announce",
                "u",
                "hasAnnounced",
                true,
                "lastAnnounceSucceeded",
                false,
                "lastAnnounceResult",
                "Could not connect to tracker",
                "tier",
                2L));
        assertEquals("error", failed.status());
        assertEquals("Could not connect to tracker", failed.message());
        assertEquals(1, failed.fails());
        assertEquals(2, failed.tier());
        assertEquals(Priority.LOW, TransmissionEngine.priority(-1));
        assertEquals(Priority.HIGH, TransmissionEngine.priority(1));
        assertEquals(Priority.NORMAL, TransmissionEngine.priority(0));
    }

    @Test
    void detailMapsPeersAndPartialFiles() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("hashString", "ABCD");
        t.put("pieceCount", 4L);
        t.put("pieces", Base64.getEncoder().encodeToString(new byte[] {(byte) 0xC0}));
        t.put("availability", List.of(-1L, -1L, 2L, 0L));
        t.put(
                "files",
                List.of(
                        Map.of("name", "dir\\a.bin", "length", 10L, "bytesCompleted", 4L),
                        Map.of("name", "dir/b.bin", "length", 5L, "bytesCompleted", 5L)));
        t.put("fileStats", List.of(Map.of("wanted", true, "priority", -1L), Map.of("wanted", false, "priority", 0L)));
        t.put(
                "peers",
                List.of(Map.of(
                        "address",
                        "10.0.0.5",
                        "port",
                        51413L,
                        "clientName",
                        "Transmission 4.0",
                        "flagStr",
                        "TDI",
                        "progress",
                        0.5,
                        "rateToClient",
                        1000L,
                        "rateToPeer",
                        20L)));
        t.put(
                "trackerStats",
                List.of(Map.of(
                        "announce",
                        "u",
                        "nextAnnounceTime",
                        1_000_100L,
                        "hasAnnounced",
                        true,
                        "lastAnnounceSucceeded",
                        true)));
        TorrentDetail d = TransmissionEngine.detailOf(t, 1_000_000L);
        assertEquals("abcd", d.general().id(), "ids are lower-case hex");
        assertArrayEquals(new boolean[] {true, true, false, false}, d.pieces());
        assertEquals(2, d.general().piecesHave());
        // Two pieces ours, one with two copies among peers, one with none: 0 whole copies + 3/4.
        assertEquals(0.75, d.general().availability(), 1e-9);
        assertEquals(100, d.general().nextAnnounceSeconds());
        assertEquals("dir/a.bin", d.files().get(0).path());
        assertEquals(Priority.LOW, d.files().get(0).priority());
        assertEquals(Priority.IGNORE, d.files().get(1).priority(), "unwanted is skip, whatever the priority says");
        TorrentDetail.Peer p = d.peers().get(0);
        assertEquals("10.0.0.5:51413", p.address());
        assertEquals("Transmission 4.0", p.client());
        assertEquals("TDI", p.flags());
        assertEquals(0.5, p.progress());
        assertEquals(1000, p.downRate());
        assertEquals(20, p.upRate());
    }

    // ---- session settings -------------------------------------------------------------------------

    @Test
    void applySettingsSendsOnlyWhatChangedSinceTheLastTime() throws Exception {
        EngineSettings start = EngineSettings.defaults();
        TransmissionEngine e = engine(start);
        server.answer("session-set", StubRpcServer.success());
        e.applySettings(start);
        assertTrue(server.calls("session-set").isEmpty(), "equal settings touch the daemon not at all");

        EngineSettings.Speed speed = start.speed();
        EngineSettings faster = start.withSpeed(new EngineSettings.Speed(
                100,
                0,
                speed.alternativeDownload(),
                speed.alternativeUpload(),
                speed.alternativeOn(),
                speed.scheduleOn(),
                speed.scheduleFrom(),
                speed.scheduleTo(),
                speed.scheduleDays(),
                speed.countOverhead()));
        e.applySettings(faster);
        Map<String, Object> sent = server.last("session-set").arguments();
        assertEquals(
                Set.of("speed-limit-down", "speed-limit-down-enabled"),
                sent.keySet(),
                "the port and the rest stay the daemon's");
        assertEquals(102L, sent.get("speed-limit-down"), "100 KiB/s is 102 KB/s");
        assertEquals(Boolean.TRUE, sent.get("speed-limit-down-enabled"));

        EngineSettings dht = faster.withProtocol(new EngineSettings.Protocol(
                false,
                true,
                true,
                true,
                EngineSettings.Protocol.Encryption.REQUIRE,
                false,
                List.of("udp://t.example/announce")));
        e.applySettings(dht);
        sent = server.last("session-set").arguments();
        assertEquals(Set.of("dht-enabled", "encryption", "default-trackers"), sent.keySet());
        assertEquals(Boolean.FALSE, sent.get("dht-enabled"));
        assertEquals("required", sent.get("encryption"));
        assertEquals("udp://t.example/announce", sent.get("default-trackers"));
    }

    @Test
    void sessionArgumentsNameEveryKeyTheDaemonHasAWordFor() {
        EngineSettings s = EngineSettings.defaults()
                .withQueue(new EngineSettings.Queue(0, 4, 99, false, new ShareLimit(1.5, 90, ShareLimit.Action.PAUSE)))
                .withSpeed(new EngineSettings.Speed(
                        0,
                        50,
                        10,
                        5,
                        true,
                        true,
                        22 * 60,
                        8 * 60,
                        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
                        true));
        Map<String, Object> a = TransmissionEngine.sessionArguments(s);
        assertEquals(6881L, a.get("peer-port"));
        assertEquals(Boolean.FALSE, a.get("peer-port-random-on-start"));
        assertEquals(Boolean.TRUE, a.get("port-forwarding-enabled"));
        assertEquals(500L, a.get("peer-limit-global"));
        assertEquals(100L, a.get("peer-limit-per-torrent"));
        assertEquals("preferred", a.get("encryption"));
        assertEquals(Boolean.FALSE, a.get("speed-limit-down-enabled"));
        assertEquals(0L, a.get("speed-limit-down"));
        assertEquals(Boolean.TRUE, a.get("speed-limit-up-enabled"));
        assertEquals(51L, a.get("speed-limit-up"));
        assertEquals(10L, a.get("alt-speed-down"));
        assertEquals(Boolean.TRUE, a.get("alt-speed-enabled"));
        assertEquals(Boolean.TRUE, a.get("alt-speed-time-enabled"));
        assertEquals(1320L, a.get("alt-speed-time-begin"));
        assertEquals(480L, a.get("alt-speed-time-end"));
        assertEquals(3L, a.get("alt-speed-time-day"), "Sunday is 1, Monday 2");
        assertEquals(Boolean.FALSE, a.get("download-queue-enabled"), "0 active downloads is no queue");
        assertEquals(Boolean.TRUE, a.get("seed-queue-enabled"));
        assertEquals(4L, a.get("seed-queue-size"));
        assertEquals(Boolean.FALSE, a.get("queue-stalled-enabled"));
        assertEquals(Boolean.TRUE, a.get("seedRatioLimited"));
        assertEquals(1.5, a.get("seedRatioLimit"));
        assertEquals(Boolean.TRUE, a.get("idle-seeding-limit-enabled"));
        assertEquals(90L, a.get("idle-seeding-limit"));
        assertFalse(a.containsKey("proxy"), "no word for a proxy");
        assertEquals(127, TransmissionEngine.dayMask(EnumSet.allOf(DayOfWeek.class)));
        assertEquals(64, TransmissionEngine.dayMask(EnumSet.of(DayOfWeek.SATURDAY)));
    }

    @Test
    void refreshLimitsAsksTheDaemonWhetherItsAlternativePairIsOn() throws Exception {
        TransmissionEngine e = engine();
        server.answer("session-get", StubRpcServer.success(Map.of("alt-speed-enabled", true)));
        assertTrue(e.refreshLimits(java.time.LocalDateTime.now()));
        assertEquals(
                List.of("alt-speed-enabled"),
                server.last("session-get").arguments().get("fields"));
    }

    @Test
    void theBlocklistIsTheDaemonsOwnFetch() throws Exception {
        TransmissionEngine e = engine();
        server.answer("session-set", StubRpcServer.success());
        server.answer("blocklist-update", StubRpcServer.success(Map.of("blocklist-size", 123456L)));
        assertEquals(123456, e.setBlocklist("http://list.example/level1.gz", true));
        Map<String, Object> set = server.last("session-set").arguments();
        assertEquals("http://list.example/level1.gz", set.get("blocklist-url"));
        assertEquals(Boolean.TRUE, set.get("blocklist-enabled"));
        assertEquals(0, e.setBlocklist("", false));
        assertEquals(1, server.calls("blocklist-update").size(), "off fetches nothing");
        server.answer(
                "blocklist-update", "{\"arguments\":{},\"result\":\"Couldn't fetch blocklist: Not Found (404)\"}");
        EngineException failed =
                assertThrows(EngineException.class, () -> e.setBlocklist("http://list.example/x", true));
        assertEquals("Couldn't fetch blocklist: Not Found (404)", failed.getMessage());
        assertEquals(0, e.blockedPeers());
    }

    @Test
    void statsCountTorrentsByStateAndLeaveUnknownWhatTheDaemonLacks() throws Exception {
        Map<String, Object> s = Json.obj(Json.obj(Json.parse(fixture("session-stats.json"))), "arguments");
        SessionStats stats =
                TransmissionEngine.statsOf(s, List.of(torrent("a", 10, 4, false, ""), torrent("b", 10, 10, true, "x")));
        assertEquals(0, stats.sessionDownload());
        assertEquals(3, stats.allTimeSeconds());
        assertEquals(1, stats.torrents().downloading());
        assertEquals(1, stats.torrents().stopped());
        assertEquals(1, stats.torrents().error());
        assertEquals(SessionStats.UNKNOWN, stats.diskBlocksRead());
        assertEquals(SessionStats.UNKNOWN, stats.totalReceived());
    }
}
