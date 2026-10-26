package com.torrenta.migrate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.transmission.Bencode;

/**
 * Transmission's torrents, from its config folder: {@code torrents/<hash>.torrent} (3.x: {@code
 * <name>.<hash16>.torrent}), or a {@code .magnet} for one whose metadata never arrived, and {@code
 * resume/<same>.resume}, bencoded: {@code destination}, {@code name}, {@code paused}, {@code
 * labels}, the dates and totals, per-file {@code priority}/{@code dnd}, the files' names on disk
 * (renamed ones included) and the progress. Transmission is not libtorrent, so each is converted
 * into libtorrent's resume format ({@link #libtorrentResume}), and the import is then the same as
 * qBittorrent's. Read only.
 *
 * <p>The progress is the {@code blocks} bitfield (16 KiB blocks, first block in the high bit) or
 * {@code all}; a piece counts only when every block of it is there. Transmission 4's {@code
 * pieces} in the same dictionary says which pieces were checked, not which are there (the stopped
 * half-downloaded fixture has it {@code all}), and is read only when there are no blocks.
 */
public final class TransmissionReader {

    public static final String CLIENT = "Transmission";

    /** Transmission's block, in bytes: what {@code blocks} counts in. */
    static final int BLOCK = 16 * 1024;

    private static final Pattern BTIH = Pattern.compile("(?i)xt=urn:btih:([0-9a-f]{40})");

    private TransmissionReader() {}

    /** What a folder held; the files that would not read, each with why. */
    public record Found(Path config, List<ImportCandidate> candidates, List<String> unreadable) {}

