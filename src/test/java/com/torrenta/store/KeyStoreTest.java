package com.torrenta.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.torrenta.engine.MutableKey;
import com.torrenta.engine.Publication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyStoreTest {

    private static MutableKey key(int fill) {
        byte[] pub = new byte[32];
        byte[] secret = new byte[64];
        Arrays.fill(pub, (byte) fill);
        Arrays.fill(secret, (byte) (fill + 1));
        return new MutableKey(pub, secret);
    }

    private static Publication films() {
        return new Publication("films", key(7), "s".getBytes(), "ab".repeat(20), 12, "cd".repeat(20));
    }

    @Test
    void aPublicationRoundTripsWithItsKeyAndNumber() {
        Publication p = films();
        String text = KeyStore.encode(p);
        assertTrue(text.startsWith("# Torrenta"), "a warning for whoever opens it");
        assertEquals(p, KeyStore.decode("films", text));
        assertFalse(p.toString().contains("08".repeat(64)), "the secret never printed");
    }

    @Test
    void aFileThatDoesNotReadIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> KeyStore.decode("x", "format=2\n"));
        assertThrows(IllegalArgumentException.class, () -> KeyStore.decode("x", "format=1\npublic=00\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> KeyStore.decode("x", KeyStore.encode(films()).replace("seq=12", "seq=many")));
        assertThrows(IllegalArgumentException.class, () -> KeyStore.decode("x", "format=1\nnot a line\n"));
    }

    @Test
    void aNameIsAFileNameOnEverySystem(@TempDir Path dir) throws IOException {
        KeyStore keys = new KeyStore(dir);
        assertEquals("", KeyStore.nameProblem("films-2026.v1_x"));
        assertFalse(KeyStore.nameProblem("").isEmpty());
        assertFalse(KeyStore.nameProblem(".hidden").isEmpty());
        assertFalse(KeyStore.nameProblem("a/b").isEmpty());
        assertFalse(KeyStore.nameProblem("con sp").isEmpty());
        assertFalse(KeyStore.nameProblem("x".repeat(65)).isEmpty());
        assertEquals("", keys.problem("films"));
        keys.save(films());
        assertFalse(keys.problem("films").isEmpty(), "taken");
    }

    @Test
    void savedOwnerOnlyAndFoundByNameAndByTorrent(@TempDir Path dir) throws IOException {
        KeyStore keys = new KeyStore(dir);
        assertEquals(List.of(), keys.list());
        Publication p = films();
        keys.save(p);
        keys.save(new Publication("books", key(9), null, "", 0, ""));
        Files.writeString(dir.resolve("keys/broken.key"), "format=1\n");
        assertEquals(
                List.of("books", "films"),
                keys.list().stream().map(Publication::name).toList(),
                "the broken one left out");
        assertEquals(Optional.of(p), keys.load("films"));
        assertEquals(Optional.empty(), keys.load("nothing"));
        assertEquals(Optional.of(p), keys.ofTorrent("cd".repeat(20)));
        assertEquals(Optional.empty(), keys.ofTorrent(""));
        if (Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keys.file("films"))));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keys.dir())));
        }
        keys.save(p.published(13));
        assertEquals(13, keys.load("films").orElseThrow().seq(), "replaced in place");
    }

    @Test
    void theRecordOnlyGoesUp() {
        Publication p = films();
        assertEquals(12, p.published(3).seq(), "a put that landed low leaves the record");
        assertEquals(14, p.published(14).seq());
        Publication next = p.withVersion("EF".repeat(20), "id");
        assertEquals("ef".repeat(20), next.infoHash());
        assertEquals(12, next.seq(), "the number is the last one published until the put");
    }
}
