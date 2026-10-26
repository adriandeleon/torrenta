package com.torrenta.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.transmission.Bencode;

/**
 * qBittorrent's torrents, from its {@code BT_backup} folder: per torrent a {@code <hash>.fastresume}
 * (libtorrent's resume format, plus qBittorrent's {@code qBt-*} keys: category, tags, the name a
 * person gave, the share limits) and, once its metadata is known, a {@code <hash>.torrent} beside
 * it; the categories' folders from {@code categories.json}. Read only: nothing of qBittorrent's is
 * changed. Its optional SQLite storage ({@code torrents.db}) is not read; {@link Found#sqlite}
 * says when that is what a folder holds, so the dialog can say how to switch qBittorrent back.
 */
public final class QbittorrentReader {

    public static final String CLIENT = "qBittorrent";

    private QbittorrentReader() {}

    /**
     * What a folder held.
     *
     * @param backup the {@code BT_backup} folder read, or the one looked for
     * @param unreadable the files that would not read, each with why
     * @param sqlite the folder keeps its torrents in {@code torrents.db}, which is not read
     */
    public record Found(Path backup, List<ImportCandidate> candidates, List<String> unreadable, boolean sqlite) {}

    /** The {@code BT_backup} folders qBittorrent keeps on this kind of machine, Flatpak's included. */
    public static List<Path> knownLocations(String osName, Path home, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> out = new ArrayList<>();
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) out.add(Path.of(local, "qBittorrent", "BT_backup"));
        } else if (os.contains("mac")) {
            out.add(home.resolve("Library/Application Support/qBittorrent/BT_backup"));
        } else {
            String data = env.getOrDefault("XDG_DATA_HOME", "");
            out.add(
                    data.isBlank()
                            ? home.resolve(".local/share/qBittorrent/BT_backup")
                            : Path.of(data, "qBittorrent", "BT_backup"));
            out.add(home.resolve(".var/app/org.qbittorrent.qBittorrent/data/qBittorrent/BT_backup"));
        }
        return out;
    }

    /** The {@code BT_backup} folder a person pointed at: it, or the one in a data folder or a profile. */
    public static Path backupFolder(Path given) {
        List<Path> places = List.of(
                given,
                given.resolve("BT_backup"),
                given.resolve("data/BT_backup"),
                given.resolve("qBittorrent/data/BT_backup"));
        for (Path p : places) if (Files.isDirectory(p) && hasResumeData(p)) return p;
        // None with resume files: an empty one, or one beside torrents.db (the SQLite mode).
        for (Path p : places.subList(1, places.size())) if (Files.isDirectory(p)) return p;
        return given.getFileName() != null && given.getFileName().toString().equals("BT_backup")
                ? given
                : given.resolve("BT_backup");
    }

    private static boolean hasResumeData(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".fastresume"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * qBittorrent's config folder for a {@code BT_backup} folder: a profile's own ({@code
     * <profile>/qBittorrent/config}), else where the platform keeps it, Flatpak's included.
     */
    static List<Path> configFolders(Path backup, Path home, Map<String, String> env) {
        List<Path> out = new ArrayList<>();
        Path data = backup.getParent();
        if (data != null && data.getParent() != null) out.add(data.getParent().resolve("config"));
        if (backup.toString().contains(".var/app/org.qbittorrent.qBittorrent"))
            out.add(home.resolve(".var/app/org.qbittorrent.qBittorrent/config/qBittorrent"));
        String config = env.getOrDefault("XDG_CONFIG_HOME", "");
        out.add(config.isBlank() ? home.resolve(".config/qBittorrent") : Path.of(config, "qBittorrent"));
        out.add(home.resolve("Library/Preferences/qBittorrent"));
        String appData = env.get("APPDATA");
        if (appData != null && !appData.isBlank()) out.add(Path.of(appData, "qBittorrent"));
        return out;
    }

    /**
     * Whether qBittorrent is running on this folder: its config folder holds Qt's {@code lockfile},
     * whose first line is the process id, and that process is alive. A file left by a crash names
     * a process that is gone, and does not count.
     */
    public static boolean running(Path backup, Path home, Map<String, String> env) {
        for (Path config : configFolders(backup, home, env)) {
            Path lock = config.resolve("lockfile");
            if (!Files.isRegularFile(lock)) continue;
            try {
                String first = Files.readAllLines(lock, StandardCharsets.UTF_8).stream()
                        .findFirst()
                        .orElse("")
                        .trim();
                long pid = Long.parseLong(first);
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            } catch (IOException | NumberFormatException e) {
                // A lock file that does not read is a lock held: better not to write beside it.
                return true;
            }
        }
        return false;
    }

    /** Everything in {@code given}'s {@code BT_backup}; one file that will not read costs that torrent. */
    public static Found read(Path given, Path home, Map<String, String> env) {
        Path backup = backupFolder(given);
        List<ImportCandidate> out = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        boolean sqlite = backup.getParent() != null
                && Files.isRegularFile(backup.getParent().resolve("torrents.db"))
                && !hasResumeData(backup);
        if (!Files.isDirectory(backup)) return new Found(backup, out, unreadable, sqlite);
        Map<String, String> categoryPaths = categoryPaths(backup, home, env);
        List<Path> resumes;
        try (Stream<Path> files = Files.list(backup)) {
            resumes = files.filter(f -> f.getFileName().toString().endsWith(".fastresume"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            unreadable.add(backup + ": " + e.getMessage());
            return new Found(backup, out, unreadable, sqlite);
        }
        for (Path resume : resumes) {
            String stem = resume.getFileName().toString().replace(".fastresume", "");
            try {
                byte[] resumeBytes = Files.readAllBytes(resume);
                Path torrentFile = backup.resolve(stem + ".torrent");
                byte[] torrent = Files.isRegularFile(torrentFile) ? Files.readAllBytes(torrentFile) : null;
                out.add(candidate(resumeBytes, torrent, categoryPaths));
            } catch (IOException | RuntimeException e) {
                unreadable.add(resume.getFileName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return new Found(backup, out, unreadable, sqlite);
    }

    /** {@code categories.json}: each category's folder, by name; a relative one or none is left out. */
    static Map<String, String> categoryPaths(Path backup, Path home, Map<String, String> env) {
        for (Path config : configFolders(backup, home, env)) {
            Path file = config.resolve("categories.json");
            if (Files.isRegularFile(file)) {
                try {
                    return categoryPaths(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return Map.of();
                }
            }
        }
        return Map.of();
    }

    /** {@code {"tv": {"save_path": "/srv/tv"}}} into names and folders. Pure. */
    static Map<String, String> categoryPaths(String json) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (JsonParser p = new JsonFactory().createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return out;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                if (p.nextToken() != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String key = p.currentName();
                    p.nextToken();
                    if (key.equals("save_path") && p.currentToken() == JsonToken.VALUE_STRING) {
                        String path = p.getText();
                        if (isAbsolute(path)) out.put(name, path);
                    } else {
                        p.skipChildren();
                    }
                }
            }
        }
        return out;
    }

    private static boolean isAbsolute(String path) {
        try {
            // A POSIX root counts on Windows too: a folder from a Linux machine is still not a relative one.
            return !path.isBlank() && (path.startsWith("/") || Path.of(path).isAbsolute());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** One torrent from its resume data and, when there is one, its {@code .torrent}. Pure. */
    @SuppressWarnings("unchecked")
    static ImportCandidate candidate(byte[] resume, byte[] torrent, Map<String, String> categoryPaths)
            throws IOException {
        if (!(Bencode.decode(resume) instanceof Map<?, ?> raw)) throw new IOException("not a resume file");
        Map<String, Object> r = (Map<String, Object>) raw;
        byte[] v1 = bytes(r.get("info-hash"));
        byte[] v2 = bytes(r.get("info-hash2"));
        String id = !allZero(v2) && v2.length >= 20
                ? HexFormat.of().formatHex(v2, 0, 20)
                : HexFormat.of().formatHex(v1);
        if (id.isEmpty()) throw new IOException("no info-hash");
        TorrentPreview preview = torrent == null ? null : Bencode.preview(torrent);
        String name = text(r.get("name"), preview == null ? id : preview.name());
        String savePath = text(r.get("qBt-savePath"), "");
        if (savePath.isEmpty()) savePath = text(r.get("save_path"), "");
        String category = text(r.get("qBt-category"), "");
        List<String> tags = new ArrayList<>();
        if (r.get("qBt-tags") instanceof List<?> list)
            for (Object t : list) if (t instanceof byte[] b) tags.add(new String(b, StandardCharsets.UTF_8));
        boolean stopped = number(r.get("paused"), 0) == 1 && number(r.get("auto_managed"), 0) == 0;
        byte[] pieceBytes = bytes(r.get("pieces"));
        long total = pieceCount(torrent, pieceBytes.length);
        boolean seedMode = number(r.get("seed_mode"), 0) == 1;
        long have = seedMode ? total : countSet(pieceBytes);
        List<ImportCandidate.FileEntry> files = new ArrayList<>();
        if (preview != null) {
            List<Object> mapped = r.get("mapped_files") instanceof List<?> m ? (List<Object>) m : List.of();
            Layout layout = layout(torrent);
            for (TorrentPreview.FileEntry f : preview.files()) {
                String path = f.path();
                if (f.index() < mapped.size() && mapped.get(f.index()) instanceof byte[] b && b.length > 0)
                    path = new String(b, StandardCharsets.UTF_8);
                files.add(
                        new ImportCandidate.FileEntry(path, f.size(), layout.claimed(f.index(), pieceBytes, seedMode)));
            }
        }
        return new ImportCandidate(
                QbittorrentReader.CLIENT,
                id,
                name,
                text(r.get("qBt-name"), ""),
                resume,
                torrent,
                savePath,
                category,
                categoryPaths.getOrDefault(category, ""),
                tags,
                stopped,
                files,
                have,
                total,
                limit(number(r.get("qBt-ratioLimit"), -2000) / 1000.0),
                (long) limit(number(r.get("qBt-seedingTimeLimit"), -2)));
    }

    /** qBittorrent's -2 (the global limit) and -1 (none) as they are; anything below as -1. */
    private static double limit(double value) {
        return value == -2 || value >= 0 ? value : -1;
    }

    /**
     * Where each file lies in the torrent's pieces: v1's files one after the other (padding
     * included), by the torrent's own index. A v2-only torrent, laid out per file, is not mapped:
     * every file counts as claimed there, which only makes the plan more careful.
     */
    record Layout(long pieceLength, Map<Integer, long[]> spans) {
        boolean claimed(int index, byte[] pieces, boolean seedMode) {
            if (seedMode) return true;
            long[] span = spans.get(index);
            if (span == null || pieceLength <= 0) return true;
            if (span[1] == 0) return false;
            long first = span[0] / pieceLength;
            long last = (span[0] + span[1] - 1) / pieceLength;
            for (long p = first; p <= last && p < pieces.length; p++) if ((pieces[(int) p] & 1) != 0) return true;
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static Layout layout(byte[] torrent) throws IOException {
        Map<Integer, long[]> spans = new HashMap<>();
        if (!(Bencode.decode(torrent) instanceof Map<?, ?> t) || !(t.get("info") instanceof Map<?, ?> info))
            return new Layout(0, spans);
        long pieceLength = info.get("piece length") instanceof Long l ? l : 0;
        if (info.get("files") instanceof List<?> list) {
            long offset = 0;
            int index = 0;
            for (Object o : list) {
                long length = o instanceof Map<?, ?> f && f.get("length") instanceof Long l ? l : 0;
                spans.put(index++, new long[] {offset, length});
                offset += length;
            }
        } else if (info.get("length") instanceof Long length) {
            spans.put(0, new long[] {0, length});
        }
        return new Layout(pieceLength, spans);
    }

    /** Pieces in the torrent: the v1 piece hashes, else what the resume data lists. */
    @SuppressWarnings("unchecked")
    private static long pieceCount(byte[] torrent, int listed) throws IOException {
        if (torrent != null
                && Bencode.decode(torrent) instanceof Map<?, ?> t
                && t.get("info") instanceof Map<?, ?> info
                && info.get("pieces") instanceof byte[] hashes) return hashes.length / 20;
        return listed;
    }

    /** libtorrent's resume {@code pieces}: one byte a piece, its lowest bit set when the piece is there. */
    static long countSet(byte[] pieces) {
        long n = 0;
        for (byte b : pieces) if ((b & 1) != 0) n++;
        return n;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) if (b != 0) return false;
        return true;
    }

    private static byte[] bytes(Object v) {
        return v instanceof byte[] b ? b : new byte[0];
    }

    private static String text(Object v, String fallback) {
        return v instanceof byte[] b && b.length > 0 ? new String(b, StandardCharsets.UTF_8) : fallback;
    }

    private static double number(Object v, double fallback) {
        return v instanceof Long l ? l : fallback;
    }
}
