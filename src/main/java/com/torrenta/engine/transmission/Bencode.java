package com.torrenta.engine.transmission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.torrenta.engine.TorrentPreview;
import org.jspecify.annotations.Nullable;

/**
 * A bencode decoder, enough to read a {@code .torrent} for the Add dialog without libtorrent: a
 * Transmission daemon has nothing like {@code inspect}, and the dialog wants the name, the files
 * and the trackers before the torrent is added.
 *
 * <p>Values are {@code Map<String,Object>} (keys as UTF-8 strings, order kept), {@code
 * List<Object>}, {@code Long}, and {@code byte[]} for strings, since a string may be binary. The
 * decoder remembers where the top-level {@code info} dictionary sits so that its hashes can be
 * taken over the exact bytes. Pure.
 */
public final class Bencode {

    private final byte[] data;
    private int pos;
    /** The {@code info} dictionary's byte range at the top level, or -1. */
    private int infoStart = -1;

    private int infoEnd = -1;

    private Bencode(byte[] data) {
        this.data = data;
    }

    /**
     * Throws unless {@code bytes} reads as a {@code .torrent} within the decoder's bounds (nesting,
     * lengths, no trailing bytes): what an upload from the network must pass before libtorrent's
     * own parser sees it.
     */
    public static void check(byte[] bytes) throws IOException {
        try {
            decode(bytes);
            preview(bytes);
        } catch (RuntimeException e) {
            throw new IOException("not a torrent", e);
        }
    }

    /** Decodes one value; trailing bytes are an error. */
    public static Object decode(byte[] data) throws IOException {
        Bencode b = new Bencode(data);
        Object value = b.read(0);
        if (b.pos != data.length) throw new IOException("trailing bytes after the torrent");
        return value;
    }

    /** What a {@code .torrent} says about itself, as the Add dialog wants it. */
    public static TorrentPreview preview(byte[] torrent) throws IOException {
        Bencode b = new Bencode(torrent);
        Object root = b.read(0);
        if (!(root instanceof Map<?, ?>)) throw new IOException("not a torrent: no dictionary");
        Map<String, Object> top = map(root);
        Map<String, Object> info = map(top.get("info"));
        if (info.isEmpty() || b.infoStart < 0) throw new IOException("not a torrent: no info dictionary");
        byte[] infoBytes = java.util.Arrays.copyOfRange(torrent, b.infoStart, b.infoEnd);

        String name = text(info.get("name.utf-8"), text(info.get("name"), ""));
        long metaVersion = info.get("meta version") instanceof Long l ? l : 1;
        boolean v2 = metaVersion >= 2 && info.get("file tree") instanceof Map<?, ?>;
        boolean v1 = info.get("pieces") instanceof byte[];
        if (!v1 && !v2) throw new IOException("not a torrent: no pieces");

        List<TorrentPreview.FileEntry> files = new ArrayList<>();
        long size = 0;
        if (v1 || !v2) {
            if (info.get("files") instanceof List<?> list) {
                int index = 0;
                for (Object o : list) {
                    Map<String, Object> f = map(o);
                    long length = f.get("length") instanceof Long l ? l : 0;
                    List<String> parts = parts(f.get("path.utf-8") != null ? f.get("path.utf-8") : f.get("path"));
                    boolean padding = f.get("attr") instanceof byte[] attr
                                    && new String(attr, StandardCharsets.UTF_8).contains("p")
                            || (!parts.isEmpty() && parts.get(0).equals(".pad"));
                    if (!padding) {
                        files.add(new TorrentPreview.FileEntry(index, name + "/" + String.join("/", parts), length));
                        size += length;
                    }
                    index++;
                }
            } else {
                long length = info.get("length") instanceof Long l ? l : 0;
                files.add(new TorrentPreview.FileEntry(0, name, length));
                size = length;
            }
        } else {
            // v2-only: the file tree, walked in sorted order as libtorrent numbers the files.
            List<String[]> leaves = new ArrayList<>();
            walkTree(map(info.get("file tree")), new ArrayList<>(), leaves);
            int index = 0;
            boolean several = leaves.size() > 1;
            for (String[] leaf : leaves) {
                long length = Long.parseLong(leaf[1]);
                String path = several ? name + "/" + leaf[0] : name;
                files.add(new TorrentPreview.FileEntry(index++, path, length));
                size += length;
            }
        }

        List<String> trackers = new ArrayList<>();
        if (top.get("announce-list") instanceof List<?> tiers) {
            for (Object tier : tiers) for (Object url : list(tier)) addTracker(trackers, text(url, ""));
        }
        addTracker(trackers, text(top.get("announce"), ""));

        String id = v2 ? hex(digest("SHA-256", infoBytes)).substring(0, 40) : hex(digest("SHA-1", infoBytes));
        return new TorrentPreview(
                id,
                name,
                size,
                List.copyOf(files),
                info.get("private") instanceof Long p && p == 1,
                List.copyOf(trackers),
                text(top.get("comment.utf-8"), text(top.get("comment"), "")),
                text(top.get("created by"), ""),
                top.get("creation date") instanceof Long d ? d : 0,
                v1,
                v2);
    }

