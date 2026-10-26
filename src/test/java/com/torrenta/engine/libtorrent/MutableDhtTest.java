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
import org.libtorrent4j.Ed25519;
import org.libtorrent4j.Entry;
import org.libtorrent4j.Pair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An updatable address found in the DHT: two sessions on loopback, each the other's one node, one
 * putting items signed with a key, the other looking them up and seeing the newer version's
 * higher sequence number.
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

    /** {@code {"ih": <20 bytes>}}, BEP 46's value. */
    private static Entry item(byte[] infoHash) {
        byte[] head = "d2:ih20:".getBytes(StandardCharsets.US_ASCII);
        byte[] bencoded = new byte[head.length + 20 + 1];
        System.arraycopy(head, 0, bencoded, 0, head.length);
        System.arraycopy(infoHash, 0, bencoded, head.length, 20);
        bencoded[bencoded.length - 1] = 'e';
        return Entry.bdecode(bencoded);
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

            Pair<byte[], byte[]> keys = Ed25519.createKeypair(Ed25519.createSeed());
            byte[] salt = "films".getBytes(StandardCharsets.US_ASCII);
            MutableSource source = new MutableSource(keys.first, salt);
            byte[] first = new byte[20];
            new Random(1).nextBytes(first);
            publisher.putMutableForTest(keys.first, keys.second, salt, item(first));

            Optional<Engine.MutableItem> found = lookUp(follower, source, 1);
            assertTrue(found.isPresent(), "found in the other session's DHT");
            assertEquals(
                    Optional.of(HexFormat.of().formatHex(first)),
                    MutableSource.infoHash(found.get().value()));
            long seq = found.get().seq();

            byte[] second = new byte[20];
            new Random(2).nextBytes(second);
            publisher.putMutableForTest(keys.first, keys.second, salt, item(second));
            Optional<Engine.MutableItem> newer = lookUp(follower, source, seq + 1);
            assertTrue(newer.isPresent(), "the newer version, by its higher sequence number");
            assertEquals(
                    Optional.of(HexFormat.of().formatHex(second)),
                    MutableSource.infoHash(newer.get().value()));
        }
    }
}