    /** Transmission's config folders on this kind of machine: the daemon's, the app's, Flatpak's. */
    public static List<Path> knownLocations(String osName, Path home, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> out = new ArrayList<>();
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) out.add(Path.of(local, "transmission"));
        } else if (os.contains("mac")) {
            out.add(home.resolve("Library/Application Support/Transmission"));
        } else {
            String config = env.getOrDefault("XDG_CONFIG_HOME", "");
            Path base = config.isBlank() ? home.resolve(".config") : Path.of(config);
            out.add(base.resolve("transmission-daemon"));
            out.add(base.resolve("transmission"));
            out.add(home.resolve(".var/app/com.transmissionbt.Transmission/config/transmission"));
        }
        return out;
    }

    /** Whether {@code dir} looks like Transmission's: a {@code resume} folder beside {@code torrents}. */
    public static boolean isConfigFolder(Path dir) {
        return Files.isDirectory(dir.resolve("resume")) && Files.isDirectory(dir.resolve("torrents"));
    }

    /**
     * Whether a Transmission is running on this folder: its {@code settings.json} names an RPC port
     * and something answers on it locally. A daemon or an app with remote control on; one with it
     * off cannot be seen this way, which the dialog's note says.
     */
    public static boolean running(Path config) {
        Path settings = config.resolve("settings.json");
        if (!Files.isRegularFile(settings)) return false;
        int port;
        try {
            port = rpcPort(Files.readString(settings, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        if (port <= 0) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code rpc-port} when {@code rpc-enabled} is not false; 0 for none. Pure. */
    static int rpcPort(String settingsJson) throws IOException {
        int port = 0;
        boolean enabled = true;
        try (JsonParser p = new JsonFactory().createParser(settingsJson)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return 0;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                JsonToken v = p.nextToken();
                if (key.equals("rpc-port") && v == JsonToken.VALUE_NUMBER_INT) port = p.getIntValue();
                else if (key.equals("rpc-enabled") && v == JsonToken.VALUE_FALSE) enabled = false;
                else p.skipChildren();
            }
        }
        return enabled ? port : 0;
    }

    public static Found read(Path config) {
        List<ImportCandidate> out = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        Path resumes = config.resolve("resume");
        if (!Files.isDirectory(resumes)) return new Found(config, out, unreadable);
        List<Path> files;
        try (Stream<Path> list = Files.list(resumes)) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".resume"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            unreadable.add(resumes + ": " + e.getMessage());
            return new Found(config, out, unreadable);
        }
        for (Path resume : files) {
            String stem = resume.getFileName().toString().replaceFirst("\\.resume$", "");
            try {
                Path torrentFile = config.resolve("torrents").resolve(stem + ".torrent");
                Path magnetFile = config.resolve("torrents").resolve(stem + ".magnet");
                byte[] torrent = Files.isRegularFile(torrentFile) ? Files.readAllBytes(torrentFile) : null;
                String magnet = torrent == null && Files.isRegularFile(magnetFile)
                        ? Files.readString(magnetFile, StandardCharsets.UTF_8).trim()
                        : "";
                out.add(candidate(Files.readAllBytes(resume), torrent, magnet));
            } catch (IOException | RuntimeException e) {
                unreadable.add(resume.getFileName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return new Found(config, out, unreadable);
    }

    /** One torrent from its resume file and its {@code .torrent} or magnet. Pure. */
    @SuppressWarnings("unchecked")
    static ImportCandidate candidate(byte[] resumeBytes, byte[] torrent, String magnet) throws IOException {
        if (!(Bencode.decode(resumeBytes) instanceof Map<?, ?> raw)) throw new IOException("not a resume file");
        Map<String, Object> r = (Map<String, Object>) raw;
        String name = text(r.get("name"), "");
        String savePath = text(r.get("destination"), "");
        boolean stopped = number(r.get("paused"), 0) == 1;
        String category = "";
        List<String> tags = new ArrayList<>();
        if (r.get("labels") instanceof List<?> labels)
            for (Object l : labels) {
                String label = l instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : "";
                if (label.startsWith("category:") && category.isEmpty())
                    category = label.substring("category:".length());
                else if (!label.isBlank()) tags.add(label);
            }
        double ratio = ratioLimit(r.get("ratio-limit"));
        List<String> onDisk = new ArrayList<>();
        if (r.get("files") instanceof List<?> list)
            for (Object f : list) onDisk.add(f instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : "");

        if (torrent == null) {
            Matcher m = BTIH.matcher(magnet);
            if (!m.find()) throw new IOException("neither a .torrent nor a magnet with a v1 hash");
            String id = m.group(1).toLowerCase(Locale.ROOT);
            byte[] resume = libtorrentResume(r, HexFormat.of().parseHex(id), name, null, null, trackersOf(magnet));
            return new ImportCandidate(
                    CLIENT,
                    id,
                    name.isEmpty() ? id : name,
                    "",
                    resume,
                    null,
                    savePath,
                    category,
                    "",
                    tags,
                    stopped,
                    List.of(),
                    0,
                    0,
                    ratio,
                    -2);
        }

        TorrentPreview preview = Bencode.preview(torrent);
        Map<String, Object> top = (Map<String, Object>) Bencode.decode(torrent);
        Map<String, Object> info = (Map<String, Object>) top.get("info");
        long pieceLength = info.get("piece length") instanceof Long l ? l : 0;
        long total = 0;
        List<Long> lengths = new ArrayList<>();
        if (info.get("files") instanceof List<?> list) {
            for (Object o : list) {
                long length = o instanceof Map<?, ?> f && f.get("length") instanceof Long l ? l : 0;
                lengths.add(length);
                total += length;
            }
        } else if (info.get("length") instanceof Long l) {
            lengths.add(l);
            total = l;
        }
        int pieces = info.get("pieces") instanceof byte[] hashes ? hashes.length / 20 : 0;
        boolean[] have = have(r.get("progress"), total, pieceLength, pieces);
        long haveCount = 0;
        for (boolean h : have) if (h) haveCount++;

        byte[] pieceBytes = new byte[have.length];
        for (int i = 0; i < have.length; i++) pieceBytes[i] = (byte) (have[i] ? 1 : 0);
        QbittorrentReader.Layout layout = QbittorrentReader.layout(torrent);
        List<ImportCandidate.FileEntry> files = new ArrayList<>();
        Map<Integer, String> renamed = new LinkedHashMap<>();
        for (TorrentPreview.FileEntry f : preview.files()) {
            String path =
                    f.index() < onDisk.size() && !onDisk.get(f.index()).isEmpty() ? onDisk.get(f.index()) : f.path();
            if (!path.equals(f.path())) renamed.put(f.index(), path);
            files.add(new ImportCandidate.FileEntry(path, f.size(), layout.claimed(f.index(), pieceBytes, false)));
        }
        byte[] resume = libtorrentResume(r, infoHash(torrent), name, pieceBytes, lengths, trackersOf(top));
        return new ImportCandidate(
                CLIENT,
                preview.id(),
                name.isEmpty() ? preview.name() : name,
                "",
                resume,
                torrent,
                savePath,
                category,
                "",
                tags,
                stopped,
                files,
                haveCount,
                pieces,
                ratio,
                -2,
                renamed);
    }

    /**
     * Which pieces are there, from Transmission's {@code progress}: {@code blocks} (a bitfield of
     * 16 KiB blocks, or {@code all}/{@code none}), else 3.x's {@code have: all}, else a {@code
     * pieces} bitfield. A piece is there only when all its blocks are. Pure.
     */
    static boolean[] have(Object progress, long total, long pieceLength, int pieces) {
        boolean[] out = new boolean[pieces];
        if (!(progress instanceof Map<?, ?> p) || pieceLength <= 0) return out;
        Object blocks = p.get("blocks");
        if (isText(blocks, "all") || isText(p.get("have"), "all")) {
            java.util.Arrays.fill(out, true);
            return out;
        }
        if (blocks instanceof byte[] bits && !isText(blocks, "none")) {
            for (int i = 0; i < pieces; i++) {
                long start = i * pieceLength;
                long end = Math.min(total, start + pieceLength);
                long first = start / BLOCK;
                long last = (end - 1) / BLOCK;
                boolean all = end > start;
                for (long b = first; b <= last && all; b++) all = bit(bits, b);
                out[i] = all;
            }
            return out;
        }
        if (blocks == null && p.get("pieces") instanceof byte[] bits && !isText(p.get("pieces"), "none"))
            for (int i = 0; i < pieces; i++) out[i] = bit(bits, i);
        return out;
    }

    private static boolean bit(byte[] bits, long index) {
        int byteIndex = (int) (index / 8);
        return byteIndex < bits.length && (bits[byteIndex] & (0x80 >>> (index % 8))) != 0;
    }

    private static boolean isText(Object v, String text) {
        return v instanceof byte[] b && new String(b, StandardCharsets.UTF_8).equals(text);
    }

    /**
     * Transmission's resume as libtorrent's: the hash, the name, the save path, stopped or not,
     * the dates and totals, each file's priority (unwanted ones skipped), the pieces that are there,
     * the trackers (libtorrent 2.1 keeps them outside the metainfo it is given). Pure.
     */
    static byte[] libtorrentResume(
            Map<String, Object> r,
            byte[] infoHash,
            String name,
            byte[] pieces,
            List<Long> lengths,
            List<List<String>> trackers) {
        Map<String, Object> out = new HashMap<>();
        out.put("file-format", "libtorrent resume file");
        out.put("file-version", 1L);
        out.put("info-hash", infoHash);
        out.put("name", name);
        out.put("save_path", text(r.get("destination"), ""));
        boolean paused = number(r.get("paused"), 0) == 1;
        out.put("paused", paused ? 1L : 0L);
        out.put("auto_managed", paused ? 0L : 1L);
        out.put("added_time", (long) number(r.get("added-date"), 0));
        out.put("completed_time", (long) number(r.get("done-date"), 0));
        out.put("total_uploaded", (long) number(r.get("uploaded"), 0));
        out.put("total_downloaded", (long) number(r.get("downloaded"), 0));
        out.put("seeding_time", (long) number(r.get("seeding-time-seconds"), 0));
        out.put("active_time", (long)
                (number(r.get("seeding-time-seconds"), 0) + number(r.get("downloading-time-seconds"), 0)));
        out.put("sequential_download", (long) number(r.get("sequential_download"), 0));
        if (pieces != null) out.put("pieces", pieces);
        if (lengths != null) {
            List<Object> dnd = r.get("dnd") instanceof List<?> l ? new ArrayList<>(l) : List.of();
            List<Object> priority = r.get("priority") instanceof List<?> l ? new ArrayList<>(l) : List.of();
            List<Long> priorities = new ArrayList<>();
            for (int i = 0; i < lengths.size(); i++) {
                boolean skip = i < dnd.size() && dnd.get(i) instanceof Long d && d == 1;
                long p = i < priority.size() && priority.get(i) instanceof Long v ? v : 0;
                // Transmission's -1/0/1 onto libtorrent's 1/4/7; unwanted is 0.
                priorities.add(skip ? 0L : p < 0 ? 1L : p > 0 ? 7L : 4L);
            }
            out.put("file_priority", priorities);
        }
        if (!trackers.isEmpty()) out.put("trackers", trackers);
        return BencodeWriter.encode(out);
    }

    /** Transmission's {@code ratio-mode}: 0 the global limit (-2), 1 its own, 2 none (-1). */
    private static double ratioLimit(Object v) {
        if (!(v instanceof Map<?, ?> m)) return -2;
        long mode = m.get("ratio-mode") instanceof Long l ? l : 0;
        if (mode == 2) return -1;
        if (mode != 1) return -2;
        try {
            return Double.parseDouble(text(m.get("ratio-limit"), "-2"));
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    /** The SHA-1 of the {@code info} dictionary's own bytes: the v1 info-hash. */
    private static byte[] infoHash(byte[] torrent) throws IOException {
        int start = indexOfInfo(torrent);
        if (start < 0) throw new IOException("no info dictionary");
        int end = endOf(torrent, start);
        try {
            return java.security.MessageDigest.getInstance("SHA-1")
                    .digest(java.util.Arrays.copyOfRange(torrent, start, end));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where the top-level {@code info} value starts. */
    private static int indexOfInfo(byte[] t) {
        if (t.length == 0 || t[0] != 'd') return -1;
        int i = 1;
        while (i < t.length && t[i] != 'e') {
            int colon = indexOf(t, (byte) ':', i);
            int length = Integer.parseInt(new String(t, i, colon - i, StandardCharsets.US_ASCII));
            String key = new String(t, colon + 1, length, StandardCharsets.UTF_8);
            int value = colon + 1 + length;
            if (key.equals("info")) return value;
            i = endOf(t, value);
        }
        return -1;
    }

    /** The index just past the bencoded value starting at {@code i}. */
    private static int endOf(byte[] t, int i) {
        byte c = t[i];
        if (c == 'i') return indexOf(t, (byte) 'e', i) + 1;
        if (c == 'l' || c == 'd') {
            int j = i + 1;
            while (t[j] != 'e') j = endOf(t, j);
            return j + 1;
        }
        int colon = indexOf(t, (byte) ':', i);
        return colon + 1 + Integer.parseInt(new String(t, i, colon - i, StandardCharsets.US_ASCII));
    }

    private static int indexOf(byte[] t, byte b, int from) {
        for (int i = from; i < t.length; i++) if (t[i] == b) return i;
        throw new IllegalArgumentException("truncated bencode");
    }

    /** The metainfo's tiers: {@code announce-list}, else {@code announce} alone. */
    private static List<List<String>> trackersOf(Map<String, Object> top) {
        List<List<String>> tiers = new ArrayList<>();
        if (top.get("announce-list") instanceof List<?> list) {
            for (Object tier : list) {
                List<String> urls = new ArrayList<>();
                if (tier instanceof List<?> t)
                    for (Object u : t) if (u instanceof byte[] b) urls.add(new String(b, StandardCharsets.UTF_8));
                if (!urls.isEmpty()) tiers.add(urls);
            }
        }
        if (tiers.isEmpty() && top.get("announce") instanceof byte[] b)
            tiers.add(List.of(new String(b, StandardCharsets.UTF_8)));
        return tiers;
    }

    /** A magnet's {@code tr=} trackers, one tier each as they come. */
    private static List<List<String>> trackersOf(String magnet) {
        List<List<String>> tiers = new ArrayList<>();
        int q = magnet.indexOf('?');
        if (q < 0) return tiers;
        for (String pair : magnet.substring(q + 1).split("&"))
            if (pair.startsWith("tr="))
                tiers.add(List.of(URLDecoder.decode(pair.substring(3), StandardCharsets.UTF_8)));
        return tiers;
    }

    private static String text(Object v, String fallback) {
        return v instanceof byte[] b && b.length > 0 ? new String(b, StandardCharsets.UTF_8) : fallback;
    }

    private static double number(Object v, double fallback) {
        return v instanceof Long l ? l : fallback;
    }
}
