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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.transmission.Bencode;

/**
 * Deluge's torrents, from its config folder: {@code state/torrents.fastresume} (one bencoded
 * dictionary of libtorrent resume data by info-hash, Deluge being libtorrent too), {@code
 * state/torrents.state} (a Python pickle of each torrent's options: paused, folder, file
 * priorities, ratio, the name a person gave, a magnet's link), {@code state/<hash>.torrent}, and
 * the Label plugin's {@code label.conf}, whose one label a torrent has is its category here. Read
 * only. The pickle goes through {@link Pickle}, which builds nothing.
 *
 * <p>A file renamed in Deluge is in the resume data's {@code mapped_files}: those become {@link
 * ImportCandidate#renamed}, which Torrenta keeps in its own sidecar because libtorrent 2.1 loses a
 * rename at a restart. A magnet Deluge never had the metadata of has no resume data at all; it
 * comes over from its link, as Transmission's do.
 */
public final class DelugeReader {

    public static final String CLIENT = "Deluge";

    /** {@code core.conf}'s default: the daemon listens here. */
    static final int DEFAULT_DAEMON_PORT = 58846;

    private static final Pattern BTIH = Pattern.compile("(?i)xt=urn:btih:([0-9a-f]{40})");

    private DelugeReader() {}

    /** What a folder held; the torrents that would not read, each with why. */
    public record Found(Path config, List<ImportCandidate> candidates, List<String> unreadable) {}

