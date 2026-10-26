package com.torrenta.engine.libtorrent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BooleanSupplier;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.MutableSource;
import com.torrenta.store.TorrentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An updatable address published and found in the DHT: two sessions on loopback, each the other's
 * one node, one publishing items signed with its key, the other looking them up and seeing the
 * newer version's higher sequence number, the one the put said it signed.
 */
class MutableDhtTest {

    /** Loopback, any port, the DHT on and nothing else that finds peers. */
    private static EngineSettings dhtOnly() {
        EngineSettings d = EngineSettings.defaults();
        EngineSettings.Protocol p = d.protocol();
        return new EngineSettings(
                0,
                false,
                "127.0.0.1",
                d.ipv6(),
                false,
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                "",
                1080,
                "",
                "",
                false,
                new EngineSettings.Protocol(true, false, false, false, p.encryption(), false, List.of()),
                d.speed(),
                d.queue());
    }

    private static void await(String what, BooleanSupplier condition, long seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("timed out waiting: " + what);
    }

    private static int port(LibtorrentEngine engine) {
        try {
            return engine.listenPortForTest();
        } catch (com.torrenta.engine.EngineException e) {
            return 0;
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static Optional<Engine.MutableItem> lookUp(LibtorrentEngine engine, MutableSource source, long minSeq)
            throws Exception {
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            Optional<Engine.MutableItem> found = engine.lookupMutable(source, Duration.ofSeconds(3));
            if (found.isPresent() && found.get().seq() >= minSeq) return found;
            Thread.sleep(500);
        }
        return Optional.empty();
    }

    @Test
    void anUpdatableAddressIsFoundAndItsNewerVersionToo(@TempDir Path dir) throws Exception {
        NativeLibrary.ensureAvailable();
        try (LibtorrentEngine publisher =
                        new LibtorrentEngine(() -> dir.resolve("a"), new TorrentStore(dir.resolve("sa")), dhtOnly());
                LibtorrentEngine follower =
                        new LibtorrentEngine(() -> dir.resolve("b"), new TorrentStore(dir.resolve("sb")), dhtOnly())) {
            publisher.start();
            follower.start();
            await("both listening", () -> port(publisher) > 0 && port(follower) > 0, 20);
            publisher.dhtOnLoopbackForTest(follower.listenPortForTest());
            follower.dhtOnLoopbackForTest(publisher.listenPortForTest());
            // A put before the routing table knows anyone stores the item nowhere.
            await(
                    "each DHT knowing the other",
                    () -> publisher.stats().dhtNodes() > 0 && follower.stats().dhtNodes() > 0,
                    60);

            com.torrenta.engine.MutableKey key = publisher.newMutableKey();
            byte[] salt = "films".getBytes(StandardCharsets.US_ASCII);
            MutableSource source = new MutableSource(key.publicKey(), salt);
            byte[] first = new byte[20];
            new Random(1).nextBytes(first);
            Engine.MutablePut put =
                    publisher.putMutable(key, salt, MutableSource.item(hex(first)), Duration.ofSeconds(60));
            assertTrue(put.nodes() > 0, "the other session stored it");

            Optional<Engine.MutableItem> found = lookUp(follower, source, put.seq());
            assertTrue(found.isPresent(), "found in the other session's DHT");
            assertEquals(put.seq(), found.get().seq(), "the number the put signed");
            assertEquals(
                    Optional.of(hex(first)), MutableSource.infoHash(found.get().value()));

            byte[] second = new byte[20];
            new Random(2).nextBytes(second);
            Engine.MutablePut next =
                    publisher.putMutable(key, salt, MutableSource.item(hex(second)), Duration.ofSeconds(60));
            assertEquals(put.seq() + 1, next.seq(), "one above the highest found");
            Optional<Engine.MutableItem> newer = lookUp(follower, source, next.seq());
            assertTrue(newer.isPresent(), "the newer version, by its higher sequence number");
            assertEquals(
                    Optional.of(hex(second)), MutableSource.infoHash(newer.get().value()));
        }
    }
}
