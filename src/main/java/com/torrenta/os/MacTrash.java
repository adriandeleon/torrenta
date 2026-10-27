package com.torrenta.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Finder's trash: {@code ~/.Trash} for what is on the home volume, {@code .Trashes/<uid>} at the
 * top of any other, the folders Finder empties. A rename under a free name, as Finder names a
 * second {@code film.mkv} {@code film 2.mkv}; Put Back is Finder's own bookkeeping and is not
 * offered for what arrives this way.
 */
final class MacTrash implements Trash {

    private final Path home;

    MacTrash(Path home) {
        this.home = home.toAbsolutePath().normalize();
    }

    @Override
    public void trash(Path path) throws IOException {
        Path item = path.toAbsolutePath().normalize();
        if (!Files.exists(item, LinkOption.NOFOLLOW_LINKS)) return;
        Path parent = item.getParent();
        if (parent == null) throw new IOException("Cannot trash " + item);
        Path trash;
        if (Files.getFileStore(parent).equals(Files.getFileStore(home))) {
            trash = home.resolve(".Trash");
        } else {
            Object uid = Files.getAttribute(home, "unix:uid");
            trash = Volumes.topOf(parent).resolve(".Trashes").resolve(uid.toString());
        }
        Files.createDirectories(trash);
        Volumes.moveUnderFreeName(item, trash);
    }
}
