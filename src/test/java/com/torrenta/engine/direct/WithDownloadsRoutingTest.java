package com.torrenta.engine.direct;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.LinkRequest;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which side each call reaches: a fake torrent engine beside a list of links that is never
 * started, so no curl runs and every download stays where the test put it.
 */
class WithDownloadsRoutingTest {

    private static final String TORRENT = "aa".repeat(20);
    private static final String URL = "https://files.example.org/pub/disk.iso";

    @TempDir
    Path dir;

    private FakeEngine fake;
    private DirectDownloads links;
    private WithDownloads engine;
    private String link;

    @BeforeEach
    void open() throws EngineException {
        fake = new FakeEngine().put(FakeEngine.torrent(TORRENT, "Ubuntu"));
        links = new DirectDownloads(
                dir.resolve("config"),
                () -> dir.resolve("dl").toString(),
                () -> DirectDownloads.Options.DEFAULTS,
                "Torrenta/test");
        engine = new WithDownloads(fake, links, EngineSettings.defaults());
        link = engine.add(URL).id();
    }

    @AfterEach
    void close() {
        links.stop();
    }

    @Test
    void aLinkIsADownloadAndAnythingElseATorrent() throws EngineException {
        assertTrue(DirectDownload.isId(link));
        assertEquals(link, engine.add(" " + URL + " ").id(), "the same link is the same download");
        engine.add("magnet:?xt=urn:btih:" + "bb".repeat(20));
        assertTrue(fake.calls().contains("add magnet:?xt=urn:btih:" + "bb".repeat(20)));

        Torrent named = engine.addLink(new LinkRequest(
                "https://files.example.org/other.bin", dir.resolve("elsewhere"), "kept name.bin", true));
        assertEquals("kept name.bin", named.name());
        assertTrue(Files.isDirectory(dir.resolve("elsewhere")));
        Torrent here = engine.addLink(new LinkRequest("https://files.example.org/third.bin", null, null, true));
        assertEquals(dir.resolve("dl").toString(), here.savePath());

        AddRequest request = AddRequest.defaults(new AddRequest.Magnet("magnet:?xt=urn:btih:cc"), dir);
        engine.add(request);
        assertTrue(fake.calls().stream().anyMatch(c -> c.startsWith("add Magnet")));

        List<String> ids = engine.torrents().stream().map(Torrent::id).toList();
        assertTrue(ids.contains(TORRENT));
        assertTrue(ids.contains(link));
        assertEquals(fake.rates().download(), engine.rates().download(), "no curl runs, so the links add nothing");
        assertEquals(fake.rates().upload(), engine.rates().upload());
    }

    @Test
    void theSessionsQuestionsGoToTheTorrentEngine() throws Exception {
        assertEquals("fake engine", engine.description());
        assertEquals(fake.capabilities(), engine.capabilities());
        assertEquals(fake.downloadDir(), engine.downloadDir());
        assertEquals(fake, engine.torrentEngine());
        assertEquals(fake.stats(), engine.stats());
        assertEquals(fake.connectivity(), engine.connectivity());
        assertEquals(0, engine.blockedPeers());
        assertEquals(fake.contentIndex(), engine.contentIndex());
        assertEquals(fake.followings(), engine.followings());
        engine.flush();
        engine.rebind();
        engine.setIpFilter(List.of());
        assertFalse(engine.refreshLimits(true));
        engine.applySettings(EngineSettings.defaults());
        assertTrue(fake.calls().containsAll(List.of("flush", "setIpFilter 0", "applySettings")));

        // What the fake has no answer for fails as the fake does, through the wrapper.
        assertThrows(EngineException.class, () -> engine.inspect(new byte[3]));
        assertThrows(
                EngineException.class,
                () -> engine.create(com.torrenta.engine.CreateRequest.defaults(dir), (done, total) -> true));
        assertThrows(EngineException.class, () -> engine.probe(new AddRequest.Magnet("magnet:?"), false));
        FakeEngine.Probe probe = new FakeEngine.Probe(new byte[0], 0);
        engine.add(AddRequest.defaults(new AddRequest.Magnet("magnet:?xt=urn:btih:dd"), dir), probe);
        assertTrue(probe.cancelled(), "the probe ends before the add");
        assertThrows(
                EngineException.class,
                () -> engine.adopt(new Engine.Adoption(new byte[0], null, "/", TorrentLabels.NONE, "x", false)));
        assertThrows(EngineException.class, () -> engine.stream(TORRENT, 0));
        assertThrows(EngineException.class, () -> engine.newMutableKey());
        assertThrows(EngineException.class, () -> engine.lookupMutable(null, java.time.Duration.ZERO));
        assertThrows(
                EngineException.class,
                () -> engine.putMutable(null, new byte[0], new byte[0], java.time.Duration.ZERO));
        assertThrows(EngineException.class, () -> engine.setBlocklist("https://lists.example.org/l.p2p", true));
        assertThrows(EngineException.class, () -> engine.setFollowing(TORRENT, null));
    }

