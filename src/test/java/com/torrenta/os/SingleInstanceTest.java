package com.torrenta.os;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceTest {

    private static final Path CWD = Path.of("").toAbsolutePath();

    @Test
    void theFirstIsPrimaryAndTheSecondHandsOffToIt(@TempDir Path dir) throws Exception {
        SingleInstance.Outcome first = SingleInstance.claim(dir, List.of(), CWD);
        SingleInstance primary =
                assertInstanceOf(SingleInstance.Outcome.Primary.class, first).instance();
        try (primary) {
            assertTrue(Files.exists(dir.resolve(SingleInstance.PORT_FILE)));
            assertTrue(Files.exists(dir.resolve(SingleInstance.TOKEN_FILE)));
            assertEquals(
                    String.valueOf(primary.port()),
                    Files.readString(dir.resolve(SingleInstance.PORT_FILE)).trim());
            assertEquals(
                    64,
                    Files.readString(dir.resolve(SingleInstance.TOKEN_FILE))
                            .trim()
                            .length());

            List<List<String>> received = new CopyOnWriteArrayList<>();
            CountDownLatch got = new CountDownLatch(1);
            primary.deliverTo(inputs -> {
                received.add(inputs);
                got.countDown();
            });

            long started = System.currentTimeMillis();
            SingleInstance.Outcome second =
                    SingleInstance.claim(dir, List.of("a.torrent", "magnet:?xt=urn:btih:abc"), CWD);
            long took = System.currentTimeMillis() - started;
            assertEquals(new SingleInstance.Outcome.HandedOff(2), second);
            assertTrue(took < 1000, "handed off in " + took + " ms");
            assertTrue(got.await(5, TimeUnit.SECONDS), "the primary received the batch");
            assertEquals(List.of(List.of(CWD.resolve("a.torrent").toString(), "magnet:?xt=urn:btih:abc")), received);
        }
        assertFalse(Files.exists(dir.resolve(SingleInstance.PORT_FILE)), "closed: the port file is gone");
        assertFalse(Files.exists(dir.resolve(SingleInstance.TOKEN_FILE)));
    }

    /** The window is not up yet when the first batch lands: it waits, in order, for the handler. */
    @Test
    void whatArrivesBeforeTheWindowIsKeptInOrder(@TempDir Path dir) throws Exception {
        try (SingleInstance primary = assertInstanceOf(
                        SingleInstance.Outcome.Primary.class, SingleInstance.claim(dir, List.of(), CWD))
                .instance()) {
            assertEquals(
                    new SingleInstance.Outcome.HandedOff(1),
                    SingleInstance.claim(dir, List.of("magnet:?xt=urn:btih:1"), CWD));
            assertEquals(new SingleInstance.Outcome.HandedOff(0), SingleInstance.claim(dir, List.of(), CWD));
            List<List<String>> received = new CopyOnWriteArrayList<>();
            primary.deliverTo(received::add);
            assertEquals(List.of(List.of("magnet:?xt=urn:btih:1"), List.of()), received);
        }
    }

    /** A crash leaves the port file behind and nothing listening; the lock is the OS's to release. */
    @Test
    void aStaleLockIsTakenOver(@TempDir Path dir) throws Exception {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        Files.writeString(dir.resolve(SingleInstance.PORT_FILE), deadPort + "\n");
        Files.writeString(dir.resolve(SingleInstance.TOKEN_FILE), "stale\n");
        Files.writeString(dir.resolve(SingleInstance.LOCK_FILE), "");
        try (SingleInstance primary = assertInstanceOf(
                        SingleInstance.Outcome.Primary.class, SingleInstance.claim(dir, List.of(), CWD))
                .instance()) {
            assertNotEquals(
                    "stale",
                    Files.readString(dir.resolve(SingleInstance.TOKEN_FILE)).trim());
            assertEquals(
                    String.valueOf(primary.port()),
                    Files.readString(dir.resolve(SingleInstance.PORT_FILE)).trim());
        }
    }

    /** A stranger on the port, or a sender with the wrong token, gets no answer and nothing is added. */
    @Test
    void theWrongTokenIsRefused(@TempDir Path dir) throws Exception {
        try (SingleInstance primary = assertInstanceOf(
                        SingleInstance.Outcome.Primary.class, SingleInstance.claim(dir, List.of(), CWD))
                .instance()) {
            List<List<String>> received = new CopyOnWriteArrayList<>();
            primary.deliverTo(received::add);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), primary.port()), 1000);
                socket.setSoTimeout(2000);
                OutputStream out = socket.getOutputStream();
                out.write(InstanceMessage.encode("not-the-token", List.of("magnet:?xt=urn:btih:evil"))
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals(-1, socket.getInputStream().read(), "closed without an answer");
            }
            assertEquals(List.of(), received);
            // And the instance is still there for the next, honest, caller.
            assertEquals(new SingleInstance.Outcome.HandedOff(0), SingleInstance.claim(dir, List.of(), CWD));
        }
    }

    /** One instance per config folder: two folders, two primaries. */
    @Test
    void eachConfigFolderHasItsOwnInstance(@TempDir Path a, @TempDir Path b) throws Exception {
        try (SingleInstance one = assertInstanceOf(
                                SingleInstance.Outcome.Primary.class, SingleInstance.claim(a, List.of(), CWD))
                        .instance();
                SingleInstance two = assertInstanceOf(
                                SingleInstance.Outcome.Primary.class, SingleInstance.claim(b, List.of(), CWD))
                        .instance()) {
            assertNotEquals(one.port(), two.port());
            assertEquals(a, one.configDir());
            assertEquals(b, two.configDir());
        }
    }

    @Test
    void theFilesAreTheOwnersAloneWhereTheFileSystemHasModes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x");
        SingleInstance.writePrivate(file, "secret");
        assertEquals("secret\n", Files.readString(file));
        try {
            assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        } catch (UnsupportedOperationException e) {
            // Windows: the folder's ACL is what there is
        }
    }
}
