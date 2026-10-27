package com.torrenta.os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The freedesktop.org trash (the Trash specification, 1.0): what GNOME Files, Dolphin and the rest
 * show and restore from. A file on the home trash's volume goes to {@code $XDG_DATA_HOME/Trash};
 * one on another volume to that volume's {@code .Trash/$uid} when the administrator made a
 * {@code .Trash} (a folder, not a link, sticky), else to {@code .Trash-$uid} at its top, made if
 * need be. Each item is a rename into {@code files/} beside an {@code info/<name>.trashinfo} that
 * says where it came from; the info file is created first and exclusively, which is how two
 * trashers agree on a name. Never a copy: a volume with no usable trash fails, and the file stays.
 */
final class FreedesktopTrash implements Trash {

    private final Path homeTrash;

    FreedesktopTrash(Path homeTrash) {
        this.homeTrash = homeTrash.toAbsolutePath().normalize();
    }

    @Override
    public void trash(Path path) throws IOException {
        Path item = path.toAbsolutePath().normalize();
        if (!Files.exists(item, LinkOption.NOFOLLOW_LINKS)) return;
        Path parent = item.getParent();
        if (parent == null) throw new IOException("Cannot trash " + item);
        if (Files.getFileStore(parent).equals(Volumes.storeOf(homeTrash))) {
            moveInto(ensure(homeTrash), item, item.toString());
            return;
        }
        Path top = Volumes.topOf(parent);
        String relative = top.relativize(item).toString();
        String uid = Integer.toString(uid(parent));
        Path shared = top.resolve(".Trash");
        if (usableShared(shared)) {
            moveInto(ensure(shared.resolve(uid)), item, relative);
            return;
        }
        moveInto(ensure(top.resolve(".Trash-" + uid)), item, relative);
    }

    /** {@code $topdir/.Trash} as the spec allows it: a folder, not a link, with the sticky bit. */
    private static boolean usableShared(Path shared) {
        try {
            if (!Files.isDirectory(shared, LinkOption.NOFOLLOW_LINKS)) return false;
            int mode = (Integer) Files.getAttribute(shared, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return (mode & 01000) != 0;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Whose files these are: the owner of what this process creates, read off a file it creates. */
    private static int uid(Path near) throws IOException {
        Path probe = Files.createTempFile("torrenta-uid", "");
        try {
            return (Integer) Files.getAttribute(probe, "unix:uid");
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IOException("No owner to trash " + near + " as", e);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /** A trash folder with its {@code files} and {@code info}, made owner-only when new. */
    private static Path ensure(Path trash) throws IOException {
        if (Files.isSymbolicLink(trash)) throw new IOException("The trash is a link: " + trash);
        for (Path dir : new Path[] {trash, trash.resolve("files"), trash.resolve("info")}) {
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.createDirectories(
                        dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(dir);
            }
        }
        return trash;
    }

    private static void moveInto(Path trash, Path item, String originalPath) throws IOException {
        byte[] info = info(originalPath, LocalDateTime.now()).getBytes(StandardCharsets.UTF_8);
        String name = item.getFileName().toString();
        boolean folder = Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS);
        for (int n = 1; n <= Volumes.TRIES; n++) {
            String candidate = folder ? name + (n == 1 ? "" : " " + n) : Volumes.numbered(name, n);
            Path infoFile = trash.resolve("info").resolve(candidate + ".trashinfo");
            try {
                Files.write(infoFile, info, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            Path target = trash.resolve("files").resolve(candidate);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                // An item whose info went missing: its name stays taken.
                Files.deleteIfExists(infoFile);
                continue;
            }
            try {
                Files.move(item, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(infoFile);
                throw e;
            }
        }
        throw new IOException("No free name for " + name + " in " + trash);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The {@code .trashinfo} text: the original path, percent-encoded, and when, in local time. */
    static String info(String originalPath, LocalDateTime when) {
        return "[Trash Info]\nPath=" + encode(originalPath) + "\nDeletionDate="
                + DATE.format(when.truncatedTo(ChronoUnit.SECONDS)) + "\n";
    }

    /** A path as the spec's {@code Path} key wants it: UTF-8, every byte but the unreserved and {@code /} escaped. */
    static String encode(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean plain = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "-_.~/!*'()".indexOf(c) >= 0;
            if (plain) out.append((char) c);
            else
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
        return out.toString();
    }
}