    @Test
    void aTorrentsCommandsReachTheTorrentEngine() throws EngineException {
        engine.pause(TORRENT);
        engine.resume(TORRENT);
        engine.forceResume(TORRENT);
        engine.recheck(TORRENT);
        engine.reannounce(TORRENT);
        engine.setSequential(TORRENT, true);
        engine.queueMove(TORRENT, Engine.QueueMove.TOP);
        engine.moveStorage(TORRENT, "/srv", false);
        engine.setSuperSeeding(TORRENT, true);
        TorrentLimits limits = new TorrentLimits(100, 200, 0, 0, false);
        engine.setLimits(TORRENT, limits);
        assertEquals(limits, engine.limits(TORRENT));
        engine.setCategory(TORRENT, "Linux");
        assertEquals("Linux", engine.category(TORRENT));
        engine.setTags(TORRENT, List.of("iso"));
        assertEquals(List.of("iso"), engine.tags(TORRENT));
        engine.exportTorrent(TORRENT);
        engine.rename(TORRENT, "Ubuntu 26.04");
        assertTrue(engine.magnetLink(TORRENT).startsWith("magnet:"));
        assertEquals(TORRENT, engine.detail(TORRENT, Set.of()).general().id());
        engine.setFilePriority(TORRENT, 0, Priority.HIGH);
        engine.renamePath(TORRENT, "a", "b");
        engine.addTrackers(TORRENT, List.of("udp://t"));
        engine.removeTracker(TORRENT, "udp://t");
        assertEquals(List.of(), engine.webSeeds(TORRENT));
        engine.addWebSeeds(TORRENT, List.of("https://w/"));
        engine.removeWebSeed(TORRENT, "https://w/");
        Engine.Detached out = engine.detach(TORRENT);
        engine.reattach(out);
        assertTrue(engine.onDisk(TORRENT).isEmpty(), "the fake has no files");
        engine.remove(TORRENT, false);

        for (String verb : List.of(
                "pause",
                "resume",
                "forceResume",
                "recheck",
                "reannounce",
                "setSequential",
                "queueMove",
                "moveStorage",
                "setSuperSeeding",
                "setLimits",
                "setCategory",
                "setTags",
                "exportTorrent",
                "rename",
                "setFilePriority",
                "renamePath",
                "addTrackers",
                "removeTracker",
                "addWebSeeds",
                "removeWebSeed",
                "detach",
                "reattach",
                "remove")) {
            assertTrue(fake.calls().stream().anyMatch(c -> c.startsWith(verb + " " + TORRENT)), verb);
        }
    }

