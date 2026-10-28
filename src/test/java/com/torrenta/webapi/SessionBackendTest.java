package com.torrenta.webapi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Connectivity;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.TorrentState;
import com.torrenta.session.Session;
import com.torrenta.session.SpeedMode;
import com.torrenta.store.LabelStore;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API's backend over a real {@link Session} polling a fake engine. */
class SessionBackendTest {

    private static final String MOVING = "aa".repeat(20);
    private static final String QUIET = "bb".repeat(20);

    @TempDir
    Path dir;

    private Settings settings;
    private LabelStore labels;
    private Session session;
    private SessionBackend backend;
    private final AtomicInteger polls = new AtomicInteger();

    @BeforeEach
    void open() {
        settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.DOWNLOAD_DIR, dir.resolve("dl").toString());
        labels = new LabelStore(dir);
        session = new Session(
                settings,
                dir,
                labels,
                new com.torrenta.store.WatchFolderStore(dir),
                new com.torrenta.rss.RssStore(dir),
                Runnable::run,
                new Session.Host() {
                    @Override
                    public void seedingDone(EngineEvent event, Torrent torrent, boolean removed) {}

                    @Override
                    public void limitsRefreshed(
                            boolean alternative, SpeedMode.Reason reason, SpeedMode.Reason paused) {}

                    @Override
                    public void problem(EngineEvent event) {}
                });
        backend = new SessionBackend(session, settings, labels);
        session.addListener(p -> polls.incrementAndGet());
    }

    @AfterEach
    void close() {
        session.close();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void withNoEngineTheListsAreEmptyAndAnAddIsRefused() {
        assertNull(backend.engine());
        assertEquals(List.of(), backend.torrents());
        assertTrue(backend.detail(MOVING).isEmpty());
        assertEquals("", backend.magnet(MOVING));
        assertEquals(TorrentLimits.NONE, backend.limits(MOVING));
        WebApi.Transfer t = backend.transfer();
        assertEquals("disconnected", t.connection());
        assertEquals(0, t.downloadRate());
        assertThrows(
                EngineException.class,
                () -> backend.add(AddRequest.defaults(new AddRequest.Magnet("magnet:?xt=urn:btih:" + MOVING), dir)));
    }

    @Test
    void anEngineUpIsAskedOnTheCallersThread() throws Exception {
        FakeEngine fake = new FakeEngine()
                .put(FakeEngine.torrent(MOVING, "Moving"))
                .put(FakeEngine.torrent(QUIET, "Quiet", TorrentState.SEEDING, 1.0));
        session.start(fake);
        await(() -> polls.get() >= 1);

        assertEquals(fake, backend.engine());
        assertEquals(2, backend.torrents().size());
        assertTrue(backend.detail(MOVING).isPresent());
        assertTrue(backend.detail("cc".repeat(20)).isEmpty(), "an id the engine does not have");
        assertEquals("magnet:?xt=urn:btih:" + MOVING, backend.magnet(MOVING));
        assertEquals("", backend.magnet("cc".repeat(20)));
        assertEquals(TorrentLimits.NONE, backend.limits(MOVING));
        assertEquals(TorrentLimits.NONE, backend.limits("cc".repeat(20)));

        // The poll saw the moving torrent move; the quiet one is as old as its completion.
        Torrent moving = backend.torrents().get(0);
        Torrent quiet = backend.torrents().get(1);
        assertTrue(backend.lastActivity(moving) > moving.added(), "the poll's clock, not the add");
        assertEquals(quiet.completed(), backend.lastActivity(quiet));
        Torrent neverDone = FakeEngine.torrent("dd".repeat(20), "New", TorrentState.DOWNLOADING, 0);
        assertEquals(neverDone.added(), backend.lastActivity(neverDone));

        WebApi.Transfer t = backend.transfer();
        assertEquals("connected", t.connection(), "an engine with no connectivity facts reads as up");
        assertEquals(fake.rates().download(), t.downloadRate());
        assertTrue(t.freeSpace() > 0, "the download folder's volume, through its nearest parent");
        assertFalse(t.alternative());

        Torrent added = backend.add(AddRequest.defaults(new AddRequest.Magnet("magnet:?xt=urn:btih:" + QUIET), dir));
        assertTrue(
                fake.calls().stream().anyMatch(c -> c.startsWith("add ")),
                fake.calls().toString());
        assertEquals(added.id(), fake.torrents().get(2).id());

        backend.removeWithFiles(fake, moving);
        assertTrue(fake.calls().contains("remove " + MOVING + " true"), "nothing on disk to trash: the engine deletes");
    }

    @Test
    void connectionIsQbittorrentsWord() {
        assertEquals("connected", SessionBackend.connection(Connectivity.NONE));
        Connectivity closed = new Connectivity(6881, false, true, Connectivity.Mapping.NONE, false, true, false, "");
        assertEquals("disconnected", SessionBackend.connection(closed));
        Connectivity listening = new Connectivity(6881, true, false, Connectivity.Mapping.NONE, false, true, false, "");
        assertEquals("firewalled", SessionBackend.connection(listening));
        Connectivity reached = new Connectivity(6881, true, false, Connectivity.Mapping.NONE, true, true, false, "");
        assertEquals("connected", SessionBackend.connection(reached));
    }

    @Test
    void freeSpaceLooksUpToAFolderThatExists() {
        assertTrue(SessionBackend.freeSpace(dir.resolve("not").resolve("yet")) > 0);
        assertTrue(SessionBackend.freeSpace(Path.of("relative-nowhere-" + System.nanoTime())) > 0, "from the cwd");
    }

    @Test
    void labelsSharesRootsAndSettingsAreThisMachines() throws Exception {
        assertEquals(dir.resolve("dl"), backend.defaultSavePath());
        assertEquals(settings, backend.settings());
        backend.putCategory(new Category("Films", dir.resolve("films").toString()));
        backend.putCategory(new Category("Loose", ""));
        backend.addTags(List.of("keep", "hd"));
        assertEquals(
                List.of("Films", "Loose"),
                backend.categories().stream().map(Category::name).toList());
        assertEquals(List.of("hd", "keep"), backend.tags());

        settings.setWebApiRoots(dir.resolve("extra").toString());
        List<Path> roots = backend.allowedRoots();
        assertTrue(roots.contains(dir.resolve("dl")));
        assertTrue(roots.contains(dir.resolve("films")));
        assertTrue(roots.contains(dir.resolve("extra")));
        assertEquals(4, roots.size(), "a category without a folder adds none: " + roots);

        backend.removeCategory("Loose");
        backend.removeTag("hd");
        assertEquals(1, backend.categories().size());
        assertEquals(List.of("keep"), backend.tags());

        Torrent t = FakeEngine.torrent(MOVING, "Moving");
        assertTrue(backend.ownShare(MOVING).isEmpty());
        assertEquals(settings.shareLimit(), backend.globalShare());
        ShareLimit own = new ShareLimit(2.0, 60, ShareLimit.Action.PAUSE);
        backend.setShareOverride(MOVING, own);
        assertEquals(own, backend.ownShare(MOVING).orElseThrow());
        assertTrue(Files.readString(dir.resolve(Settings.FILE_NAME)).contains(MOVING), "saved");
        assertEquals(session.shareLimitFor(t), backend.share(t));
        assertFalse(backend.alternativeInForce());
        assertEquals(session.addDefaults(), backend.addDefaults());
        assertTrue(backend.rootFolder().isEmpty());
        assertEquals(List.of(), backend.log(0));
    }

    @Test
    void aChangeFromOutsideIsSavedAppliedAndToldToTheOwner() throws Exception {
        AtomicInteger told = new AtomicInteger();
        backend.setOnSettingsChanged(told::incrementAndGet);
        settings.set(Settings.WEBAPI_PORT, "8181");
        backend.settingsChanged();
        assertEquals(1, told.get());
        assertTrue(Files.readString(dir.resolve(Settings.FILE_NAME)).contains("8181"));

        backend.setOnSettingsChanged(null);
        backend.settingsChanged();
        assertEquals(1, told.get(), "no listener is no listener, not a failure");
    }
}
