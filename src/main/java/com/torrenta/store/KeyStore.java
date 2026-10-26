package com.torrenta.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.MutableKey;
import com.torrenta.engine.Publication;

import static com.torrenta.i18n.Messages.tr;

/**
 * The updatable torrents this machine publishes, one {@code keys/<name>.key} each: the key pair,
 * the salt, the current version and the highest number published. Owner-only where the file
 * system has modes, written beside the file and moved into place, and never synced: the secret
 * key is what lets anyone publish versions, and losing the file ends them.
 */
public final class KeyStore {

    private static final Logger LOG = Logger.getLogger(KeyStore.class.getName());

    public static final String DIR = "keys";
    public static final String SUFFIX = ".key";

    private final Path dir;

    /** @param configDir the folder the {@code keys} folder is in */
    public KeyStore(Path configDir) {
        this.dir = configDir.resolve(DIR);
    }

    public Path dir() {
        return dir;
    }

    public Path file(String name) {
        return dir.resolve(name + SUFFIX);
    }

    /** Why {@code name} cannot name a new publication, or empty: a file name on every system. */
    public String problem(String name) {
        String why = nameProblem(name);
        if (!why.isEmpty()) return why;
        return Files.exists(file(name.trim())) ? tr("publish.problem.taken", name.trim()) : "";
    }

    /** The rule alone, pure: letters, digits, dot, dash and underscore, 1 to 64, no leading dot. */
    public static String nameProblem(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return tr("publish.problem.empty");
        if (n.length() > 64 || !n.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return tr("publish.problem.name");
        return "";
    }

    /** Every publication found, by name; a file that does not read is said in the debug log and left out. */
    public synchronized List<Publication> list() {
        List<Publication> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path f : files) {
                String file = f.getFileName().toString();
                String name = file.substring(0, file.length() - SUFFIX.length());
                try {
                    out.add(decode(name, Files.readString(f)));
                } catch (IOException | IllegalArgumentException e) {
                    LOG.log(Level.WARNING, "Cannot read the key " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list " + dir, e);
        }
        out.sort(java.util.Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public synchronized Optional<Publication> load(String name) {
        Path f = file(name);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(decode(name, Files.readString(f)));
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Cannot read the key " + f + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The publication whose current version is torrent {@code id}. */
    public Optional<Publication> ofTorrent(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        return list().stream().filter(p -> p.torrentId().equals(id)).findFirst();
    }

    public synchronized void save(Publication p) throws IOException {
        if (!nameProblem(p.name()).isEmpty()) throw new IOException(nameProblem(p.name()));
        Files.createDirectories(dir);
        restrictDir(dir);
        Path target = file(p.name());
        Path tmp = Files.createTempFile(dir, ".torrenta-", ".part");
        try {
            ProfileStore.restrict(tmp);
            Files.writeString(tmp, encode(p));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void restrictDir(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            LOG.log(Level.FINE, "No POSIX modes for " + dir, e);
        }
    }

    // ---- the format, pure -----------------------------------------------------------------------

    /** Lines of {@code key=value}, hex for bytes, with a warning on top for whoever opens it. */
    public static String encode(Publication p) {
        HexFormat hex = HexFormat.of();
        return "# Torrenta: the key of an updatable torrent. Anyone with this file can publish its versions;\n"
                + "# without it no new version can be published. Keep a copy somewhere safe.\n"
                + "format=1\n"
                + "public=" + hex.formatHex(p.key().publicKey()) + "\n"
                + "secret=" + hex.formatHex(p.key().secretKey()) + "\n"
                + "salt=" + hex.formatHex(p.salt()) + "\n"
                + "infoHash=" + p.infoHash() + "\n"
                + "seq=" + p.seq() + "\n"
                + "torrent=" + p.torrentId() + "\n";
    }

    /** The publication in {@code text}; an unknown format, a missing key or bad hex is refused. */
    public static Publication decode(String name, String text) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("not a key=value line: " + l);
            values.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
        }
        if (!"1".equals(values.get("format"))) throw new IllegalArgumentException("unknown format");
        HexFormat hex = HexFormat.of();
        String pub = values.get("public");
        String secret = values.get("secret");
        if (pub == null || secret == null) throw new IllegalArgumentException("no key pair");
        long seq;
        try {
            seq = Long.parseLong(values.getOrDefault("seq", "0"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("seq is not a number");
        }
        return new Publication(
                name,
                new MutableKey(hex.parseHex(pub), hex.parseHex(secret)),
                hex.parseHex(values.getOrDefault("salt", "")),
                values.getOrDefault("infoHash", ""),
                seq,
                values.getOrDefault("torrent", ""));
    }
}
