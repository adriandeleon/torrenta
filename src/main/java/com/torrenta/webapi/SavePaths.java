package com.torrenta.webapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.torrenta.i18n.Messages.tr;

/**
 * Where a client may put files: inside the allowed roots (the download folder, the categories'
 * folders, the folders added by hand on the Remote access page), or anyone with the password
 * could write anywhere this user can. A relative path is taken under the default save path, as
 * qBittorrent takes it. The comparison is made on real paths: the longest part of the path that
 * exists is resolved through its links, so a link inside a root that points out of it is out,
 * and a {@code ..} that climbs out is out.
 */
public final class SavePaths {

    private SavePaths() {}

    /** A path that may be used, or why not, in the words the client sees. */
    public record Decision(Optional<Path> path, String problem) {
        static Decision ok(Path p) {
            return new Decision(Optional.of(p), "");
        }

        static Decision refused(String why) {
            return new Decision(Optional.empty(), why);
        }
    }

    /** {@code requested} (blank for the default) checked against {@code roots}. */
    public static Decision resolve(String requested, Path defaultPath, Collection<Path> roots) {
        Path wanted;
        try {
            wanted = requested == null || requested.isBlank() ? defaultPath : defaultPath.resolve(requested.trim());
        } catch (InvalidPathException e) {
            return Decision.refused(tr("webapi.savePath.unreadable", requested));
        }
        Path real = real(wanted.toAbsolutePath().normalize());
        for (Path root : roots) {
            if (root == null) continue;
            if (real.startsWith(real(root.toAbsolutePath().normalize()))) return Decision.ok(real);
        }
        return Decision.refused(tr("webapi.savePath.outside", wanted));
    }

    /** Whether {@code path} is inside one of {@code roots}: a new category's folder must be. */
    public static boolean inside(Path path, Collection<Path> roots) {
        return resolve(path.toString(), path, roots).path().isPresent();
    }

    /**
     * The path with its existing part resolved through links and the rest appended as written
     * (already normalised, so it holds no {@code ..} of its own past the existing part).
     */
    static Path real(Path absolute) {
        List<Path> tail = new ArrayList<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            tail.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) return absolute;
        Path out;
        try {
            out = existing.toRealPath();
        } catch (IOException e) {
            out = existing;
        }
        for (Path part : tail) out = out.resolve(part);
        return out.normalize();
    }
}
