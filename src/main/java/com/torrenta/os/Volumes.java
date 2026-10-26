package com.torrenta.os;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** What the trashes that are folders share: which volume a path is on, and a move under a free name. */
final class Volumes {

    private Volumes() {}

    /** The volume a path is on, asked of its nearest ancestor that exists. */
    static FileStore storeOf(Path path) throws IOException {
        Path p = path.toAbsolutePath();
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p = p.getParent();
        if (p == null) throw new IOException("No such folder: " + path);
        return Files.getFileStore(p);
    }

    /** The folder a volume is mounted at, the top of the ones on the same volume as {@code dir}. */
    static Path topOf(Path dir) throws IOException {
        Path p = dir.toAbsolutePath().normalize();
        FileStore store = Files.getFileStore(p);
        for (Path up = p.getParent(); up != null; up = up.getParent()) {
            if (!Files.getFileStore(up).equals(store)) break;
            p = up;
        }
        return p;
    }

    /**
     * The name for the {@code n}th of the same name in one folder, the first being the name
     * itself: {@code film.mkv}, {@code film 2.mkv}; a folder, or a name whose only dot leads it,
     * is numbered at the end.
     */
    static String numbered(String name, int n) {
        if (n <= 1) return name;
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name + " " + n : name.substring(0, dot) + " " + n + name.substring(dot);
    }

    /** How many of the same name are tried before a trash gives up. */
    static final int TRIES = 10_000;

    /** Moves {@code path} into {@code dir} under the first free name: a rename, never a copy. */
    static Path moveUnderFreeName(Path path, Path dir) throws IOException {
        String name = path.getFileName().toString();
        boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        for (int n = 1; n <= TRIES; n++) {
            Path target = dir.resolve(folder ? name + (n == 1 ? "" : " " + n) : numbered(name, n));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                return Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                // Taken between the look and the move: the next name.
            }
        }
        throw new IOException("No free name for " + name + " in " + dir);
    }
}