    /** The v1 info-hash, hex, or empty when the torrent carries no v1 pieces. */
    static String infoHashV1(byte[] torrent) throws IOException {
        Bencode b = new Bencode(torrent);
        Map<String, Object> top = map(b.read(0));
        Map<String, Object> info = map(top.get("info"));
        if (b.infoStart < 0 || !(info.get("pieces") instanceof byte[])) return "";
        return hex(digest("SHA-1", java.util.Arrays.copyOfRange(torrent, b.infoStart, b.infoEnd)));
    }

    private static void walkTree(Map<String, Object> node, List<String> path, List<String[]> leaves) {
        List<String> keys = new ArrayList<>(node.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            Map<String, Object> child = map(node.get(key));
            if (key.isEmpty()) {
                long length = child.get("length") instanceof Long l ? l : 0;
                leaves.add(new String[] {String.join("/", path), Long.toString(length)});
                continue;
            }
            path.add(key);
            walkTree(child, path, leaves);
            path.remove(path.size() - 1);
        }
    }

    private static void addTracker(List<String> out, String url) {
        if (!url.isEmpty() && !out.contains(url)) out.add(url);
    }

    private static List<String> parts(@Nullable Object value) {
        List<String> out = new ArrayList<>();
        for (Object o : list(value)) out.add(text(o, ""));
        return out;
    }

    private static String text(@Nullable Object value, String fallback) {
        return value instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(@Nullable Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    // ---- the decoder ----------------------------------------------------------------------------

    private Object read(int depth) throws IOException {
        if (depth > 64) throw new IOException("nested too deep");
        if (pos >= data.length) throw new IOException("the torrent is cut short");
        byte c = data[pos];
        if (c == 'd') {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            while (true) {
                if (pos >= data.length) throw new IOException("the dictionary is cut short");
                if (data[pos] == 'e') {
                    pos++;
                    return map;
                }
                Object keyValue = read(depth + 1);
                if (!(keyValue instanceof byte[] keyBytes)) throw new IOException("a dictionary key is not a string");
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                int valueStart = pos;
                Object value = read(depth + 1);
                if (depth == 0 && key.equals("info")) {
                    infoStart = valueStart;
                    infoEnd = pos;
                }
                map.put(key, value);
            }
        }
        if (c == 'l') {
            pos++;
            List<Object> list = new ArrayList<>();
            while (true) {
                if (pos >= data.length) throw new IOException("the list is cut short");
                if (data[pos] == 'e') {
                    pos++;
                    return list;
                }
                list.add(read(depth + 1));
            }
        }
        if (c == 'i') {
            int end = indexOf((byte) 'e', pos + 1);
            String number = new String(data, pos + 1, end - pos - 1, StandardCharsets.US_ASCII);
            pos = end + 1;
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw new IOException("not an integer: " + number);
            }
        }
        if (c >= '0' && c <= '9') {
            int colon = indexOf((byte) ':', pos);
            long length;
            try {
                length = Long.parseLong(new String(data, pos, colon - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new IOException("bad string length");
            }
            int start = colon + 1;
            if (length < 0 || start + length > data.length) throw new IOException("the string is cut short");
            pos = (int) (start + length);
            return java.util.Arrays.copyOfRange(data, start, pos);
        }
        throw new IOException("not bencode at byte " + pos);
    }

    private int indexOf(byte wanted, int from) throws IOException {
        for (int i = from; i < data.length; i++) if (data[i] == wanted) return i;
        throw new IOException("the torrent is cut short");
    }
}
