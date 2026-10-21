package com.torrenta.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.torrenta.i18n.Messages.tr;

/**
 * A folder watched for {@code .torrent} and {@code .magnet} files, and what to do with one that
 * appears: the shape qBittorrent and Transmission give it.
 *
 * <p>Engine-neutral and pure: the rules that turn an entry and a file into an {@link AddRequest}
 * are static methods here, tested on their own; {@link WatchFolders} is the thread that applies
 * them. Kept in {@code watch-folders.json} by the store; never synced, since a folder is this
 * machine's.
 *
 * @param folder the folder watched, as typed
 * @param afterAdd what becomes of the file once its torrent is in the session
 * @param saveTo where the torrent's files go
 * @param savePath the folder for {@link SaveTo#FOLDER}, else empty
 * @param category the category to file the torrent under, or empty for none
 * @param startPaused added stopped; the person resumes it
 */
public record WatchFolder(
        String folder, AfterAdd afterAdd, SaveTo saveTo, String savePath, String category, boolean startPaused) {

    /** The file after its torrent was added: gone, or kept under a name the watcher skips. */
    public enum AfterAdd {
        DELETE("delete"),
        RENAME("rename");

        private final String label;

        AfterAdd(String label) {
            this.label = label;
        }

        public String label() {
            return tr("watchFolder.afterAdd." + label);
        }
    }

    /** Where the torrent's files go. */
    public enum SaveTo {
        DEFAULT("default"),
        WATCH_FOLDER("watchFolder"),
        FOLDER("folder");

        private final String label;

        SaveTo(String label) {
            this.label = label;
        }

        public String label() {
            return tr("watchFolder.saveTo." + label);
        }
    }

    /** Appended to a file whose torrent was added, when the entry keeps files. */
    public static final String HANDLED_SUFFIX = ".added";

    /** Appended to a file that could not be read as a torrent or a magnet; it is never looked at again. */
    public static final String INVALID_SUFFIX = ".invalid";

    public static final String TORRENT_SUFFIX = ".torrent";
    public static final String MAGNET_SUFFIX = ".magnet";

    public WatchFolder {
        folder = folder == null ? "" : folder.trim();
        if (folder.isEmpty()) throw new IllegalArgumentException("a watch folder needs a folder");
        if (afterAdd == null) afterAdd = AfterAdd.RENAME;
        if (saveTo == null) saveTo = SaveTo.DEFAULT;
        savePath = savePath == null ? "" : savePath.trim();
        if (saveTo == SaveTo.FOLDER && savePath.isEmpty()) saveTo = SaveTo.DEFAULT;
        if (saveTo != SaveTo.FOLDER) savePath = "";
        category = TorrentLabels.normalizeName(category);
    }

    /** The folder watched, absolute. */
    public Path path() {
        return Path.of(folder).toAbsolutePath().normalize();
    }

    /**
     * Where a torrent from this folder is saved: the entry's own folder, the watch folder, else
     * the category's folder when it has one, else the download folder — the Add dialog's rule
     * with the entry's choice in front of it.
     */
    public Path destination(Path defaultDir, Optional<Category> category) {
        return switch (saveTo) {
            case FOLDER -> Path.of(savePath).toAbsolutePath().normalize();
            case WATCH_FOLDER -> path();
            case DEFAULT -> DownloadPlan.destination(defaultDir, category);
        };
    }

    /** The request for a file found here: the entry's choices, the dialog's defaults for the rest. */
    public AddRequest request(AddRequest.Source source, Path destination, AddRequest.Storage storage) {
        return new AddRequest(
                source,
                destination,
                startPaused,
                false,
                false,
                AddRequest.StopCondition.NONE,
                java.util.Map.of(),
                new TorrentLabels(category, List.of()),
                storage);
    }

    /** Whether the watcher picks the file up: a {@code .torrent} or a {@code .magnet}, by name. */
    public static boolean watched(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(TORRENT_SUFFIX) || name.endsWith(MAGNET_SUFFIX);
    }

    /** Whether the file holds magnet links rather than a torrent. */
    public static boolean isMagnetFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(MAGNET_SUFFIX);
    }

    /** {@code x.torrent} → {@code x.torrent.added}, beside it. */
    public static Path handled(Path file) {
        return file.resolveSibling(file.getFileName() + HANDLED_SUFFIX);
    }

    /** {@code x.torrent} → {@code x.torrent.invalid}, beside it. */
    public static Path invalid(Path file) {
        return file.resolveSibling(file.getFileName() + INVALID_SUFFIX);
    }

    /**
     * The magnet links in a {@code .magnet} file: one per line, as qBittorrent reads them; blank
     * lines and lines that are not magnets (a comment, say) are skipped.
     */
    public static List<String> magnetLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (Inputs.classify(s) == Inputs.Kind.MAGNET && Inputs.looksAddable(s)) out.add(s);
        }
        return out;
    }

    /**
     * The info-hash a magnet names, as the engine would id the torrent: a v2 hash ({@code
     * urn:btmh:1220…}) wins over a v1 one, a base32 v1 hash is turned into hex. Empty when the
     * link names none. Pure, so a torrent already in the session is recognised before the add.
     */
    public static Optional<String> magnetId(String magnet) {
        if (magnet == null) return Optional.empty();
        String query = magnet.startsWith("magnet:?") ? magnet.substring("magnet:?".length()) : magnet;
        String v1 = null;
        String v2 = null;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) continue;
            String key = param.substring(0, eq);
            String value = param.substring(eq + 1).toLowerCase(Locale.ROOT);
            if (!key.equals("xt") && !key.startsWith("xt.")) continue;
            if (value.startsWith("urn:btmh:1220") && value.length() == "urn:btmh:1220".length() + 64) {
                v2 = value.substring("urn:btmh:1220".length());
            } else if (value.startsWith("urn:btih:")) {
                String hash = value.substring("urn:btih:".length());
                if (hash.length() == 40) v1 = hash;
                else if (hash.length() == 32) v1 = base32ToHex(hash);
            }
        }
        if (v2 != null && isHex(v2)) return Optional.of(v2);
        if (v1 != null && isHex(v1)) return Optional.of(v1);
        return Optional.empty();
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** RFC 4648 base32 (what magnets use for a v1 hash) to lowercase hex; null when not base32. */
    private static String base32ToHex(String s) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder hex = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int value = alphabet.indexOf(s.charAt(i));
            if (value < 0) return null;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                hex.append(String.format("%02x", (buffer >> bits) & 0xff));
            }
        }
        return hex.toString();
    }

    /**
     * Remembers the size of every file seen and says when one has stopped growing: the same size
     * at two looks in a row, so a {@code .torrent} still being copied in is not read half-way.
     * Pure; one per watcher.
     */
    public static final class Growth {
        private final java.util.Map<Path, Long> seen = new java.util.HashMap<>();

        /** Records this look; true when the file was the same size at the look before. */
        public boolean settled(Path file, long size) {
            Long before = seen.put(file, size);
            return before != null && before == size;
        }

        /** The file is gone or handled: its next appearance starts over. */
        public void forget(Path file) {
            seen.remove(file);
        }

        /** Files seen before but not this time are forgotten. */
        public void retain(java.util.Collection<Path> present) {
            seen.keySet().retainAll(new java.util.HashSet<>(present));
        }
    }
}