    /**
     * Deluge's config folders on this kind of machine: its own under the XDG folder (macOS too,
     * where Deluge uses the same), Flatpak's, a Debian {@code deluged} package's; Windows's in
     * {@code %APPDATA%}.
     */
    public static List<Path> knownLocations(String osName, Path home, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> out = new ArrayList<>();
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            if (appData != null && !appData.isBlank()) out.add(Path.of(appData, "deluge"));
            return out;
        }
        String config = env.getOrDefault("XDG_CONFIG_HOME", "");
        out.add((config.isBlank() ? home.resolve(".config") : Path.of(config)).resolve("deluge"));
        if (!os.contains("mac")) {
            out.add(home.resolve(".var/app/org.deluge_torrent.deluge/config/deluge"));
            out.add(Path.of("/var/lib/deluged/config"));
        }
        return out;
    }

    /** Whether {@code dir} looks like Deluge's: a {@code state} folder with its torrents' state in it. */
    public static boolean isConfigFolder(Path dir) {
        Path state = dir.resolve("state");
        return Files.isRegularFile(state.resolve("torrents.state"))
                || Files.isRegularFile(state.resolve("torrents.fastresume"));
    }

    /**
     * Whether a Deluge is running on this folder: something answers locally on the daemon port
     * {@code core.conf} names. Deluge's window, in its standalone mode, runs the same daemon.
     */
    public static boolean running(Path config) {
        int port = DEFAULT_DAEMON_PORT;
        Path core = config.resolve("core.conf");
        if (Files.isRegularFile(core)) {
            try {
                port = daemonPort(Files.readString(core, StandardCharsets.UTF_8));
            } catch (IOException e) {
                // A core.conf that does not read: the default port, as Deluge itself would use.
            }
        }
        if (port <= 0) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code core.conf}'s {@code daemon_port}; the default when it says none. Pure. */
    static int daemonPort(String coreConf) throws IOException {
        int port = DEFAULT_DAEMON_PORT;
        try (JsonParser p = body(coreConf)) {
            if (p == null) return port;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                JsonToken v = p.nextToken();
                if (key.equals("daemon_port") && v == JsonToken.VALUE_NUMBER_INT) port = p.getIntValue();
                else p.skipChildren();
            }
        }
        return port;
    }

    /** {@code label.conf}'s {@code torrent_labels}: each torrent's label, by its hash. Pure. */
    static Map<String, String> labels(String labelConf) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (JsonParser p = body(labelConf)) {
            if (p == null) return out;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String key = p.currentName();
                JsonToken v = p.nextToken();
                if (!key.equals("torrent_labels") || v != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String id = p.currentName();
                    if (p.nextToken() == JsonToken.VALUE_STRING && !p.getText().isBlank())
                        out.put(id.toLowerCase(Locale.ROOT), p.getText());
                    else p.skipChildren();
                }
            }
        }
        return out;
    }

    /**
     * A Deluge config file is two JSON objects, one after the other: the file's version, then the
     * settings. The parser positioned inside the second, or null when there is none.
     */
    private static JsonParser body(String text) throws IOException {
        JsonParser p = new JsonFactory().createParser(text);
        if (p.nextToken() != JsonToken.START_OBJECT) {
            p.close();
            return null;
        }
        p.skipChildren();
        if (p.nextToken() != JsonToken.START_OBJECT) {
            p.close();
            return null;
        }
        return p;
    }

    /** Everything in {@code config}'s {@code state} folder; one torrent that will not read costs that torrent. */
    @SuppressWarnings("unchecked")
    public static Found read(Path config) {
        List<ImportCandidate> out = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        Path state = config.resolve("state");
        Map<String, byte[]> resumes = new HashMap<>();
        Path fastresume = state.resolve("torrents.fastresume");
        if (Files.isRegularFile(fastresume)) {
            try {
                if (Bencode.decode(Files.readAllBytes(fastresume)) instanceof Map<?, ?> all)
                    for (Map.Entry<?, ?> e : all.entrySet())
                        if (e.getValue() instanceof byte[] blob)
                            resumes.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), blob);
            } catch (IOException | RuntimeException e) {
                unreadable.add(fastresume.getFileName() + ": " + message(e));
            }
        }
        Map<String, String> labels = Map.of();
        Path labelConf = config.resolve("label.conf");
        if (Files.isRegularFile(labelConf)) {
            try {
                labels = labels(Files.readString(labelConf, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                unreadable.add(labelConf.getFileName() + ": " + message(e));
            }
        }
        List<Map<String, Object>> states = new ArrayList<>();
        Path stateFile = state.resolve("torrents.state");
        if (Files.isRegularFile(stateFile)) {
            try {
                states = torrents(Pickle.read(Files.readAllBytes(stateFile)));
            } catch (IOException | RuntimeException e) {
                unreadable.add(stateFile.getFileName() + ": " + message(e));
            }
        }
        // Deluge writes the state beside the resume data; a torrent with resume data and no state
        // (a state file that did not read) still comes over, with libtorrent's own word for the rest.
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> s : states) {
            String id = text(s.get("torrent_id")).toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) byId.put(id, s);
        }
        for (String id : resumes.keySet()) byId.putIfAbsent(id, Map.of());
        for (Map.Entry<String, Map<String, Object>> e : byId.entrySet()) {
            String id = e.getKey();
            try {
                Path torrentFile = state.resolve(id + ".torrent");
                byte[] torrent = Files.isRegularFile(torrentFile) ? Files.readAllBytes(torrentFile) : null;
                out.add(candidate(id, resumes.get(id), torrent, e.getValue(), labels.getOrDefault(id, "")));
            } catch (IOException | RuntimeException ex) {
                unreadable.add(id + ": " + message(ex));
            }
        }
        return new Found(config, out, unreadable);
    }

    /** The {@code TorrentManagerState}'s torrents, each as its {@code TorrentState}'s fields. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> torrents(Object pickled) throws IOException {
        if (!(pickled instanceof Pickle.Obj manager) || !(manager.state().get("torrents") instanceof List<?> list))
            throw new IOException("not Deluge's torrent state");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) if (o instanceof Pickle.Obj t) out.add(t.state());
        return out;
    }

    /**
     * One torrent from its resume data (none for a magnet that never had its metadata), its {@code
     * .torrent} and its state. Pure.
     */
    @SuppressWarnings("unchecked")
    static ImportCandidate candidate(String id, byte[] resume, byte[] torrent, Map<String, Object> state, String label)
            throws IOException {
        boolean stopped = state.get("paused") instanceof Boolean b && b;
        String savePath = text(state.get("save_path"));
        String rename = text(state.get("name"));
        double ratio = state.get("stop_at_ratio") instanceof Boolean on
                ? (on && state.get("stop_ratio") instanceof Number r ? r.doubleValue() : -1)
                : -2;
        TorrentPreview preview = torrent == null ? null : Bencode.preview(torrent);

        if (resume == null) {
            // Never saved by Deluge: a magnet still fetching, or one lost before its first save.
            String magnet = text(state.get("magnet"));
            String name = preview != null ? preview.name() : displayName(magnet, id);
            Map<String, Object> r = new HashMap<>();
            r.put("file-format", "libtorrent resume file");
            r.put("file-version", 1L);
            Matcher m = BTIH.matcher(magnet);
            byte[] hash = preview == null && m.find()
                    ? HexFormat.of().parseHex(m.group(1))
                    : id.length() == 40 ? HexFormat.of().parseHex(id) : null;
            if (hash == null) throw new IOException("no resume data and no v1 hash to start from");
            r.put("info-hash", hash);
            r.put("name", name);
            r.put("save_path", savePath);
            r.put("paused", stopped ? 1L : 0L);
            r.put("auto_managed", stopped ? 0L : 1L);
            List<List<String>> trackers = trackersOf(magnet);
            if (!trackers.isEmpty()) r.put("trackers", trackers);
            List<ImportCandidate.FileEntry> files = new ArrayList<>();
            if (preview != null)
                for (TorrentPreview.FileEntry f : preview.files())
                    files.add(new ImportCandidate.FileEntry(f.path(), f.size(), false));
            return new ImportCandidate(
                    CLIENT,
                    preview != null ? preview.id() : id,
                    name,
                    rename,
                    BencodeWriter.encode(r),
                    torrent,
                    savePath,
                    label,
                    "",
                    List.of(),
                    stopped,
                    files,
                    0,
                    preview == null ? 0 : pieceCount(torrent),
                    ratio,
                    -2);
        }

        // The resume data is libtorrent's: read as qBittorrent's is (its renamed files from
        // mapped_files included), the state's options over it.
        ImportCandidate base = QbittorrentReader.candidate(resume, torrent, Map.of());
        return new ImportCandidate(
                CLIENT,
                base.id(),
                base.name(),
                rename,
                resume,
                torrent,
                savePath.isEmpty() ? base.savePath() : savePath,
                label,
                "",
                List.of(),
                state.isEmpty() ? base.stopped() : stopped,
                base.files(),
                base.piecesHave(),
                base.pieces(),
                state.isEmpty() ? -2 : ratio,
                -2,
                base.renamed());
    }

    @SuppressWarnings("unchecked")
    private static long pieceCount(byte[] torrent) throws IOException {
        return Bencode.decode(torrent) instanceof Map<?, ?> t
                        && t.get("info") instanceof Map<?, ?> info
                        && info.get("pieces") instanceof byte[] hashes
                ? hashes.length / 20
                : 0;
    }

    /** A magnet's {@code dn}, else the hash. */
    private static String displayName(String magnet, String id) {
        int q = magnet.indexOf('?');
        if (q >= 0)
            for (String pair : magnet.substring(q + 1).split("&"))
                if (pair.startsWith("dn=")) return URLDecoder.decode(pair.substring(3), StandardCharsets.UTF_8);
        return id;
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

    private static String text(Object v) {
        return v instanceof String s ? s : v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : "";
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
