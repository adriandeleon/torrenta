package com.torrenta.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The one owner of what is on disk about torrents: one opaque blob per torrent, keyed by
 * info-hash.
 *
 * <p>Engine-neutral on purpose. The blob is whatever the engine needs to bring the torrent back —
 * for libtorrent, its resume data, which carries the info dictionary, the save path, the trackers
 * and the piece state — and this class never looks inside it. One file per torrent, {@code
 * <hash>.fastresume}, so a single corrupt file costs one torrent rather than the session, and so a
 * person can delete one by hand.
 *
 * <p>Beside the blob a torrent may have a {@code <hash>.labels} sidecar: what the engine files it
 * under (category, tags), which libtorrent's resume data has no room for, and a {@code
 * <hash>.storage} sidecar: what the engine still owes the files (a move on completion, names to
 * take back). Also opaque bytes here; both go when the torrent goes.
 *
 * <p>Writes are atomic (temp file beside the target, then a move) and the class is synchronized:
 * the engine calls it from its alert thread while the FX side may be removing a torrent.
 */
public final class TorrentStore {

    private static final Logger LOG = Logger.getLogger(TorrentStore.class.getName());

    public static final String FOLDER = "torrents";
    public static final String SUFFIX = ".fastresume";
    public static final String LABELS_SUFFIX = ".labels";
    public static final String STORAGE_SUFFIX = ".storage";

    /** A v1 or v2 info-hash in hex; anything else never becomes a file name. */
    private static final Pattern ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private final Path dir;

    public TorrentStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public synchronized void save(String id, byte[] data) throws IOException {
        write(fileFor(id, SUFFIX), data);
    }

    /** Forgets the torrent: its blob and its sidecars. */
    public synchronized void delete(String id) throws IOException {
        Files.deleteIfExists(fileFor(id, SUFFIX));
        Files.deleteIfExists(fileFor(id, LABELS_SUFFIX));
        Files.deleteIfExists(fileFor(id, STORAGE_SUFFIX));
    }

    public synchronized void saveStorage(String id, byte[] data) throws IOException {
        write(fileFor(id, STORAGE_SUFFIX), data);
    }

    public synchronized void deleteStorage(String id) throws IOException {
        Files.deleteIfExists(fileFor(id, STORAGE_SUFFIX));
    }

    /** The torrent's storage sidecar, if it has one. */
    public synchronized Optional<byte[]> loadStorage(String id) throws IOException {
        Path file = fileFor(id, STORAGE_SUFFIX);
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    public synchronized void saveLabels(String id, byte[] data) throws IOException {
        write(fileFor(id, LABELS_SUFFIX), data);
    }

    public synchronized void deleteLabels(String id) throws IOException {
        Files.deleteIfExists(fileFor(id, LABELS_SUFFIX));
    }

    /** The torrent's labels sidecar, if it has one. */
    public synchronized Optional<byte[]> loadLabels(String id) throws IOException {
        Path file = fileFor(id, LABELS_SUFFIX);
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    private void write(Path target, byte[] data) throws IOException {
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".part");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public synchronized boolean contains(String id) {
        return Files.isRegularFile(fileFor(id, SUFFIX));
    }

    /**
     * Every stored torrent, by id, in name order. Files that are not {@code <hash>.fastresume} are
     * ignored and logged, not deleted: they are somebody's, just not ours.
     */
    public synchronized Map<String, byte[]> loadAll() throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.endsWith(SUFFIX)) continue;
                String id = name.substring(0, name.length() - SUFFIX.length());
                if (!ID.matcher(id).matches() || !Files.isRegularFile(file)) {
                    LOG.warning("Ignoring " + file + ": not a torrent of ours");
                    continue;
                }
                out.put(id, Files.readAllBytes(file));
            }
        }
        return out;
    }

    private Path fileFor(String id, String suffix) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("not an info-hash: " + id);
        return dir.resolve(id + suffix);
    }
}