    @Test
    void whatOnlyATorrentCanDoIsRefusedForADownloadByName() throws EngineException {
        String before = String.join("\n", fake.calls());
        List<Refused> refused = List.of(
                () -> engine.recheck(link),
                () -> engine.reannounce(link),
                () -> engine.setSequential(link, true),
                () -> engine.queueMove(link, Engine.QueueMove.UP),
                () -> engine.setSuperSeeding(link, true),
                () -> engine.exportTorrent(link),
                () -> engine.magnetLink(link),
                () -> engine.setFilePriority(link, 0, Priority.LOW),
                () -> engine.renamePath(link, "a", "b"),
                () -> engine.addTrackers(link, List.of("udp://t")),
                () -> engine.removeTracker(link, "udp://t"),
                () -> engine.addWebSeeds(link, List.of("https://w/")),
                () -> engine.removeWebSeed(link, "https://w/"),
                () -> engine.detach(link),
                () -> engine.reattach(new Engine.Detached(link, "disk.iso", new byte[0], null, null, 0)),
                () -> engine.stream(link, 0),
                () -> engine.setFollowing(link, null));
        for (Refused call : refused) {
            EngineException e = assertThrows(EngineException.class, call::run);
            assertFalse(e.getMessage().isBlank());
        }
        assertTrue(
                assertThrows(EngineException.class, () -> engine.recheck(link))
                        .getMessage()
                        .contains("disk.iso"),
                "the refusal names the download");
        assertEquals(before, String.join("\n", fake.calls()), "nothing reached the torrent engine");
        assertEquals(List.of(), engine.webSeeds(link));
    }

    @Test
    void aDownloadsOwnCommandsReachTheList() throws Exception {
        engine.pause(link);
        engine.resume(link);
        engine.forceResume(link);
        engine.setCategory(link, "ISOs");
        assertEquals("ISOs", engine.category(link));
        engine.setTags(link, List.of("b", "a"));
        assertEquals(List.of("a", "b"), engine.tags(link));
        engine.setLimits(link, new TorrentLimits(5000, 9, 9, 9, true));
        assertEquals(new TorrentLimits(5000, 0, 0, 0, false), engine.limits(link), "only the rate is a link's");
        engine.rename(link, "The disk");
        assertEquals(
                "The disk",
                engine.torrents().stream()
                        .filter(t -> t.id().equals(link))
                        .findFirst()
                        .orElseThrow()
                        .name());
        TorrentDetail detail = engine.detail(link, Set.of());
        assertEquals(URL, detail.general().currentTracker());
        assertEquals(1, detail.files().size(), "every part, whatever was asked");
        assertTrue(
                engine.onDisk(link).orElseThrow().files().getFirst().toString().endsWith(".part"));

        Path moved = dir.resolve("moved");
        engine.moveStorage(link, moved.toString(), true);
        assertEquals(moved.toString(), links.get(link).orElseThrow().savePath());

        List<EngineEvent> heard = new CopyOnWriteArrayList<>();
        Consumer<EngineEvent> listener = heard::add;
        engine.addListener(listener);
        engine.remove(link, true);
        assertFalse(links.has(link));
        assertTrue(heard.stream().anyMatch(e -> link.equals(e.torrentId())), "the list's events are heard");
        engine.removeListener(listener);
        String other = engine.add("https://files.example.org/again.iso").id();
        assertTrue(heard.stream().noneMatch(e -> other.equals(e.torrentId())));
        assertTrue(fake.calls().stream().noneMatch(c -> c.contains(link)));
    }

    @Test
    void startAndStopReachBothAndTheLinksShareTheLimitInForce() throws EngineException {
        EngineSettings.Speed speed = new EngineSettings.Speed(100, 0, 10, 0, false, false, 0, 0, Set.of(), false);
        EngineSettings d = EngineSettings.defaults();
        EngineSettings limited = new EngineSettings(
                d.listenPort(),
                d.randomPort(),
                d.listenInterface(),
                d.ipv6(),
                d.upnp(),
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                EngineSettings.ProxyType.SOCKS5,
                "proxy.lan",
                1080,
                "",
                "",
                false,
                d.protocol(),
                speed,
                d.queue());
        engine.applySettings(limited);
        engine.refreshLimits(true);
        engine.pause(link); // started, the list would run curl for it
        engine.start();
        assertTrue(fake.started());
        assertTrue(links.has(link), "the list is read back from what it saved");
        engine.stop();
        assertTrue(fake.stopped());
        assertTrue(Files.isRegularFile(dir.resolve("config").resolve(DirectDownloads.FILE)));
    }

    @FunctionalInterface
    private interface Refused {
        void run() throws Exception;
    }
}
