package com.torrenta.store;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.torrenta.engine.EngineProfile;
import com.torrenta.sync.SyncKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine profiles on disk: a round trip, the owner-only mode, and the password kept out of the open. */
class ProfileStoreTest {

    private static final EngineProfile BOX = new EngineProfile(
            "k7x", "Seedbox", EngineProfile.Kind.REMOTE, "http://box.example:9091", "alice", "s3cret");

    @Test
    void roundTripsThroughTheFileWithTheLocalEngineAlwaysFirst(@TempDir Path dir) throws Exception {
        ProfileStore store = new ProfileStore(dir);
        store.load();
        assertEquals(List.of(EngineProfile.LOCAL), store.all(), "the local engine needs no file");
        assertEquals(EngineProfile.LOCAL, store.byId("local").orElseThrow());
        assertEquals(EngineProfile.LOCAL, store.byId("").orElseThrow());
        assertEquals(EngineProfile.LOCAL, store.byId(null).orElseThrow());
        assertTrue(store.byId("k7x").isEmpty());

        store.set(List.of(EngineProfile.LOCAL, BOX));
        assertTrue(Files.isRegularFile(dir.resolve(ProfileStore.FILE)));
        String text = Files.readString(dir.resolve(ProfileStore.FILE));
        assertTrue(text.contains("\"id\":\"k7x\""), text);
        assertFalse(text.contains("\"kind\":\"local\""), "the local engine is never written");

        ProfileStore again = new ProfileStore(dir);
        again.load();
        assertEquals(List.of(BOX), again.entries());
        assertEquals(List.of(EngineProfile.LOCAL, BOX), again.all());
        assertEquals(BOX, again.byId("k7x").orElseThrow());
        assertEquals("s3cret", again.byId("k7x").orElseThrow().password(), "the password comes back whole");
    }

    @Test
    void theFileIsOwnerOnlyWhereTheFileSystemHasModes(@TempDir Path dir) throws Exception {
        ProfileStore store = new ProfileStore(dir);
        store.set(List.of(BOX));
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        Set<PosixFilePermission> modes = Files.getPosixFilePermissions(dir.resolve(ProfileStore.FILE));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), modes, "600");
    }

    @Test
    void thePasswordStaysOutOfLogsAndOfSync(@TempDir Path dir) throws Exception {
        assertFalse(BOX.toString().contains("s3cret"), BOX.toString());
        assertTrue(BOX.toString().contains("password set"), "but that there is one is said");
        assertEquals("local", EngineProfile.LOCAL.toString());
        assertFalse(SyncKeys.isSynced(Settings.ENGINE_PROFILE), "which daemon this machine reaches is its own");
        assertFalse(SyncKeys.isSynced("engine.anything"));

        // A broken file is a log line, never the contents.
        Files.writeString(
                dir.resolve(ProfileStore.FILE), "[{\"id\":\"x\",\"url\":\"http://h\",\"password\":\"s3cret\"");
        Logger log = Logger.getLogger(ProfileStore.class.getName());
        StringBuilder logged = new StringBuilder();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.append(record.getMessage()).append(' ');
                if (record.getThrown() != null) logged.append(record.getThrown().getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        log.addHandler(handler);
        try {
            ProfileStore store = new ProfileStore(dir);
            store.load();
            assertTrue(store.entries().isEmpty());
        } finally {
            log.removeHandler(handler);
        }
        assertTrue(logged.toString().contains("Could not read"), logged.toString());
        assertFalse(logged.toString().contains("s3cret"), logged.toString());
    }

    @Test
    void theFormatSkipsWhatItCannotUse() throws Exception {
        List<EngineProfile> parsed = ProfileStore.parse(
                "[{\"id\":\"a\",\"name\":\"A\",\"kind\":\"remote\",\"url\":\"http://a:9091\",\"username\":\"\",\"password\":\"\",\"extra\":1},"
                        + " {\"id\":\"\",\"url\":\"http://no-id\"}, {\"id\":\"b\",\"name\":\"no url\"}, 7,"
                        + " {\"id\":\"local\",\"url\":\"http://x\"}]");
        assertEquals(1, parsed.size());
        assertEquals("a", parsed.get(0).id());
        assertEquals("A", parsed.get(0).name());
        assertEquals(EngineProfile.Kind.REMOTE, parsed.get(0).kind());
        assertEquals("[]\n", ProfileStore.format(List.of()));
        assertEquals("[]\n", ProfileStore.format(List.of(EngineProfile.LOCAL)));
        assertEquals(List.of(BOX), ProfileStore.parse(ProfileStore.format(List.of(BOX))));
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> ProfileStore.parse("{\"not\":\"a list\"}"));
    }

    @Test
    void settingsRememberTheProfileForTheNextStart(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.load();
        assertEquals(EngineProfile.LOCAL_ID, settings.engineProfile(), "local by default");
        settings.setEngineProfile("k7x");
        assertEquals("k7x", settings.engineProfile());
        settings.setEngineProfile(EngineProfile.LOCAL_ID);
        assertEquals(EngineProfile.LOCAL_ID, settings.engineProfile());
        assertEquals("", settings.get(Settings.ENGINE_PROFILE, ""), "local is the absence of the key");
    }
}
