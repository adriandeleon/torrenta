package com.torrenta.automation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import org.jspecify.annotations.Nullable;

/**
 * The command line a person writes for an event, and what it becomes for one torrent. Pure, so it
 * is tested: "my script got the name in three pieces" and "the path lost its spaces" are both
 * rules here.
 *
 * <p>The line is split the way a shell would, but no shell runs: {@link #tokens} respects single
 * and double quotes and a backslash before a space or a quote, and the placeholders are then
 * replaced inside each token, so a name with spaces stays one argument. The placeholders are
 * qBittorrent's, spelled the same: {@code %N} name, {@code %L} category, {@code %G} tags (comma
 * separated), {@code %F} content path (the root folder, or the one file), {@code %R} root path
 * (empty for a torrent without a root folder), {@code %D} save path, {@code %C} file count,
 * {@code %Z} size in bytes, {@code %T} current tracker, {@code %I} info-hash v1, {@code %J}
 * info-hash v2, {@code %K} torrent id, {@code %%} a percent sign. Anything else after a percent
 * is left as written.
 */
public final class EventCommand {

    /**
     * What the placeholders stand for, read from one torrent once so every token sees the same
     * facts.
     *
     * @param rootPath the save path plus the root folder, or null for a torrent without one
     */
    public record Subject(
            String name,
            String category,
            List<String> tags,
            Path contentPath,
            @Nullable Path rootPath,
            Path savePath,
            int fileCount,
            long size,
            String tracker,
            String infoHashV1,
            String infoHashV2,
            String id) {

        public Subject {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    private EventCommand() {}

    /**
     * The facts for one torrent. {@code detail} may be null (a magnet before its metadata, a
     * torrent already removed): the file count is then 0, the content path is the save path plus
     * the name, and the hashes are what the id says.
     */
    public static Subject subject(Torrent torrent, @Nullable TorrentDetail detail) {
        Path save = Path.of(torrent.savePath() == null ? "" : torrent.savePath());
        List<String> paths = detail == null
                ? List.of()
                : detail.files().stream().map(TorrentDetail.FileStatus::path).toList();
        String root = rootFolder(paths);
        Path content;
        if (!root.isEmpty()) content = resolve(save, root);
        else if (paths.size() == 1) content = resolve(save, paths.get(0));
        else if (paths.isEmpty()) content = resolve(save, torrent.name());
        else content = save; // several files and no folder of their own
        String v1;
        String v2;
        if (detail != null) {
            v1 = detail.general().infoHashV1();
            v2 = detail.general().infoHashV2();
        } else {
            v1 = torrent.id().length() == 40 ? torrent.id() : "";
            v2 = torrent.id().length() == 64 ? torrent.id() : "";
        }
        return new Subject(
                torrent.name(),
                torrent.category(),
                torrent.tags(),
                content,
                root.isEmpty() ? null : resolve(save, root),
                save,
                paths.size(),
                torrent.size(),
                torrent.tracker(),
                v1,
                v2,
                torrent.id());
    }

    /**
     * {@code base/child}, or {@code base} alone when the child is not a path this file system
     * can name (a magnet's display name with a quote in it, on Windows): the placeholder then
     * points at the folder rather than failing the whole command.
     */
    private static Path resolve(Path base, String child) {
        try {
            return base.resolve(child);
        } catch (java.nio.file.InvalidPathException e) {
            return base;
        }
    }

    /**
     * The folder every file of the torrent is under, relative to the save path, or empty when
     * there is none: a single file at the top, or several files side by side.
     */
    public static String rootFolder(List<String> filePaths) {
        String root = null;
        for (String path : filePaths) {
            String[] parts = path.split("[/\\\\]", 2);
            if (parts.length < 2 || parts[0].isEmpty()) return "";
            if (root == null) root = parts[0];
            else if (!root.equals(parts[0])) return "";
        }
        return root == null ? "" : root;
    }

    /**
     * The line split into arguments: whitespace separates, {@code '…'} keeps everything literally,
     * {@code "…"} keeps spaces and takes {@code \"} as a quote, and outside quotes a backslash
     * before a space, a tab or a quote keeps that character (any other backslash is itself, so a
     * Windows path survives). An unterminated quote runs to the end of the line.
     */
    public static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                i++;
            } else if (c == '\'') {
                inToken = true;
                int end = line.indexOf('\'', i + 1);
                if (end < 0) end = n;
                current.append(line, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                inToken = true;
                i++;
                while (i < n && line.charAt(i) != '"') {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < n && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                        current.append(line.charAt(i + 1));
                        i += 2;
                    } else {
                        current.append(d);
                        i++;
                    }
                }
                i++; // the closing quote, or past the end
            } else if (c == '\\' && i + 1 < n && escapable(line.charAt(i + 1))) {
                inToken = true;
                current.append(line.charAt(i + 1));
                i += 2;
            } else {
                inToken = true;
                current.append(c);
                i++;
            }
        }
        if (inToken) out.add(current.toString());
        return out;
    }

    private static boolean escapable(char c) {
        return c == ' ' || c == '\t' || c == '"' || c == '\'';
    }

    /** Every token with its placeholders replaced; the list keeps its length. */
    public static List<String> substitute(List<String> tokens, Subject subject) {
        List<String> out = new ArrayList<>(tokens.size());
        for (String token : tokens) out.add(substitute(token, subject));
        return out;
    }

    /** One token with its placeholders replaced. */
    public static String substitute(String token, Subject s) {
        if (token.indexOf('%') < 0) return token;
        StringBuilder out = new StringBuilder(token.length() + 32);
        int i = 0;
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c != '%' || i + 1 >= token.length()) {
                out.append(c);
                i++;
                continue;
            }
            char p = token.charAt(i + 1);
            String value =
                    switch (p) {
                        case '%' -> "%";
                        case 'N' -> s.name();
                        case 'L' -> s.category();
                        case 'G' -> String.join(",", s.tags());
                        case 'F' -> s.contentPath().toString();
                        case 'R' -> s.rootPath() == null ? "" : s.rootPath().toString();
                        case 'D' -> s.savePath().toString();
                        case 'C' -> String.valueOf(s.fileCount());
                        case 'Z' -> String.valueOf(s.size());
                        case 'T' -> s.tracker();
                        case 'I' -> s.infoHashV1();
                        case 'J' -> s.infoHashV2();
                        case 'K' -> s.id();
                        default -> null;
                    };
            if (value == null) {
                out.append(c).append(p);
            } else {
                out.append(value);
            }
            i += 2;
        }
        return out.toString();
    }
}
