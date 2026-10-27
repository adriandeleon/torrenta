package com.torrenta.migrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a real qBittorrent 5.1.0 left in its BT_backup, read. */
class QbittorrentReaderTest {

    private static final Path NO_HOME = Path.of("/nonexistent-home");

    private static ImportCandidate named(List<ImportCandidate> all, String name) {
        return all.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theThreeTorrentsWithEverythingQbittorrentKeptOfThem() {
        QbittorrentReader.Found found = QbittorrentReader.read(QbittorrentImportTest.fixture(), NO_HOME, Map.of());
        assertEquals(List.of(), found.unreadable());
        assertFalse(found.sqlite());
        assertEquals(3, found.candidates().size());

        ImportCandidate seed = named(found.candidates(), "Seed.bin");
        assertEquals("7d434420c3804d11c60483ef340365e3766d8e42", seed.id(), "its v1 hash: no v2 here");
        assertEquals("tv", seed.category());
        assertEquals("/srv/qbittorrent/dl/tv", seed.categoryPath(), "from categories.json in the profile's config");
        assertEquals(List.of("keep"), seed.tags());
        assertEquals("/srv/qbittorrent/dl", seed.savePath());
        assertFalse(seed.stopped());
        assertTrue(seed.complete());
        assertEquals(3, seed.pieces());
        assertEquals(2.0, seed.ratioLimit(), 1e-9, "qBittorrent keeps it in thousandths");
        assertEquals(-2, seed.seedingMinutes(), "the global one");
        assertEquals(List.of(new ImportCandidate.FileEntry("Seed.bin", 49152, true)), seed.files());

        ImportCandidate pack = named(found.candidates(), "Pack");
        assertEquals("Pack Renamed", pack.rename());
        assertEquals("Pack Renamed", pack.shownName());
        assertTrue(pack.stopped(), "paused and not managed by the queue");
        assertEquals(4, pack.piecesHave());
        assertEquals(8, pack.pieces());
        assertEquals(
                List.of(
                        new ImportCandidate.FileEntry("Pack/a.bin", 65536, true),
                        new ImportCandidate.FileEntry("Pack/b.bin", 65536, false)),
                pack.files(),
                "the second file holds none of the pieces that are there");
        assertEquals(-2.0, pack.ratioLimit(), 1e-9);
        assertEquals(Map.of(), pack.renamed(), "no file renamed");

        ImportCandidate magnet = named(found.candidates(), "Nobody.Serves.This");
        assertNull(magnet.metainfo(), "no .torrent: the metadata never arrived");
        assertEquals(0, magnet.pieces());
        assertTrue(magnet.files().isEmpty());
    }

    /**
     * A file renamed in qBittorrent is in libtorrent's {@code mapped_files}, which libtorrent 2.1
     * shows under the old name and loses at a restart: it is handed over as a rename for Torrenta's
     * own sidecar too, as Deluge's are (the same key, proven end to end in DelugeImportTest).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFileRenamedInQbittorrentIsHandedOverAsARename() throws IOException {
        ImportCandidate pack = named(
                QbittorrentReader.read(QbittorrentImportTest.fixture(), NO_HOME, Map.of())
                        .candidates(),
                "Pack");
        Map<String, Object> resume = new java.util.HashMap<>(
                (Map<String, Object>) com.torrenta.engine.transmission.Bencode.decode(pack.resume()));
        resume.put("mapped_files", List.of("", "Pack\\bee.bin"));
        ImportCandidate renamed = QbittorrentReader.candidate(BencodeWriter.encode(resume), pack.metainfo(), Map.of());
        assertEquals(Map.of(1, "Pack/bee.bin"), renamed.renamed(), "forward slashes, as the sidecar keeps them");
        assertEquals("Pack/bee.bin", renamed.files().get(1).path(), "looked for under the name it has on disk");
        assertEquals("Pack/a.bin", renamed.files().get(0).path(), "an empty entry is the file's own name");
    }

    @Test
    void theFolderIsFoundFromTheProfileTheDataFolderOrItself() {
        Path profile = QbittorrentImportTest.fixture();
        Path backup = profile.resolve("qBittorrent/data/BT_backup");
        assertEquals(backup, QbittorrentReader.backupFolder(profile));
        assertEquals(backup, QbittorrentReader.backupFolder(profile.resolve("qBittorrent/data")));
        assertEquals(backup, QbittorrentReader.backupFolder(backup));
    }

    @Test
    void whereQbittorrentKeepsItsTorrentsOnEachSystem() {
        Path home = Path.of("/home/someone");
        assertEquals(
                List.of(
                        home.resolve(".local/share/qBittorrent/BT_backup"),
                        home.resolve(".var/app/org.qbittorrent.qBittorrent/data/qBittorrent/BT_backup")),
                QbittorrentReader.knownLocations("Linux", home, Map.of()),
                "Flatpak's too");
        assertEquals(
                List.of(
                        Path.of("/data/qBittorrent/BT_backup"),
                        home.resolve(".var/app/org.qbittorrent.qBittorrent/data/qBittorrent/BT_backup")),
                QbittorrentReader.knownLocations("Linux", home, Map.of("XDG_DATA_HOME", "/data")));
        assertEquals(
                List.of(home.resolve("Library/Application Support/qBittorrent/BT_backup")),
                QbittorrentReader.knownLocations("Mac OS X", home, Map.of()));
        assertEquals(
                List.of(Path.of("C:\\Users\\someone\\AppData\\Local", "qBittorrent", "BT_backup")),
                QbittorrentReader.knownLocations(
                        "Windows 11", home, Map.of("LOCALAPPDATA", "C:\\Users\\someone\\AppData\\Local")));
    }

    /** Two clients writing one file is corruption: a live lock file means qBittorrent is running. */
    @Test
    void qbittorrentIsRunningWhileItsLockFileNamesALiveProcess(@TempDir Path dir) throws IOException {
        Path backup = Files.createDirectories(dir.resolve("qBittorrent/data/BT_backup"));
        Path config = Files.createDirectories(dir.resolve("qBittorrent/config"));
        assertFalse(QbittorrentReader.running(backup, NO_HOME, Map.of()), "no lock file");
        Files.writeString(config.resolve("lockfile"), ProcessHandle.current().pid() + "\nqbittorrent\nhost\n");
        assertTrue(QbittorrentReader.running(backup, NO_HOME, Map.of()), "a live process: this one");
        Files.writeString(config.resolve("lockfile"), Long.MAX_VALUE + "\nqbittorrent\nhost\n");
        assertFalse(QbittorrentReader.running(backup, NO_HOME, Map.of()), "left by a crash");
    }

    @Test
    void anSqliteProfileIsSaidAndAnUnreadableFileCostsOnlyItself(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data/BT_backup")).getParent();
        Files.writeString(data.resolve("torrents.db"), "SQLite format 3");
        assertTrue(QbittorrentReader.read(dir, NO_HOME, Map.of()).sqlite());

        Path backup = Files.createDirectories(dir.resolve("other/BT_backup"));
        Files.copy(
                QbittorrentImportTest.fixture()
                        .resolve("qBittorrent/data/BT_backup/7d434420c3804d11c60483ef340365e3766d8e42.fastresume"),
                backup.resolve("7d434420c3804d11c60483ef340365e3766d8e42.fastresume"));
        Files.writeString(backup.resolve("ffff.fastresume"), "not bencode");
        QbittorrentReader.Found found = QbittorrentReader.read(backup, NO_HOME, Map.of());
        assertEquals(1, found.candidates().size());
        assertEquals(1, found.unreadable().size());
        assertTrue(found.unreadable().getFirst().startsWith("ffff.fastresume"));
    }

    @Test
    void aCategoryFolderOnlyWhenItIsAbsolute() throws IOException {
        String tv = Path.of("/srv/tv").toAbsolutePath().toString();
        Map<String, String> paths = QbittorrentReader.categoryPaths("{\"tv\":{\"save_path\":\""
                + tv.replace("\\", "\\\\") + "\"},\"films\":{\"save_path\":\"films\"},\"none\":{}}");
        assertEquals(Map.of("tv", tv), paths, "a relative folder is qBittorrent's own, which is not ours");
    }
}
